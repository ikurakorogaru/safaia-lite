param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$GradleCache = (Join-Path $env:USERPROFILE '.gradle\caches')
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
if (-not $JdkHome) {
    $candidates = @(Get-ChildItem -Path "$env:ProgramFiles\Java\jdk-25*", "$env:ProgramFiles\Eclipse Adoptium\jdk-25*" -Directory -ErrorAction SilentlyContinue)
    if ($candidates.Count -gt 0) { $JdkHome = $candidates[0].FullName }
}
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
    throw 'JDK 25 is required. Use -JdkHome <JDK directory>.'
}
$javaTool = Join-Path $JdkHome 'bin\java.exe'
$compilerTool = Join-Path $JdkHome 'bin\javac.exe'
$archiveTool = Join-Path $JdkHome 'bin\jar.exe'
$dependencyJars = @(Get-ChildItem -LiteralPath (Join-Path $GradleCache 'modules-2\files-2.1') -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' -and $_.FullName -notmatch '0\.158\.0\+' })
$minecraftJars = @(Get-ChildItem -LiteralPath (Join-Path $GradleCache 'fabric-loom\minecraftMaven') -Recurse -Filter '*-26.2.jar')
if ($minecraftJars.Count -lt 2) { throw 'Minecraft 26.2 Loom cache is missing. Use gradlew.bat build to resolve dependencies first.' }
$compileClasspath = (($dependencyJars + $minecraftJars) | Sort-Object FullName | ForEach-Object FullName) -join ';'
$buildRoot = Join-Path $projectRoot 'build\manual'
$classesRoot = [IO.Path]::GetFullPath((Join-Path $buildRoot 'classes'))
if (-not $classesRoot.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Build output must stay inside this project.'
}
if (Test-Path -LiteralPath $classesRoot) { Remove-Item -LiteralPath $classesRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesRoot | Out-Null
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$compileArguments = @('--release','25','-encoding','UTF-8','-proc:none','-classpath',$compileClasspath,'-d',$classesRoot) + $sourceFiles
$argumentFile = Join-Path $buildRoot 'compile.args'
$quotedArguments = $compileArguments | ForEach-Object { '"' + $_.Replace('\','/') + '"' }
[IO.File]::WriteAllLines($argumentFile, $quotedArguments, [Text.UTF8Encoding]::new($false))
& $compilerTool '-J-Duser.language=en' "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }

$testRoot = Join-Path $projectRoot 'src\test\java'
if (Test-Path -LiteralPath $testRoot) {
    $testClasses = Join-Path $buildRoot 'test-classes'
    New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
    $testSources = @(Get-ChildItem -LiteralPath $testRoot -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $compilerTool '--release' '25' '-encoding' 'UTF-8' '-cp' $classesRoot '-d' $testClasses @testSources
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    Push-Location $projectRoot
    try {
        & $javaTool '-cp' "$classesRoot;$testClasses" 'com.example.client.CoreChecks' 2>&1 |
            Tee-Object -FilePath (Join-Path $buildRoot 'test-results.txt')
        if ($LASTEXITCODE -ne 0) { throw 'Core checks failed.' }
    } finally { Pop-Location }
}
$resourcesRoot = Join-Path $projectRoot 'src\main\resources'
$metadata = Get-Content -LiteralPath (Join-Path $resourcesRoot 'fabric.mod.json') -Raw | ConvertFrom-Json
$artifactPath = Join-Path $buildRoot "safaia-$($metadata.version).jar"
$licenseRoot = Join-Path $buildRoot 'license'
New-Item -ItemType Directory -Force -Path $licenseRoot | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $licenseRoot 'LICENSE_safaia')
& $archiveTool '--create' '--file' $artifactPath '--date=2026-09-15T00:00:00Z' '-C' $classesRoot '.' '-C' $resourcesRoot '.' '-C' $licenseRoot 'LICENSE_safaia'
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }
Write-Output "Built and checked: $artifactPath"
