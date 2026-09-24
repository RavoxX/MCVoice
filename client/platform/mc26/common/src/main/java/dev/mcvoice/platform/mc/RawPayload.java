package dev.mcvoice.platform.mc;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Opaque plugin-channel payload (raw bytes) used for Simple Voice Chat channels. */
public final class RawPayload implements CustomPacketPayload {
    private final Type<RawPayload> type;
    private final byte[] data;

    public RawPayload(Type<RawPayload> type, byte[] data) {
        this.type = type;
        this.data = data;
    }

    public static Type<RawPayload> type(String channel) {
        return new Type<RawPayload>(Identifier.parse(channel));
    }

    /** Codec that carries the remaining bytes of the packet unchanged (bounded by the vanilla payload limit). */
    public static StreamCodec<FriendlyByteBuf, RawPayload> codec(final Type<RawPayload> type) {
        return StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data),
            buf -> {
                byte[] b = new byte[buf.readableBytes()];
                buf.readBytes(b);
                return new RawPayload(type, b);
            });
    }

    public byte[] data() {
        return data;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return type;
    }
}
