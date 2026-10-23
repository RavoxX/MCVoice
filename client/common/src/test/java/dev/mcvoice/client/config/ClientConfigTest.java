package dev.mcvoice.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @Test void directoryFilterAndUpdatePreferenceDefaultAndPersist() throws Exception {
        ClientConfig c = load("{}");
        assertEquals(false, c.groupServerOnly); assertEquals(true, c.checkForUpdates);
        c.groupServerOnly=true; c.checkForUpdates=false; c.save();
        ClientConfig reloaded=ClientConfig.load(dir.toFile());
        assertEquals(true,reloaded.groupServerOnly); assertEquals(false,reloaded.checkForUpdates);
    }
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

    @Test void legacyGroupModeCannotOverrideTheSharedMicrophoneMode() throws Exception {
        assertEquals(ClientConfig.ActivationMode.PUSH_TO_TALK, load("{}").activationMode);
        ClientConfig c = load("{\"activationMode\":\"VOICE_ACTIVATION\",\"groupActivationMode\":\"PUSH_TO_TALK\"}");
        assertEquals(ClientConfig.ActivationMode.VOICE_ACTIVATION, c.activationMode);
        c.save();
        assertEquals(ClientConfig.ActivationMode.VOICE_ACTIVATION, ClientConfig.load(dir.toFile()).activationMode);
        assertFalse(c.toJson().containsKey("groupActivationMode"));
        assertEquals(ClientConfig.ActivationMode.PUSH_TO_TALK, load("{\"groupActivationMode\":\"VOICE_ACTIVATION\"}").activationMode);
    }

    @Test void concurrentPlayerControlsAndSerializationRemainSafe() throws Exception {
        ClientConfig c=new ClientConfig();java.util.UUID id=java.util.UUID.randomUUID();
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> write=worker.submit(()->{for(int n=0;n<5000;n++){c.mutedPlayers.add(id);c.playerVolume.put(id,0.5);c.mutedPlayers.remove(id);}});
            for(int n=0;n<500;n++){dev.mcvoice.client.json.Json.write(c.toJson());c.volumeOf(id);c.mutedPlayers.contains(id);}
            write.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0.5,c.volumeOf(id));
        }finally{worker.shutdownNow();}
    }
}
