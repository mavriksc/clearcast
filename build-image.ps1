param(
    [switch]$Push
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$configFile = Join-Path (Get-Location) ".build.env"
if (Test-Path $configFile) {
    . $configFile
}

$Image = if ($env:DOCKER_IMAGE) { $env:DOCKER_IMAGE } else { "smcarlisle/clearcast" }
$Tag = (git rev-parse --short=8 HEAD).Trim()

Write-Host "Building image ${Image}:${Tag} and ${Image}:latest..."
docker build -t "${Image}:${Tag}" -t "${Image}:latest" .

$shouldPush = $Push -or ($env:DOCKER_PUSH -match '^(1|true|yes)$')
if ($shouldPush) {
    Write-Host "Pushing ${Image}:${Tag}..."
    try {
        docker push "${Image}:${Tag}"
    } catch {
        Write-Warning "Push failed for ${Image}:${Tag}. Ensure you are logged in and have access."
        exit 1
    }
    Write-Host "Pushing ${Image}:latest..."
    try {
        docker push "${Image}:latest"
    } catch {
        Write-Warning "Push failed for ${Image}:latest. Ensure you are logged in and have access."
        exit 1
    }
}
