package org.wyrdsekai.server.hermod;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.hermod.GrantAuthority;
import org.wyrdsekai.hermod.SignedGrant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The steward's consent act, as a command: mint a signed data-domain
 * grant with THIS node's household authority key. The grant file is
 * what an origin device carries (a phone receives its copy at pairing);
 * doors verify it against the authority public key wherever the task
 * lands. Deliberately explicit — scope, device class, and lifetime are
 * all spoken aloud in the invocation, never defaulted.
 *
 * Usage: hermod-grant <dataDomain> <deviceClass> <days> [scopeId]
 * Writes: <dataDir>/hermod-grants/<dataDomain>-<deviceClass>.json
 */
public final class GrantMintMain {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: hermod-grant <dataDomain> <deviceClass> <days> [scopeId]");
            System.exit(2);
        }
        var domain = args[0];
        var deviceClass = args[1];
        var days = Long.parseLong(args[2]);
        var dataDir = Path.of(System.getenv().getOrDefault(
            "WYRDSEKAI_DATA_DIR", System.getProperty("user.home") + "/.wyrdsekai"));
        var scope = args.length > 3 ? args[3]
            : System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "home");

        var identity = NodeIdentity.loadOrGenerate(dataDir.resolve("node-identity.json"));
        var key = GrantAuthority.privateKeyFromSeed(identity.privateKeySeedBytes());
        var now = Instant.now();
        var grant = GrantAuthority.mint(UUID.randomUUID().toString(), scope, domain,
            deviceClass, now, now.plus(Duration.ofDays(days)), "v1", key);

        var outDir = dataDir.resolve("hermod-grants");
        Files.createDirectories(outDir);
        var out = outDir.resolve(domain + "-" + deviceClass + ".json");
        Files.write(out, JSON.writeValueAsBytes(grant));

        // Prove what was minted verifies against this household's key
        // before telling the steward it exists.
        var ok = GrantAuthority.verifier(identity.publicKeyBytes()).test(
            JSON.readValue(Files.readAllBytes(out), SignedGrant.class));
        if (!ok) {
            Files.deleteIfExists(out);
            System.err.println("mint self-check FAILED — no grant written");
            System.exit(1);
        }
        System.out.println("granted: domain '" + domain + "' to device class '"
            + deviceClass + "' for " + days + " days (scope " + scope + ")");
        System.out.println("grant file: " + out);
        System.out.println("expires: " + grant.expiresAt());
    }
}
