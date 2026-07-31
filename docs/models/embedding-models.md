---
license: apache-2.0
library_name: onnx
pipeline_tag: sentence-similarity
tags:
  - wyrdsekai
  - embeddings
  - onnx
  - quantized
base_model: sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
---

# Wyrdsekai embedding models (ONNX, int8)

Int8 ONNX quantizations of open sentence-transformer encoders, repackaged so
[wyrdsekai](https://github.com/Wyrdsekai/wyrdsekai) installers have a stable,
versioned mirror. These are **not wyrdsekai fine-tunes** (with one noted
exception) — credit and licenses belong to the upstream authors.

| File | Upstream | Role in wyrdsekai |
|---|---|---|
| `paraphrase-multilingual-MiniLM-L12-v2-q8.onnx` (+tokenizer, `paraphrase-l12/`) | [sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2) | Default retrieval encoder (384-d): memory, library and tool-description search. Installed by `wyrd setup`. |
| `paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx` | same base, SetFit-tuned by wyrdsekai | Classifier-head encoder (task routing). Tuned on synthetic labelled corpora only; kept **separate** from the retrieval encoder on purpose — SetFit tuning degrades retrieval quality. |
| `minilm-l6-v2-q8.onnx` (+tokenizer) | [sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) | Lightweight fallback for constrained devices. |

All upstream models are Apache 2.0; the quantizations carry the same license.
Hardware permitting, `wyrd embedding-model download bge-m3` upgrades retrieval to
[BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) (fetched from its own repo, not
mirrored here).
