#!/usr/bin/env python3
"""
Reptile meta-learning: find CfC weights equidistant from all archetype solutions.
The "base soul" — weights that can rapidly specialize to ANY archetype in 5-10 gradient steps.

Uses first-order meta-learning (Nichol et al., 2018):
  For each meta-iteration:
    1. Sample an archetype + trajectory
    2. Copy theta -> phi
    3. Train phi on trajectory for K steps
    4. Update theta <- theta + beta * (phi - theta)

After ~2000 iterations, theta is the base soul.

Requirements:
    pip install torch ncps

Usage:
    python reptile_pretrain.py --data ./trajectories --output ./base_soul_weights.json --iterations 2000
"""

import argparse
import json
import os
import random
import sys

import torch
import torch.nn as nn
import numpy as np


class CfCDriveModel(nn.Module):
    """Simplified CfC model matching CfCCell.java architecture."""

    def __init__(self, input_dim=32, backbone1=48, backbone2=32, output_dim=16):
        super().__init__()
        self.backbone1 = nn.Linear(input_dim, backbone1)
        self.backbone2 = nn.Linear(backbone1, backbone2)
        self.f_head = nn.Linear(backbone2, output_dim)  # time-gate
        self.g_head = nn.Linear(backbone2, output_dim)  # fast response
        self.h_head = nn.Linear(backbone2, output_dim)  # slow attractor

    def forward(self, x, dt):
        """
        x: (batch, 32) — [tanks(8), drives(8), events(8), archetype(8)]
        dt: (batch, 1) — delta time
        """
        a1 = torch.nn.functional.silu(self.backbone1(x))
        a2 = torch.nn.functional.silu(self.backbone2(a1))

        f = torch.nn.functional.softplus(self.f_head(a2))  # positive
        g = torch.tanh(self.g_head(a2))
        h = torch.tanh(self.h_head(a2))

        interp = torch.sigmoid(-f * dt)
        output = interp * g + (1 - interp) * h
        return output


def load_trajectories(data_dir):
    """Load all trajectories grouped by archetype."""
    archetypes = {}
    for arch_name in os.listdir(data_dir):
        arch_dir = os.path.join(data_dir, arch_name)
        if not os.path.isdir(arch_dir):
            continue
        episodes = []
        for fname in sorted(os.listdir(arch_dir)):
            if not fname.endswith(".json"):
                continue
            with open(os.path.join(arch_dir, fname)) as f:
                episodes.append(json.load(f))
        if episodes:
            archetypes[arch_name] = episodes
            print(f"  Loaded {len(episodes)} episodes for {arch_name}")
    return archetypes


def episode_to_tensors(episode, archetype_vec):
    """Convert episode to input/target tensors."""
    inputs = []
    targets = []
    dts = []

    for step in episode:
        # Input: tanks(8) + drives(8) + events(8) + archetype(8) = 32
        inp = step["tanks_before"] + step["drives_before"] + step["events"] + archetype_vec
        # Target: tanks_after(8) + drives_after(8) = 16
        tgt = step["tanks_after"] + step["drives_after"]
        inputs.append(inp)
        targets.append(tgt)
        dts.append([step["delta_time"]])

    return (torch.tensor(inputs, dtype=torch.float32),
            torch.tensor(targets, dtype=torch.float32),
            torch.tensor(dts, dtype=torch.float32))


# Archetype conditioning vectors (8-dim, learned during this training)
ARCHETYPE_VECS = {
    "scholar":  [1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.5],
    "guardian":  [0.0, 0.5, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0],
    "artisan":   [0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0],
    "diplomat":  [0.0, 0.0, 0.5, 0.0, 1.0, 0.0, 0.0, 0.0],
    "explorer":  [1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0],
    "steward":   [0.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0],
}


