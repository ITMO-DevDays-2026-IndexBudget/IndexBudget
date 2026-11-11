#!/usr/bin/env bash
# Requires reportPreview output. The container mounts the checkout read-only.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
test -f build/report-previews/fractional.html || {
    echo 'Generate reports first: ./gradlew reportPreview' >&2
    exit 1
}
# Keep the browser image and npm package on the same release.
docker run --rm --init --shm-size=1g \
    --mount "type=bind,source=$PWD,target=/workspace,readonly" \
    --workdir /workspace \
    mcr.microsoft.com/playwright:v1.64.1-noble \
    bash -euc '
        npm install --prefix /tmp/report-ui --ignore-scripts --no-audit --no-fund playwright@1.64.1
        export NODE_PATH=/tmp/report-ui/node_modules
        node src/test/javascript/report-controls.test.js
        node src/test/javascript/report-layout.test.js
    '
