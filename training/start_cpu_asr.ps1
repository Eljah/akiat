param([switch]$Smoke)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$env:PYTHONUTF8 = '1'
$env:CUDA_VISIBLE_DEVICES = ''
$env:HF_HOME = Join-Path $repoRoot '.cache\huggingface'
$env:HF_HUB_DISABLE_PROGRESS_BARS = '0'
$env:HF_HUB_DOWNLOAD_TIMEOUT = '30'
$arguments = @('-u', 'training/transcribe_cpu.py', '--threads', '1')
if ($Smoke) { $arguments += '--smoke' }
& (Join-Path $repoRoot '.venv\Scripts\python.exe') @arguments
if ($LASTEXITCODE -ne 0) { throw "CPU transcription failed: exit $LASTEXITCODE" }
