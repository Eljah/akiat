param([string]$Report)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) { $jdk = 'D:\Java\jdk-11.0.15' }
$jar = Join-Path $PSScriptRoot 'build\akiat-review-11.jar'
if (-not (Test-Path $jar)) { & (Join-Path $PSScriptRoot 'build.ps1') }
if (-not $Report) { $Report = (Get-Content (Join-Path $repo 'outputs\corpus_review\LATEST.txt') -Raw -Encoding UTF8).Trim() }
# This is the interactive editor requested by the user; its desktop window is visible.
$worker = Start-Process -FilePath (Join-Path $jdk 'bin\javaw.exe') -ArgumentList @('-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"'),('"'+$Report+'"')) -WorkingDirectory $repo -PassThru
Write-Output "Akiat desktop PID=$($worker.Id)"
