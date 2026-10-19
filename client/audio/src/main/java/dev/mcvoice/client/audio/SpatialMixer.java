package dev.mcvoice.client.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.mcvoice.client.proximity.PlaybackDecision;
import dev.mcvoice.client.proximity.PlaybackValidator;
import dev.mcvoice.client.proximity.TrackedPlayer;
import dev.mcvoice.client.proximity.WorldSnapshot;

/**
 * Mixes all remote speakers into one stereo 20 ms frame.
 *
 * <p>Every frame is re-validated against the newest {@link WorldSnapshot}
 * through the {@link Validator} (the mandatory local entity check); positions
 * for panning/attenuation come from the same snapshot, never from the network.
 * Accepted gain changes are ramped across the frame; a rejected playback
 * check silences the speaker immediately, before decoding or mixing.
 */
public final class SpatialMixer {
    public interface Validator {
        PlaybackDecision check(WorldSnapshot snapshot, UUID speaker, int mode);
    }

    public interface Settings {
        double masterVolume();

        double volumeOf(UUID speaker);

        double range(int mode);
    }

    /** Extra positional/non-player sources mixed in (speaker test tone, mic monitor). */
    public interface ExtraSource {
        /** Add 960 stereo frames (interleaved) into {@code mix}; return false when finished. */
        boolean mixInto(int[] mix);
    }

    // Group and positional channels have independent jitter/decoder state.
    private final SpatialMixer groups;

    public SpatialMixer() { this(true); }
    private SpatialMixer(boolean withGroups) { groups = withGroups ? new SpatialMixer(false) : null; }

    private boolean groupActive(UUID speaker, long nowMs) {
        if (groups == null) return false;
        SpeakerStream g = groups.streams.get(speaker);
        return g != null && nowMs - g.lastEnqueueMs <= 300;
    }

    private final Map<UUID, SpeakerStream> streams = new ConcurrentHashMap<UUID, SpeakerStream>();
    private final Map<UUID, SpeakerStream> active = new ConcurrentHashMap<UUID, SpeakerStream>();
    private long lastReapMs;
    private final List<ExtraSource> extras = new ArrayList<ExtraSource>();
    private final int[] mix = new int[OpusCodec.FRAME_SAMPLES * 2];
    private final short[] out = new short[OpusCodec.FRAME_SAMPLES * 2];
    private final double[] g = new double[2];
    private volatile long decodedFrames, concealedFrames;

    /** Queue an Opus frame for a speaker (network threads). */
    public void enqueue(UUID speaker, long senderEpoch, int seq16, int mode, int flags, byte[] data, int off, int len, long nowMs) {
        if (groups != null && mode == PlaybackValidator.MODE_GROUP) {
            flush(speaker);
            groups.enqueue(speaker, senderEpoch, seq16, mode, flags, data, off, len, nowMs);
            return;
        }
        if (groupActive(speaker, nowMs)) return;
        SpeakerStream s = streams.get(speaker);
        if (s == null) {
            s = new SpeakerStream(speaker);
            SpeakerStream prev = streams.putIfAbsent(speaker, s);
            if (prev != null) {
                s = prev;
            }
        }
        synchronized (s) {
            if (streams.get(speaker) != s) return; // clear/reaping won the race; next frame creates current state
            if (s.senderEpoch != senderEpoch) {
                // the speaker changed world session: its sequence numbers restart
                s.jitter.reset();
                s.unwrap.reset();
                s.senderEpoch = senderEpoch;
            }
            long ext = s.unwrap.unwrap(seq16);
            s.lastEnqueueMs = nowMs;
            s.jitter.insert(ext, data, off, len, mode, flags, nowMs);
            if (!active.containsKey(speaker)) active.put(speaker, s);
        }
    }

    /** Queue a frame with an already extended 64-bit sequence (Simple Voice Chat uses longs). */
    public void enqueueExtended(UUID speaker, long seq, int mode, int flags, byte[] data, int off, int len, long nowMs) {
        if (groupActive(speaker, nowMs)) return;
        SpeakerStream s = streams.get(speaker);
        if (s == null) {
            s = new SpeakerStream(speaker);
            SpeakerStream prev = streams.putIfAbsent(speaker, s);
            if (prev != null) {
                s = prev;
            }
        }
        synchronized (s) {
            if (streams.get(speaker) != s) return;
            s.lastEnqueueMs = nowMs;
            s.jitter.insert(seq, data, off, len, mode, flags, nowMs);
            if (!active.containsKey(speaker)) active.put(speaker, s);
        }
    }

    /** Transport switch: drop buffered audio and decoder state, start silent (fade in). */
    public void flush(UUID speaker) {
        SpeakerStream s = streams.get(speaker);
        if (s != null) {
            synchronized (s) {
                s.flush();
                s.gainL = 0;
                s.gainR = 0;
                active.remove(speaker, s);
            }
        }
    }

    public void clearGroups() { if (groups != null) groups.clear(); }

    public void clear() {
        if (groups != null) groups.clear();
        active.clear();
        streams.clear();
    }

    public synchronized void addExtra(ExtraSource src) {
        extras.add(src);
    }

