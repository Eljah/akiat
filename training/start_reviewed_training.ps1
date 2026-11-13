param([switch]$Worker, [string]$RunDirectory, [switch]$Resume, [int]$Epochs=40)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo
if ($Resume -and -not $RunDirectory) { throw 'Resume requires an explicit RunDirectory' }
if (-not $RunDirectory) { $RunDirectory = Join-Path $repo ('outputs\reviewed_' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$RunDirectory = [IO.Path]::GetFullPath($RunDirectory)
if ($Worker) {
    $linuxRepo = '/mnt/' + $repo.Substring(0,1).ToLower() + $repo.Substring(2).Replace('\','/')
    $linuxRun = '/mnt/' + $RunDirectory.Substring(0,1).ToLower() + $RunDirectory.Substring(2).Replace('\','/')
    $resumeArgs = @()
    if ($Resume) { $resumeArgs += '--resume' }
    & wsl.exe -d Ubuntu -- "$linuxRepo/.tools/piper-venv/bin/python" -u "$linuxRepo/training/train_reviewed.py" --run-dir $linuxRun --epochs $Epochs @resumeArgs
    if ($LASTEXITCODE -ne 0) { throw "Reviewed training failed: exit $LASTEXITCODE" }
    exit
}
if ((Get-PSDrive C).Free -lt 4GB) { throw 'Need at least 4 GiB free on C before local training' }
if ((Get-PSDrive D).Free -lt 8GB) { throw 'Need at least 8 GiB free on D before local training' }
if ($Resume) {
    if (-not (Test-Path -LiteralPath (Join-Path $RunDirectory 'checkpoints\last.ckpt'))) { throw 'Resume checkpoint missing' }
} elseif (Test-Path -LiteralPath $RunDirectory) { throw 'Run directory already exists; use a fresh directory' }
$logBase = $RunDirectory
if ($Resume) { $logBase += '.resume-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
$stdout = $logBase + '.log'
$stderr = $logBase + '.err.log'
$workerArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$PSCommandPath+'"'),'-Worker','-RunDirectory',('"'+$RunDirectory+'"'),'-Epochs',$Epochs)
if ($Resume) { $workerArgs += '-Resume' }
$process = Start-Process powershell.exe -WindowStyle Hidden -WorkingDirectory $repo -PassThru `
    -ArgumentList $workerArgs `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr
@{pid=$process.Id;run=$RunDirectory;stdout=$stdout;stderr=$stderr;resume=[bool]$Resume;epochs=$Epochs} | ConvertTo-Json | Set-Content -Encoding UTF8 ($logBase+'.launch.json')
$monitor = Get-ScheduledTask -TaskName AkiatFreezeMonitor -ErrorAction SilentlyContinue
if ($monitor -and $monitor.State -ne 'Running') { Start-ScheduledTask -TaskName AkiatFreezeMonitor }
Write-Output "Started reviewed training PID=$($process.Id); run=$RunDirectory"
