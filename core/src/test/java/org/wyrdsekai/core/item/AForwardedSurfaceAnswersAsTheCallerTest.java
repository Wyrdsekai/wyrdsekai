package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whoever holds the item is who the household answers.
 *
 * <h2>The invariant, and why nothing enforced it</h2>
 * {@link VisitorItemProvider} forwards ~36 surfaces to the household's content.
 * That used to be ONE shared provider, built by {@code Main} with the
 * placeholder identity {@code "household"} — and more than twenty of those
 * surfaces read the caller's identity internally: study reach, note ownership,
 * journal encryption, filesystem audit, oracle predictions, and every inference
 * and web cost. So a person holding an item had their books hidden, could not
 * delete their own note, wrote journal entries encrypted under a key they could
 * not read back, and spent from a budget that was nobody's.
 *
 * <p>An existing test enforced that all 36 surfaces WERE forwarded. None
 * enforced that a forward carries the caller. Breadth without identity is what
 * let this sit. This test pins the identity.</p>
 */
class AForwardedSurfaceAnswersAsTheCallerTest {

    private static final String ALICE = "did:key:z6MkAliceHoldsAnItemInHerOwnHouse00000";
    private static final String BOB = "did:key:z6MkBobIsSomeoneElseEntirely0000000000";

    private final ConcurrentLinkedQueue<String> askedAs = new ConcurrentLinkedQueue<>();

    @AfterEach
    void tearDown() {
        HouseholdItemContent.resetForTests();
        HouseholdResources.resetForTests();
    }

    /** Content that simply reports which identity it was built for. */
    private void registerIdentityRecordingContent() {
        HouseholdItemContent.registerFactory(callerDid -> new RecordingContent(
            callerDid == null ? "«nobody»" : callerDid, askedAs));
    }

    @Test
    @DisplayName("the household answers as the person holding the item")
    void theCallerIsCarried() {
        registerIdentityRecordingContent();

        new VisitorItemProvider("home", "home").withCaller(ALICE)
            .searchKnowledge("kovacs", 3);
        assertThat(askedAs).as("Alice's search asked as Alice").containsExactly(ALICE);
    }

    @Test
    @DisplayName("two people are answered as two people, not as one household")
    void twoCallersAreNotOneIdentity() {
        registerIdentityRecordingContent();

        new VisitorItemProvider("home", "home").withCaller(ALICE).notesList(null);
        new VisitorItemProvider("home", "home").withCaller(BOB).notesList(null);

        assertThat(askedAs)
            .as("each person's notes are their own — this pair used to be one placeholder")
            .containsExactly(ALICE, BOB);
    }

    @Test
    @DisplayName("the identity-bearing surfaces all carry the caller")
    void everyIdentityBearingSurfaceCarriesTheCaller() {
        registerIdentityRecordingContent();
        var held = new VisitorItemProvider("home", "home").withCaller(ALICE);

        // One call per surface the audit found reads the caller's identity.
        held.searchKnowledge("q", 1);          // whose shelves may I read
        held.readKnowledgeChunk("id");         // ditto, for one chunk
        held.notesAdd("note", List.of());      // whose note is this
        held.notesList(null);                  // whose notes are these
        held.notesDelete("id");                // may I delete MY note
        held.hostFind("*.txt", 1);             // audited as whom
        held.hostMove("a", "b");               // audited as whom
        held.hostMkdir("d");                   // audited as whom
        held.libraryIngest("/books", "books", "full"); // filed under whom
        held.webSearch("q", null, 1);          // charged to whom
        held.llmSummarize("text", "brief");     // charged to whom

        assertThat(askedAs)
            .as("every one of them asked as Alice")
            .isNotEmpty()
            .allMatch(ALICE::equals);
    }

    @Test
    @DisplayName("an anonymous surface is answered as nobody — never as a stand-in")
    void anonymityIsNotAnIdentity() {
        registerIdentityRecordingContent();

        new VisitorItemProvider("home", "home").searchKnowledge("kovacs", 3);

        assertThat(askedAs)
            .as("no caller must stay no caller; a placeholder here is the whole bug")
            .containsExactly("«nobody»");
    }

    /** Records the identity it was constructed for, on every surface asked. */
    private record RecordingContent(String identity, ConcurrentLinkedQueue<String> log)
            implements ItemWorldApiProvider {

        private void note() { log.add(identity); }

        // The two surfaces the interface leaves abstract; neither is under test.
        @Override
        public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params,
                                                 int depth) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> inventoryList() {
            return List.of();
        }

        @Override public String webFetch(String url, int maxChars) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) {
            return List.of();
        }
        @Override public String llmAnalyze(String text, String prompt) { return ""; }
        @Override public void agentSpeak(String text) { }
        @Override public void agentRemember(String content) { }
        @Override public void agentTell(String target, String message) { }

        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) {
            note(); return List.of();
        }
        @Override public Map<String, Object> readKnowledgeChunk(String id) {
            note(); return Map.of();
        }
        @Override public Map<String, Object> notesAdd(String c, List<String> t) {
            note(); return Map.of();
        }
        @Override public List<Map<String, Object>> notesList(String tag) {
            note(); return List.of();
        }
        @Override public Map<String, Object> notesDelete(String id) { note(); return Map.of(); }
        @Override public Map<String, Object> hostFind(String p, int m) { note(); return Map.of(); }
        @Override public Map<String, Object> hostMove(String f, String t) {
            note(); return Map.of();
        }
        @Override public Map<String, Object> hostMkdir(String p) { note(); return Map.of(); }
        @Override public Map<String, Object> libraryIngest(String p, String c, String m) {
            note(); return Map.of();
        }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) {
            note(); return List.of();
        }
        @Override public String llmSummarize(String text, String instruction) {
            note(); return "";
        }
    }
}
