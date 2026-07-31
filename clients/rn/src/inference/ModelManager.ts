/**
 * Model catalog and download management.
 *
 * Filesystem operations use react-native-fs via dynamic require so that
 * the module still works in web/test environments where RNFS is absent.
 */

import { ModelInfo } from './types';

/** Minimal interface for react-native-fs operations used by ModelManager. */
interface RNFSModule {
  exists(path: string): Promise<boolean>;
  stat(path: string): Promise<{ size: number | string }>;
  readDir(path: string): Promise<Array<{ name: string; size: number | string }>>;
  mkdir(path: string): Promise<void>;
  unlink(path: string): Promise<void>;
  downloadFile(options: {
    fromUrl: string;
    toFile: string;
    progressInterval?: number;
    begin?: (res: { statusCode: number; contentLength: number }) => void;
    progress?: (res: { bytesWritten: number; contentLength: number }) => void;
  }): { promise: Promise<{ statusCode: number; bytesWritten: number }> };
}

/** Built-in model catalog aligned with MODELS.md phone tier. */
export const MODEL_CATALOG: ModelInfo[] = [
  {
    id: 'qwen3.5-2b-q4',
    name: 'Qwen3.5 2B',
    filename: 'Qwen3.5-2B-Q4_K_M.gguf',
    url: 'https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf',
    size: 1_280_000_000,
    tier: 'phone',
    description: 'Best for phone. Holds personality, fast inference. Q4 quantization.',
  },
  {
    id: 'qwen3-0.6b-q8',
    name: 'Qwen3 0.6B',
    filename: 'Qwen3-0.6B-Q8_0.gguf',
    // HuggingFace paths are case-sensitive. This was lowercase — "qwen3-0.6b-q8_0.gguf"
    // — and 404'd on every attempt, so this model could never be downloaded in the RN
    // app at all. KMP had the correct casing all along, which is why it only ever broke
    // on one client.
    url: 'https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf',
    size: 639_000_000,
    tier: 'tiny',
    description: 'Fastest. Q8 quantization. Runs on any device with 2GB+ RAM.',
  },
  {
    id: 'qwen3-4b-q4',
    name: 'Qwen3 4B',
    filename: 'Qwen3-4B-Q4_K_M.gguf',
    url: 'https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf',
    size: 2_500_000_000,
    tier: 'medium',
    description: 'Strong quality. Q4 quantization. Needs 8GB+ RAM.',
  },
];

export class ModelManager {
  private modelsDir: string;
  private activeModelId: string | null = null;

  constructor(modelsDir: string) {
    this.modelsDir = modelsDir;
  }

  getAvailableModels(): ModelInfo[] {
    return MODEL_CATALOG;
  }

  getModelsDir(): string {
    return this.modelsDir;
  }

  getActiveModel(): string | null {
    return this.activeModelId;
  }

  setActiveModel(modelId: string | null): void {
    this.activeModelId = modelId;
  }

  /**
   * Check which models from the catalog exist on the device filesystem.
   * Returns an empty array if react-native-fs is unavailable (web/test).
   */
  async getDownloadedModels(): Promise<ModelInfo[]> {
    try {
      const RNFS = require('react-native-fs');
      const downloaded: ModelInfo[] = [];
      for (const model of MODEL_CATALOG) {
        const path = `${this.modelsDir}/${model.filename}`;
        if (await RNFS.exists(path)) {
          // Verify file is actually substantial (not a 0-byte ghost)
          try {
            const stat = await RNFS.stat(path);
            if (Number(stat.size || 0) > 1000) {
              downloaded.push(model);
            }
          } catch {
            // stat failed, skip this model
          }
        }
      }
      return downloaded;
    } catch {
      return []; // RNFS not available (web/test environment)
    }
  }

  /**
   * Download a model from Hugging Face to the local models directory.
   * @returns The absolute path to the downloaded file.
   */
  async downloadModel(
    modelId: string,
    onProgress: (percent: number) => void,
  ): Promise<string> {
    const model = MODEL_CATALOG.find((m) => m.id === modelId);
    if (!model) throw new Error(`Unknown model: ${modelId}`);

    let RNFS: RNFSModule;
    try {
      RNFS = require('react-native-fs');
    } catch {
      throw new Error('react-native-fs not available. Cannot download models in this environment.');
    }
    const destPath = `${this.modelsDir}/${model.filename}`;

    // Check if already downloaded AND file is large enough (>50% expected size)
    if (await RNFS.exists(destPath)) {
      try {
        const stat = await RNFS.stat(destPath);
        const fileSize = Number(stat.size || 0);
        if (fileSize > model.size * 0.5) {
          return destPath; // File looks complete
        }
        // File is too small — partial download, delete and retry
        await RNFS.unlink(destPath);
      } catch {
        // stat failed, re-download
      }
    }

    // Ensure directory exists
    await RNFS.mkdir(this.modelsDir);

    const { promise } = RNFS.downloadFile({
      fromUrl: model.url,
      toFile: destPath,
      progressInterval: 500,
      begin: (res: { statusCode: number; contentLength: number }) => {
        if (res.statusCode >= 300) {
          throw new Error(`HTTP ${res.statusCode} from ${model.url}`);
        }
      },
      progress: (res: { bytesWritten: number; contentLength: number }) => {
        if (res.contentLength > 0) {
          onProgress(Math.round((res.bytesWritten / res.contentLength) * 100));
        }
      },
    });

    const result = await promise;

    // Verify download actually wrote data
    if (result.statusCode >= 300) {
      await RNFS.unlink(destPath).catch(() => {});
      throw new Error(`Download failed: HTTP ${result.statusCode}`);
    }
    if (result.bytesWritten < model.size * 0.5) {
      await RNFS.unlink(destPath).catch(() => {});
      throw new Error(
        `Download incomplete: got ${result.bytesWritten} bytes, expected ~${model.size}`,
      );
    }

    onProgress(100);
    return destPath;
  }

  /** Delete a downloaded model file. */
  async deleteModel(modelId: string): Promise<void> {
    const model = MODEL_CATALOG.find((m) => m.id === modelId);
    if (!model) return;

    try {
      const RNFS = require('react-native-fs');
      const path = `${this.modelsDir}/${model.filename}`;
      if (await RNFS.exists(path)) {
        await RNFS.unlink(path);
      }
    } catch {
      // Ignore if RNFS unavailable
    }

    if (this.activeModelId === modelId) {
      this.activeModelId = null;
    }
  }

  /** Get the on-disk path for a model, or null if not downloaded. */
  async getModelPath(modelId: string): Promise<string | null> {
    const model = MODEL_CATALOG.find((m) => m.id === modelId);
    if (!model) return null;

    try {
      const RNFS = require('react-native-fs');
      const path = `${this.modelsDir}/${model.filename}`;
      return (await RNFS.exists(path)) ? path : null;
    } catch {
      return null;
    }
  }

  /** Human-readable file size string. */
  formatSize(bytes: number): string {
    if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
    return `${(bytes / 1_000_000).toFixed(0)} MB`;
  }
}
