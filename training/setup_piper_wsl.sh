#!/usr/bin/env bash
set -euo pipefail
cd /mnt/d/Java/akiat
export PIP_CACHE_DIR="$PWD/.cache/pip-wsl"
export TMPDIR="$PWD/.tools/tmp-wsl"
export OMP_NUM_THREADS=1 MKL_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1
mkdir -p "$TMPDIR"
if [ ! -d .tools/piper ]; then
  git clone https://github.com/rhasspy/piper.git .tools/piper
  git -C .tools/piper checkout 73c04d81d5590ecc46e522de3601ce7fb29fc2be
fi
python3 -m venv .tools/piper-venv
PY="$PWD/.tools/piper-venv/bin/python"
"$PY" -m pip install 'pip==24.0' 'setuptools==69.5.1' wheel
if [ -f .cache/wheels/torch-1.13.1+cpu-cp310-cp310-linux_x86_64.whl ]; then
  echo '11692523b87c45b79ddfb5148b12a713d85235d399915490d94e079521f7e014  .cache/wheels/torch-1.13.1+cpu-cp310-cp310-linux_x86_64.whl' | sha256sum --check
  "$PY" -m pip install .cache/wheels/torch-1.13.1+cpu-cp310-cp310-linux_x86_64.whl
else
  "$PY" -m pip install 'torch==1.13.1+cpu' --index-url https://download.pytorch.org/whl/cpu
fi
"$PY" -m pip install 'six==1.17.0' 'numpy==1.24.4' 'cython==0.29.37' 'scipy==1.10.1' 'numba==0.57.1' \
  'librosa==0.10.1' 'pytorch-lightning==1.7.7' 'torchmetrics==0.11.4' \
  'onnxruntime==1.17.3' 'onnx==1.15.0' 'protobuf==4.25.3' 'piper-phonemize==1.1.0'
"$PY" -m pip install --no-deps --no-build-isolation -e .tools/piper/src/python
"$PY" training/patch_legacy_piper.py
cd .tools/piper/src/python/piper_train/vits/monotonic_align
mkdir -p monotonic_align
"$PY" -m Cython.Build.Cythonize -i core.pyx
mv core*.so monotonic_align/
"$PY" -m piper_train --help
"$PY" -m piper_train.preprocess --help
