package dev.mcvoice.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigTest {
    @TempDir
    Path dir;

    @BeforeEach
    void noOverride() {
        String env = System.getProperty("mcvoice.backendUrl", System.getenv("MCVOICE_BACKEND_URL"));
        assumeTrue(env == null || env.trim().isEmpty(), "backend URL overridden by the environment");
    }

    private ClientConfig load(String json) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir.toFile(), "mcvoice.json")), "UTF-8");
        w.write(json);
        w.close();
        return ClientConfig.load(dir.toFile());
    }

    @Test
    void emptySavedBackendUrlKeepsTheBuiltInDefault() throws Exception {
        assertEquals(ClientConfig.buildDefaultBackendUrl(), load("{\"backendUrl\":\"\"}").backendUrl);
        assertEquals(ClientConfig.buildDefaultBackendUrl(), load("{\"backendUrl\":\"  \"}").backendUrl);
        assertEquals(ClientConfig.buildDefaultBackendUrl(), load("{}").backendUrl);
    }

    @Test
    void savedBackendUrlWins() throws Exception {
        assertEquals("wss://voice.example.org/v1/control", load("{\"backendUrl\":\" wss://voice.example.org/v1/control \"}").backendUrl);
    }
}
