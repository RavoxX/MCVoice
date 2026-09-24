package dev.mcvoice.client.audio;

import dev.mcvoice.thirdparty.concentus.OpusApplication;
import dev.mcvoice.thirdparty.concentus.OpusDecoder;
import dev.mcvoice.thirdparty.concentus.OpusEncoder;
import dev.mcvoice.thirdparty.concentus.OpusException;
import dev.mcvoice.thirdparty.concentus.OpusSignal;

/** Opus 48 kHz mono, 20 ms frames, via the vendored pure-Java Concentus port (no natives). */
public final class OpusCodec {
    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SAMPLES = 960;
    public static final int MAX_PACKET = 1000;

    private OpusCodec() {
    }

    public static final class Encoder {
        private final OpusEncoder enc;

        public Encoder(int bitrate) {
            try {
                enc = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
                enc.setBitrate(bitrate);
                enc.setComplexity(5);
                enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
                enc.setUseInbandFEC(true);
                enc.setPacketLossPercent(5);
                enc.setUseVBR(true);
            } catch (OpusException e) {
                throw new IllegalStateException("opus encoder init failed", e);
            }
        }

        public void setBitrate(int bitrate) {
            enc.setBitrate(bitrate);
        }

        public int bitrate() {
            return enc.getBitrate();
        }

        /** Encode exactly 960 samples; returns the packet length. */
        public int encode(short[] pcm, byte[] out) {
            try {
                return enc.encode(pcm, 0, FRAME_SAMPLES, out, 0, Math.min(out.length, MAX_PACKET));
            } catch (OpusException e) {
                return 0;
            }
        }

        public void reset() {
            enc.resetState();
        }
    }

    public static final class Decoder {
        private final OpusDecoder dec;

        public Decoder() {
            try {
                dec = new OpusDecoder(SAMPLE_RATE, 1);
            } catch (OpusException e) {
                throw new IllegalStateException("opus decoder init failed", e);
            }
        }

        /** Decode a packet (or conceal a lost one when {@code data == null}); returns samples (960). */
        public int decode(byte[] data, int off, int len, short[] out) {
            try {
                if (data == null || len == 0) {
                    return dec.decode(null, 0, 0, out, 0, FRAME_SAMPLES, false);
                }
                return dec.decode(data, off, len, out, 0, FRAME_SAMPLES, false);
            } catch (OpusException e) {
                java.util.Arrays.fill(out, 0, FRAME_SAMPLES, (short) 0);
                return FRAME_SAMPLES;
            } catch (RuntimeException e) {
                // malformed packets must never break the mixer
                java.util.Arrays.fill(out, 0, FRAME_SAMPLES, (short) 0);
                return FRAME_SAMPLES;
            }
        }

        public void reset() {
            dec.resetState();
        }
    }
}
