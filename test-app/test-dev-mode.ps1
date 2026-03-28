#
# Integration test for play-manage-maven-plugin dev mode hot-reload.
#
# Tests:
#   1. App starts and /health returns "healthy-v1"
#   2. Java source edit -> hot-reload -> /health returns "healthy-v2"
#   3. Twirl template edit -> hot-reload -> /status returns "status-v2"
#
# Usage:
#   # First, install the plugin from the repo root:
#   mvn -f ..\pom.xml clean install
#   # Then run this script from test-app/:
#   .\test-dev-mode.ps1
#

$ErrorActionPreference = "Stop"

$Port = 9000
$BaseUrl = "http://localhost:$Port"
$StartupTimeout = 90
$ReloadTimeout = 45
$DevServerProcess = $null

function Cleanup {
    if ($DevServerProcess -and !$DevServerProcess.HasExited) {
        Write-Host "Stopping dev server (PID $($DevServerProcess.Id))..."
        # Kill the entire process tree (mvn wrapper + java child) on Windows
        & taskkill /F /T /PID $DevServerProcess.Id 2>$null | Out-Null
        $DevServerProcess.WaitForExit(5000) | Out-Null
    }
    # Also kill any orphaned java process still on port 9000
    $portPid = (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue |
                Select-Object -First 1).OwningProcess
    if ($portPid) {
        Write-Host "Killing orphaned process on port $Port (PID $portPid)..."
        Stop-Process -Id $portPid -Force -ErrorAction SilentlyContinue
    }
}

trap { Cleanup; break }

# ------------------------------------------------------------------
# Helper: poll an endpoint until body matches expected string
# ------------------------------------------------------------------
function Poll-Until {
    param(
        [string]$Url,
        [string]$Expected,
        [int]$Timeout,
        [string]$Description
    )

    Write-Host "Waiting for $Description (timeout ${Timeout}s)..."
    $deadline = (Get-Date).AddSeconds($Timeout)

    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
            $body = $response.Content.Trim()
            if ($body -eq $Expected) {
                Write-Host "  OK: $Description"
                return
            }
        } catch {
            # Server not ready yet
        }
        Start-Sleep -Seconds 1
    }

    # Final attempt for error message
    $lastBody = "<no response>"
    try {
        $lastBody = (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2).Content.Trim()
    } catch {}

    Write-Host "  FAIL: $Description - timed out after ${Timeout}s"
    Write-Host "  Expected: '$Expected', last got: '$lastBody'"
    Cleanup
    exit 1
}

# ------------------------------------------------------------------
# Step 0a: Reset source files to v1 (idempotent)
# ------------------------------------------------------------------
$controllerPath = "app\controllers\HealthController.java"
$templatePath = "app\views\status.scala.html"
(Get-Content $controllerPath) -replace 'healthy-v2', 'healthy-v1' | Set-Content $controllerPath
(Get-Content $templatePath) -replace 'status-v2', 'status-v1' | Set-Content $templatePath

# ------------------------------------------------------------------
# Step 0b: Build the test app (routes, templates, compile)
# ------------------------------------------------------------------
Write-Host "=== Building test app ==="
mvn -q generate-sources compile process-classes
if ($LASTEXITCODE -ne 0) {
    Write-Host "FAIL: Initial build failed"
    exit 1
}

# ------------------------------------------------------------------
# Step 1: Start dev server in background
# ------------------------------------------------------------------
Write-Host "=== Starting dev server on port $Port ==="
$DevServerProcess = Start-Process -FilePath "mvn" `
    -ArgumentList "play-manage:run", "-Dplay.httpPort=$Port" `
    -RedirectStandardOutput "dev-server.log" `
    -RedirectStandardError "dev-server-err.log" `
    -PassThru -NoNewWindow

Write-Host "Dev server PID: $($DevServerProcess.Id)"

# ------------------------------------------------------------------
# Step 2: Wait for initial startup and verify /health
# ------------------------------------------------------------------
Poll-Until -Url "$BaseUrl/health" -Expected "healthy-v1" -Timeout $StartupTimeout `
    -Description "/health returns 'healthy-v1'"

# ------------------------------------------------------------------
# Step 3: Edit Java source -> verify hot-reload
# ------------------------------------------------------------------
Write-Host "=== Editing HealthController.java (v1 -> v2) ==="
$controllerPath = "app\controllers\HealthController.java"
(Get-Content $controllerPath) -replace 'healthy-v1', 'healthy-v2' | Set-Content $controllerPath

Poll-Until -Url "$BaseUrl/health" -Expected "healthy-v2" -Timeout $ReloadTimeout `
    -Description "/health returns 'healthy-v2' after Java hot-reload"

# ------------------------------------------------------------------
# Step 4: Edit Twirl template -> verify hot-reload
# ------------------------------------------------------------------
Write-Host "=== Editing status.scala.html (v1 -> v2) ==="
$templatePath = "app\views\status.scala.html"
(Get-Content $templatePath) -replace 'status-v1', 'status-v2' | Set-Content $templatePath

Poll-Until -Url "$BaseUrl/status" -Expected "status-v2" -Timeout $ReloadTimeout `
    -Description "/status returns 'status-v2' after template hot-reload"

# ------------------------------------------------------------------
# Done
# ------------------------------------------------------------------
Cleanup
Write-Host ""
Write-Host "=== ALL TESTS PASSED ==="
exit 0
