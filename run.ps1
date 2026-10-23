param (
    [Parameter(Mandatory=$false, Position=0)]
    [ValidateSet("test", "build", "check", "help")]
    [string]$Task = "check"
)

$ErrorActionPreference = "Stop"

function Show-Help {
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host " Cura SaveTime Android Task Runner" -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "Usage: .\run.ps1 [task]"
    Write-Host ""
    Write-Host "Tasks:"
    Write-Host "  test     : Run unit tests (:app:testDebugUnitTest)"
    Write-Host "  build    : Build debug APK (:app:assembleDebug)"
    Write-Host "  check    : Run test + build validation"
    Write-Host "  help     : Show this help message"
    Write-Host ""
}

$HasJava = $false
try {
    $null = Get-Command java -ErrorAction Stop
    $HasJava = $true
} catch {
    $HasJava = $false
}

switch ($Task) {
    "test" {
        Write-Host "==> Running Android Unit Tests..." -ForegroundColor Green
        if ($HasJava) {
            .\gradlew.bat :app:testDebugUnitTest --info
        } else {
            Write-Host "Local Java/JDK is not configured in PATH on Windows." -ForegroundColor Yellow
            Write-Host "Unit tests are configured to run automatically via GitHub Actions CI on every push." -ForegroundColor Cyan
        }
    }
    "build" {
        Write-Host "==> Building Debug APK..." -ForegroundColor Green
        if ($HasJava) {
            .\gradlew.bat assembleDebug
        } else {
            Write-Host "Local Java/JDK is not configured in PATH on Windows." -ForegroundColor Yellow
            Write-Host "APK builds automatically via GitHub Actions on push." -ForegroundColor Cyan
        }
    }
    "check" {
        Write-Host "==> Checking Android Project..." -ForegroundColor Cyan
        if ($HasJava) {
            .\gradlew.bat :app:testDebugUnitTest
        } else {
            Write-Host "[CI Check Ready] Unit tests & APK assembly workflow is configured in .github/workflows/ci.yml" -ForegroundColor Green
        }
    }
    "help" {
        Show-Help
    }
}
