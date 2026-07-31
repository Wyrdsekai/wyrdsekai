/**
 * Server fixture for E2E tests.
 * Starts and stops the real Wyrdsekai Java server.
 * Matches the pattern from server-side TestServerBootstrap.java.
 */

import { spawn, ChildProcess } from 'child_process';
import { resolve } from 'path';
import http from 'http';

const SERVER_ROOT = resolve(__dirname, '../../../../server');
const SERVER_BIN = resolve(SERVER_ROOT, 'build/install/server/bin/server');
const DEFAULT_PORT = 7070;

export class ServerFixture {
  private process: ChildProcess | null = null;
  private port: number;
  private ready = false;

  constructor(port: number = DEFAULT_PORT) {
    this.port = port;
  }

  /** Start the real Wyrdsekai server and wait for it to be ready. */
  async start(timeoutMs = 30_000): Promise<void> {
    if (this.process) return;

    this.process = spawn(SERVER_BIN, ['--port', String(this.port)], {
      cwd: SERVER_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env },
    });

    // Forward server output for debugging
    this.process.stdout?.on('data', (data: Buffer) => {
      if (process.env.DEBUG_SERVER) {
        process.stderr.write(`[server] ${data}`);
      }
    });
    this.process.stderr?.on('data', (data: Buffer) => {
      if (process.env.DEBUG_SERVER) {
        process.stderr.write(`[server:err] ${data}`);
      }
    });

    this.process.on('exit', (code) => {
      if (code !== null && code !== 0) {
        console.error(`[ServerFixture] Server exited with code ${code}`);
      }
      this.process = null;
      this.ready = false;
    });

    // Poll /ready until 200 or timeout
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      try {
        const status = await this.checkReady();
        if (status === 200) {
          this.ready = true;
          return;
        }
      } catch {
        // Server not up yet — keep polling
      }
      await sleep(500);
    }

    // Timeout — kill and throw
    this.stop();
    throw new Error(`Server did not become ready within ${timeoutMs}ms`);
  }

  /** Stop the server process. */
  stop(): void {
    if (this.process) {
      this.process.kill('SIGTERM');
      this.process = null;
      this.ready = false;
    }
  }

  /** Base URL for HTTP requests. */
  baseUrl(): string {
    return `http://localhost:${this.port}`;
  }

  /** WebSocket URL for client connections. */
  wsUrl(): string {
    return `ws://localhost:${this.port}/ws`;
  }

  /** Check if the server is ready. */
  isReady(): boolean {
    return this.ready;
  }

  /** GET /ready and return status code. */
  private checkReady(): Promise<number> {
    return new Promise((resolve, reject) => {
      const req = http.get(`${this.baseUrl()}/ready`, (res) => {
        res.resume(); // drain response body
        resolve(res.statusCode ?? 0);
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
