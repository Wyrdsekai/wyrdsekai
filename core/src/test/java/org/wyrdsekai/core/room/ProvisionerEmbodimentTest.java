package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.parlor.ParlorManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * audit — every dynamically-provisioned room declares
 * an embodiment_summary at the authoring surface (the provisioner class).
 * Foundation rooms in JSON have their own audit via
 * {@code FoundationRoomLoaderTest.allFoundationRoomsDeclareEmbodimentSummary};
 * this gate covers the Java-coded provisioners.
 */
class ProvisionerEmbodimentTest {

    @Test
    void studyProvisionerDeclaresEmbodimentSummary() {
        assertNotNull(StudyProvisioner.EMBODIMENT_SUMMARY,
            "StudyProvisioner.EMBODIMENT_SUMMARY required");
        assertFalse(StudyProvisioner.EMBODIMENT_SUMMARY.isBlank(),
            "StudyProvisioner.EMBODIMENT_SUMMARY must be non-blank");
    }

    @Test
    void workshopProvisionerDeclaresEmbodimentSummary() {
        assertNotNull(WorkshopProvisioner.EMBODIMENT_SUMMARY,
            "WorkshopProvisioner.EMBODIMENT_SUMMARY required");
        assertFalse(WorkshopProvisioner.EMBODIMENT_SUMMARY.isBlank(),
            "WorkshopProvisioner.EMBODIMENT_SUMMARY must be non-blank");
    }

    @Test
    void homeProvisionerDeclaresEmbodimentSummary() {
        assertNotNull(HomeProvisioner.EMBODIMENT_SUMMARY,
            "HomeProvisioner.EMBODIMENT_SUMMARY required");
        assertFalse(HomeProvisioner.EMBODIMENT_SUMMARY.isBlank(),
            "HomeProvisioner.EMBODIMENT_SUMMARY must be non-blank");
    }

    @Test
    void parlorManagerDeclaresEmbodimentSummary() {
        assertNotNull(ParlorManager.EMBODIMENT_SUMMARY,
            "ParlorManager.EMBODIMENT_SUMMARY required (auto-scaled parlors)");
        assertFalse(ParlorManager.EMBODIMENT_SUMMARY.isBlank(),
            "ParlorManager.EMBODIMENT_SUMMARY must be non-blank");
    }
}
