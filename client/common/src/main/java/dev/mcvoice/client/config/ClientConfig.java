package dev.mcvoice.client.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import dev.mcvoice.client.json.Json;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;

/**
 * Client configuration (config/mcvoice.json). Never contains secrets: session
 * keys and resume tokens are kept in memory only.
 */
public final class ClientConfig {
    public enum ActivationMode { PUSH_TO_TALK, VOICE_ACTIVATION }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // --- backend
    /** Control endpoint, e.g. wss://voice.example.org/v1/control. Empty = cloud voice disabled. */
    public String backendUrl = "";
    /** Optional override of the UDP voice endpoint announced by the backend ("host:port"). */
    public String voiceEndpointOverride = "";
    /** Allow ws:// to non-loopback hosts (development only). */
    public boolean allowInsecureControl = false;
    /** "mojang" (default) or "offline" (development backends only). */
    public String authMode = "mojang";
    public boolean cloudEnabled = true;
    public boolean svcInteropEnabled = true;

    // --- audio devices
    public String inputDevice = "";
    public String outputDevice = "";

    // --- transmission
    public ActivationMode activationMode = ActivationMode.PUSH_TO_TALK;
    /** Voice activation threshold in dBFS (-60..0). */
    public double voiceActivationThresholdDb = -45;
    public double micGain = 1.0;
    public boolean noiseSuppression = true;
    public boolean automaticGainControl = false;
    public boolean muted = false;
    public boolean deafened = false;

    // --- playback
    public double normalDistance = 48;
    public double whisperDistance = 8;
    public double masterVolume = 1.0;
    public Map<UUID, Double> playerVolume = new HashMap<UUID, Double>();
    public Set<UUID> mutedPlayers = new HashSet<UUID>();

    // --- ui / diagnostics
    public boolean showHud = true;
    public boolean showDebugOverlay = false;
    public boolean debugLogging = false;
    /** Per-frame logging (very verbose). */
    public boolean traceFrames = false;
    /** Exact positions in logs; also requires debugLogging. */
    public boolean logPositions = false;

    private transient File file;

