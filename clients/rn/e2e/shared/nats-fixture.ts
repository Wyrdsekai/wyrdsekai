/**
 * NATS fixture for E2E tests.
 * Starts and stops NATS via docker compose using docker-compose.e2e.yml.
 */

import { execSync, ExecSyncOptions } from 'child_process';
import { resolve } from 'path';
import http from 'http';

const DOCKER_DIR = resolve(__dirname, '../../../../docker');
const COMPOSE_FILE = resolve(DOCKER_DIR, 'docker-compose.e2e.yml');
const NATS_WS_PORT = 9222;
const NATS_MONITOR_PORT = 8222;

const execOpts: ExecSyncOptions = {
  cwd: DOCKER_DIR,
  stdio: process.env.DEBUG_NATS ? 'inherit' : 'pipe',
};

export class NatsFixture {
  private started = false;

  /** Start NATS container and wait for it to be healthy. */
  async start(timeoutMs = 30_000): Promise<void> {
    if (this.started) return;

    execSync(
      `docker compose -f ${COMPOSE_FILE} up -d nats`,
      execOpts,
    );

    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      try {
        const ok = await this.checkHealth();
        if (ok) {
          this.started = true;
          return;
        }
      } catch {
        // Not ready yet
      }
      await sleep(500);
    }

    throw new Error(`NATS did not become healthy within ${timeoutMs}ms`);
  }

  /** Stop NATS container. */
  stop(): void {
    try {
      execSync(
        `docker compose -f ${COMPOSE_FILE} stop nats`,
        execOpts,
      );
    } catch {
      // Best-effort cleanup
    }
    this.started = false;
  }

  /** WebSocket URL for NATS connections. */
  wsUrl(): string {
    return `ws://localhost:${NATS_WS_PORT}`;
  }

  /** Check NATS health via monitor endpoint. */
  private checkHealth(): Promise<boolean> {
    return new Promise((resolve, reject) => {
      const req = http.get(`http://localhost:${NATS_MONITOR_PORT}/healthz`, (res) => {
        res.resume();
        resolve(res.statusCode === 200);
      });
      req.on('error', reject);
      req.setTimeout(2000, () => {
        req.destroy();
        reject(new Error('timeout'));
      });
    });
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
