#!/usr/bin/env bash
# Requires reportPreview output. The container mounts the checkout read-only.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
test -f build/report-previews/fractional.html || {
    echo 'Generate reports first: ./gradlew reportPreview' >&2
    exit 1
}
# Keep the browser image and npm package on the same release.
mkdir -p build/report-ui-verification
echo 'Starting the browser-check container (Docker downloads the image on the first run).'
docker run --rm --init --shm-size=1g \
    --mount "type=bind,source=$PWD,target=/workspace,readonly" \
    --mount "type=bind,source=$PWD/build/report-ui-verification,target=/artifacts" \
    --env "REPORT_SERIES_DEMO=${1:-}" \
    --workdir /workspace \
    mcr.microsoft.com/playwright:v1.64.1-noble \
    bash -euc '
        echo "[1/4] Installing the Playwright npm package (network required, timeout 120s)."
        if ! timeout --kill-after=5s 120s npm install --prefix /tmp/report-ui \
            --ignore-scripts --no-audit --no-fund --loglevel=http \
            --fetch-retries=1 --fetch-timeout=30000 \
            --fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=3000 playwright@1.64.1; then
            echo "Playwright installation failed or timed out. Check the npm output above and network access from Docker." >&2
            exit 1
        fi
        export NODE_PATH=/tmp/report-ui/node_modules
        echo "[2/4] Checking report controls."
        node src/test/javascript/report-controls.test.js
        echo "[3/4] Checking standalone reports in Chromium."
        node src/test/javascript/report-layout.test.js
        echo "[4/4] Checking budget-series reports in Chromium."
        REPORT_UI_ARTIFACTS=/artifacts node src/test/javascript/series-layout.test.js "$REPORT_SERIES_DEMO"
        echo "Browser checks passed. Screenshots: build/report-ui-verification/"
    '
