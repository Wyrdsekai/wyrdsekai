package org.wyrdsekai.core.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates workbench skill submissions before packaging as SoulItems.
 * Checks syntax, size, function signature, and runtime constraints.
 */
public final class WorkbenchValidator {

    /** Maximum code size in bytes for GraalJS/shell. */
    public static final int MAX_CODE_SIZE = 4096;

    /** Maximum code size in bytes for Python (scripts tend to be longer). */
    public static final int MAX_CODE_SIZE_PYTHON = 8192;

    /** Maximum number of test cases. */
    public static final int MAX_TEST_CASES = 10;

    /** Supported runtimes. */
    private static final List<String> SUPPORTED_RUNTIMES = List.of("graaljs", "shell", "python");

    private WorkbenchValidator() {}

    /**
     * Validation result — list of errors (empty = valid).
     */
    public record ValidationResult(List<String> errors) {
        public boolean valid() { return errors.isEmpty(); }
        public String summary() {
            return valid() ? "Valid" : String.join("; ", errors);
        }
    }

    /**
     * Validate a workbench submission.
     */
    public static ValidationResult validate(String skillName, String runtime,
                                              String code, List<?> testCases) {
        var errors = new ArrayList<String>();

        // Name
        if (skillName == null || skillName.isBlank()) {
            errors.add("Skill name is required");
        } else if (skillName.length() > 64) {
            errors.add("Skill name too long (max 64 characters)");
        } else if (!skillName.matches("[a-zA-Z0-9_-]+")) {
            errors.add("Skill name must be alphanumeric with hyphens/underscores");
        }

        // Runtime
        if (runtime == null || !SUPPORTED_RUNTIMES.contains(runtime)) {
            errors.add("Unsupported runtime '" + runtime +
                "'. Supported: " + SUPPORTED_RUNTIMES);
        }

        // Code
        if (code == null || code.isBlank()) {
            errors.add("Code is required");
        } else {
            int maxSize = "python".equals(runtime) ? MAX_CODE_SIZE_PYTHON : MAX_CODE_SIZE;
            if (code.length() > maxSize) {
                errors.add("Code exceeds maximum size (" + code.length() +
                    " bytes, max " + maxSize + ")");
            }

            // GraalJS: must define execute function
            if ("graaljs".equals(runtime) && !code.contains("function execute")) {
                errors.add("GraalJS skills must define 'function execute(params)'");
            }

            // Python: must define execute function or main block
            if ("python".equals(runtime) && !code.contains("def execute")
                && !code.contains("if __name__")) {
                errors.add("Python skills must define 'def execute(params)' or 'if __name__' block");
            }
        }

        // Test cases
        if (testCases != null && testCases.size() > MAX_TEST_CASES) {
            errors.add("Too many test cases (" + testCases.size() +
                ", max " + MAX_TEST_CASES + ")");
        }

        return new ValidationResult(errors);
    }

    /**
     * Quick check if a runtime is supported.
     */
    public static boolean isSupportedRuntime(String runtime) {
        return runtime != null && SUPPORTED_RUNTIMES.contains(runtime);
    }

    /**
     * Get the list of supported runtimes.
     */
    public static List<String> supportedRuntimes() {
        return SUPPORTED_RUNTIMES;
    }
}
