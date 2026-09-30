#!/bin/bash
# Train one arm of the brain adapter on the research box: the generic adapter's recipe (all 40 layers, bf16,
# rank 16, lr 1e-4), one epoch, on the same-socket A6000 pair by UUID, node-0 cores and memory, under the
# latency watchdog. Usage: dh_train.sh <arm>   (reads ~/a3b-brain/<arm>.json, writes lora_<arm>/ and <arm>.gguf)
set -u
ARM=${1:?arm}; cd ~/a3b-brain || exit 1
echo $$ > train_$ARM.pid; date +%s > train_$ARM.start
export CUDA_VISIBLE_DEVICES=GPU-3e2b803f-cc80-3430-a663-1fc787277077,GPU-38c2b69b-12f9-c6af-1280-178f96b748f9
export OMP_NUM_THREADS=16 MKL_NUM_THREADS=16 OPENBLAS_NUM_THREADS=1 NUMEXPR_NUM_THREADS=1 TOKENIZERS_PARALLELISM=false \
       PYTHONUNBUFFERED=1 LAYERS_LAST=0 DETACH_EXPERTS=0
nohup ~/a3b-generic/watchdog_rise.sh ~/a3b-brain/train_$ARM.pid ~/a3b-brain/train_$ARM.start >> wd.out 2>&1 &
numactl --cpunodebind=0 --membind=0 "$HOME/venvs-tune/glimmer/bin/python" ~/a3b-generic/spine_lora_sweep.py \
  "$HOME/hf/Qwen3.6-35B-A3B" "$ARM.json" "lora_$ARM" 1 16 1e-4 2>&1 | grep --line-buffered "^\[conv\]" > "train_$ARM.log"
[ -f "lora_$ARM/run.json" ] && PYTHONPATH=$HOME/llama.cpp/gguf-py:$HOME/llama.cpp CUDA_VISIBLE_DEVICES="" nice \
  "$HOME/venvs-tune/glimmer/bin/python" "$HOME/a3b-train/convert_lora_gdn.py" --base "$HOME/hf/Qwen3.6-35B-A3B" \
  --outtype f16 --outfile "$ARM.gguf" "lora_$ARM" > "convert_$ARM.log" 2>&1
echo "done $(date -Is)" > "train_$ARM.done"
