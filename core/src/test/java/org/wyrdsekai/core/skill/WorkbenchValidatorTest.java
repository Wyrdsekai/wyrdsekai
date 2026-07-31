package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchValidatorTest {

    private static final String VALID_CODE = "function execute(params) { return params.x; }";

    @Test void valid_submission_passes() {
        var result = WorkbenchValidator.validate("weather-check", "graaljs", VALID_CODE, null);
        assertThat(result.valid()).isTrue();
        assertThat(result.summary()).isEqualTo("Valid");
    }

    @Test void null_name_rejected() {
        var result = WorkbenchValidator.validate(null, "graaljs", VALID_CODE, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("name is required"));
    }

    @Test void blank_name_rejected() {
        var result = WorkbenchValidator.validate("  ", "graaljs", VALID_CODE, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("name is required"));
    }

    @Test void long_name_rejected() {
        var longName = "a".repeat(65);
        var result = WorkbenchValidator.validate(longName, "graaljs", VALID_CODE, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("too long"));
    }

    @Test void name_with_spaces_rejected() {
        var result = WorkbenchValidator.validate("my skill", "graaljs", VALID_CODE, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("alphanumeric"));
    }

    @Test void name_with_hyphens_and_underscores_allowed() {
        var result = WorkbenchValidator.validate("my_skill-v2", "graaljs", VALID_CODE, null);
        assertThat(result.valid()).isTrue();
    }

    @Test void unsupported_runtime_rejected() {
        var result = WorkbenchValidator.validate("test", "ruby", VALID_CODE, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Unsupported runtime"));
    }

    @Test void null_runtime_rejected() {
        var result = WorkbenchValidator.validate("test", null, VALID_CODE, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Unsupported runtime"));
    }

    @Test void shell_runtime_accepted() {
        // shell is supported for validation but not yet for execution
        var result = WorkbenchValidator.validate("test", "shell", "echo hello", null);
        assertThat(result.valid()).isTrue();
    }

    @Test void null_code_rejected() {
        var result = WorkbenchValidator.validate("test", "graaljs", null, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Code is required"));
    }

    @Test void blank_code_rejected() {
        var result = WorkbenchValidator.validate("test", "graaljs", "  ", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Code is required"));
    }

    @Test void oversized_code_rejected() {
        var bigCode = "function execute(params) { " + "x".repeat(WorkbenchValidator.MAX_CODE_SIZE) + " }";
        var result = WorkbenchValidator.validate("test", "graaljs", bigCode, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("exceeds maximum size"));
    }

    @Test void graaljs_without_execute_function_rejected() {
        var result = WorkbenchValidator.validate("test", "graaljs",
            "function run(params) { return 1; }", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("function execute"));
    }

    @Test void shell_without_execute_function_accepted() {
        var result = WorkbenchValidator.validate("test", "shell", "echo hello", null);
        assertThat(result.valid()).isTrue();
    }

    @Test void too_many_test_cases_rejected() {
        var cases = new ArrayList<>();
        for (int i = 0; i < WorkbenchValidator.MAX_TEST_CASES + 1; i++) {
            cases.add("case" + i);
        }
        var result = WorkbenchValidator.validate("test", "graaljs", VALID_CODE, cases);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Too many test cases"));
    }

    @Test void max_test_cases_accepted() {
        var cases = new ArrayList<>();
        for (int i = 0; i < WorkbenchValidator.MAX_TEST_CASES; i++) {
            cases.add("case" + i);
        }
        var result = WorkbenchValidator.validate("test", "graaljs", VALID_CODE, cases);
        assertThat(result.valid()).isTrue();
    }

    @Test void multiple_errors_accumulated() {
        var result = WorkbenchValidator.validate(null, "ruby", null, null);
        assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test void isSupportedRuntime_returns_correct_results() {
        assertThat(WorkbenchValidator.isSupportedRuntime("graaljs")).isTrue();
        assertThat(WorkbenchValidator.isSupportedRuntime("shell")).isTrue();
        assertThat(WorkbenchValidator.isSupportedRuntime("python")).isTrue();
        assertThat(WorkbenchValidator.isSupportedRuntime("ruby")).isFalse();
        assertThat(WorkbenchValidator.isSupportedRuntime(null)).isFalse();
    }

    @Test void supportedRuntimes_includes_all_three() {
        var runtimes = WorkbenchValidator.supportedRuntimes();
        assertThat(runtimes).containsExactly("graaljs", "shell", "python");
    }

    @Test void python_runtime_accepted() {
        var result = WorkbenchValidator.validate("test", "python",
            "def execute(params):\n    return params", null);
        assertThat(result.valid()).isTrue();
    }

    @Test void python_without_execute_or_main_rejected() {
        var result = WorkbenchValidator.validate("test", "python",
            "print('hello')", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("def execute"));
    }

    @Test void python_with_main_block_accepted() {
        var result = WorkbenchValidator.validate("test", "python",
            "if __name__ == '__main__':\n    print('hello')", null);
        assertThat(result.valid()).isTrue();
    }

    @Test void python_code_size_limit_is_larger() {
        // Python gets 8192 bytes instead of 4096
        var code = "def execute(params):\n    x = '" + "a".repeat(5000) + "'";
        var result = WorkbenchValidator.validate("test", "python", code, null);
        assertThat(result.valid()).isTrue();

        // But over 8192 is still rejected
        var bigCode = "def execute(params):\n    x = '" + "a".repeat(8200) + "'";
        var bigResult = WorkbenchValidator.validate("test", "python", bigCode, null);
        assertThat(bigResult.valid()).isFalse();
        assertThat(bigResult.errors()).anyMatch(e -> e.contains("exceeds maximum size"));
    }
}
