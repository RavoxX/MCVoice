package dev.mcvoice.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TransportStatusTest {
    @Test
    void labels() {
        assertEquals("Hybrid: Cloud + SVC", TransportStatus.of(true, false, true, true).label);
        assertEquals("Cloud Voice", TransportStatus.of(true, false, false, true).label);
        assertEquals("Simple Voice Chat Interop", TransportStatus.of(false, false, true, true).label);
        assertEquals("Simple Voice Chat Interop", TransportStatus.of(false, true, true, true).label);
        assertEquals("Reconnecting", TransportStatus.of(false, true, false, true).label);
        assertEquals("Offline", TransportStatus.of(false, false, false, true).label);
        assertEquals(TransportStatus.DISABLED, TransportStatus.of(false, false, false, false));
    }
}
