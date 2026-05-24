$ErrorActionPreference = "Continue"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = Join-Path $Root ".run"
$PidFile = Join-Path $RunDir "pids.json"
$AiDir = Join-Path $Root "insight-spark-ai-service"
$BackendDir = Join-Path $Root "insight-spark-backend"
$FrontendDir = Join-Path $Root "insight-spark-frontend"

function Get-ProcessCommandLine {
  param([int]$ProcessId)
  try {
    return [string]((Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop).CommandLine)
  } catch {
    return ""
  }
}

function Stop-ProcessQuietly {
  param(
    [int]$ProcessId,
    [string]$Label
  )

  $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
  if ($null -eq $proc) {
    return
  }

  Write-Host "Stopping $Label PID $ProcessId ..."
  try {
    Stop-Process -Id $ProcessId -Force -ErrorAction Stop
  } catch {
    taskkill /PID $ProcessId /T /F | Out-Null
  }
}

function Get-ChildrenMap {
  $map = @{}
  foreach ($item in Get-CimInstance Win32_Process -ErrorAction SilentlyContinue) {
    $parent = [int]$item.ParentProcessId
    if (-not $map.ContainsKey($parent)) {
      $map[$parent] = New-Object System.Collections.Generic.List[int]
    }
    [void]$map[$parent].Add([int]$item.ProcessId)
  }
  return $map
}

function Get-Depth {
  param(
    [int]$ProcessId,
    [hashtable]$ParentMap
  )

  $depth = 0
  $current = $ProcessId
  while ($ParentMap.ContainsKey($current)) {
    $current = [int]$ParentMap[$current]
    $depth++
    if ($depth -gt 20) { break }
  }
  return $depth
}

function Get-ProjectProcesses {
  $markers = @(
    [string]$Root,
    [string]$AiDir,
    [string]$BackendDir,
    [string]$FrontendDir,
    "uvicorn main:app",
    "spring-boot:run",
    "vite --host 0.0.0.0",
    "npm run dev",
    "mvn clean spring-boot:run",
    "mvn.cmd"
  )

  foreach ($item in Get-CimInstance Win32_Process -ErrorAction SilentlyContinue) {
    $commandLine = Get-ProcessCommandLine -ProcessId ([int]$item.ProcessId)
    if ([string]::IsNullOrWhiteSpace($commandLine)) {
      continue
    }
    $matched = $false
    foreach ($marker in $markers) {
      if (-not [string]::IsNullOrWhiteSpace($marker) -and $commandLine.Contains($marker)) {
        $matched = $true
        break
      }
    }
    if ($matched) {
      [pscustomobject]@{
        ProcessId = [int]$item.ProcessId
        ParentProcessId = [int]$item.ParentProcessId
        Name = [string]$item.Name
        CommandLine = $commandLine
      }
    }
  }
}

$targets = New-Object System.Collections.Generic.List[object]
$seen = New-Object 'System.Collections.Generic.HashSet[int]'

if (Test-Path $PidFile) {
  try {
    $records = Get-Content -Encoding UTF8 -Path $PidFile | ConvertFrom-Json
    if ($records -isnot [System.Array]) {
      $records = @($records)
    }
    foreach ($record in $records) {
      $pidValue = [int]$record.pid
      if ($pidValue -gt 0 -and $seen.Add($pidValue)) {
        $targets.Add([pscustomobject]@{
          ProcessId = $pidValue
          Label = [string]$record.name
        })
      }
    }
  } catch {
    Write-Host "PID file unreadable, fallback to process scan."
  }
}

$projectProcesses = @(Get-ProjectProcesses)
$parentMap = @{}
foreach ($item in $projectProcesses) {
  $parentMap[[int]$item.ProcessId] = [int]$item.ParentProcessId
}

foreach ($item in $projectProcesses) {
  $pidValue = [int]$item.ProcessId
  if ($pidValue -gt 0 -and $seen.Add($pidValue)) {
    $targets.Add([pscustomobject]@{
      ProcessId = $pidValue
      Label = "$($item.Name)"
      Depth = (Get-Depth -ProcessId $pidValue -ParentMap $parentMap)
    })
  }
}

$targets = $targets | Sort-Object @{ Expression = { $_.Depth }; Descending = $true }, @{ Expression = { $_.ProcessId }; Descending = $true }

foreach ($target in $targets) {
  Stop-ProcessQuietly -ProcessId ([int]$target.ProcessId) -Label ([string]$target.Label)
}

if (Test-Path $PidFile) {
  Remove-Item -Force -Path $PidFile -ErrorAction SilentlyContinue
}

foreach ($port in @(5173, 8080, 8000)) {
  $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  foreach ($listener in $listeners) {
    $pidValue = [int]$listener.OwningProcess
    if ($seen.Contains($pidValue)) {
      continue
    }
    $proc = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if ($null -eq $proc) {
      continue
    }
    $commandLine = Get-ProcessCommandLine -ProcessId $pidValue
    if (
      $commandLine.Contains($Root) -or
      $commandLine.Contains($AiDir) -or
      $commandLine.Contains($BackendDir) -or
      $commandLine.Contains($FrontendDir) -or
      $commandLine.Contains("uvicorn main:app") -or
      $commandLine.Contains("spring-boot:run") -or
      $commandLine.Contains("vite --host 0.0.0.0")
    ) {
      Stop-ProcessQuietly -ProcessId $pidValue -Label "port $port ($($proc.ProcessName))"
    }
  }
}

Write-Host "Done."
