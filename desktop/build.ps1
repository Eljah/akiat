param([switch]$Test)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) { $jdk = 'D:\Java\jdk-11.0.15' }
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
$build = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $javac -encoding UTF-8 --release 11 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed (JDK 11+ required)' }
& $jar --create --file (Join-Path $build 'akiat-review-2.jar') --main-class akiat.review.ReviewApp -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR build failed' }
if ($Test) {
    $tests = @(Get-ChildItem (Join-Path $PSScriptRoot 'test') -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $javac -encoding UTF-8 --release 11 -cp $classes -d $classes $tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    $report = (Get-Content (Join-Path $repo 'outputs\corpus_review\LATEST.txt') -Raw -Encoding UTF8).Trim()
    & $java '-Dfile.encoding=UTF-8' -cp $classes akiat.review.ReviewTests $report (Join-Path $repo 'outputs\desktop_test')
    if ($LASTEXITCODE -ne 0) { throw 'Desktop tests failed' }
}
Write-Output "Built $build\akiat-review-2.jar"
