$ErrorActionPreference = "Stop"

$BackendRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $BackendRoot
$Python = Join-Path $BackendRoot ".venv\Scripts\python.exe"

if (-not (Test-Path $Python)) {
    throw "Backend virtual environment was not found at $Python"
}

Push-Location $BackendRoot
try {
    & $Python -m pip install pyinstaller
    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onedir `
        --name "AI-Face-Studio-Backend" `
        --distpath (Join-Path $RepoRoot "dist\windows") `
        --workpath (Join-Path $RepoRoot "build\pyinstaller") `
        --add-data "web;web" `
        --add-data "model_store;model_store" `
        --collect-all "insightface" `
        --collect-all "onnxruntime" `
        --collect-all "cv2" `
        --collect-all "PIL" `
        --hidden-import "multipart.multipart" `
        --hidden-import "python_multipart.multipart" `
        launcher.py
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Bundle created at:"
Write-Host (Join-Path $RepoRoot "dist\windows\AI-Face-Studio-Backend\AI-Face-Studio-Backend.exe")
