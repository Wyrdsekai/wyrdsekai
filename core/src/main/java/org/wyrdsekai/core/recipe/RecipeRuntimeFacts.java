package org.wyrdsekai.core.recipe;

import org.wyrdsekai.core.agent.classifier.ClassifierArm;
import org.wyrdsekai.core.config.WyrdConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a recipe may know about the node it runs on, seeded into every
 * {@link RecipeContext} before the recipe's own parameters (so a declared
 * parameter or a caller's value always wins over these).
 *
 * <p>Why these exist: the bundled recipes used to point at the development
 * tree — a class path written by Gradle into {@code cli/build/}, a deploy
 * directory under {@code core/src/main/resources/}. An installed node has
 * neither, so {@code retrain-classifier-head} failed at its probe step on
 * every household node from 2026-09-22 and, had the probe passed, would have
 * deployed into a folder nothing reads. A recipe now asks the runtime for the
 * node's own java and class path, and for the companion's own classifier
 * folder, and those are right on a developer's checkout and on an install
 * alike.
 *
 * <ul>
 *   <li>{@code runtime_java} — the java binary this node runs on.</li>
 *   <li>{@code runtime_classpath} — this node's class path (the shipped jars
 *       on an install; the Gradle runtime class path in development).</li>
 *   <li>{@code data_dir} — the node's data directory.</li>
 *   <li>{@code agent_did}, {@code agent_classifier_dir} — only when the run
 *       belongs to a companion: her DID and the folder her own classifier
 *       heads live in ({@link ClassifierArm#perAgentDirFor}).</li>
 * </ul>
 */
public final class RecipeRuntimeFacts {

    public static final String RUNTIME_JAVA = "runtime_java";
    public static final String RUNTIME_CLASSPATH = "runtime_classpath";
    public static final String DATA_DIR = "data_dir";
    public static final String AGENT_DID = "agent_did";
    public static final String AGENT_CLASSIFIER_DIR = "agent_classifier_dir";

    private RecipeRuntimeFacts() {}

    /** Facts about this process: its java, its class path, the node's data dir. */
    public static Map<String, Object> ofThisProcess() {
        var out = new LinkedHashMap<String, Object>();
        out.put(RUNTIME_JAVA, javaBinary());
        out.put(RUNTIME_CLASSPATH, System.getProperty("java.class.path", ""));
        out.put(DATA_DIR, dataDir());
        return out;
    }

    /** Facts about the companion a run belongs to; empty when it belongs to no one. */
    public static Map<String, Object> ofAgent(String agentDid) {
        var out = new LinkedHashMap<String, Object>();
        if (agentDid == null || agentDid.isBlank()) return out;
        out.put(AGENT_DID, agentDid);
        out.put(AGENT_CLASSIFIER_DIR, ClassifierArm.perAgentDirFor(agentDid).toString());
        return out;
    }

    static String javaBinary() {
        var home = System.getProperty("java.home", "");
        if (!home.isBlank()) {
            var unix = Path.of(home, "bin", "java");
            if (Files.isRegularFile(unix)) return unix.toString();
            var windows = Path.of(home, "bin", "java.exe");
            if (Files.isRegularFile(windows)) return windows.toString();
        }
        return "java";
    }

    private static String dataDir() {
        var dir = WyrdConfig.get().dataDir();
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home") + "/.wyrdsekai";
        }
        return dir;
    }
}
