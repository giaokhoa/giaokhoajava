#!/usr/bin/env bash
# Chromium chạy trong image chính thức; Node, Java và Compose tiếp tục chạy trên host Linux.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="$(node -p 'require(process.argv[1]).version' "$ROOT/frontend/node_modules/playwright/package.json")"

docker run --detach --init --network host --ipc=host \
  --name giaokhoa-playwright \
  --volume "$ROOT/frontend:/work:ro" --workdir /work \
  "mcr.microsoft.com/playwright:v${version}-noble" \
  node node_modules/playwright/cli.js run-server --host 127.0.0.1 --port 3002

for attempt in $(seq 1 30); do
  if curl --fail --silent --max-time 2 http://127.0.0.1:3002/ >/dev/null; then
    exit 0
  fi
  sleep 1
done

docker logs giaokhoa-playwright
echo 'Playwright Server không sẵn sàng ở 127.0.0.1:3002.' >&2
exit 1
