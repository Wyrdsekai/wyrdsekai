package org.wyrdsekai.scripting.sandbox;

import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SandboxContextBuilder — verifies API injection at each sandbox level.
 */
class SandboxContextBuilderTest {

    @TempDir
    Path workspace;

    @Test
    void room_script_has_no_http_or_db_or_fs() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.ROOM_SCRIPT, null)) {
            var bindings = ctx.getBindings("js");
            assertThat(bindings.hasMember("http")).isFalse();
            assertThat(bindings.hasMember("crypto")).isFalse();
            assertThat(bindings.hasMember("html")).isFalse();
            assertThat(bindings.hasMember("fs")).isFalse();
            assertThat(bindings.hasMember("Database")).isFalse();
        }
    }

    @Test
    void skill_basic_has_http_and_crypto_and_html() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_BASIC, null)) {
            var bindings = ctx.getBindings("js");
            assertThat(bindings.hasMember("http")).isTrue();
            assertThat(bindings.hasMember("crypto")).isTrue();
            assertThat(bindings.hasMember("html")).isTrue();
            // No fs or database at BASIC level
            assertThat(bindings.hasMember("fs")).isFalse();
        }
    }

    @Test
    void skill_data_has_db_and_fs() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_DATA, workspace)) {
            var bindings = ctx.getBindings("js");
            // All of BASIC
            assertThat(bindings.hasMember("http")).isTrue();
            assertThat(bindings.hasMember("crypto")).isTrue();
            assertThat(bindings.hasMember("html")).isTrue();
            // Plus DATA-level APIs
            assertThat(bindings.hasMember("fs")).isTrue();
            assertThat(bindings.hasMember("_dbFactory")).isTrue();
        }
    }

    @Test
    void skill_full_allows_java_interop() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_FULL, workspace)) {
            // SKILL_FULL should allow accessing Java types
            var result = ctx.eval("js",
                "var System = Java.type('java.lang.System'); System.getProperty('os.name');");
            assertThat(result.isString()).isTrue();
            assertThat(result.asString()).isNotBlank();
        }
    }

    @Test
    void room_script_blocks_java_interop() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.ROOM_SCRIPT, null)) {
            var result = ctx.eval("js",
                "try { Java.type('java.lang.System'); 'escaped'; } catch(e) { 'blocked'; }");
            assertThat(result.asString()).isEqualTo("blocked");
        }
    }

    @Test
    void skill_basic_blocks_java_interop() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_BASIC, null)) {
            var result = ctx.eval("js",
                "try { Java.type('java.lang.Runtime'); 'escaped'; } catch(e) { 'blocked'; }");
            assertThat(result.asString()).isEqualTo("blocked");
        }
    }

    @Test
    void crypto_works_from_js_at_basic_level() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_BASIC, null)) {
            var result = ctx.eval("js", "crypto.sha256('hello')");
            assertThat(result.asString())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        }
    }

    @Test
    void fs_works_from_js_at_data_level() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_DATA, workspace)) {
            ctx.eval("js", "fs.write('test.txt', 'hello from JS')");
            var result = ctx.eval("js", "fs.read('test.txt')");
            assertThat(result.asString()).isEqualTo("hello from JS");
        }
    }

    @Test
    void null_level_throws() {
        assertThatThrownBy(() -> SandboxContextBuilder.build(null, workspace))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null");
    }

    @Test
    void sandbox_level_includes_check() {
        assertThat(SandboxLevel.SKILL_FULL.includes(SandboxLevel.ROOM_SCRIPT)).isTrue();
        assertThat(SandboxLevel.SKILL_FULL.includes(SandboxLevel.SKILL_DATA)).isTrue();
        assertThat(SandboxLevel.ROOM_SCRIPT.includes(SandboxLevel.SKILL_BASIC)).isFalse();
        assertThat(SandboxLevel.SKILL_BASIC.includes(SandboxLevel.SKILL_BASIC)).isTrue();
    }
}