    /** Produce the next frame (960 interleaved stereo samples). Called by the playback thread. */
    public short[] mixFrame(WorldSnapshot snap, Validator validator, Settings settings, long nowMs) {
        java.util.Arrays.fill(mix, 0);
        double master = settings.masterVolume();
        Iterator<SpeakerStream> it = active.values().iterator();
        while (it.hasNext()) {
            SpeakerStream s = it.next();
            synchronized (s) {
                if (streams.get(s.speaker) != s) {
                    active.remove(s.speaker, s);
                    continue;
                }
                JitterBuffer.Result r = s.jitter.poll();
                if (r == JitterBuffer.Result.EMPTY) {
                    s.talking = false;
                    if (!s.jitter.active()) active.remove(s.speaker, s);
                    s.gainL = 0;
                    s.gainR = 0;
                    continue;
                }
                int mode = s.jitter.outMode; // concealment must keep the previous frame's channel
                PlaybackDecision d = validator.check(snap, s.speaker, mode);
                s.talking = d.accepted();
                s.lastFrameMs = nowMs;
                if (!d.accepted()) {
                    // Drain rejected traffic without decoding or mixing stale PCM into a fade.
                    if (!s.rejected) s.decoder.reset();
                    s.rejected = true;
                    s.gainL = s.gainR = 0;
                    s.levelDb = -100;
                    continue;
                }
                if (r == JitterBuffer.Result.FRAME && s.jitter.outLen == 0 && (s.jitter.outFlags & 1) != 0) {
                    s.talking = false;
                    s.decoder.reset();
                    s.gainL = s.gainR = 0;
                    s.levelDb = -100;
                    continue;
                }
                s.rejected = false;
                if (r == JitterBuffer.Result.FRAME) {
                    s.decoder.decode(s.jitter.outData, 0, s.jitter.outLen, s.pcm);
                    decodedFrames++;
                } else {
                    s.decoder.decode(null, 0, 0, s.pcm);
                    concealedFrames++;
                }
                double tl = 0, tr = 0;
                if (mode == PlaybackValidator.MODE_GROUP) {
                    // voice group: not positional, centred at the player's volume (spec 9.1)
                    Spatializer.gains(0, master * settings.volumeOf(s.speaker), 1, g);
                    tl = g[0];
                    tr = g[1];
                } else {
                    TrackedPlayer p = snap.player(s.speaker);
                    double dist = snap.distanceTo(p);
                    double att = Spatializer.attenuation(dist, settings.range(mode));
                    double pan = Spatializer.pan(snap.x, snap.z, snap.yaw, p.x, p.z);
                    double facing = Spatializer.facing(snap.x, snap.z, snap.yaw, p.x, p.z);
                    Spatializer.gains(pan, att * master * settings.volumeOf(s.speaker), facing, g);
                    tl = g[0];
                    tr = g[1];
                }
                double sum = 0;
                double l0 = s.gainL, r0 = s.gainR;
                int n = OpusCodec.FRAME_SAMPLES;
                if (l0 == tl && r0 == tr) {
                    for (int i = 0; i < n; i++) {
                        int v = s.pcm[i];
                        sum += (double) v * v;
                        mix[2 * i] += (int) (v * tl);
                        mix[2 * i + 1] += (int) (v * tr);
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        double f = (double) i / n;
                        double gl = l0 + (tl - l0) * f;
                        double gr = r0 + (tr - r0) * f;
                        int v = s.pcm[i];
                        sum += (double) v * v;
                        mix[2 * i] += (int) (v * gl);
                        mix[2 * i + 1] += (int) (v * gr);
                    }
                }
                s.levelDb = sum < 1 ? -100 : 20 * Math.log10(Math.sqrt(sum / n) / 32768.0);
                s.gainL = tl;
                s.gainR = tr;
            }
        }
        if (nowMs - lastReapMs >= 1000) {
            lastReapMs = nowMs;
            for (SpeakerStream s : streams.values()) {
                synchronized (s) {
                    if (!s.jitter.active() && nowMs - s.lastFrameMs > 60000) streams.remove(s.speaker, s);
                }
            }
        }
        synchronized (this) {
            Iterator<ExtraSource> e = extras.iterator();
            while (e.hasNext()) {
                if (!e.next().mixInto(mix)) {
                    e.remove();
                }
            }
        }
        short[] groupAudio = groups == null || (groups.active.isEmpty() && nowMs - groups.lastReapMs < 1000)
            ? null : groups.mixFrame(snap, validator, settings, nowMs);
        for (int i = 0; i < mix.length; i++) {
            int v = mix[i] + (groupAudio == null ? 0 : groupAudio[i]);
            out[i] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
        }
        return out;
    }

    /** Allocation-free lookup for a rendered player's speaking indicator. */
    public boolean isTalking(UUID speaker) {
        SpeakerStream s = streams.get(speaker);
        return (s != null && s.talking) || (groups != null && groups.isTalking(speaker));
    }

    /** Speakers currently producing audio (for the HUD). */
    public Collection<UUID> talking() {
        List<UUID> l = new ArrayList<UUID>();
        for (SpeakerStream s : streams.values()) {
            if (s.talking) {
                l.add(s.speaker);
            }
        }
        if (groups != null) for (UUID u : groups.talking()) if (!l.contains(u)) l.add(u);
        return l;
    }

    public double averageJitterMs() {
        double sum = 0;
        int n = 0;
        for (SpeakerStream s : streams.values()) {
            if (s.talking) {
                sum += s.jitter.jitterMs();
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    public double averageLoss() {
        double sum = 0;
        int n = 0;
        for (SpeakerStream s : streams.values()) {
            if (s.jitter.receivedFrames() > 0) {
                sum += s.jitter.lossRatio();
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    public long decodedFrames() {
        return decodedFrames + (groups == null ? 0 : groups.decodedFrames());
    }

    public long concealedFrames() {
        return concealedFrames + (groups == null ? 0 : groups.concealedFrames());
    }

    public int streamCount() {
        return streams.size() + (groups == null ? 0 : groups.streamCount());
    }
}
