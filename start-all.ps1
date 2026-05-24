param(
  [switch]$SkipAi,
  [string]$PythonPath = ""
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = Join-Path $Root ".run"
$LogDir = Join-Path $RunDir "logs"
$PidFile = Join-Path $RunDir "pids.json"

$AiDir = Join-Path $Root "insight-spark-ai-service"
$BackendDir = Join-Path $Root "insight-spark-backend"
$FrontendDir = Join-Path $Root "insight-spark-frontend"
$ProjectVenvPython = Join-Path $AiDir ".venv\Scripts\python.exe"

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Test-PortOpen {
  param([int]$Port)
  $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  return ($null -ne $conn)
}

function Wait-PortClosed {
  param(
    [int]$Port,
    [int]$TimeoutSeconds
  )
  $end = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $end) {
    if (-not (Test-PortOpen $Port)) {
      return
    }
    Start-Sleep -Seconds 1
  }
}

function Wait-WebReady {
  param(
    [string]$Url,
    [int]$TimeoutSeconds,
    [string]$Label
  )
  $end = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $end) {
    try {
      $res = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
      if ($res.StatusCode -ge 200 -and $res.StatusCode -lt 500) {
        Write-Host "$Label ready: HTTP $($res.StatusCode)"
        return
      }
    } catch {
      $response = $_.Exception.Response
      if ($response) {
        $statusCode = [int]$response.StatusCode
        if ($statusCode -eq 401 -or $statusCode -eq 403) {
          Write-Host "$Label ready: HTTP $statusCode"
          return
        }
      }
      Start-Sleep -Seconds 2
    }
  }
  throw "$Label not ready in $TimeoutSeconds seconds: $Url"
}

function Start-App {
  param(
    [string]$Name,
    [string]$Exe,
    [string]$AppArgs,
    [string]$WorkDir,
    [string]$LogPrefix
  )
  $outLog = Join-Path $LogDir "$LogPrefix.out.log"
  $errLog = Join-Path $LogDir "$LogPrefix.err.log"
  Write-Host "Starting $Name ..."
  $proc = Start-Process -FilePath $Exe -ArgumentList $AppArgs -WorkingDirectory $WorkDir -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
  Write-Host "$Name PID: $($proc.Id)"
  return [pscustomobject]@{
    name = $Name
    pid = $proc.Id
    stdout = $outLog
    stderr = $errLog
    startedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
  }
}

function Resolve-CommandPath {
  param(
    [string[]]$Candidates
  )

  foreach ($candidate in $Candidates) {
    $value = [string]$candidate
    if ([string]::IsNullOrWhiteSpace($value)) {
      continue
    }
    if (Test-Path $value) {
      return (Resolve-Path $value).Path
    }
    $cmd = Get-Command $value -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) {
      return $cmd.Source
    }
  }
  return $null
}

function Resolve-PythonRuntime {
  $resolved = Resolve-CommandPath -Candidates @(
    $PythonPath,
    $env:INSIGHT_SPARK_PYTHON,
    $ProjectVenvPython,
    "python",
    "py"
  )
  if (-not $resolved) {
    throw "No available Python runtime found. You can pass -PythonPath or set INSIGHT_SPARK_PYTHON."
  }
  return $resolved
}

function Resolve-MavenCommand {
  $resolved = Resolve-CommandPath -Candidates @(
    $env:INSIGHT_SPARK_MVN,
    "mvn.cmd",
    "mvn"
  )
  if (-not $resolved) {
    throw "Maven not found. Please install Maven or set INSIGHT_SPARK_MVN."
  }
  return $resolved
}

function Resolve-NpmCommand {
  $resolved = Resolve-CommandPath -Candidates @(
    $env:INSIGHT_SPARK_NPM,
    "npm.cmd",
    "npm"
  )
  if (-not $resolved) {
    throw "npm not found. Please install Node.js or set INSIGHT_SPARK_NPM."
  }
  return $resolved
}

if (Test-Path $PidFile) {
  Write-Host "Existing PID file found. Stopping previous services first."
  $stopScript = Join-Path $Root "stop-all.ps1"
  if (Test-Path $stopScript) {
    & $stopScript
    Wait-PortClosed -Port 5173 -TimeoutSeconds 15
    Wait-PortClosed -Port 8080 -TimeoutSeconds 15
    Wait-PortClosed -Port 8000 -TimeoutSeconds 15
  } else {
    Write-Host "stop-all.ps1 not found. Removing stale PID file only."
    Remove-Item -Force -Path $PidFile
  }
}

$records = @()
$ResolvedPython = $null
$ResolvedMaven = $null
$ResolvedNpm = $null

if (-not $SkipAi) {
  $ResolvedPython = Resolve-PythonRuntime
  Write-Host "AI Python: $ResolvedPython"
}
$ResolvedMaven = Resolve-MavenCommand
$ResolvedNpm = Resolve-NpmCommand
Write-Host "Maven    : $ResolvedMaven"
Write-Host "NPM      : $ResolvedNpm"

if ($SkipAi) {
  Write-Host "Skip AI service."
} elseif (Test-PortOpen 8000) {
  Write-Host "AI port 8000 is already listening."
} else {
  if ($ResolvedPython -match "\\py(?:\.exe)?$") {
    $aiArgs = "-3.10 -m uvicorn main:app --host 0.0.0.0 --port 8000"
  } else {
    $aiArgs = "-m uvicorn main:app --host 0.0.0.0 --port 8000"
  }
  $records += Start-App -Name "AI service" -Exe $ResolvedPython -AppArgs $aiArgs -WorkDir $AiDir -LogPrefix "ai-service"
  Wait-WebReady -Url "http://localhost:8000/health" -TimeoutSeconds 90 -Label "AI service"
}

if (Test-PortOpen 8080) {
  Write-Host "Backend port 8080 is already listening."
} else {
  $records += Start-App -Name "Backend" -Exe $ResolvedMaven -AppArgs "clean spring-boot:run" -WorkDir $BackendDir -LogPrefix "backend"
}

Wait-WebReady -Url "http://localhost:8080/api/data/tables" -TimeoutSeconds 180 -Label "Backend"

if (Test-PortOpen 5173) {
  Write-Host "Frontend port 5173 is already listening."
} else {
  $records += Start-App -Name "Frontend" -Exe $ResolvedNpm -AppArgs "run dev -- --host 0.0.0.0" -WorkDir $FrontendDir -LogPrefix "frontend"
}

$records | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path $PidFile

Write-Host ""
Write-Host "All services started."
Write-Host "Frontend: http://localhost:5173"
Write-Host "Backend : http://localhost:8080"
Write-Host "AI      : http://localhost:8000"
Write-Host "Stop    : .\stop-all.ps1"
