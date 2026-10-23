package dev.mcvoice.client.proximity;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NetworkIdsTest {
    @Test void directoryAddressesNormalizeCaseDefaultPortAndDnsRootDotWithoutResolvingAliases() {
        assertEquals(NetworkIds.of("play.example.org"), NetworkIds.of("PLAY.Example.Org.:25565"));
        assertEquals(NetworkIds.of("play.example.org"), NetworkIds.of("play.example.org."));
        assertNotEquals(NetworkIds.of("play.example.org"), NetworkIds.of("alias.example.org"));
        assertNotEquals(NetworkIds.of("play.example.org"), NetworkIds.of("play.example.org:25566"));
        assertEquals(NetworkIds.of("[::1]"), NetworkIds.of("[::1]:25565"));
    }
}
