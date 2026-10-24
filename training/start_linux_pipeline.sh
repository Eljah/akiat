#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p outputs/night_pipeline
nohup .tools/piper-venv/bin/python -u training/run_linux_pipeline.py \
    >> outputs/night_pipeline/linux_pipeline.log 2>&1 < /dev/null &
runner=$!
sleep 1
kill -0 "$runner"
printf 'Linux pipeline started: PID=%s\n' "$runner"
