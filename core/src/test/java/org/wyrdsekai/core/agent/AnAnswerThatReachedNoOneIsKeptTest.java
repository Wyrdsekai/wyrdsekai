package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The household node, 2026-10-05: a visitor asked for a piece of writing as a tell, her bunshin
 * went to work, she walked to another room on her own time, and when the report came back the
 * node logged "had an answer for anonymous and no way reached them". The line was lost: the
 * visitor had no session by then and a visitor has no mail. The same walk made the organic
 * dispatch suite flaky.
 *
 * <p>Three things now hold: on her own time she stays with a person her bunshin works for while
 * they are in the room; a line that reaches no one is kept on disk and said to them when they are
 * next seen (they walk in, they speak to her, or a session of theirs answers again); and her
 * bunshin's report is also said where she stands, since the copy folded back into that room.</p>
 */
class AnAnswerThatReachedNoOneIsKeptTest {

    private static final Path ACTOR = Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    @DisplayName("a kept line survives a restart and is taken out when the person is seen")
    void keptAcrossRestart(@TempDir Path data) {
        var file = OwedLines.pathFor(data.toString(), "companion-mia");
        var owed = OwedLines.load(file);
        assertThat(owed.isEmpty()).isTrue();
        owed.add("anon-1a2b3c4d", "anonymous", "My bunshin came back with what she went for. Loyalty between friends is…");
        owed.add("did:key:z6Mk-person", "operator", "The study is open again.");
        assertThat(Files.exists(file)).isTrue();

        var again = OwedLines.load(file);
        assertThat(again.size()).isEqualTo(2);
        assertThat(again.owes("anon-1a2b3c4d")).isTrue();
        assertThat(again.owes("someone-else")).isFalse();
        var taken = again.takeFor("anon-1a2b3c4d");
        assertThat(taken).hasSize(1);
        assertThat(taken.get(0).text()).startsWith("My bunshin came back");
        assertThat(again.size()).isEqualTo(1);
        assertThat(OwedLines.load(file).size()).as("the take is saved").isEqualTo(1);
    }

    @Test
    @DisplayName("the ledger is bounded and lets a week-old line go")
    void boundedAndExpiring() {
        var owed = OwedLines.inMemory();
        for (int i = 0; i < OwedLines.MAX + 5; i++) owed.add("p", "someone", "line " + i);
        assertThat(owed.size()).isEqualTo(OwedLines.MAX);
        assertThat(owed.all().get(0).text()).as("the oldest went first").isEqualTo("line 5");
        assertThat(owed.expire(Instant.now())).isZero();
        assertThat(owed.expire(Instant.now().plus(OwedLines.KEEP).plus(Duration.ofMinutes(1)))).isEqualTo(OwedLines.MAX);
        assertThat(owed.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an unreadable file is an empty ledger, not a crash")
    void unreadableIsEmpty(@TempDir Path data) throws Exception {
        var file = data.resolve("owed.json");
        Files.writeString(file, "{not json");
        assertThat(OwedLines.load(file).isEmpty()).isTrue();
        assertThat(OwedLines.pathFor(null, "x")).isNull();
        assertThat(OwedLines.pathFor(data.toString(), "companion-mia").getFileName().toString()).isEqualTo("owed-companion-mia.json");
    }

    @Test
    @DisplayName("a line kept more than a couple of minutes is framed as from before")
    void framedAsFromBefore() {
        var fresh = new OwedLines.Owed("p", "someone", "Here it is.", Instant.now());
        var old = new OwedLines.Owed("p", "someone", "Here it is.", Instant.now().minus(Duration.ofMinutes(10)));
        assertThat(CompanionActor.owedPreface(fresh)).isEmpty();
        assertThat(CompanionActor.owedPreface(old)).isEqualTo("From earlier, while you were away: ");
    }

    @Test
    @DisplayName("the actor keeps what reached no one, says a report where she is, and delivers when they are seen")
    void theActorIsWired() throws Exception {
        var src = Files.readString(ACTOR);
        assertThat(src).contains("keepOwed(answering, adapted);");
        assertThat(src).contains("if (ActivityLogger.AUTHORED_REPORT.equals(authoredBy)) {\n"
            + "                    roomRef.tell(new RoomCommand.SayInRoom(");
        assertThat(src).as("a private line that reached no one goes to mail, then is kept")
            .contains("&& !sendReplyByMail(privatelyTo.entityId(), privatelyTo.entityName(), adapted)) {\n"
                + "            keepOwed(privatelyTo, adapted);");
        assertThat(src).doesNotContain("had an answer for {} and no way reached them");
        assertThat(src).contains("case WorldEvent.EntityEntered entered -> {\n                deliverOwedTo(entered.entityId());");
        assertThat(src).as("their next line is answered after what she owed them")
            .contains("this.locale = said.locale();\n                }\n"
                + "                // What she owed them from earlier comes first, before their new line is answered.\n"
                + "                deliverOwedTo(said.entityId());");
        assertThat(src).contains(".onMessage(OwedLinesRetry.class, this::onOwedLinesRetry)");
        assertThat(src).contains("if (owedFile != null) this.owedLines = OwedLines.load(owedFile);");
    }

    @Test
    @DisplayName("on her own time she stays with a person her bunshin works for while they are in the room")
    void sheStays() throws Exception {
        var src = Files.readString(ACTOR);
        assertThat(src).contains("if (bunshinFor != null) bunshinWorkingFor.put(slotId, bunshinFor);");
        assertThat(src).contains("if (slotId != null) bunshinWorkingFor.remove(slotId);");
        assertThat(src).contains("var waiting = personWaitingOnHerBunshinHere();\n"
            + "        if (!reactiveInference && waiting != null) {");
        assertThat(src).as("a person asking her to come somewhere still moves her").contains("!reactiveInference && waiting != null");
    }
}