def inner_loop(model, episode, archetype_vec, inner_steps=10, inner_lr=0.001):
    """Run K gradient steps on one episode. Returns updated model parameters."""
    inputs, targets, dts = episode_to_tensors(episode, archetype_vec)
    optimizer = torch.optim.SGD(model.parameters(), lr=inner_lr)

    for _ in range(inner_steps):
        optimizer.zero_grad()
        pred = model(inputs, dts)
        loss = nn.functional.mse_loss(pred, targets)
        loss.backward()
        optimizer.step()

    return loss.item()


def reptile_update(theta_model, phi_model, beta=0.1):
    """Reptile outer update: theta <- theta + beta * (phi - theta)."""
    with torch.no_grad():
        for tp, pp in zip(theta_model.parameters(), phi_model.parameters()):
            tp.data += beta * (pp.data - tp.data)


def export_weights_json(model, path):
    """Export model weights as JSON matching CfCCell.loadJson() format."""
    state = model.state_dict()

    def to_list(t):
        return t.detach().cpu().numpy().flatten().tolist()

    weights = {
        "w1": to_list(state["backbone1.weight"]),
        "b1": to_list(state["backbone1.bias"]),
        "w2": to_list(state["backbone2.weight"]),
        "b2": to_list(state["backbone2.bias"]),
        "wf": to_list(state["f_head.weight"]),
        "bf": to_list(state["f_head.bias"]),
        "wg": to_list(state["g_head.weight"]),
        "bg": to_list(state["g_head.bias"]),
        "wh": to_list(state["h_head.weight"]),
        "bh": to_list(state["h_head.bias"]),
        "hidden": [0.0] * 16,
    }

    with open(path, "w") as f:
        json.dump(weights, f, indent=2)
    print(f"Exported weights to {path} ({os.path.getsize(path)} bytes)")


def main():
    parser = argparse.ArgumentParser(description="Reptile meta-learning for CfC base soul")
    parser.add_argument("--data", default="./trajectories", help="Trajectory data directory")
    parser.add_argument("--output", default="./base_soul_weights.json", help="Output weights file")
    parser.add_argument("--archetype-output", default="./archetype_vectors.json", help="Archetype vectors")
    parser.add_argument("--iterations", type=int, default=2000, help="Meta-iterations")
    parser.add_argument("--inner-steps", type=int, default=10, help="Inner loop gradient steps")
    parser.add_argument("--inner-lr", type=float, default=0.001, help="Inner loop learning rate")
    parser.add_argument("--beta", type=float, default=0.1, help="Reptile outer loop step size")
    args = parser.parse_args()

    print("Loading trajectories...")
    archetypes = load_trajectories(args.data)
    if not archetypes:
        print("No trajectories found. Run generate_synthetic.py first.")
        sys.exit(1)

    arch_names = list(archetypes.keys())
    print(f"Archetypes: {arch_names}")

    # Initialize theta (the base soul)
    theta = CfCDriveModel()
    print(f"Model parameters: {sum(p.numel() for p in theta.parameters())}")

    # Meta-learning loop
    for iteration in range(args.iterations):
        # Sample archetype + episode
        arch = random.choice(arch_names)
        episode = random.choice(archetypes[arch])
        arch_vec = ARCHETYPE_VECS.get(arch, [0.0] * 8)

        # Copy theta -> phi
        phi = CfCDriveModel()
        phi.load_state_dict(theta.state_dict())

        # Inner loop: train phi on this episode
        loss = inner_loop(phi, episode, arch_vec,
                         inner_steps=args.inner_steps, inner_lr=args.inner_lr)

        # Outer update: theta <- theta + beta * (phi - theta)
        reptile_update(theta, phi, beta=args.beta)

        if (iteration + 1) % 100 == 0:
            print(f"  Iteration {iteration + 1}/{args.iterations} — arch={arch}, inner_loss={loss:.6f}")

    # Export
    export_weights_json(theta, args.output)

    # Export archetype vectors
    with open(args.archetype_output, "w") as f:
        json.dump(ARCHETYPE_VECS, f, indent=2)
    print(f"Exported archetype vectors to {args.archetype_output}")

    print("Done. Base soul ready for deployment.")


if __name__ == "__main__":
    main()
