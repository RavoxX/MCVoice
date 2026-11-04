package dev.mcvoice.client.svc.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.platform.SvcChannelNames;
import dev.mcvoice.client.svc.SvcCompat;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.transport.SvcUdpClient;

/** Exercises the production facade's automatic recovery against the unmodified real server. */
public final class SvcRecoveryProbe {
    private static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    public static void main(String[] args) throws Exception {
        String host=args.length>0?args[0]:"127.0.0.1";
        PrintStream protocol=new PrintStream(System.out,true,"UTF-8"); System.setOut(System.err);
        Set<String> channels=ConcurrentHashMap.newKeySet();
        AtomicReference<SimpleVoiceChatAdapter.Receiver> receiver=new AtomicReference<SimpleVoiceChatAdapter.Receiver>();
        SimpleVoiceChatAdapter adapter=new SimpleVoiceChatAdapter() {
            public boolean supported() { return true; }
            public boolean serverAcceptsChannel(String channel) { return channels.contains(channel); }
            public boolean send(String channel,byte[] payload) { protocol.println("SEND "+channel+" "+SvcProbe.hex(payload)); return true; }
            public void setReceiver(Receiver value) { receiver.set(value); }
        };
        SvcCompat client=new SvcCompat(adapter,()->new InetSocketAddress(host,25565));
        java.util.concurrent.ConcurrentLinkedQueue<String> input=new java.util.concurrent.ConcurrentLinkedQueue<String>();
        Thread reader=new Thread(()->{
            try {
                BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
                String line; while((line=in.readLine())!=null) input.add(line);
            } catch(Exception e) { throw new AssertionError(e); }
        },"recovery-probe-input"); reader.setDaemon(true); reader.start();
        protocol.println("REGISTER "+String.join(",",SvcChannelNames.CLIENTBOUND));
        long deadline=System.currentTimeMillis()+90000,healthySince=0;
        int stage=0; SvcUdpClient before=null; SecretInfo beforeRenewal=null;
        boolean reconnected=false,renewed=false,ok=false;
        try {
            while(System.currentTimeMillis()<deadline) {
                String line;
                while((line=input.poll())!=null) {
                    if(line.equals("JOINED")) client.reset(true);
                    else if(line.startsWith("CHANNELS ")) for(String channel:line.substring(9).split(",")) channels.add(channel);
                    else if(line.startsWith("RECV ")) {
                        String[] parts=line.split(" ",3);
                        if(parts.length==3) receiver.get().onPayload(parts[1],SvcProbe.unhex(parts[2]));
                    }
                }
                client.tick(System.currentTimeMillis());
                if(client.connected()) {
                    SvcUdpClient current=(SvcUdpClient)field(client,"udp");
                    if(stage==0) {
                        before=current;
                        ((DatagramSocket)field(current,"socket")).close(); // local socket failure, no server modification
                        stage=1;
                    } else if(stage==1 && current!=before) {
                        reconnected=true; before=current;
                        beforeRenewal=(SecretInfo)field(client,"secret");
                        client.onRenewalRequired(); stage=2;
                    } else if(stage==2 && current!=before) {
                        SecretInfo after=(SecretInfo)field(client,"secret");
                        renewed=!beforeRenewal.secret.equals(after.secret);
                        healthySince=System.currentTimeMillis(); stage=3;
                    } else if(stage==3 && System.currentTimeMillis()-healthySince>5000) {
                        ok=reconnected && renewed && client.onlinePlayers().containsValue("mcvoice_probe"); break;
                    }
                }
                Thread.sleep(20);
            }
            protocol.println("RESULT {\"ok\":"+ok+",\"automatic_reconnect\":"+reconnected+",\"fresh_key_renewal\":"+renewed+"}");
        } finally { client.reset(false); }
        System.exit(ok?0:1);
    }
}
