package org.wyrdsekai.hermod;

/**
 * What actually runs an admitted envelope on this device. hermod core
 * defines the seam only; wyrdsekai binds it to its inference stack in
 * an adapter OUTSIDE this package (extraction purity).
 */
public interface TaskExecutor {

    record TaskResult(String envelopeId, boolean ok, String output, String error) {
        public static TaskResult ok(String id, String out) { return new TaskResult(id, true, out, ""); }
        public static TaskResult fail(String id, String err) { return new TaskResult(id, false, "", err); }
    }

    /** True if this executor can run the given task type. */
    boolean handles(String taskType);

    TaskResult execute(TaskEnvelope envelope);
}
