package dev.mcvoice.client.network.ws;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal RFC 6455 WebSocket client for the control channel: text messages,
 * fragmentation, ping/pong, close. Java 8 compatible (no java.net.http).
 *
 * <p>TLS uses the JVM trust store and enables HTTPS hostname verification,
 * which {@link SSLSocket} does not do by default.
 */
public final class WebSocketClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom RNG = new SecureRandom();

    public static final int MAX_MESSAGE = 1 << 20;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed;
    private int closeCode = -1;
    private String closeReason = "";

    private WebSocketClient(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /** Connect and perform the opening handshake. */
    public static WebSocketClient connect(URI uri, int timeoutMs, String userAgent) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls;
        if ("wss".equals(scheme)) {
            tls = true;
        } else if ("ws".equals(scheme)) {
            tls = false;
        } else {
            throw new IOException("unsupported scheme " + scheme);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("missing host");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setTcpNoDelay(true);
        raw.setSoTimeout(timeoutMs);
        Socket s = raw;
        if (tls) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            ssl.startHandshake();
            s = ssl;
        }
        InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
        OutputStream out = s.getOutputStream();

        byte[] keyBytes = new byte[16];
        RNG.nextBytes(keyBytes);
        String key = base64(keyBytes);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        boolean defaultPort = port == (tls ? 443 : 80);
        String req = "GET " + path + " HTTP/1.1\r\n"
            + "Host: " + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + (defaultPort ? "" : ":" + port) + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "User-Agent: " + userAgent + "\r\n\r\n";
        out.write(req.getBytes(UTF8));
        out.flush();

        String status = readLine(in);
        if (!status.startsWith("HTTP/1.1 101")) {
            s.close();
            throw new IOException("WebSocket upgrade refused: " + status);
        }
        String accept = null;
        String upgrade = null;
        while (true) {
            String line = readLine(in);
            if (line.isEmpty()) {
                break;
            }
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String name = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(c + 1).trim();
            if ("sec-websocket-accept".equals(name)) {
                accept = value;
            } else if ("upgrade".equals(name)) {
                upgrade = value;
            }
        }
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade) || !expectedAccept(key).equals(accept)) {
            s.close();
            throw new IOException("invalid WebSocket handshake response");
        }
        s.setSoTimeout(0);
        return new WebSocketClient(s, in, out);
    }

    static String expectedAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return base64(sha1.digest((key + GUID).getBytes(UTF8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(byte[] b) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("connection closed during handshake");
            }
            if (c == '\n') {
                int len = b.length();
                if (len > 0 && b.charAt(len - 1) == '\r') {
                    b.setLength(len - 1);
                }
                return b.toString();
            }
            if (b.length() > 8192) {
                throw new IOException("handshake line too long");
            }
            b.append((char) c);
        }
    }

    /** Set a read timeout (0 = none); {@link #receive} then throws SocketTimeoutException. */
    public void setReadTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    public void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(UTF8));
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        if (closed && opcode != 0x8) {
            throw new IOException("closed");
        }
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        int len = payload.length;
        ByteArrayOutputStream f = new ByteArrayOutputStream(len + 14);
        f.write(0x80 | opcode);
        if (len < 126) {
            f.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            f.write(0x80 | 126);
            f.write(len >>> 8);
            f.write(len);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                f.write((int) ((long) len >>> (8 * i)));
            }
        }
        f.write(mask, 0, 4);
        for (int i = 0; i < len; i++) {
            f.write(payload[i] ^ mask[i & 3]);
        }
        synchronized (writeLock) {
            out.write(f.toByteArray());
            out.flush();
        }
    }

    /**
     * Block until the next text message. Answers pings; returns null when the
     * server closed the connection (see {@link #closeCode()}).
     */
    public String receive() throws IOException {
        ByteArrayOutputStream message = null;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                closed = true;
                return null;
            }
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            if ((b0 & 0x70) != 0) {
                throw new IOException("reserved bits set");
            }
            if ((b1 & 0x80) != 0) {
                throw new IOException("server frames must not be masked");
            }
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte();
                }
            }
            if (len < 0 || len > MAX_MESSAGE) {
                throw new IOException("frame too large");
            }
            byte[] payload = readFully((int) len);
            switch (opcode) {
                case 0x9: // ping
                    sendFrame(0xA, payload);
                    continue;
                case 0xA: // pong
                    continue;
                case 0x8: // close
                    closed = true;
                    if (payload.length >= 2) {
                        closeCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        closeReason = new String(payload, 2, payload.length - 2, UTF8);
                    }
                    try {
                        sendFrame(0x8, payload.length >= 2 ? new byte[] {payload[0], payload[1]} : new byte[0]);
                    } catch (IOException ignored) {
                        // peer may already be gone
                    }
                    return null;
                case 0x1:
                case 0x2:
                    if (message != null) {
                        throw new IOException("unexpected new message inside fragmented message");
                    }
                    if (opcode == 0x2) {
                        throw new IOException("binary frames are not used by the control protocol");
                    }
                    if (fin) {
                        return new String(payload, UTF8);
                    }
                    message = new ByteArrayOutputStream();
                    message.write(payload, 0, payload.length);
                    continue;
                case 0x0:
                    if (message == null) {
                        throw new IOException("continuation without start");
                    }
                    if (message.size() + payload.length > MAX_MESSAGE) {
                        throw new IOException("message too large");
                    }
                    message.write(payload, 0, payload.length);
                    if (fin) {
                        return new String(message.toByteArray(), UTF8);
                    }
                    continue;
                default:
                    throw new IOException("unknown opcode " + opcode);
            }
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private byte[] readFully(int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
        }
        return b;
    }

    public int closeCode() {
        return closeCode;
    }

    public String closeReason() {
        return closeReason;
    }

    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    /** Send a close frame and close the socket. */
    public void close(int code, String reason) {
        if (!socket.isClosed()) {
            try {
                byte[] r = reason.getBytes(UTF8);
                byte[] p = new byte[2 + Math.min(r.length, 120)];
                p[0] = (byte) (code >>> 8);
                p[1] = (byte) code;
                System.arraycopy(r, 0, p, 2, p.length - 2);
                sendFrame(0x8, p);
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
