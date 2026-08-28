package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verifies the
 * {@code preferred_language} and {@code cultural_register_preference}
 * columns survive a full save/close/reopen round-trip on a real SQLite
 * file. The test uses a file-based DB (rather than the in-memory shared-
 * cache one) so the schema migration runs twice — once on the fresh
 * create, once on reopen — and the second run must be idempotent.
 */
@Tag("integration")
class CulturalRegisterPersistenceTest {

    @TempDir Path workspace;

    private String jdbcUrl;

    @BeforeEach
    void setUp() {
        // File-backed JDBC URL — survives connection close/reopen.
        jdbcUrl = "jdbc:sqlite:" + workspace.resolve("accounts.db");
    }

    @Test void preferred_language_round_trips_through_close_and_reopen() {
        // Open store, save an account with preferred_language, close.
        var store1 = new AccountStore(jdbcUrl);
        var account = PlayerAccount.create("Operator")
            .withPreferredLanguage("ja-JP");
        store1.save(account);

        // Reopen — fresh AccountStore, fresh schema-init pass (must be idempotent),
        // and the value must come back intact.
        var store2 = new AccountStore(jdbcUrl);
        var found = store2.findByDid(account.did()).orElseThrow();
        assertThat(found.preferredLanguage()).isEqualTo("ja-JP");
        assertThat(found.culturalRegisterPreference()).isNull();
    }

    @Test void cultural_register_override_round_trips() {
        var store1 = new AccountStore(jdbcUrl);
        var account = PlayerAccount.create("KikokushijoMasumi")
            .withPreferredLanguage("ja-JP")
            .withCulturalRegisterPreference("anglo");
        store1.save(account);

        var store2 = new AccountStore(jdbcUrl);
        var found = store2.findByDid(account.did()).orElseThrow();
        assertThat(found.preferredLanguage()).isEqualTo("ja-JP");
        assertThat(found.culturalRegisterPreference()).isEqualTo("anglo");
    }

    @Test void update_changes_preferences_on_existing_account() {
        var store = new AccountStore(jdbcUrl);
        var account = PlayerAccount.create("Bob");
        store.save(account);

        // Initially both null.
        var loaded1 = store.findByDid(account.did()).orElseThrow();
        assertThat(loaded1.preferredLanguage()).isNull();
        assertThat(loaded1.culturalRegisterPreference()).isNull();

        // Update with new preferences via withers + save (upsert path).
        store.save(loaded1
            .withPreferredLanguage("es-MX")
            .withCulturalRegisterPreference("latin-warm"));

        var loaded2 = store.findByDid(account.did()).orElseThrow();
        assertThat(loaded2.preferredLanguage()).isEqualTo("es-MX");
        assertThat(loaded2.culturalRegisterPreference()).isEqualTo("latin-warm");

        // Clearing back to null also round-trips.
        store.save(loaded2
            .withPreferredLanguage(null)
            .withCulturalRegisterPreference(null));
        var loaded3 = store.findByDid(account.did()).orElseThrow();
        assertThat(loaded3.preferredLanguage()).isNull();
        assertThat(loaded3.culturalRegisterPreference()).isNull();
    }

    @Test void list_all_returns_preferences() {
        var store = new AccountStore(jdbcUrl);
        store.save(PlayerAccount.create("Alice").withPreferredLanguage("en-US"));
        store.save(PlayerAccount.create("Beto").withPreferredLanguage("pt-BR"));
        store.save(PlayerAccount.create("Yuki")
            .withPreferredLanguage("ja-JP")
            .withCulturalRegisterPreference("japanese-formal"));

        var all = store.listAll();
        assertThat(all).hasSize(3);
        assertThat(all).anyMatch(a -> "Yuki".equals(a.displayName())
            && "japanese-formal".equals(a.culturalRegisterPreference()));
        assertThat(all).anyMatch(a -> "Beto".equals(a.displayName())
            && "pt-BR".equals(a.preferredLanguage()));
    }

    @Test void migration_is_idempotent_on_reopen() {
        // Idempotency check: opening the store three times in succession on the
        // same file must succeed each time without errors. Catches regressions
        // where the ALTER TABLE add-column path crashes because the column
        // already exists.
        new AccountStore(jdbcUrl);
        new AccountStore(jdbcUrl);
        new AccountStore(jdbcUrl);
        var store = new AccountStore(jdbcUrl);
        // And we can still read/write.
        var account = PlayerAccount.create("Idempotent")
            .withPreferredLanguage("ko-KR");
        store.save(account);
        var found = store.findByDid(account.did()).orElseThrow();
        assertThat(found.preferredLanguage()).isEqualTo("ko-KR");
    }
}
