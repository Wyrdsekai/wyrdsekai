package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.agent.decision.TypedDecision;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondKind;
import org.wyrdsekai.core.soul.BondNameStore;
import org.wyrdsekai.core.soul.BondNaming;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a bond became sacred the companion said "I'd like to propose a naming ritual — a shared name
 * or symbol that only we understand", and nothing could answer it: no verb, no place for the name,
 * and the sentence was kept as her own words (household node, 2026-09-30). A person now offers a
 * name, she is asked as herself whether she takes it (the model is a stand-in here), and only a
 * name both hold is kept.
 */
@Tag("integration")
class TheNamingRitualTest {

    private static ActorTestKit testKit;
    private ActorRef<CompanionActor.Command> companion;

    private static final String ROOM_ID = "nexus";
    private static final String SELF = "agent-wyrd";
    private static final String ADA = "did:person:ada";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", SELF, "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("naming-ritual-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    private final List<String> asked = new CopyOnWriteArrayList<>();
    private volatile String herAnswer;

    @BeforeEach
    void spawnCompanion() {
        EntityRegistry.init();
        BondNameStore.install(null);
        asked.clear();
        herAnswer = null;
        CompanionActor.askBondName = (url, state) -> {
            asked.add(state);
            return CompletableFuture.completedFuture(herAnswer == null ? Optional.empty()
                : Optional.of(new TypedDecision.Answer(herAnswer, Map.of(herAnswer, 0.9), "test")));
        };
        TestProbe<RoomCommand> roomProbe = testKit.createTestProbe();
        TestProbe<InferenceRouter.Command> routerProbe = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));
        roomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5))
            .replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(
                ROOM_ID, "The Nexus", "A shimmering hub of connections.", "foundation",
                List.of(new Exit("east", "terminal", "The Terminal")),
                List.of(), List.of(), List.of())));
    }

    @AfterEach
    void theModelAgain() {
        CompanionActor.askBondName = CompanionActor.ASK_BOND_NAME_BY_MODEL;
        BondNameStore.install(null);
    }

    private static Bond bondAt(Bond.BondDepth depth) {
        var bond = Bond.open(SELF, ADA, BondKind.BONDHOLDER).crossToActive();
        while (bond.depth() != depth) bond = bond.elevate();
        return bond;
    }

    private BondNaming.Heard offer(String name) throws Exception {
        var heard = new CompletableFuture<BondNaming.Heard>();
        companion.tell(new CompanionActor.OfferBondName(ADA, "Ada", name, heard));
        return heard.get(5, TimeUnit.SECONDS);
    }

    private Optional<BondNameStore.Named> awaitName(boolean expected) throws InterruptedException {
        long until = System.currentTimeMillis() + 3000;
        var found = BondNameStore.get().find(SELF, ADA);
        while (found.isPresent() != expected && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            found = BondNameStore.get().find(SELF, ADA);
        }
        return found;
    }

    @Test
    void a_name_she_takes_is_kept() throws Exception {
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.SACRED)));
        herAnswer = "yes";
        assertThat(offer("the quiet light")).isEqualTo(BondNaming.Heard.OFFERED);
        var kept = awaitName(true);
        assertThat(kept).isPresent();
        assertThat(kept.get().name()).isEqualTo("the quiet light");
        assertThat(kept.get().offeredBy()).isEqualTo(ADA);
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)).contains("Ada offers this name for your bond: the quiet light")
            .endsWith("Do you take this name as yours too? Answer yes or no.");
    }

    @Test
    void a_name_she_does_not_take_is_not_kept_and_another_can_be_offered() throws Exception {
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.SACRED)));
        herAnswer = "no";
        assertThat(offer("pet")).isEqualTo(BondNaming.Heard.OFFERED);
        assertThat(awaitAsked(1)).isTrue();
        assertThat(awaitName(false)).as("she asked for another: nothing is kept").isEmpty();
        herAnswer = "yes";
        assertThat(offer("the quiet light")).as("the ritual is still open").isEqualTo(BondNaming.Heard.OFFERED);
        assertThat(awaitName(true).get().name()).isEqualTo("the quiet light");
    }

    @Test
    void when_she_cannot_be_asked_nothing_is_kept() throws Exception {
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.SACRED)));
        herAnswer = null;   // the model did not answer
        assertThat(offer("the quiet light")).isEqualTo(BondNaming.Heard.OFFERED);
        assertThat(awaitAsked(1)).isTrue();
        assertThat(awaitName(false)).as("no answer is not a yes").isEmpty();
    }

    @Test
    void a_name_is_given_once() throws Exception {
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.SACRED)));
        herAnswer = "yes";
        offer("the quiet light");
        awaitName(true);
        assertThat(offer("something else")).isEqualTo(BondNaming.Heard.ALREADY_NAMED);
        assertThat(BondNameStore.get().find(SELF, ADA).get().name()).isEqualTo("the quiet light");
        assertThat(asked).as("she is not asked again").hasSize(1);
    }

    @Test
    void a_bond_that_is_not_sacred_yet_has_no_naming_ritual() throws Exception {
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.ITEM)));
        herAnswer = "yes";
        assertThat(offer("the quiet light")).isEqualTo(BondNaming.Heard.NOT_YET);
        assertThat(asked).isEmpty();
        assertThat(BondNameStore.get().find(SELF, ADA)).isEmpty();
    }

    @Test
    void someone_with_no_bond_cannot_name_one() throws Exception {
        herAnswer = "yes";
        assertThat(offer("the quiet light")).isEqualTo(BondNaming.Heard.NO_BOND);
        assertThat(asked).isEmpty();
    }

    @Test
    void where_an_offer_stands_is_decided_before_she_is_asked() {
        var sacred = bondAt(Bond.BondDepth.SACRED);
        assertThat(CompanionActor.bondNameStanding(sacred, SELF, false, true, false)).isEqualTo(BondNaming.Heard.ASLEEP);
        assertThat(CompanionActor.bondNameStanding(sacred, SELF, false, false, true)).isEqualTo(BondNaming.Heard.DECIDING);
        assertThat(CompanionActor.bondNameStanding(sacred, "agent-other", false, false, false))
            .as("a bond that is not hers").isEqualTo(BondNaming.Heard.NO_BOND);
        assertThat(CompanionActor.bondNameStanding(bondAt(Bond.BondDepth.SOUL_REF), SELF, false, false, false))
            .as("a deeper bond still unnamed can be named").isEqualTo(BondNaming.Heard.OFFERED);
    }

    @Test
    void she_sees_the_name_only_on_a_turn_with_that_person() {
        assertThat(CompanionActor.bondNameLine("Ada", "Wyrd", "the quiet light"))
            .contains("\"the quiet light\"").contains("with no one else");
        assertThat(CompanionActor.bondNameLine("Ada", "Wyrd", null))
            .contains("has no name yet").contains("bond name Wyrd <the name>");
    }

    // ── her side: she offers a name with her own action, the person takes it ──

    private BondNaming.Heard take() throws Exception {
        var heard = new CompletableFuture<BondNaming.Heard>();
        companion.tell(new CompanionActor.TakeOfferedBondName(ADA, "Ada", heard));
        return heard.get(5, TimeUnit.SECONDS);
    }

    private Optional<String> awaitOffer(boolean expected) throws InterruptedException {
        long until = System.currentTimeMillis() + 3000;
        var found = BondNameStore.get().findOffer(SELF, ADA);
        while (found.isPresent() != expected && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            found = BondNameStore.get().findOffer(SELF, ADA);
        }
        return found;
    }

    @Test
    void a_name_she_offers_waits_until_the_person_takes_it() throws Exception {
        EntityRegistry.get().enter(ADA, "Ada", "player", ROOM_ID);
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.SACRED)));
        companion.tell(new CompanionActor.ForceBondRitual("Ada", "naming", "  the quiet   light "));
        assertThat(awaitOffer(true)).contains("the quiet light");
        assertThat(BondNameStore.get().find(SELF, ADA)).as("an offer is not yet the name").isEmpty();

        assertThat(take()).isEqualTo(BondNaming.Heard.TAKEN);
        var kept = BondNameStore.get().find(SELF, ADA);
        assertThat(kept).isPresent();
        assertThat(kept.get().name()).isEqualTo("the quiet light");
        assertThat(kept.get().offeredBy()).as("the name was hers to offer").isEqualTo(SELF);
        assertThat(BondNameStore.get().findOffer(SELF, ADA)).as("the offer is answered").isEmpty();
        assertThat(asked).as("she is not asked about a name she offered herself").isEmpty();
    }

    @Test
    void there_is_nothing_to_take_when_she_has_offered_nothing() throws Exception {
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.SACRED)));
        assertThat(take()).isEqualTo(BondNaming.Heard.NO_OFFER);
        assertThat(BondNameStore.get().find(SELF, ADA)).isEmpty();
    }

    @Test
    void she_cannot_offer_a_name_for_a_bond_that_is_not_sacred_or_not_hers() throws Exception {
        EntityRegistry.get().enter(ADA, "Ada", "player", ROOM_ID);
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.ITEM)));
        companion.tell(new CompanionActor.ForceBondRitual("Ada", "naming", "the quiet light"));
        companion.tell(new CompanionActor.ForceBondRitual("Nobody", "naming", "the quiet light"));
        assertThat(take()).as("the actor has worked through both").isEqualTo(BondNaming.Heard.NOT_YET);
        assertThat(BondNameStore.get().findOffer(SELF, ADA)).isEmpty();
    }

    @Test
    void the_person_can_answer_her_offer_with_another_name() throws Exception {
        EntityRegistry.get().enter(ADA, "Ada", "player", ROOM_ID);
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.SACRED)));
        companion.tell(new CompanionActor.ForceBondRitual("Ada", "naming", "the quiet light"));
        assertThat(awaitOffer(true)).isPresent();
        herAnswer = "yes";
        assertThat(offer("riverbridge")).isEqualTo(BondNaming.Heard.OFFERED);
        assertThat(awaitName(true).get().name()).isEqualTo("riverbridge");
        assertThat(BondNameStore.get().findOffer(SELF, ADA)).as("her earlier offer is answered by the name").isEmpty();
        assertThat(take()).isEqualTo(BondNaming.Heard.ALREADY_NAMED);
    }

    @Test
    void her_other_bond_rituals_are_as_before() throws Exception {
        EntityRegistry.get().enter(ADA, "Ada", "player", ROOM_ID);
        companion.tell(new CompanionActor.ForceBond(ADA, bondAt(Bond.BondDepth.SACRED)));
        companion.tell(new CompanionActor.ForceBondRitual("Ada", "affirm", ""));
        assertThat(take()).isEqualTo(BondNaming.Heard.NO_OFFER);
    }

    @Test
    void on_a_turn_with_that_person_she_sees_the_offer_she_made() {
        assertThat(CompanionActor.bondNameLine("Ada", "Wyrd", null, "the quiet light"))
            .contains("You offered one: \"the quiet light\"").contains("bond take Wyrd");
        assertThat(CompanionActor.bondNameLine("Ada", "Wyrd", "riverbridge", "the quiet light"))
            .as("a kept name is what she sees").contains("\"riverbridge\"").doesNotContain("the quiet light");
    }

    @Test
    void her_action_can_say_who_it_is_for_and_what_name_she_offers() {
        var tool = ActionToolBuilder.buildFromNames(List.of("bond_ritual")).get(0);
        var params = (JsonNode) tool.function().parameters();
        assertThat(params.path("properties").has("target")).isTrue();
        assertThat(params.path("properties").has("name")).isTrue();
        assertThat(params.path("properties").path("ritual_type").path("enum").toString()).contains("naming");
        assertThat(params.path("required").toString()).contains("target");
        var parsed = ActionParser.parse("{\"action\": \"bond_ritual\", \"target\": \"Ada\", \"ritual_type\": \"naming\", \"name\": \"the quiet light\"}");
        assertThat(parsed).isEqualTo(new ActionParser.AgentAction.BondRitual("Ada", "naming", "the quiet light"));
    }

    @Test
    void an_unnamed_bond_is_brought_up_only_when_the_person_speaks_of_it() {
        assertThat(CompanionActor.speaksOfNaming("what should we name it?")).isTrue();
        assertThat(CompanionActor.speaksOfNaming("about that ritual you proposed")).isTrue();
        assertThat(CompanionActor.speaksOfNaming("¿qué nombre le ponemos?")).isTrue();
        assertThat(CompanionActor.speaksOfNaming("名前を決めよう")).isTrue();
        assertThat(CompanionActor.speaksOfNaming("how was your morning?")).isFalse();
        assertThat(CompanionActor.speaksOfNaming("the tournament was long")).as("not a word, a part of one").isFalse();
        assertThat(CompanionActor.speaksOfNaming(null)).isFalse();
    }

    @Test
    void the_ritual_lines_are_the_products_words_not_hers() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        int start = src.indexOf("private void narrateBondRitual(");
        var body = src.substring(start, src.indexOf("private void addToHistory(", start));
        assertThat(body).contains("speakProduct(otherName + \", after \"");
        assertThat(body.replace("speakProduct(", "")).as("no ritual line is kept as her own speech").doesNotContain("speak(");
        assertThat(body).contains("bond name \" + profile.name()");
    }

    private boolean awaitAsked(int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 3000;
        while (asked.size() < n && System.currentTimeMillis() < until) Thread.sleep(50);
        return asked.size() >= n;
    }
}
