package dev.mcvoice.client.svc.protocol;

import java.util.UUID;

/**
 * One Simple Voice Chat wire-protocol generation. Implementations live in
 * {@code protocol/vN}; {@link SvcProtocols} maps server compatibility versions
 * to them. Unknown compatibility versions are never guessed at runtime beyond
 * the registered candidates: the layer fails safe instead.
 */
public interface SvcProtocol {
    String name();

    /** "voicechat:request_secret" payload for the given compatibility version. */
    byte[] requestSecret(int compatibilityVersion);

    SecretInfo parseSecret(byte[] payload) throws SvcFormatException;

    /** "voicechat:update_state" payload. */
    byte[] updateState(boolean disabled);

    // UDP plaintext packets ([id][body]) -------------------------------------

    byte[] authenticate(UUID player, UUID secret);

    byte[] connectionCheck();

    byte[] keepAlive();

    byte[] ping(UUID id, long timestamp);

    int writeMic(SvcBuf out, byte[] opus, int off, int len, long sequence, boolean whispering);

    /** Parse a decrypted server packet into {@code out}; returns false for packets we ignore. */
    boolean parseServerPacket(byte[] plain, int off, int len, SvcIncoming out) throws SvcFormatException;
}
