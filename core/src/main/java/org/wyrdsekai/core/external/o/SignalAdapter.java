package org.wyrdsekai.core.external.o;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Signal outbound. Wraps the bundled
 * {@code signal-cli} subprocess that already powers
 * {@code SignalChannel} ( channel maturation).
 *
 * <p>Methods: {@code send, send_group}.</p>
 *
 * <p>Credentials slot: {@code signal.account} — the registered phone number
 * passed to {@code signal-cli -u ...}. The adapter intentionally does NOT
 * shell-quote dynamic args via a shell; {@link ProcessBuilder} bypasses the
 * shell so each argv is passed atomically.</p>
 */
public final class SignalAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(SignalAdapter.class);

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private final Function<String, Optional<String>> credentials;
    private final ProcessRunner runner;

    public SignalAdapter() {
        this(slot -> CredentialResolver.get().resolve(slot),
            (cmd, timeout) -> AdapterHttp.runProcess(cmd, timeout));
    }

    SignalAdapter(Function<String, Optional<String>> credentials, ProcessRunner runner) {
        this.credentials = credentials;
        this.runner = runner;
    }

    @Override public String namespace() { return "signal"; }

    @Override public Set<String> capabilities() {
        return Set.of("send", "send_group");
    }

    @Override public String credentialSlot() { return "signal.account"; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var account = credentials.apply("signal.account");
        if (account.isEmpty()) {
            return AdapterResponse.fail("credentials_missing",
                "signal.account not in Safe (expected E.164 phone number)", false);
        }
        var args = req.args();
        return switch (req.method()) {
            case "send" -> send(args, account.get(), false);
            case "send_group" -> send(args, account.get(), true);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse send(Map<String, Object> args, String account, boolean group) {
        var recipientKey = group ? "groupId" : "recipient";
        var recipient = AdapterHttp.str(args, recipientKey);
        var text = AdapterHttp.str(args, "text");
        if (recipient == null || recipient.isBlank()) {
            return AdapterResponse.fail("invalid_argument",
                "'" + recipientKey + "' is required", false);
        }
        if (text == null) text = "";

        List<String> command;
        if (group) {
            command = List.of("signal-cli", "-u", account, "send",
                "-g", recipient, "-m", text);
        } else {
            command = List.of("signal-cli", "-u", account, "send",
                "-m", text, recipient);
        }
        var resp = runner.run(command, SEND_TIMEOUT);
        if (!resp.success()) {
            // Pass through process errors as-is; mark them retryable for
            // process_timeout but not for explicit failures.
            return resp;
        }
        return AdapterResponse.ok(Map.of(
            "timestamp", System.currentTimeMillis(),
            "recipient", recipient));
    }

    /** Test seam — tests inject a fake to avoid actually shelling out. */
    @FunctionalInterface
    interface ProcessRunner {
        AdapterResponse run(List<String> command, Duration timeout);
    }
}
