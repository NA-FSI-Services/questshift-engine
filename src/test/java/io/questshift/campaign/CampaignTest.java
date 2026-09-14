package io.questshift.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CampaignTest {

    private final Campaign campaign = new Campaign();

    @BeforeEach
    void rooms() {
        Campaign.Room first = room("room-a", 1);
        Campaign.Room second = room("room-b", 2);
        campaign.rooms.add(first);
        campaign.rooms.add(second);
    }

    @Test
    void firstRoomIsLowestOrder() {
        assertEquals("room-a", campaign.firstRoom().id);
    }

    @Test
    void nextRoomWalksOrder() {
        assertEquals("room-b", campaign.nextRoom("room-a").id);
        assertNull(campaign.nextRoom("room-b"));
    }

    @Test
    void nextRoomFallsBackWhenIdUnknown() {
        assertEquals("room-a", campaign.nextRoom("missing").id);
    }

    @Test
    void roomById() {
        assertEquals("room-b", campaign.roomById("room-b").id);
        assertNull(campaign.roomById("nope"));
    }

    private static Campaign.Room room(String id, int order) {
        Campaign.Room room = new Campaign.Room();
        room.id = id;
        room.order = order;
        return room;
    }
}
