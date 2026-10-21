param([int]$InstallerProcessId = 0)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$env:PYTHONUTF8 = '1'
$statusPath = Join-Path $repoRoot 'asr\cpu_status.json'
function Save-Status([string]$stage, [string]$detail) {
    @{stage=$stage; detail=$detail; updated=(Get-Date -Format o)} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $statusPath
}
try {
    if ($InstallerProcessId -gt 0) {
        Save-Status 'installing' 'Waiting for CPU Python dependencies'
        Wait-Process -Id $InstallerProcessId -ErrorAction SilentlyContinue
    }
    & .venv/Scripts/python.exe -c "import torch, transformers, imageio_ffmpeg; print('Torch:', torch.__version__, 'CUDA build:', torch.version.cuda); assert torch.version.cuda is None"
    if ($LASTEXITCODE -ne 0) { throw 'CPU dependencies are not ready' }
    Save-Status 'downloading_model' 'Downloading Whisper weights in resumable HTTP ranges'
    & .venv/Scripts/python.exe -u training/download_asr_model.py
    if ($LASTEXITCODE -ne 0) { throw 'Model download failed; inspect log and retry to resume' }
    Save-Status 'loading_model' 'Loading model before the 12-second CPU test'
    & "$PSScriptRoot/start_cpu_asr.ps1" -Smoke
    Save-Status 'transcribing' 'Transcribing root MP3 files on CPU'
    & "$PSScriptRoot/start_cpu_asr.ps1"
    Save-Status 'needs_review' 'ASR complete; review transcripts and prepare corpus and checkpoint before TTS training'
} catch {
    Save-Status 'failed' $_.Exception.Message
    throw
}
