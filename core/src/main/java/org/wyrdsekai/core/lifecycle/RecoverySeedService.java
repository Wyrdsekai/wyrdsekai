package org.wyrdsekai.core.lifecycle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.interiority.ChronicleEntry;
import org.wyrdsekai.core.agent.interiority.ChronicleEntryStore;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.AgentIdentityProvisioner;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulStore;

import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * makes, checks and restores a companion's Recovery Seed.
 *
 * <p>Generate: the seed carries her latest soul manifest, her bond-table rows, a hash
 * of her chronicle and her own signing key, sealed under the steward's passphrase
 * ({@link RecoverySeedCodec}). One copy is kept in {@code <data-dir>/recovery-seed/}
 * (the spec's "stored: local"; it is sealed), the other goes to the steward.
 *
 * <p>Restore, on a node where the hardware she lived on is gone: her key is resealed
 * under this node's household secret, the manifest and bonds are stored, and the
 * {@code souls/<entityId>.did} birth record is written, so the next start's
 * respawn sweep wakes her as herself. A restore never displaces anyone: a node that
 * already holds her, or holds a different companion under her name, refuses.
 *
 * <p>Nothing here logs a passphrase, the seed's content or the file bytes, and nothing
 * is written to her trail except, on restore, one chronicle line saying where she
 * came from (§5.4: "I exist from a backup of [age]").
 */
public final class RecoverySeedService {

    private static final Logger log = LoggerFactory.getLogger(RecoverySeedService.class);

    /** Shortest passphrase accepted for a new seed. */
    public static final int MIN_PASSPHRASE = 12;

    /** Why a seed request was refused; the route maps each to an HTTP status and the CLI to words. */
    public enum Reason {
        PASSPHRASE_TOO_SHORT, WRONG_PASSPHRASE, NOT_A_SEED, NO_SUCH_COMPANION,
        WHICH_COMPANION, NO_KEY_HERE, ALREADY_HERE, NAME_TAKEN
    }

    public static final class Refused extends Exception {
        private final Reason reason;
        private final List<String> names;

        Refused(Reason reason, String message) {
            this(reason, message, List.of());
        }

        Refused(Reason reason, String message, List<String> names) {
            super(message);
            this.reason = reason;
            this.names = names;
        }

        public Reason reason() { return reason; }

        /** Companion names, for {@link Reason#WHICH_COMPANION}. */
        public List<String> names() { return names; }
    }

    /** What a seed holds, without the secrets: enough to say whose it is and how old. */
    public record Summary(String name, String entityId, String did, Instant createdAt,
                          int bonds, boolean carriesKey, boolean hereAlready) {}

    /** A freshly made seed: the sealed bytes and where the local copy was kept. */
    public record Made(Summary summary, byte[] file, Path localCopy) {}

    private record Resident(String name, String entityId, String did) {}

    private final SoulStore souls;
    private final BondStore bonds;
    private final Path soulsDir;
    private final Path seedDir;

    public RecoverySeedService(SoulStore souls, BondStore bonds, Path soulsDir, Path seedDir) {
        this.souls = souls;
        this.bonds = bonds;
        this.soulsDir = soulsDir;
        this.seedDir = seedDir;
    }

    /**
     * Make a seed for the companion named {@code who} (name, entity id or DID; may be
     * blank when exactly one companion lives here).
     */
    public Made generate(String who, char[] passphrase) throws Exception {
        if (passphrase == null || passphrase.length < MIN_PASSPHRASE) {
            throw new Refused(Reason.PASSPHRASE_TOO_SHORT,
                "The passphrase must be at least " + MIN_PASSPHRASE + " characters.");
        }
        var resident = find(who);
        var seed = build(resident);
        var file = RecoverySeedCodec.encrypt(seed, passphrase);
        var local = keepLocalCopy(resident.entityId(), file);
        log.info("Recovery Seed made for '{}' ({}); sealed copy kept at {}",
            resident.name(), resident.did(), local);
        return new Made(summarize(seed), file, local);
    }

    /** Open a seed and say whose it is. Changes nothing. */
    public Summary verify(byte[] file, char[] passphrase) throws Refused {
        return summarize(open(file, passphrase));
    }

    /** Bring her back on this node. She wakes at the next start. */
    public Summary restore(byte[] file, char[] passphrase) throws Exception {
        var seed = open(file, passphrase);
        var manifest = seed.soulManifest();
        if (manifest == null || manifest.profile() == null
                || !seed.agentDid().equals(manifest.did())
                || !seed.entityId().equals(manifest.profile().entityId())) {
            throw new Refused(Reason.NOT_A_SEED,
                "This seed does not carry a soul manifest that can be restored.");
        }
        if (isHere(seed.agentDid())) {
            throw new Refused(Reason.ALREADY_HERE,
                seed.agentName() + " already lives on this node. Nothing was changed.");
        }
        var takenBy = didRecordedFor(seed.entityId());
        if (takenBy != null && !takenBy.equals(seed.agentDid())) {
            throw new Refused(Reason.NAME_TAKEN, "A different companion already lives here as '"
                + seed.agentName() + "'. Nothing was changed.");
        }
        var existing = AgentIdentityProvisioner.existingDidFor(seed.entityId());
        if (existing.isPresent() && !existing.get().equals(seed.agentDid())) {
            throw new Refused(Reason.NAME_TAKEN, "A different companion already lives here as '"
                + seed.agentName() + "'. Nothing was changed.");
        }

        if (seed.agentKey() != null) {
            var secret = AgentIdentityProvisioner.secret().orElseThrow(() -> new Refused(
                Reason.NO_KEY_HERE, "This node cannot keep her signing key yet (its zone secret"
                    + " is not ready). Nothing was changed."));
            var raw = Base64.getDecoder().decode(seed.agentKey());
            try {
                var identity = AgentIdentity.restored(seed.agentDid(), raw, keyLog(seed),
                    seed.createdAt(), seed.parentDid(), secret);
                if (!AgentIdentityProvisioner.record(identity, seed.entityId())) {
                    throw new Refused(Reason.NO_KEY_HERE,
                        "This node could not store her signing key. Nothing was changed.");
                }
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        }
        souls.store(manifest);
        if (bonds != null && seed.bonds() != null) {
            for (var bond : seed.bonds()) {
                if (bond != null && bond.bondId() != null) bonds.save(bond);
            }
        }
        Files.createDirectories(soulsDir);
        Files.writeString(soulsDir.resolve(seed.entityId() + ".did"), seed.agentDid() + "\n");
        var chronicle = ChronicleEntryStore.get();
        if (chronicle != null) {
            chronicle.append(new ChronicleEntry(seed.agentDid(), Instant.now(),
                ChronicleEntry.Kind.NOTE,
                "My prior substrate was lost. I exist from a Recovery Seed made on "
                    + seed.createdAt().toString().substring(0, 10)
                    + ". What happened after that is not in me.",
                Map.of("source", "recovery_seed", "seedCreatedAt", seed.createdAt().toString())));
        }
        log.info("Recovery Seed restored '{}' ({}); she wakes at the next start",
            seed.agentName(), seed.agentDid());
        return summarize(seed);
    }

    RecoverySeed build(Resident resident) throws Exception {
        var manifest = souls.latest(resident.did()).orElseThrow(() -> new Refused(
            Reason.NO_SUCH_COMPANION, "No soul is stored for " + resident.name() + "."));
        String agentKey = null;
        var identity = AgentIdentityProvisioner.find(resident.did()).orElse(null);
        var secret = AgentIdentityProvisioner.secret().orElse(null);
        if (identity != null && identity.privateKeyEncrypted() != null && secret != null) {
            var raw = identity.rawPrivateKey(secret);
            try {
                agentKey = Base64.getEncoder().encodeToString(raw);
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        }
        var bondRows = bonds != null ? bonds.bondsForAgent(resident.did()) : List.<Bond>of();
        var pointers = new ArrayList<RecoverySeed.BondPointer>();
        for (var b : bondRows) {
            var other = resident.did().equals(b.agentADid()) ? b.agentBDid() : b.agentADid();
            pointers.add(new RecoverySeed.BondPointer(other,
                b.state() != null ? b.state().name() : (b.active() ? "ACTIVE" : "SEVERED"),
                b.scarred(), b.lastInteraction()));
        }
        var keyLog = new ArrayList<String>();
        if (manifest.keyLog() != null) {
            for (var event : manifest.keyLog()) keyLog.add(event.toString());
        }
        return new RecoverySeed(
            RecoverySeed.CURRENT_FORMAT_VERSION, Instant.now(),
            resident.did(), manifest.publicKeyMultibase(), keyLog, manifest.parentDid(),
            resident.name(), resident.entityId(),
            manifest.profile() != null ? manifest.profile().systemPrompt() : null,
            manifest.residentIdentity(), Map.of(), pointers,
            List.of(), List.of(), List.of(), null,
            chronicleAnchor(resident.did()),
            agentKey, manifest, bondRows);
    }

    private RecoverySeed open(byte[] file, char[] passphrase) throws Refused {
        if (passphrase == null || passphrase.length == 0) {
            throw new Refused(Reason.WRONG_PASSPHRASE, "The passphrase is empty.");
        }
        RecoverySeed seed;
        try {
            seed = RecoverySeedCodec.decrypt(file, passphrase);
        } catch (AEADBadTagException e) {
            throw new Refused(Reason.WRONG_PASSPHRASE,
                "The passphrase is wrong, or the file was changed or damaged.");
        } catch (IllegalArgumentException e) {
            throw new Refused(Reason.NOT_A_SEED, "This is not a Wyrdsekai Recovery Seed file, "
                + "or it is damaged.");
        } catch (Exception e) {
            throw new Refused(Reason.NOT_A_SEED, "This Recovery Seed could not be read.");
        }
        if (seed == null || seed.agentDid() == null || seed.entityId() == null
                || seed.createdAt() == null
                || seed.formatVersion() > RecoverySeed.CURRENT_FORMAT_VERSION) {
            throw new Refused(Reason.NOT_A_SEED, "This Recovery Seed is from a newer or broken"
                + " build and cannot be read here.");
        }
        return seed;
    }

    private Summary summarize(RecoverySeed seed) {
        return new Summary(seed.agentName(), seed.entityId(), seed.agentDid(), seed.createdAt(),
            seed.bonds() != null ? seed.bonds().size()
                : seed.bondPointers() != null ? seed.bondPointers().size() : 0,
            seed.agentKey() != null, isHere(seed.agentDid()));
    }

    private boolean isHere(String did) {
        return souls.exists(did) || AgentIdentityProvisioner.find(did).isPresent();
    }

    private Resident find(String who) throws Refused {
        var residents = residents();
        if (residents.isEmpty()) {
            throw new Refused(Reason.NO_SUCH_COMPANION, "No companion lives on this node yet.");
        }
        if (who == null || who.isBlank()) {
            if (residents.size() == 1) return residents.getFirst();
            throw new Refused(Reason.WHICH_COMPANION, "More than one companion lives here; name one.",
                residents.stream().map(Resident::name).toList());
        }
        var wanted = who.strip().toLowerCase(Locale.ROOT);
        for (var r : residents) {
            if (r.did().equals(who.strip()) || r.entityId().toLowerCase(Locale.ROOT).equals(wanted)
                    || (r.name() != null && r.name().toLowerCase(Locale.ROOT).equals(wanted))) {
                return r;
            }
        }
        throw new Refused(Reason.NO_SUCH_COMPANION, "No companion named '" + who + "' lives here.",
            residents.stream().map(Resident::name).toList());
    }

    /** Companions born or restored here: a soul whose birth record names its DID (as Main's respawn sweep). */
    private List<Resident> residents() {
        var out = new ArrayList<Resident>();
        for (SoulManifest m : souls.listLatest()) {
            var p = m.profile();
            if (p == null || p.entityId() == null || m.did() == null) continue;
            if (!"agent".equals(p.entityType())) continue;
            if (!m.did().equals(didRecordedFor(p.entityId()))) continue;
            out.add(new Resident(p.name() != null ? p.name() : p.entityId(), p.entityId(), m.did()));
        }
        return out;
    }

    private String didRecordedFor(String entityId) {
        var file = soulsDir.resolve(entityId + ".did");
        if (!Files.isRegularFile(file)) return null;
        try {
            var did = Files.readString(file).strip();
            return did.isEmpty() ? null : did;
        } catch (IOException e) {
            return null;
        }
    }

    private static String chronicleAnchor(String did) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var chronicle = ChronicleEntryStore.get();
        if (chronicle != null) {
            for (var e : chronicle.recent(did, Duration.ofDays(36_500), 100_000)) {
                digest.update((e.ts() + "|" + e.kind() + "|" + e.summary() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<ObjectNode> keyLog(RecoverySeed seed) throws IOException {
        var out = new ArrayList<ObjectNode>();
        if (seed.keyLog() == null) return out;
        for (var raw : seed.keyLog()) {
            if (Json.mapper().readTree(raw) instanceof ObjectNode node) out.add(node);
        }
        return out;
    }

    private Path keepLocalCopy(String entityId, byte[] file) throws IOException {
        Files.createDirectories(seedDir);
        restrict(seedDir, true);
        var target = seedDir.resolve(entityId + ".wsrs");
        var tmp = seedDir.resolve(entityId + ".wsrs.tmp");
        Files.write(tmp, file);
        restrict(tmp, false);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private static void restrict(Path path, boolean dir) {
        try {
            var perms = dir
                ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE)
                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the data folder is already the steward's own profile.
        }
    }
}
