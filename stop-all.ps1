$ErrorActionPreference = "Continue"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = Join-Path $Root ".run"
$PidFile = Join-Path $RunDir "pids.json"

function Stop-ProcessTree {
  param(
    [int]$ProcessId,
    [string]$Label
  )

  $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
  if ($null -eq $proc) {
    Write-Host "$Label PID $ProcessId is not running."
    return
  }

  Write-Host "Stopping $Label PID $ProcessId ..."
  taskkill /PID $ProcessId /T /F | Out-Null
}

if (Test-Path $PidFile) {
  $records = Get-Content -Encoding UTF8 -Path $PidFile | ConvertFrom-Json
  if ($records -isnot [System.Array]) {
    $records = @($records)
  }

  foreach ($record in ($records | Sort-Object pid -Descending)) {
    Stop-ProcessTree -ProcessId ([int]$record.pid) -Label ([string]$record.name)
  }

  if (Test-Path $PidFile) {
    Remove-Item -Force -Path $PidFile
  }
} else {
  Write-Host "No PID file found. Falling back to common ports."
}

$ports = @(5173, 8080, 8000)
foreach ($port in $ports) {
  $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  foreach ($listener in $listeners) {
    $pidValue = [int]$listener.OwningProcess
    $proc = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -match "node|java|python|cmd") {
      Stop-ProcessTree -ProcessId $pidValue -Label "port $port ($($proc.ProcessName))"
    }
  }
}

Write-Host "Done."
