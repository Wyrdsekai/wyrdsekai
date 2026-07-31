package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BlockListServiceTest {

    private static final String DID_BAD =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

    @BeforeEach
    void setUp() {
        BlockListService.resetForTests();
    }

    @AfterEach
    void tearDown() {
        BlockListService.resetForTests();
    }

    @Test void get_returnsNullBeforeInit() {
        assertNull(BlockListService.get());
    }

    @Test void init_loadsEmptyWhenFileMissing(@TempDir Path dir) {
        BlockListService.init(dir);
        var svc = BlockListService.get();
        assertNotNull(svc);
        assertEquals(0, svc.blockList().size());
    }

    @Test void init_loadsExistingEntries(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("blocks"),
            DID_BAD + "\t" + Instant.now() + "\trevoke\n");
        BlockListService.init(dir);
        assertTrue(BlockListService.get().isBlocked(DID_BAD));
    }

    @Test void isBlocked_nullAndBlankSafe(@TempDir Path dir) {
        BlockListService.init(dir);
        var svc = BlockListService.get();
        assertFalse(svc.isBlocked(null));
        assertFalse(svc.isBlocked(""));
        assertFalse(svc.isBlocked("   "));
    }

    @Test void isBlocked_unknownDidReturnsFalse(@TempDir Path dir) {
        BlockListService.init(dir);
        assertFalse(BlockListService.get().isBlocked(DID_BAD));
    }

    @Test void init_malformedFileDegradesToEmpty(@TempDir Path dir) throws Exception {
        // A malformed blocks file MUST NOT prevent bootstrap — spec has no
        // hard fail on blocklist parse errors. Empty + loud WARN is the
        // pragmatic degrade mode.
        Files.writeString(dir.resolve("blocks"), "this is not a valid entry\n");
        BlockListService.init(dir);
        var svc = BlockListService.get();
        assertNotNull(svc,
            "malformed file must still produce an initialised (empty) service");
        assertEquals(0, svc.blockList().size());
    }

    @Test void init_idempotent(@TempDir Path dir) {
        BlockListService.init(dir);
        var first = BlockListService.get();
        BlockListService.init(dir);
        var second = BlockListService.get();
        assertSame(first, second, "second init should be a no-op");
    }

    @Test void resetForTests_clearsSingleton(@TempDir Path dir) {
        BlockListService.init(dir);
        assertNotNull(BlockListService.get());
        BlockListService.resetForTests();
        assertNull(BlockListService.get());
    }

    @Test void save_persistsChanges(@TempDir Path dir) throws Exception {
        BlockListService.init(dir);
        var svc = BlockListService.get();
        svc.blockList().add(DID_BAD, Instant.now(), false, "bad actor");
        svc.save();

        // Fresh load should see the persisted entry.
        BlockListService.resetForTests();
        BlockListService.init(dir);
        assertTrue(BlockListService.get().isBlocked(DID_BAD));
    }
}
