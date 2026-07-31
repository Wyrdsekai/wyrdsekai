package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntrypointDetectorTest {

    @Test
    void metadata_override_winsOverHeuristic(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("main.py"), "print('hi')");
        var result = EntrypointDetector.fromMetadata(
            Map.of("entrypoint", "uv run main.py"));
        assertTrue(result.isPresent());
        assertEquals(List.of("uv", "run", "main.py"), result.get());
    }

    @Test
    void blank_metadata_isIgnored() {
        assertTrue(EntrypointDetector.fromMetadata(Map.of("entrypoint", "")).isEmpty());
        assertTrue(EntrypointDetector.fromMetadata(Map.of()).isEmpty());
        assertTrue(EntrypointDetector.fromMetadata(null).isEmpty());
    }

    @Test
    void detect_pythonMainPy(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("main.py"), "print('hello')");
        var argv = EntrypointDetector.detect(workspace);
        assertTrue(argv.isPresent());
        assertEquals(List.of("python3", "main.py"), argv.get());
    }

    @Test
    void detect_nodeIndexJs(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.js"), "console.log('hi')");
        var argv = EntrypointDetector.detect(workspace);
        assertTrue(argv.isPresent());
        assertEquals(List.of("node", "index.js"), argv.get());
    }

    @Test
    void detect_packageJsonStart_winsOverIndexJs(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.js"), "");
        Files.writeString(workspace.resolve("package.json"),
            "{\"name\":\"x\",\"scripts\":{\"start\":\"node server.js\"}}");
        var argv = EntrypointDetector.detect(workspace);
        assertTrue(argv.isPresent());
        assertEquals(List.of("npm", "start", "--silent"), argv.get());
    }

    @Test
    void detect_packageJsonMain_whenNoStart(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("package.json"),
            "{\"name\":\"x\",\"main\":\"server.js\"}");
        var argv = EntrypointDetector.detect(workspace);
        assertTrue(argv.isPresent());
        assertEquals(List.of("node", "server.js"), argv.get());
    }

    @Test
    void detect_makefileRun_winsOverPython(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("Makefile"),
            "run:\n\tpython3 main.py\n");
        Files.writeString(workspace.resolve("main.py"), "");
        var argv = EntrypointDetector.detect(workspace);
        assertTrue(argv.isPresent());
        assertEquals(List.of("make", "run"), argv.get());
    }

    @Test
    void detect_cargoToml(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("Cargo.toml"), "[package]\nname=\"x\"");
        var argv = EntrypointDetector.detect(workspace);
        assertTrue(argv.isPresent());
        assertEquals(List.of("cargo", "run", "--quiet"), argv.get());
    }

    @Test
    void detect_goMod(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("go.mod"), "module x\n");
        var argv = EntrypointDetector.detect(workspace);
        assertTrue(argv.isPresent());
        assertEquals(List.of("go", "run", "."), argv.get());
    }

    @Test
    void detect_emptyWorkspace_isEmpty(@TempDir Path workspace) {
        assertTrue(EntrypointDetector.detect(workspace).isEmpty());
    }

    @Test
    void detect_nullOrMissing_isEmpty() {
        assertTrue(EntrypointDetector.detect(null).isEmpty());
        assertTrue(EntrypointDetector.detect(Path.of("/no/such/dir/anywhere")).isEmpty());
    }
}
