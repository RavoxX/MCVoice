package dev.mcvoice.client.network.ws;

import java.io.*;
import java.net.*;
import java.lang.reflect.Constructor;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketSafetyTest {
    static WebSocketClient client(byte[] input, OutputStream output) throws Exception {
        Constructor<WebSocketClient> ctor=WebSocketClient.class.getDeclaredConstructor(Socket.class,Socket.class,InputStream.class,OutputStream.class);
        ctor.setAccessible(true);Socket socket=new Socket();
        return ctor.newInstance(socket,socket,new ByteArrayInputStream(input),output);
    }

    @Test void stalledPongWriteDoesNotBlockReader() throws Exception {
        CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);
        OutputStream blocked=new OutputStream() {
            public void write(int value) throws IOException { writing.countDown();try { release.await(); }catch(InterruptedException e){throw new IOException(e);} }
        };
        WebSocketClient c=client(new byte[]{(byte)0x89,0,(byte)0x81,2,'o','k'},blocked);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<String> message=worker.submit(()->c.receive());
            assertEquals("ok",message.get(1,TimeUnit.SECONDS));
            assertTrue(writing.await(1,TimeUnit.SECONDS));
        } finally { c.abort();release.countDown();worker.shutdownNow(); }
    }

    @Test void fragmentedOversizedAndMalformedCloseControlsFailBeforePayloadRead() throws Exception {
        for(byte[] input:new byte[][]{{9,0},{(byte)0x89,126,0,126},{(byte)0x88,1,0}}) {
            WebSocketClient c=client(input,new ByteArrayOutputStream());
            try { assertThrows(IOException.class,()->c.receive()); }finally{c.abort();}
        }
    }

    @Test void handshakeReadFailureClosesTheUnderlyingSocket() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0)) {
            Future<Integer> eof=worker.submit(()->{
                try(Socket peer=server.accept()) {
                    peer.setSoTimeout(3000);InputStream in=peer.getInputStream();int matched=0;
                    byte[] end="\r\n\r\n".getBytes("US-ASCII");
                    while(matched<end.length){int b=in.read();if(b<0)throw new EOFException();matched=b==end[matched]?matched+1:0;}
                    OutputStream out=peer.getOutputStream();out.write("HTTP/1.1 101 Switching Protocols\r\n".getBytes("US-ASCII"));
                    byte[] longLine=new byte[8194];java.util.Arrays.fill(longLine,(byte)'x');out.write(longLine);out.flush();
                    return in.read();
                }
            });
            assertThrows(IOException.class,()->WebSocketClient.connect(new URI("ws://127.0.0.1:"+server.getLocalPort()),2000,"test"));
            assertEquals(-1,eof.get(4,TimeUnit.SECONDS));
        }finally{worker.shutdownNow();}
    }
}
