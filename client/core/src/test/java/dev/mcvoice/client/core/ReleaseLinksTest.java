package dev.mcvoice.client.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReleaseLinksTest {
    private static final String URL = "https://github.com/RavoxX/MCVoice/releases/tag/v0.1.17-mc26.3";

    @Test void usesDesktopLaunchersWithoutAwtOrShellParsing() {
        assertEquals(Arrays.asList("rundll32.exe", "url.dll,FileProtocolHandler", URL), ReleaseLinks.command("Windows 11", URL));
        assertEquals(Arrays.asList("open", URL), ReleaseLinks.command("Mac OS X", URL));
        assertEquals(Arrays.asList("xdg-open", URL), ReleaseLinks.command("Linux", URL));
    }

    @Test void rejectsOtherOriginsSchemesAndCommandSyntax() {
        for (String value : new String[] {null, "file:///etc/passwd", URL + "&calc", URL + "\n", URL + "/other",
            URL.replace("github.com", "github.com.evil.example"), URL.replace("RavoxX", "other"), URL.replace("https:", "http:")})
            assertThrows(IllegalArgumentException.class, () -> ReleaseLinks.command("Windows 11", value));
    }
}
