package dev.mcvoice.client.svc.transport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import dev.mcvoice.client.network.udp.ReplayWindow;
import dev.mcvoice.client.svc.protocol.SvcBuf;
import dev.mcvoice.client.svc.protocol.SvcFormatException;
import dev.mcvoice.client.svc.protocol.SvcIncoming;

/** Session-owned metadata only: never retains audio or evicts accepted replay history. */
final class SvcReplayGuard {
    enum Result { ACCEPT, REPLAY, EXHAUSTED }
    private final int maxStreams, maxControls;
    private final Map<Stream, ReplayWindow> streams = new HashMap<Stream, ReplayWindow>();
    private final Set<UUID> controls = new HashSet<UUID>();

    SvcReplayGuard() { this(4096, 65536); }
    SvcReplayGuard(int maxStreams, int maxControls) { this.maxStreams = maxStreams; this.maxControls = maxControls; }

    Result accept(SvcIncoming in, byte[] authenticatedGcm) throws SvcFormatException {
        if (in.kind == SvcIncoming.Kind.PLAYER_SOUND || in.kind == SvcIncoming.Kind.LOCATION_SOUND) {
            Stream key = new Stream(in.kind, in.channelId, in.sender);
            ReplayWindow window = streams.get(key);
            if (window == null) {
                if (streams.size() >= maxStreams) return Result.EXHAUSTED;
                window = new ReplayWindow(true); streams.put(key, window);
            }
            return window.checkAndUpdate(in.sequence) ? Result.ACCEPT : Result.REPLAY;
        }
        SvcBuf nonce = SvcBuf.reader(authenticatedGcm, 0, 12);
        UUID id = new UUID(nonce.readLong(), Integer.toUnsignedLong(nonce.readInt()));
        if (controls.contains(id)) return Result.REPLAY;
        if (controls.size() >= maxControls) return Result.EXHAUSTED;
        controls.add(id);
        return Result.ACCEPT;
    }

    private static final class Stream {
        final SvcIncoming.Kind kind;
        final UUID channel, sender;
        Stream(SvcIncoming.Kind kind, UUID channel, UUID sender) { this.kind = kind; this.channel = channel; this.sender = sender; }
        @Override public int hashCode() { return kind.hashCode() ^ channel.hashCode() ^ sender.hashCode(); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Stream)) return false;
            Stream stream = (Stream) other;
            return kind == stream.kind && channel.equals(stream.channel) && sender.equals(stream.sender);
        }
    }
}
