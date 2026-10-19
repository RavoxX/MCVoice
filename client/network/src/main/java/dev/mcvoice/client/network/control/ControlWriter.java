package dev.mcvoice.client.network.control;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

/** One connection's bounded FIFO. A lost delta requires reconnect, never silent dropping/replay. */
final class ControlWriter {
    interface Output {
        void write(String message) throws IOException;
        /** Must abort the underlying socket without writing or taking the writer's lock. */
        void abort();
    }
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<String>(64);
    private final Output output;
    private final Thread thread;
    private volatile boolean closed;

    ControlWriter(Output output) {
        this.output = output;
        thread = new Thread(() -> run(), "MCVoice-ControlWriter");
        thread.setDaemon(true);
        thread.start();
    }

    boolean offer(String message) {
        if (closed) return false;
        if (!queue.offer(message)) {
            close();
            return false;
        }
        if (closed) { queue.clear(); return false; }
        return true;
    }

    void close() {
        closed = true;
        queue.clear();
        thread.interrupt();
        output.abort();
    }

    private void run() {
        try {
            while (!closed) {
                String message = queue.take();
                if (!closed) output.write(message);
            }
        } catch (IOException e) {
            // Abort wakes the reader; its normal reconnect path reports the failure.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }
}
