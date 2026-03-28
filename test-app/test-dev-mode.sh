#!/usr/bin/env bash
#
# Integration test for play-manage-maven-plugin dev mode hot-reload.
#
# Tests:
#   1. App starts and /health returns "healthy-v1"
#   2. Java source edit → hot-reload → /health returns "healthy-v2"
#   3. Twirl template edit → hot-reload → /status returns "status-v2"
#
# Usage:
#   # First, install the plugin from the repo root:
#   mvn -f ../pom.xml clean install
#   # Then run this script:
#   bash test-dev-mode.sh
#
set -euo pipefail

PORT=9000
BASE_URL="http://localhost:${PORT}"
STARTUP_TIMEOUT=90
RELOAD_TIMEOUT=45
DEV_SERVER_PID=""

cleanup() {
    if [[ -n "${DEV_SERVER_PID}" ]]; then
        echo "Stopping dev server (PID ${DEV_SERVER_PID})..."
        kill "${DEV_SERVER_PID}" 2>/dev/null || true
        wait "${DEV_SERVER_PID}" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ------------------------------------------------------------------
# Helper: poll an endpoint until body matches expected string
# Usage: poll_until <url> <expected> <timeout_seconds> <description>
# ------------------------------------------------------------------
poll_until() {
    local url="$1" expected="$2" timeout="$3" desc="$4"
    local deadline=$((SECONDS + timeout))

    echo "Waiting for ${desc} (timeout ${timeout}s)..."
    while (( SECONDS < deadline )); do
        local body
        body=$(curl -sf "${url}" 2>/dev/null) || true
        # Trim whitespace for template responses
        body=$(echo "${body}" | tr -d '[:space:]')
        expected_trimmed=$(echo "${expected}" | tr -d '[:space:]')
        if [[ "${body}" == "${expected_trimmed}" ]]; then
            echo "  OK: ${desc}"
            return 0
        fi
        sleep 1
    done

    echo "  FAIL: ${desc} — timed out after ${timeout}s"
    echo "  Expected: '${expected}', last got: '$(curl -sf "${url}" 2>/dev/null || echo "<no response>")'"
    return 1
}

# ------------------------------------------------------------------
# Step 0a: Reset source files to v1 (idempotent)
# ------------------------------------------------------------------
sed -i 's/healthy-v2/healthy-v1/' app/controllers/HealthController.java
sed -i 's/status-v2/status-v1/' app/views/status.scala.html

# ------------------------------------------------------------------
# Step 0b: Build the test app (routes, templates, compile)
# ------------------------------------------------------------------
echo "=== Building test app ==="
mvn -q generate-sources compile process-classes

# ------------------------------------------------------------------
# Step 1: Start dev server in background
# ------------------------------------------------------------------
echo "=== Starting dev server on port ${PORT} ==="
mvn play-manage:run -Dplay.httpPort="${PORT}" > dev-server.log 2>&1 &
DEV_SERVER_PID=$!
echo "Dev server PID: ${DEV_SERVER_PID}"

# ------------------------------------------------------------------
# Step 2: Wait for initial startup and verify /health
# ------------------------------------------------------------------
poll_until "${BASE_URL}/health" "healthy-v1" "${STARTUP_TIMEOUT}" "/health returns 'healthy-v1'"

# ------------------------------------------------------------------
# Step 3: Edit Java source → verify hot-reload
# ------------------------------------------------------------------
echo "=== Editing HealthController.java (v1 → v2) ==="
sed -i 's/healthy-v1/healthy-v2/' app/controllers/HealthController.java

poll_until "${BASE_URL}/health" "healthy-v2" "${RELOAD_TIMEOUT}" "/health returns 'healthy-v2' after Java hot-reload"

# ------------------------------------------------------------------
# Step 4: Edit Twirl template → verify hot-reload
# ------------------------------------------------------------------
echo "=== Editing status.scala.html (v1 → v2) ==="
sed -i 's/status-v1/status-v2/' app/views/status.scala.html

poll_until "${BASE_URL}/status" "status-v2" "${RELOAD_TIMEOUT}" "/status returns 'status-v2' after template hot-reload"

# ------------------------------------------------------------------
# Done
# ------------------------------------------------------------------
echo ""
echo "=== ALL TESTS PASSED ==="
