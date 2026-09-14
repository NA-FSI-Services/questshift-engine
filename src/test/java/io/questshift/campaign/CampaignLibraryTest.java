package io.questshift.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CampaignLibraryTest {

    @Inject CampaignLibrary library;

    @Test
    void defaultCampaignIsDevopsDungeon() {
        assertEquals("devops-dungeon", library.defaultCampaign().metadata.id);
        assertFalse(library.all().isEmpty());
        assertEquals("devops-dungeon", library.require("devops-dungeon").metadata.id);
    }

    @Test
    void unknownCampaignIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> library.require("no-such-campaign"));
    }
}
