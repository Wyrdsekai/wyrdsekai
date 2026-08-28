#!/usr/bin/env python3
"""Raw P2P link integrity: properly-synchronized copies between every GPU
pair, checksum-verified both sides. If the hardware were corrupting data,
this corrupts too. Clean here + model garbage = software sync bug.
"""
import torch, itertools

n = torch.cuda.device_count()
print("devices:", [(i, torch.cuda.get_device_name(i)) for i in range(n)], flush=True)
ITERS = 200
SIZE = 64 * 1024 * 1024  # 256MB fp32 per copy

for a, b in itertools.permutations(range(n), 2):
    p2p = torch.cuda.can_device_access_peer(a, b)
    bad = 0
    for it in range(ITERS):
        src = torch.randn(SIZE, device=f"cuda:{a}")
        want = src.double().sum().item()
        dst = src.to(f"cuda:{b}")
        torch.cuda.synchronize(a); torch.cuda.synchronize(b)
        got = dst.double().sum().item()
        if got != want or not torch.equal(src.cpu(), dst.cpu()):
            bad += 1
        del src, dst
    print(f"{a}->{b} p2p={p2p} iters={ITERS} corrupt={bad} "
          f"({'CLEAN' if bad == 0 else 'HARDWARE FAULT'})", flush=True)

# Stress: concurrent bidirectional traffic on the NVLink pair (1<->2)
if n >= 3 and torch.cuda.can_device_access_peer(1, 2):
    bad = 0
    s1 = torch.cuda.Stream(device=1)
    s2 = torch.cuda.Stream(device=2)
    for it in range(ITERS):
        x = torch.randn(SIZE, device="cuda:1")
        y = torch.randn(SIZE, device="cuda:2")
        wx, wy = x.double().sum().item(), y.double().sum().item()
        with torch.cuda.stream(s1):
            x2 = x.to("cuda:2", non_blocking=True)
        with torch.cuda.stream(s2):
            y1 = y.to("cuda:1", non_blocking=True)
        s1.synchronize(); s2.synchronize()
        torch.cuda.synchronize(1); torch.cuda.synchronize(2)
        if x2.double().sum().item() != wx or y1.double().sum().item() != wy:
            bad += 1
        del x, y, x2, y1
    print(f"1<->2 concurrent bidirectional (NVLink): iters={ITERS} corrupt={bad} "
          f"({'CLEAN' if bad == 0 else 'HARDWARE FAULT'})", flush=True)
print("P2P_INTEGRITY_DONE", flush=True)
