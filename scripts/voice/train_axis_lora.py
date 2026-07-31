import json, argparse, torch
from datasets import Dataset
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import LoraConfig
from trl import SFTTrainer, SFTConfig

ap = argparse.ArgumentParser()
ap.add_argument("--axis", required=True)
ap.add_argument("--pairs", required=True)
ap.add_argument("--base", default="/home/you/wyrdsekai-4b-v10-merged")
ap.add_argument("--out", required=True)
a = ap.parse_args()

rows = []
for line in open(a.pairs):
    line = line.strip()
    if not line:
        continue
    o = json.loads(line)
    rows.append({"messages": [
        {"role": "system", "content": "You are a companion, speaking in your own voice."},
        {"role": "user", "content": "Say this in your own voice: " + o["negative"]},
        {"role": "assistant", "content": o["positive"]},
    ]})
ds = Dataset.from_list(rows)
print(f"[{a.axis}] {len(rows)} SFT examples")

tok = AutoTokenizer.from_pretrained(a.base)
model = AutoModelForCausalLM.from_pretrained(a.base, torch_dtype=torch.bfloat16, device_map="cuda")
peft_cfg = LoraConfig(r=8, lora_alpha=16, lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"])
cfg = SFTConfig(output_dir=a.out, num_train_epochs=5, per_device_train_batch_size=2,
    gradient_accumulation_steps=2, learning_rate=2e-4, bf16=True, logging_steps=5,
    save_strategy="no", report_to=[], max_length=256)
tr = SFTTrainer(model=model, train_dataset=ds, args=cfg, peft_config=peft_cfg, processing_class=tok)
tr.train()
tr.save_model(a.out)
print("SAVED", a.out)
