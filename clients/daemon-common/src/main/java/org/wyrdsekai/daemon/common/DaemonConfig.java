package org.wyrdsekai.daemon.common;

import java.net.InetAddress;
import java.util.prefs.Preferences;

/**
 * Daemon configuration backed by Java Preferences (cross-platform).
 * On Windows: stored in registry. On Linux/macOS: ~/.java/.userPrefs.
 *
 * Android daemon uses SharedPreferences instead but reads the same keys.
 */
public final class DaemonConfig {

    private static final String PREF_NODE = "/org/wyrdsekai/daemon";

    // Keys
    public static final String KEY_NATS_URL = "nats.url";
    public static final String KEY_NODE_NAME = "node.name";
    public static final String KEY_MODEL_ID = "model.id";
    public static final String KEY_MODEL_PATH = "model.path";
    public static final String KEY_INFERENCE_PORT = "inference.port";
    public static final String KEY_MAX_THREADS = "max.threads";
    public static final String KEY_CONTEXT_SIZE = "context.size";
    public static final String KEY_GPU_LAYERS = "gpu.layers";
    public static final String KEY_RUN_ON_BATTERY = "run.on.battery";
    public static final String KEY_AUTO_START = "auto.start";
    public static final String KEY_FLASH_ATTENTION = "flash.attention";

    // Defaults
    public static final String DEFAULT_NATS_URL = "nats://127.0.0.1:4222";
    public static final int DEFAULT_INFERENCE_PORT = 8080;
    public static final int DEFAULT_MAX_THREADS = 0; // 0 = auto
    public static final int DEFAULT_CONTEXT_SIZE = 2048;
    public static final int DEFAULT_GPU_LAYERS = 0;

    private final Preferences prefs;

    public DaemonConfig() {
        this.prefs = Preferences.userRoot().node(PREF_NODE);
    }

    /** For testing: inject a custom Preferences node. */
    DaemonConfig(Preferences prefs) {
        this.prefs = prefs;
    }

    // --- Getters ---

    public String natsUrl() {
        return prefs.get(KEY_NATS_URL, DEFAULT_NATS_URL);
    }

    public String nodeName() {
        return prefs.get(KEY_NODE_NAME, defaultNodeName());
    }

    public String modelId() {
        return prefs.get(KEY_MODEL_ID, "");
    }

    public String modelPath() {
        return prefs.get(KEY_MODEL_PATH, "");
    }

    public int inferencePort() {
        return prefs.getInt(KEY_INFERENCE_PORT, DEFAULT_INFERENCE_PORT);
    }

    public int maxThreads() {
        int configured = prefs.getInt(KEY_MAX_THREADS, DEFAULT_MAX_THREADS);
        if (configured <= 0) {
            // Auto: use half of available processors (leave headroom)
            return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }
        return configured;
    }

    public int contextSize() {
        return prefs.getInt(KEY_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE);
    }

    public int gpuLayers() {
        return prefs.getInt(KEY_GPU_LAYERS, DEFAULT_GPU_LAYERS);
    }

    public boolean runOnBattery() {
        return prefs.getBoolean(KEY_RUN_ON_BATTERY, false);
    }

    public boolean autoStart() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }

    public boolean flashAttention() {
        return prefs.getBoolean(KEY_FLASH_ATTENTION, true);
    }

    // --- Setters ---

    public void setNatsUrl(String url) { prefs.put(KEY_NATS_URL, url); }
    public void setNodeName(String name) { prefs.put(KEY_NODE_NAME, name); }
    public void setModelId(String id) { prefs.put(KEY_MODEL_ID, id); }
    public void setModelPath(String path) { prefs.put(KEY_MODEL_PATH, path); }
    public void setInferencePort(int port) { prefs.putInt(KEY_INFERENCE_PORT, port); }
    public void setMaxThreads(int threads) { prefs.putInt(KEY_MAX_THREADS, threads); }
    public void setContextSize(int size) { prefs.putInt(KEY_CONTEXT_SIZE, size); }
    public void setGpuLayers(int layers) { prefs.putInt(KEY_GPU_LAYERS, layers); }
    public void setRunOnBattery(boolean run) { prefs.putBoolean(KEY_RUN_ON_BATTERY, run); }
    public void setAutoStart(boolean auto) { prefs.putBoolean(KEY_AUTO_START, auto); }
    public void setFlashAttention(boolean enabled) { prefs.putBoolean(KEY_FLASH_ATTENTION, enabled); }

    private static String defaultNodeName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "daemon-" + ProcessHandle.current().pid();
        }
    }
}
