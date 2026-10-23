param (
    [Parameter(Mandatory=$false, Position=0)]
    [ValidateSet("test", "lint", "format", "check", "help")]
    [string]$Task = "check"
)

$ErrorActionPreference = "Stop"

# Ensure Node.js and user PATH are loaded
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
}

function Show-Help {
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host " AI-Driven Development Task Runner" -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "Usage: .\run.ps1 [task]"
    Write-Host ""
    Write-Host "Tasks:"
    Write-Host "  test     : Run all automated tests"
    Write-Host "  lint     : Run linter and style checks"
    Write-Host "  format   : Automatically format code"
    Write-Host "  check    : Run format check + lint + tests"
    Write-Host "  help     : Show this help message"
    Write-Host ""
}

$PythonCmd = if (Test-Path ".\.venv\Scripts\python.exe") { ".\.venv\Scripts\python.exe" } else { "python" }
$PytestCmd = if (Test-Path ".\.venv\Scripts\pytest.exe") { ".\.venv\Scripts\pytest.exe" } else { "pytest" }
$RuffCmd   = if (Test-Path ".\.venv\Scripts\ruff.exe")   { ".\.venv\Scripts\ruff.exe" }   else { "ruff" }
$HasNode   = Test-Path "package.json"

switch ($Task) {
    "test" {
        Write-Host "==> Running tests..." -ForegroundColor Green
        if (Test-Path "pyproject.toml") {
            & $PytestCmd -v
        } elseif ($HasNode) {
            npm test
        } else {
            Write-Host "No test configuration found." -ForegroundColor Yellow
        }
    }
    "lint" {
        Write-Host "==> Running linter..." -ForegroundColor Green
        if (Test-Path "pyproject.toml") {
            & $RuffCmd check .
        } elseif ($HasNode) {
            npm run lint
        }
    }
    "format" {
        Write-Host "==> Formatting code..." -ForegroundColor Green
        if (Test-Path "pyproject.toml") {
            & $RuffCmd format .
            & $RuffCmd check --fix .
        } elseif ($HasNode) {
            npm run format
        }
    }
    "check" {
        Write-Host "==> Running full quality check (Format / Lint / Test)..." -ForegroundColor Cyan
        if (Test-Path "pyproject.toml") {
            Write-Host "[1/3] Checking format..."
            & $RuffCmd format --check .
            Write-Host "[2/3] Checking lint..."
            & $RuffCmd check .
            Write-Host "[3/3] Running tests..."
            & $PytestCmd
        } elseif ($HasNode) {
            Write-Host "[1/3] Checking types..."
            npm run typecheck
            Write-Host "[2/3] Checking lint..."
            npm run lint
            Write-Host "[3/3] Running tests..."
            npm test
        }
        Write-Host "==> All checks passed successfully!" -ForegroundColor Green
    }
    Default {
        Show-Help
    }
}
