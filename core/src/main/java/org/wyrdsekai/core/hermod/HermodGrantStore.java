package org.wyrdsekai.core.hermod;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.hermod.GrantAuthority;
import org.wyrdsekai.hermod.SignedGrant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The household's view of its own hermod data-domain grants — the FIRST
 * runtime reader of {@code <dataDir>/hermod-grants/} (GrantMintMain has
 * been the only writer). Read-and-revoke only: minting stays a spoken
 * steward act at the CLI, and the files stay flat and portable because
 * they ARE the artifact a device carries.
 *
 * Revocation is a tombstone (rename to {@code *.revoked}), never a
 * delete — the household keeps its history. Honesty note carried up to
 * the in-world surface: revoking here stops NEW distribution; a copy
 * already carried by a device remains cryptographically valid until its
 * own expiry. There is no recall of a signature.
 */
public final class HermodGrantStore {

    private static final Logger log = LoggerFactory.getLogger(HermodGrantStore.class);
    // Reads both instant encodings: GrantMintMain wrote numeric epochs;
    // JavaTimeModule accepts ISO-8601 strings too if the writer ever aligns.
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** One grant file, judged: active | expired | invalid-signature | unreadable. */
    public record GrantView(SignedGrant grant, String status, String fileName) {
    }

    private final Path dir;
    private final byte[] authoritySpki;
    private final Clock clock;

    public HermodGrantStore(Path dir, byte[] authoritySpki, Clock clock) {
        this.dir = dir;
        this.authoritySpki = authoritySpki;
        this.clock = clock;
    }

    /** Every non-tombstoned grant in the directory, re-verified on read. */
    public List<GrantView> list() {
        var out = new ArrayList<GrantView>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .forEach(p -> {
                    var name = p.getFileName().toString();
                    try {
                        var grant = JSON.readValue(Files.readAllBytes(p), SignedGrant.class);
                        var status = !GrantAuthority.verifier(authoritySpki).test(grant)
                            ? "invalid-signature"
                            : grant.expiresAt().isBefore(Instant.now(clock))
                                ? "expired" : "active";
                        out.add(new GrantView(grant, status, name));
                    } catch (Exception e) {
                        out.add(new GrantView(null, "unreadable", name));
                    }
                });
        } catch (Exception e) {
            log.warn("hermod-grants list failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Tombstone a grant by id, or by its file's domain-class stem.
     * Returns the revoked view, or null when nothing matched.
     */
    public GrantView revoke(String grantIdOrStem) {
        if (grantIdOrStem == null || grantIdOrStem.isBlank()) return null;
        var wanted = grantIdOrStem.trim();
        for (var view : list()) {
            var stem = view.fileName().endsWith(".json")
                ? view.fileName().substring(0, view.fileName().length() - 5)
                : view.fileName();
            var byId = view.grant() != null && wanted.equals(view.grant().grantId());
            if (!byId && !wanted.equals(stem)) continue;
            try {
                var from = dir.resolve(view.fileName());
                Files.move(from, dir.resolve(view.fileName() + ".revoked"));
                log.info("hermod grant tombstoned: {} ({})", view.fileName(),
                    view.grant() != null ? view.grant().grantId() : "unreadable");
                return view;
            } catch (Exception e) {
                log.warn("hermod grant revoke failed for {}: {}", view.fileName(), e.getMessage());
                return null;
            }
        }
        return null;
    }
}
