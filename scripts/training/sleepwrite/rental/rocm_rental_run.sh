#!/bin/bash
# The ROCm trainer proof on a rented AMD box, from this tree. Usage:
#   scripts/training/sleepwrite/rental/rocm_rental_run.sh <user@host> [port]
# Copies bin/wyrd, the sleepwrite scripts and this directory to ~/wyrd-src on the box, starts
# rocm_pod_run.sh there under nohup, and prints how to follow it.
set -u
HOST="$1"; PORT="${2:-22}"
R="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$R" || exit 1
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/bin" "$STAGE/scripts/training/sleepwrite" "$STAGE/pod"
cp bin/wyrd "$STAGE/bin/"; cp scripts/training/sleepwrite/*.py "$STAGE/scripts/training/sleepwrite/"
cp scripts/training/sleepwrite/rental/rocm_pod_run.sh scripts/training/sleepwrite/rental/make_corpus.py "$STAGE/pod/"
SSH="ssh -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 -p $PORT $HOST"
rsync -az -e "ssh -p $PORT -o StrictHostKeyChecking=accept-new" "$STAGE/" "$HOST:wyrd-src/" || exit 3
$SSH "chmod +x ~/wyrd-src/pod/*.sh; nohup bash ~/wyrd-src/pod/rocm_pod_run.sh > ~/pod-run.log 2>&1 & echo started pid \$!"
echo "follow: $SSH 'tail -f ~/pod-run.log'    result: ~/wyrd/summary.json"
