# Re-publish CI workflow files after granting GitHub `workflow` scope:
#   gh auth refresh -h github.com -s repo,workflow,read:org
$ErrorActionPreference = "Stop"
$repos = @(
  "Fiap-Pos-Tech-Challenge-os-service",
  "Fiap-Pos-Tech-Challenge-billing-service",
  "Fiap-Pos-Tech-Challenge-execution-service"
)
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

foreach ($name in $repos) {
  $path = Join-Path $root $name
  Set-Location $path

  $lines = Get-Content .gitignore | Where-Object {
    $_ -notmatch "temporarily ignored until workflow scope" -and
    $_ -ne ".github/workflows/ci-cd.yml"
  }
  $lines | Set-Content .gitignore

  git add .gitignore .github/workflows/ci-cd.yml
  git commit -m "ci: restore GitHub Actions workflow pipeline"
  git push
  Write-Host "Restored workflow for $name"
}
