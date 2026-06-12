#!/usr/bin/env bash
# Fresh-install smoke test for the telegram-files image.
#
# Boots the given image with an EMPTY data directory (SQLite mode), waits for
# /api/health to return 200, then fails on any ERROR/Exception/"no such column"
# in the container logs.
#
# Usage: misc/smoke-test.sh [IMAGE]
#   IMAGE  image tag to test (default: telegram-files:smoke)
#
# Exit code: 0 = healthy fresh install, non-zero = failure (logs printed).
set -u

IMAGE="${1:-telegram-files:smoke}"
NAME="tf-smoke-$$"
PORT=18979
DATA_DIR="$(mktemp -d /tmp/tf-smoke.XXXXXX)"

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1
    rm -rf "$DATA_DIR"
}
trap cleanup EXIT

echo "==> Smoke test: $IMAGE (port $PORT, data $DATA_DIR)"

docker run -d --name "$NAME" \
    -e APP_ENV=prod \
    -e APP_ROOT=/app/data \
    -e TELEGRAM_API_ID=12345 \
    -e TELEGRAM_API_HASH=smoke-test \
    -e LOG_LEVEL=INFO \
    -p "$PORT:80" \
    -v "$DATA_DIR:/app/data" \
    "$IMAGE" >/dev/null || { echo "FAIL: container did not start"; exit 1; }

echo "==> Waiting for /api/health (max 60s)"
healthy=0
for _ in $(seq 1 30); do
    if curl -fsS -o /dev/null "http://localhost:$PORT/api/health"; then
        healthy=1
        break
    fi
    sleep 2
done

if [ "$healthy" -ne 1 ]; then
    echo "FAIL: /api/health never returned 200"
    docker logs "$NAME" 2>&1 | tail -50
    exit 1
fi
echo "OK: health endpoint up"

echo "==> Checking API spot endpoints"
fail=0
for path in /api/health /api/telegrams; do
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT$path")
    if [ "$code" != "200" ]; then
        echo "FAIL: GET $path -> $code"
        fail=1
    else
        echo "OK: GET $path -> 200"
    fi
done

echo "==> Scanning logs for errors"
if docker logs "$NAME" 2>&1 | grep -iE "no such column|SEVERE|Exception" | grep -v "TDLib" | head -20 | grep -q .; then
    echo "FAIL: error lines found in logs:"
    docker logs "$NAME" 2>&1 | grep -iE "no such column|SEVERE|Exception" | grep -v "TDLib" | head -20
    fail=1
else
    echo "OK: no error lines in logs"
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "==> SMOKE TEST PASSED"