    /** Build-time default backend URL (may be empty). */
    public static String buildDefaultBackendUrl() {
        Properties p = new Properties();
        InputStream in = ClientConfig.class.getResourceAsStream("/mcvoice-defaults.properties");
        if (in != null) {
            try {
                p.load(in);
            } catch (Exception ignored) {
                // defaults stay empty
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        String url = p.getProperty("backend.url", "").trim();
        return url.startsWith("${") ? "" : url;
    }

    public static ClientConfig load(File dir) {
        ClientConfig c = new ClientConfig();
        c.backendUrl = buildDefaultBackendUrl();
        c.file = new File(dir, "mcvoice.json");
        String env = System.getProperty("mcvoice.backendUrl", System.getenv("MCVOICE_BACKEND_URL"));
        if (c.file.isFile()) {
            try {
                Reader r = new InputStreamReader(new FileInputStream(c.file), UTF8);
                StringBuilder b = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    b.append(buf, 0, n);
                }
                r.close();
                c.apply(Json.parseObject(b.toString()));
            } catch (Exception e) {
                VoiceLog.warn(Category.CONTROL, "could not read " + c.file + ", using defaults: " + e.getMessage());
            }
        }
        if (env != null && !env.trim().isEmpty()) {
            c.backendUrl = env.trim();
        }
        return c;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    void apply(Map<String, Object> m) {
        String url = Json.str(m, "backendUrl");
        if (url != null) {
            backendUrl = url.trim();
        }
        String vo = Json.str(m, "voiceEndpointOverride");
        if (vo != null) {
            voiceEndpointOverride = vo.trim();
        }
        allowInsecureControl = Json.bool(m, "allowInsecureControl", allowInsecureControl);
        String am = Json.str(m, "authMode");
        if ("offline".equals(am) || "mojang".equals(am)) {
            authMode = am;
        }
        cloudEnabled = Json.bool(m, "cloudEnabled", cloudEnabled);
        svcInteropEnabled = Json.bool(m, "svcInteropEnabled", svcInteropEnabled);
        String in = Json.str(m, "inputDevice");
        if (in != null) {
            inputDevice = in;
        }
        String out = Json.str(m, "outputDevice");
        if (out != null) {
            outputDevice = out;
        }
        String mode = Json.str(m, "activationMode");
        if ("VOICE_ACTIVATION".equals(mode)) {
            activationMode = ActivationMode.VOICE_ACTIVATION;
        } else if ("PUSH_TO_TALK".equals(mode)) {
            activationMode = ActivationMode.PUSH_TO_TALK;
        }
        voiceActivationThresholdDb = clamp(Json.num(m, "voiceActivationThresholdDb", voiceActivationThresholdDb), -60, 0);
        micGain = clamp(Json.num(m, "micGain", micGain), 0, 4);
        noiseSuppression = Json.bool(m, "noiseSuppression", noiseSuppression);
        automaticGainControl = Json.bool(m, "automaticGainControl", automaticGainControl);
        muted = Json.bool(m, "muted", muted);
        deafened = Json.bool(m, "deafened", deafened);
        normalDistance = clamp(Json.num(m, "normalDistance", normalDistance), 1, 256);
        whisperDistance = clamp(Json.num(m, "whisperDistance", whisperDistance), 1, 64);
        masterVolume = clamp(Json.num(m, "masterVolume", masterVolume), 0, 2);
        Map<String, Object> pv = Json.objAt(m, "playerVolume");
        if (pv != null) {
            for (Map.Entry<String, Object> e : pv.entrySet()) {
                try {
                    if (e.getValue() instanceof Number) {
                        playerVolume.put(UUID.fromString(e.getKey()), clamp(((Number) e.getValue()).doubleValue(), 0, 2));
                    }
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entry
                }
            }
        }
        List<Object> mp = Json.list(m, "mutedPlayers");
        if (mp != null) {
            for (Object o : mp) {
                try {
                    mutedPlayers.add(UUID.fromString(String.valueOf(o)));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entry
                }
            }
        }
        showHud = Json.bool(m, "showHud", showHud);
        showDebugOverlay = Json.bool(m, "showDebugOverlay", showDebugOverlay);
        debugLogging = Json.bool(m, "debugLogging", debugLogging);
        traceFrames = Json.bool(m, "traceFrames", traceFrames);
        logPositions = Json.bool(m, "logPositions", logPositions);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("backendUrl", backendUrl);
        m.put("voiceEndpointOverride", voiceEndpointOverride);
        m.put("allowInsecureControl", allowInsecureControl);
        m.put("authMode", authMode);
        m.put("cloudEnabled", cloudEnabled);
        m.put("svcInteropEnabled", svcInteropEnabled);
        m.put("inputDevice", inputDevice);
        m.put("outputDevice", outputDevice);
        m.put("activationMode", activationMode.name());
        m.put("voiceActivationThresholdDb", voiceActivationThresholdDb);
        m.put("micGain", micGain);
        m.put("noiseSuppression", noiseSuppression);
        m.put("automaticGainControl", automaticGainControl);
        m.put("muted", muted);
        m.put("deafened", deafened);
        m.put("normalDistance", normalDistance);
        m.put("whisperDistance", whisperDistance);
        m.put("masterVolume", masterVolume);
        Map<String, Object> pv = new LinkedHashMap<String, Object>();
        for (Map.Entry<UUID, Double> e : playerVolume.entrySet()) {
            pv.put(e.getKey().toString(), e.getValue());
        }
        m.put("playerVolume", pv);
        List<Object> mp = new ArrayList<Object>();
        for (UUID u : mutedPlayers) {
            mp.add(u.toString());
        }
        m.put("mutedPlayers", mp);
        m.put("showHud", showHud);
        m.put("showDebugOverlay", showDebugOverlay);
        m.put("debugLogging", debugLogging);
        m.put("traceFrames", traceFrames);
        m.put("logPositions", logPositions);
        return m;
    }

    public synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File tmp = new File(file.getPath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            w.write(Json.write(toJson()));
            w.close();
            if (!tmp.renameTo(file)) {
                if (file.delete() && tmp.renameTo(file)) {
                    return;
                }
            }
        } catch (Exception e) {
            VoiceLog.warn(Category.CONTROL, "could not save " + file + ": " + e.getMessage());
        }
    }

    public double volumeOf(UUID player) {
        Double v = playerVolume.get(player);
        return v == null ? 1.0 : v;
    }

    /** For tests. */
    public static ClientConfig fromJson(String json) throws Json.ParseException {
        ClientConfig c = new ClientConfig();
        c.apply(Json.parseObject(json));
        return c;
    }
}
