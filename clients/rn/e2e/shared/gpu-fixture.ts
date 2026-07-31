/**
 * GPU fixture for E2E tests.
 *
 * When CT3 (WebLLM) tests are enabled (WYRD_WEBGPU=1), ensures there's
 * enough free GPU memory for browser-side inference by dynamically
 * restarting SGLang with a lower mem-fraction-static if needed.
 *
 * The docker-compose.e2e.yml supports SGLANG_GPU_MEM_UTIL env var —
 * we just pass a lower value when restarting.
 */

import { execSync } from 'child_process';
import { resolve } from 'path';

const COMPOSE_FILE = resolve(__dirname, '../../../../docker/docker-compose.e2e.yml');
const MIN_FREE_MB = 2048; // WebLLM needs ~2 GB for smallest model + overhead
const REDUCED_MEM_UTIL = '0.60'; // 60% of VRAM → ~9.8 GB for SGLang on 16 GB card

interface GpuInfo {
  totalMB: number;
  usedMB: number;
  freeMB: number;
}

export class GpuFixture {
  private restartedSglang = false;

  /** Query nvidia-smi for GPU memory. Returns null if no NVIDIA GPU. */
  getGpuMemory(): GpuInfo | null {
    try {
      const out = execSync(
        'nvidia-smi --query-gpu=memory.total,memory.used,memory.free --format=csv,noheader,nounits',
        { encoding: 'utf-8', timeout: 5000 },
      ).trim();
      const [total, used, free] = out.split(',').map(s => parseInt(s.trim(), 10));
      if (isNaN(total)) return null;
      return { totalMB: total, usedMB: used, freeMB: free };
    } catch {
      return null;
    }
  }

  /** Check if SGLang container is running. */
  isSglangRunning(): boolean {
    try {
      const out = execSync(
        'docker ps --filter name=wyrdsekai-e2e-sglang --format "{{.Status}}"',
        { encoding: 'utf-8', timeout: 5000 },
      ).trim();
      return out.includes('Up');
    } catch {
      return false;
    }
  }

  /**
   * Ensure enough free GPU memory for WebLLM.
   *
   * If SGLang is running and free VRAM < MIN_FREE_MB:
   *   1. Stop SGLang
   *   2. Restart it with SGLANG_GPU_MEM_UTIL=0.60
   *   3. Wait for health check
   */
  async ensureWebLLMMemory(): Promise<void> {
    const gpu = this.getGpuMemory();
    if (!gpu) {
      console.log('[GpuFixture] No NVIDIA GPU detected, skipping memory check');
      return;
    }

    console.log(`[GpuFixture] GPU memory: ${gpu.freeMB} MB free / ${gpu.totalMB} MB total`);

    if (gpu.freeMB >= MIN_FREE_MB) {
      console.log(`[GpuFixture] Sufficient free VRAM (${gpu.freeMB} MB >= ${MIN_FREE_MB} MB)`);
      return;
    }

    if (!this.isSglangRunning()) {
      console.warn(`[GpuFixture] Only ${gpu.freeMB} MB free but SGLang not running — can't reconfigure`);
      return;
    }

    console.log(`[GpuFixture] Only ${gpu.freeMB} MB free, restarting SGLang with mem-fraction-static ${REDUCED_MEM_UTIL}...`);

    try {
      // Stop SGLang
      execSync(
        `COMPOSE_PROFILES=sglang docker compose -f ${COMPOSE_FILE} stop sglang`,
        { encoding: 'utf-8', timeout: 30_000, stdio: 'pipe' },
      );

      // Brief pause for GPU memory to be released
      await sleep(3000);

      // Restart with lower memory utilization
      execSync(
        `COMPOSE_PROFILES=sglang SGLANG_GPU_MEM_UTIL=${REDUCED_MEM_UTIL} docker compose -f ${COMPOSE_FILE} up -d sglang`,
        { encoding: 'utf-8', timeout: 30_000, stdio: 'pipe' },
      );

      this.restartedSglang = true;

      // Wait for SGLang to be healthy (model reload takes time)
      console.log('[GpuFixture] Waiting for SGLang to reload...');
      const start = Date.now();
      const timeout = 180_000; // 3 min for model reload
      while (Date.now() - start < timeout) {
        try {
          const health = execSync(
            'curl -sf http://localhost:8000/health',
            { encoding: 'utf-8', timeout: 5000 },
          );
          if (health) {
            const after = this.getGpuMemory();
            console.log(`[GpuFixture] SGLang restarted. GPU: ${after?.freeMB ?? '?'} MB free`);
            return;
          }
        } catch {
          // Not ready yet
        }
        await sleep(5000);
      }
      console.warn('[GpuFixture] SGLang health check timed out after restart');
    } catch (e: any) {
      console.warn(`[GpuFixture] Failed to restart SGLang: ${e.message}`);
    }
  }

  /**
   * Restore SGLang to original settings if we changed it.
   */
  async restore(): Promise<void> {
    if (!this.restartedSglang) return;

    console.log('[GpuFixture] Restoring SGLang to default mem-fraction-static...');
    try {
      execSync(
        `COMPOSE_PROFILES=sglang docker compose -f ${COMPOSE_FILE} stop sglang`,
        { encoding: 'utf-8', timeout: 30_000, stdio: 'pipe' },
      );
      await sleep(2000);
      execSync(
        `COMPOSE_PROFILES=sglang docker compose -f ${COMPOSE_FILE} up -d sglang`,
        { encoding: 'utf-8', timeout: 30_000, stdio: 'pipe' },
      );
      console.log('[GpuFixture] SGLang restarting with default settings');
    } catch (e: any) {
      console.warn(`[GpuFixture] Failed to restore SGLang: ${e.message}`);
    }
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise(r => setTimeout(r, ms));
}
