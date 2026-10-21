$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$env:PYTHONUTF8 = '1'
$logRoot = Join-Path $repoRoot 'outputs\night_pipeline'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
foreach ($stage in @('verify', 'smoke_train')) {
    # Python/Lightning write normal progress to stderr. Capture it without
    # PowerShell 5 turning each line into a NativeCommandError record.
    $check = Start-Process -FilePath wsl.exe -ArgumentList @('-d', 'Ubuntu', '--',
        '/mnt/d/Java/akiat/.tools/piper-venv/bin/python', '-u',
        '/mnt/d/Java/akiat/training/piper_stage.py', $stage) -WindowStyle Hidden -Wait -PassThru `
        -RedirectStandardOutput (Join-Path $logRoot "$stage.stdout.log") `
        -RedirectStandardError (Join-Path $logRoot "$stage.stderr.log")
    if ($check.ExitCode -ne 0) { throw "Piper $stage failed with code $($check.ExitCode); see $logRoot\$stage.stderr.log" }
    Write-Output "PASS: $stage"
}
Write-Output 'Piper checkpoint, synthesis, preprocessing and one training step verified.'
exit 0
