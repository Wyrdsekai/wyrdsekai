#!/usr/bin/env bash
# Bootstrap the dedicated SetFit venv. SetFit 1.1.3 hard-requires transformers<5
# (it imports transformers.training_args.default_logdir, removed in 5.x), which
# conflicts with the main .venv-home-server (transformers 5.x). So the contrastive
# encoder pretrain + ONNX export run in their own isolated venv.
#
# Used by: scripts/classifier/train_setfit.py + export_setfit_encoder_onnx.py,
# and the retrain-classifier-head recipe's setfit-pretrain step (which resolves
# this venv's python if present, else falls back to system python3).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../training" && pwd)"
VENV="$HERE/.venv-setfit"
PYBIN="${PYTHON:-python3}"
if [ ! -x "$VENV/bin/python" ]; then
  echo "[setfit-venv] creating $VENV"
  "$PYBIN" -m venv "$VENV"
fi
"$VENV/bin/pip" install -q --upgrade pip
"$VENV/bin/pip" install -q \
  setfit 'transformers>=4.46,<5' sentence-transformers 'optimum[onnxruntime]' \
  skl2onnx onnx onnxruntime numpy
"$VENV/bin/python" - <<'PY'
import setfit, transformers
from optimum.onnxruntime import ORTModelForFeatureExtraction  # noqa
assert transformers.__version__ < "5", transformers.__version__
print(f"[setfit-venv] OK — setfit {setfit.__version__}, transformers {transformers.__version__}")
PY
