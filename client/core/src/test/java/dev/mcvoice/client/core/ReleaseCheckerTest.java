package dev.mcvoice.client.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mcvoice.client.json.Json;

class ReleaseCheckerTest {
    private Map<String, Object> release(String version, String minecraft, String loader) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tag_name", "v" + version + "-mc" + minecraft);
        result.put("draft", false); result.put("prerelease", false);
        result.put("html_url", "https://untrusted.invalid/");
        result.put("assets", Arrays.asList(Json.obj().put("name", "mcvoice-" + version + "-mc" + minecraft + "-" + loader + ".jar")
            .put("state", "uploaded").map()));
        return result;
    }

    @Test void selectsNumericNewerStableVersionForExactGameAndLoaderWithFixedGithubLink() throws Exception {
        List<Object> releases = new ArrayList<>();
        releases.add(release("0.1.9", "26.3", "fabric"));
        releases.add(release("0.1.11", "26.3", "fabric"));
        releases.add(release("0.1.12", "26.2", "fabric"));
        releases.add(release("0.1.13", "26.3", "forge"));
        Map<String, Object> prerelease = release("0.2.0", "26.3", "fabric"); prerelease.put("prerelease", true); releases.add(prerelease);
        Map<String, Object> draft = release("0.3.0", "26.3", "fabric"); draft.put("draft", true); releases.add(draft);
        releases.add(release("0.1.10", "26.3", "fabric"));
        ReleaseChecker.Update update = ReleaseChecker.find("0.1.10", "26.3", "fabric", p -> Json.write(releases));
        assertEquals("0.1.11", update.version);
        assertEquals("https://github.com/RavoxX/MCVoice/releases/tag/v0.1.11-mc26.3", update.url);
        assertNull(ReleaseChecker.find("0.1.11", "26.3", "fabric", p -> Json.write(releases)));
        assertNull(ReleaseChecker.find("dev", "26.3", "fabric", p -> { fail("development builds do not request releases"); return ""; }));
    }

    @Test void searchesBeyondOnePageAndBoundsWorkWithoutClaimingUnavailableRelease() throws Exception {
        List<Object> unrelated = new ArrayList<>();
        for (int i=0; i<100; i++) unrelated.add(release("0.1.20", "1.20.1", "fabric"));
        int[] calls = {0};
        ReleaseChecker.Update update = ReleaseChecker.find("0.1.10", "26.3", "legacyfabric", page -> {
            calls[0]++; return Json.write(page == 1 ? unrelated : Arrays.asList(release("0.1.11", "26.3", "legacyfabric")));
        });
        assertEquals(2, calls[0]); assertEquals("0.1.11", update.version);
        calls[0]=0;
        assertNull(ReleaseChecker.find("0.1.10", "26.3", "forge", p -> { calls[0]++; return Json.write(unrelated); }));
        assertEquals(10, calls[0]);
        assertNull(ReleaseChecker.find("0.1.10", "26.3", "fabric", p -> "{}"));
        assertThrows(Exception.class, () -> ReleaseChecker.find("0.1.10", "26.3", "fabric", p -> { throw new java.io.IOException("offline"); }));
    }
}
