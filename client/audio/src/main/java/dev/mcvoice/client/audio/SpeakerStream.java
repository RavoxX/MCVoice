package dev.mcvoice.client.audio;

import java.util.UUID;

import dev.mcvoice.client.util.SequenceUnwrapper;

/** Mixer state of one remote speaker: jitter buffer, decoder, current gains for smooth ramps. */
final class SpeakerStream {
    final UUID speaker;
    final JitterBuffer jitter = new JitterBuffer();
    final OpusCodec.Decoder decoder = new OpusCodec.Decoder();
    final SequenceUnwrapper unwrap = new SequenceUnwrapper();
    final short[] pcm = new short[OpusCodec.FRAME_SAMPLES];
    long senderEpoch = -1;
    double gainL, gainR;
    long lastFrameMs;
    volatile boolean talking;
    volatile double levelDb = -100;

    SpeakerStream(UUID speaker) {
        this.speaker = speaker;
    }

    void flush() {
        jitter.reset();
        decoder.reset();
        unwrap.reset();
        senderEpoch = -1;
    }
}
