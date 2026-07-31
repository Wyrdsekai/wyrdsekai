#!/usr/bin/env python3
"""
Archetype conditioning: train shared CfC with archetype embedding vectors.
Takes the Reptile-initialized base soul and fine-tunes with conditioning.

The 8-dim archetype vector is concatenated to the input, allowing a single
shared CfC to handle all archetypes. Interpolation between archetypes produces
smooth behavioral blends (Scholar-Guardian hybrid = averaged vectors).

Requirements:
    pip install torch

Usage:
    python condition_train.py --data ./trajectories --weights ./base_soul_weights.json \
        --output ./conditioned_soul_weights.json --epochs 50
"""

import argparse
import json
import os
import sys

import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader


# Import from reptile_pretrain
from reptile_pretrain import (
    CfCDriveModel, ARCHETYPE_VECS, load_trajectories, export_weights_json
)


class TrajectoryDataset(Dataset):
    """Dataset of (input, target, dt) tuples from all archetypes."""

    def __init__(self, archetypes_data):
        self.samples = []
        for arch_name, episodes in archetypes_data.items():
            arch_vec = ARCHETYPE_VECS.get(arch_name, [0.0] * 8)
            for episode in episodes:
                for step in episode:
                    inp = step["tanks_before"] + step["drives_before"] + step["events"] + arch_vec
                    tgt = step["tanks_after"] + step["drives_after"]
                    dt = step["delta_time"]
                    self.samples.append((inp, tgt, dt))

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        inp, tgt, dt = self.samples[idx]
        return (torch.tensor(inp, dtype=torch.float32),
                torch.tensor(tgt, dtype=torch.float32),
                torch.tensor([dt], dtype=torch.float32))


def load_base_weights(model, path):
    """Load Reptile-pretrained weights into model."""
    with open(path) as f:
        data = json.load(f)

    state = model.state_dict()

    def reshape(flat, shape):
        return torch.tensor(flat, dtype=torch.float32).reshape(shape)

    state["backbone1.weight"] = reshape(data["w1"], state["backbone1.weight"].shape)
    state["backbone1.bias"] = reshape(data["b1"], state["backbone1.bias"].shape)
    state["backbone2.weight"] = reshape(data["w2"], state["backbone2.weight"].shape)
    state["backbone2.bias"] = reshape(data["b2"], state["backbone2.bias"].shape)
    state["f_head.weight"] = reshape(data["wf"], state["f_head.weight"].shape)
    state["f_head.bias"] = reshape(data["bf"], state["f_head.bias"].shape)
    state["g_head.weight"] = reshape(data["wg"], state["g_head.weight"].shape)
    state["g_head.bias"] = reshape(data["bg"], state["g_head.bias"].shape)
    state["h_head.weight"] = reshape(data["wh"], state["h_head.weight"].shape)
    state["h_head.bias"] = reshape(data["bh"], state["h_head.bias"].shape)

    model.load_state_dict(state)
    print(f"Loaded base weights from {path}")


def main():
    parser = argparse.ArgumentParser(description="Archetype conditioning training")
    parser.add_argument("--data", default="./trajectories", help="Trajectory data directory")
    parser.add_argument("--weights", default="./base_soul_weights.json", help="Reptile base weights")
    parser.add_argument("--output", default="./conditioned_soul_weights.json", help="Output weights")
    parser.add_argument("--archetype-output", default="./archetype_vectors.json", help="Learned archetype vectors")
    parser.add_argument("--epochs", type=int, default=50, help="Training epochs")
    parser.add_argument("--lr", type=float, default=0.001, help="Learning rate")
    parser.add_argument("--batch-size", type=int, default=64, help="Batch size")
    args = parser.parse_args()

    print("Loading trajectories...")
    archetypes = load_trajectories(args.data)
    if not archetypes:
        print("No trajectories found. Run generate_synthetic.py first.")
        sys.exit(1)

    # Create dataset
    dataset = TrajectoryDataset(archetypes)
    dataloader = DataLoader(dataset, batch_size=args.batch_size, shuffle=True)
    print(f"Dataset: {len(dataset)} samples")

    # Initialize model from base weights
    model = CfCDriveModel()
    if os.path.exists(args.weights):
        load_base_weights(model, args.weights)
    else:
        print(f"Warning: base weights not found at {args.weights}, using random init")

    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.epochs)

    # Training loop
    for epoch in range(args.epochs):
        total_loss = 0
        batches = 0
        for inputs, targets, dts in dataloader:
            optimizer.zero_grad()
            pred = model(inputs, dts)
            loss = nn.functional.mse_loss(pred, targets)
            loss.backward()
            optimizer.step()
            total_loss += loss.item()
            batches += 1

        scheduler.step()
        avg_loss = total_loss / batches
        if (epoch + 1) % 10 == 0 or epoch == 0:
            print(f"  Epoch {epoch + 1}/{args.epochs} — loss={avg_loss:.6f}, lr={scheduler.get_last_lr()[0]:.6f}")

    # Export conditioned weights
    export_weights_json(model, args.output)

    # Export archetype vectors (could be learned in future; for now use fixed)
    with open(args.archetype_output, "w") as f:
        json.dump(ARCHETYPE_VECS, f, indent=2)
    print(f"Exported archetype vectors to {args.archetype_output}")

    print("Done. Conditioned soul weights ready for deployment.")
    print(f"Deploy: copy {args.output} to ~/.wyrdsekai/souls/base_cfc.json")


if __name__ == "__main__":
    main()
