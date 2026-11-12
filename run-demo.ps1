$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$jar = Join-Path $PSScriptRoot "demo-runner\target\fase4-demo-runner.jar"
if (-not (Test-Path $jar)) {
    Write-Host "Building demo runner..."
    Push-Location "demo-runner"
    .\mvnw.cmd -q -DskipTests package
    Pop-Location
}

Write-Host "Running Fase 4 demo..."
java -jar $jar @args
