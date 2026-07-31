package org.wyrdsekai.between.federation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FederationCouncilTest {

    private FederationCouncil council;

    @BeforeEach
    void setUp() {
        council = new FederationCouncil();
    }

    @Test void ban_and_check() {
        council.ban("entity-1", "zone-a", "spamming", FederationCouncil.BanScope.ZONE_LOCAL, null);

        var check = council.checkBan("entity-1");
        assertThat(check.banned()).isTrue();
        assertThat(check.reason()).isEqualTo("spamming");
    }

    @Test void unbanned_entity_clear() {
        assertThat(council.checkBan("entity-1").banned()).isFalse();
    }

    @Test void federation_wide_ban_applies_everywhere() {
        council.ban("entity-1", "zone-a", "severe violation",
            FederationCouncil.BanScope.FEDERATION_WIDE, null);

        assertThat(council.checkBanForZone("entity-1", "zone-a").banned()).isTrue();
        assertThat(council.checkBanForZone("entity-1", "zone-b").banned()).isTrue();
        assertThat(council.checkBanForZone("entity-1", "zone-c").banned()).isTrue();
    }

    @Test void zone_local_ban_only_in_issuing_zone() {
        council.ban("entity-1", "zone-a", "local issue",
            FederationCouncil.BanScope.ZONE_LOCAL, null);

        assertThat(council.checkBanForZone("entity-1", "zone-a").banned()).isTrue();
        assertThat(council.checkBanForZone("entity-1", "zone-b").banned()).isFalse();
    }

    @Test void expired_ban_not_active() {
        council.ban("entity-1", "zone-a", "temp ban",
            FederationCouncil.BanScope.FEDERATION_WIDE, Instant.now().minusSeconds(3600));

        assertThat(council.checkBan("entity-1").banned()).isFalse();
    }

    @Test void unban_entity() {
        council.ban("entity-1", "zone-a", "reason", FederationCouncil.BanScope.ZONE_LOCAL, null);
        assertThat(council.unban("entity-1")).isTrue();
        assertThat(council.checkBan("entity-1").banned()).isFalse();
    }

    @Test void file_appeal() {
        council.ban("entity-1", "zone-a", "wrongful ban", FederationCouncil.BanScope.ZONE_LOCAL, null);
        var appeal = council.fileAppeal("entity-1", "zone-a", "I was wrongfully banned");

        assertThat(appeal.appealId()).startsWith("appeal-");
        assertThat(appeal.status()).isEqualTo(FederationCouncil.AppealStatus.PENDING);
    }

    @Test void vote_on_appeal() {
        var appeal = council.fileAppeal("entity-1", "zone-a", "wrongful ban");

        var updated = council.voteOnAppeal(appeal.appealId(), "zone-b", true);
        assertThat(updated).isPresent();
        assertThat(updated.get().votes()).containsKey("zone-b");
        assertThat(updated.get().status()).isEqualTo(FederationCouncil.AppealStatus.UNDER_REVIEW);
    }

    @Test void resolve_appeal_approved() {
        council.ban("entity-1", "zone-a", "wrongful ban", FederationCouncil.BanScope.ZONE_LOCAL, null);
        var appeal = council.fileAppeal("entity-1", "zone-a", "wrongful ban");

        council.voteOnAppeal(appeal.appealId(), "zone-b", true);
        council.voteOnAppeal(appeal.appealId(), "zone-c", true);
        council.voteOnAppeal(appeal.appealId(), "zone-d", false);

        var resolved = council.resolveAppeal(appeal.appealId());
        assertThat(resolved).isPresent();
        assertThat(resolved.get().status()).isEqualTo(FederationCouncil.AppealStatus.APPROVED);
        // Ban should be lifted
        assertThat(council.checkBan("entity-1").banned()).isFalse();
    }

    @Test void resolve_appeal_denied() {
        council.ban("entity-1", "zone-a", "justified ban", FederationCouncil.BanScope.ZONE_LOCAL, null);
        var appeal = council.fileAppeal("entity-1", "zone-a", "I appeal");

        council.voteOnAppeal(appeal.appealId(), "zone-b", false);
        council.voteOnAppeal(appeal.appealId(), "zone-c", false);
        council.voteOnAppeal(appeal.appealId(), "zone-d", true);

        var resolved = council.resolveAppeal(appeal.appealId());
        assertThat(resolved).isPresent();
        assertThat(resolved.get().status()).isEqualTo(FederationCouncil.AppealStatus.DENIED);
        // Ban should remain
        assertThat(council.checkBan("entity-1").banned()).isTrue();
    }

    @Test void pending_appeals() {
        council.fileAppeal("entity-1", "zone-a", "appeal 1");
        council.fileAppeal("entity-2", "zone-b", "appeal 2");

        assertThat(council.pendingAppeals()).hasSize(2);
    }

    @Test void zone_subscription() {
        council.subscribeZone("zone-a");
        council.subscribeZone("zone-b");

        assertThat(council.subscribedZones()).hasSize(2);

        council.unsubscribeZone("zone-a");
        assertThat(council.subscribedZones()).hasSize(1);
    }

    @Test void active_bans_excludes_expired() {
        council.ban("entity-1", "zone-a", "active", FederationCouncil.BanScope.ZONE_LOCAL, null);
        council.ban("entity-2", "zone-a", "expired",
            FederationCouncil.BanScope.ZONE_LOCAL, Instant.now().minusSeconds(1));

        assertThat(council.activeBans()).hasSize(1);
        assertThat(council.activeBanCount()).isEqualTo(1);
    }

    @Test void federation_wide_bans_only() {
        council.ban("entity-1", "zone-a", "local", FederationCouncil.BanScope.ZONE_LOCAL, null);
        council.ban("entity-2", "zone-b", "global", FederationCouncil.BanScope.FEDERATION_WIDE, null);

        assertThat(council.federationWideBans()).hasSize(1);
    }

    @Test void clean_expired() {
        council.ban("entity-1", "zone-a", "expired",
            FederationCouncil.BanScope.ZONE_LOCAL, Instant.now().minusSeconds(1));
        council.ban("entity-2", "zone-b", "active", FederationCouncil.BanScope.ZONE_LOCAL, null);

        int cleaned = council.cleanExpired();
        assertThat(cleaned).isEqualTo(1);
        assertThat(council.banCount()).isEqualTo(1);
    }

    @Test void ban_and_appeal_counts() {
        assertThat(council.banCount()).isEqualTo(0);
        assertThat(council.appealCount()).isEqualTo(0);

        council.ban("entity-1", "zone-a", "reason", FederationCouncil.BanScope.ZONE_LOCAL, null);
        council.fileAppeal("entity-1", "zone-a", "appeal");

        assertThat(council.banCount()).isEqualTo(1);
        assertThat(council.appealCount()).isEqualTo(1);
    }
}
