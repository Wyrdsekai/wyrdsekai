package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.room.WorkshopProvisioner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * CodePlane became CodeZaiku, and three identifiers that had the old name
 * baked into them are PERSISTED: a Coding Familiar's DID, the filename its
 * registry entry lives under, and a workshop room's id.
 *
 * <p>Nothing migrates any of them. New ones mint the new spelling; anything
 * already written keeps the old one forever, so every read path has to accept
 * both. Each case fails differently and none of them fail loudly:</p>
 *
 * <ul>
 *   <li>the identity constructor REJECTS a DID it does not recognise, so a
 *       pre-rename familiar would be unloadable rather than degraded;</li>
 *   <li>the registry would simply not find the file, and the bondholder would
 *       be handed a SECOND familiar as though they had never summoned one;</li>
 *   <li>a workshop room already in the world would stop being recognised as a
 *       workshop at all.</li>
 * </ul>
 */
class APreRenameFamiliarStillOpensTest {

    private static final String BONDHOLDER = "did:wyrd:user:operator";
    private static final String PARENT = "did:wyrd:agent:parent";
    private static final String LEGACY_DID =
        CodingFamiliarIdentity.LEGACY_DID_PREFIX + BONDHOLDER;

    /** The same familiar, with only its DID written the pre-rename way. */
    private static CodingFamiliarIdentity withLegacyDid(CodingFamiliarIdentity f) {
        return new CodingFamiliarIdentity(
            LEGACY_DID, f.name(), f.kindSubtype(), f.bondholderDid(),
            f.parentAgentDid(), f.createdAt(), f.promotionEligible(),
            f.sharedSubstrateWith(), f.preferredLanguageStacks(),
            f.preferredTaskShapes(), f.codingDNA(), f.soulFragmentIds(),
            f.vitality(), f.autonomyTier(), f.modeLock());
    }

    @Test
    void aLegacyDidIsStillAValidFamiliarIdentity() {
        // The compact constructor is the validation path, so building one
        // directly is the real check -- not a bypass.
        var current = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, "Coder");
        assertThatCode(() -> withLegacyDid(current)).doesNotThrowAnyException();
    }

    @Test
    void theBondholderIsRecoverableFromEitherSpelling() {
        assertThat(CodingFamiliarIdentity.bondholderDidFromFamiliarDid(LEGACY_DID))
            .isEqualTo(BONDHOLDER);
        assertThat(CodingFamiliarIdentity.bondholderDidFromFamiliarDid(
            CodingFamiliarIdentity.DID_PREFIX + BONDHOLDER))
            .isEqualTo(BONDHOLDER);
    }

    @Test
    void aFreshFamiliarMintsTheCurrentSpelling() {
        assertThat(CodingFamiliarIdentity.didFor(BONDHOLDER))
            .startsWith(CodingFamiliarIdentity.DID_PREFIX);
    }

    @Test
    void theRegistryFindsAFileWrittenUnderTheOldPrefix(@TempDir Path soulsDir) throws Exception {
        var registry = new CodingFamiliarRegistry(soulsDir);
        var familiars = soulsDir.resolve(CodingFamiliarRegistry.FAMILIARS_SUBDIR);
        Files.createDirectories(familiars);

        // Write the entry exactly as a pre-rename install left it on disk.
        var legacyName = CodingFamiliarRegistry.LEGACY_FILE_PREFIX
            + BONDHOLDER.replace(':', '_') + ".json";
        Files.writeString(familiars.resolve(legacyName), "{"
            + "\"did\":\"" + LEGACY_DID + "\","
            + "\"name\":\"Coder\","
            + "\"bondholderDid\":\"" + BONDHOLDER + "\","
            + "\"autonomyTier\":\"ASSISTED\","
            + "\"summonedAt\":\"1970-01-01T00:00:00Z\"}");

        assertThat(registry.fileFor(BONDHOLDER).getFileName().toString())
            .as("the old file must be FOUND, not stepped over")
            .isEqualTo(legacyName);
    }

    @Test
    void aWorkshopRoomKeepsItsIdentityAcrossTheRename() {
        var legacyRoom = WorkshopProvisioner.LEGACY_ROOM_ID_PREFIX + "u-1";
        assertThat(WorkshopProvisioner.isWorkshopRoom(legacyRoom)).isTrue();
        assertThat(WorkshopProvisioner.bondholderIdFromWorkshop(legacyRoom)).isEqualTo("u-1");

        var currentRoom = WorkshopProvisioner.workshopRoomId("u-1");
        assertThat(WorkshopProvisioner.isWorkshopRoom(currentRoom)).isTrue();
        assertThat(WorkshopProvisioner.bondholderIdFromWorkshop(currentRoom)).isEqualTo("u-1");
    }
}
