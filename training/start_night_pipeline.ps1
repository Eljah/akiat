$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$out = Join-Path $repoRoot 'outputs\night_pipeline'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$stamp = Get-Date -Format yyyyMMdd-HHmmss
$worker = Start-Process -FilePath (Join-Path $repoRoot '.venv\Scripts\python.exe') `
    -ArgumentList @('-u', 'training/watch_night_pipeline.py') -WorkingDirectory $repoRoot `
    -WindowStyle Hidden -RedirectStandardOutput (Join-Path $out "watcher.$stamp.log") `
    -RedirectStandardError (Join-Path $out "watcher.$stamp.err.log") -PassThru
Write-Output "Night pipeline watcher started: PID=$($worker.Id). Status: $out\status.json"
