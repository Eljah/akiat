param([switch]$Worker)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$asr = Join-Path $repoRoot 'asr'
New-Item -ItemType Directory -Force -Path $asr | Out-Null
if ($Worker) {
    try {
        & "$PSScriptRoot/start_cpu_asr.ps1" -Smoke
        & "$PSScriptRoot/start_cpu_asr.ps1"
        @{stage='needs_review'; detail='ASR complete; review draft transcripts'; updated=(Get-Date -Format o)} |
            ConvertTo-Json | Set-Content -Encoding UTF8 "$asr/cpu_status.json"
    } catch {
        @{stage='failed'; detail=$_.Exception.Message; updated=(Get-Date -Format o)} |
            ConvertTo-Json | Set-Content -Encoding UTF8 "$asr/cpu_status.json"
        throw
    }
    exit
}
$existing = Get-CimInstance Win32_Process | Where-Object {
    ($_.Name -eq 'powershell.exe' -and $_.CommandLine -like "*$PSCommandPath*" -and $_.CommandLine -match '-Worker') -or
    ($_.Name -match '^python' -and $_.CommandLine -match 'training[/\\]transcribe_cpu.py')
}
if ($existing) { throw "ASR already running: $($existing.ProcessId -join ', ')" }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdout = Join-Path $asr "cpu_safe.$stamp.log"
$stderr = Join-Path $asr "cpu_safe.$stamp.err.log"
$process = Start-Process powershell.exe -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Worker'
) -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
$process.Id | Set-Content "$asr/cpu_pipeline.pid"
@{pid=$process.Id; stdout=$stdout; stderr=$stderr; started=(Get-Date -Format o)} |
    ConvertTo-Json | Set-Content -Encoding UTF8 "$asr/cpu_safe_run.json"
Write-Output "Started safe CPU pipeline: PID=$($process.Id); log=$stdout; errors=$stderr"
