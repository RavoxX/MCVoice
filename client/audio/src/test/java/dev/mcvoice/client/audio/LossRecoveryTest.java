package dev.mcvoice.client.audio;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.mcvoice.thirdparty.concentus.*;

class LossRecoveryTest {
    static byte[][] packets(int frames) throws Exception {
        OpusEncoder encoder = new OpusEncoder(48000,1,OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(20000); encoder.setComplexity(5);
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        encoder.setForceMode(OpusMode.MODE_SILK_ONLY);
        encoder.setMaxBandwidth(OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND);
        encoder.setUseInbandFEC(true); encoder.setPacketLossPercent(30);
        byte[][] packets = new byte[frames][];
        short[] pcm = new short[960]; byte[] packet = new byte[1000];
        for (int f=0;f<frames;f++) {
            for(int i=0;i<960;i++) {
                double t=(f*960.0+i)/48000;
                pcm[i]=(short)(8000*Math.sin(2*Math.PI*180*t)+4000*Math.sin(2*Math.PI*(380+f*11)*t));
            }
            int length=encoder.encode(pcm,0,960,packet,0,packet.length);
            packets[f]=Arrays.copyOf(packet,length);
        }
        return packets;
    }

    @Test void fecUsesTheNextPacketAndLeavesItsNormalDecodeInOrder() throws Exception {
        byte[][] packets=packets(40);
        OpusCodec.Decoder fec=new OpusCodec.Decoder(), plc=new OpusCodec.Decoder();
        OpusDecoder reference=new OpusDecoder(48000,1);
        short[] actual=new short[960], expected=new short[960], concealed=new short[960];
        for(int i=0;i<20;i++) {
            fec.decode(packets[i],0,packets[i].length,actual);
            plc.decode(packets[i],0,packets[i].length,concealed);
            reference.decode(packets[i],0,packets[i].length,expected,0,960,false);
        }
        assertEquals(960,fec.decodeFec(packets[21],0,packets[21].length,actual));
        reference.decode(packets[21],0,packets[21].length,expected,0,960,true);
        assertArrayEquals(expected,actual);
        plc.decode(null,0,0,concealed);
        assertFalse(Arrays.equals(concealed,actual),"fixture must contain actual redundancy, not merely PLC");
        fec.decode(packets[21],0,packets[21].length,actual);
        reference.decode(packets[21],0,packets[21].length,expected,0,960,false);
        assertArrayEquals(expected,actual,"lookahead must not consume or decode the future frame twice");
    }

    @Test void fecLookaheadIsValidatedBeforeAnyDecodeOrSpeakingActivity() throws Exception {
        byte[][] packets=packets(5); SpatialMixer mixer=new SpatialMixer();
        for(int i:new int[]{0,2,3}) mixer.enqueue(AudioPipelineTest.SPEAKER,1,i,0,4,packets[i],0,packets[i].length,i*20);
        mixer.mixFrame(AudioPipelineTest.snapWith(true,5),AudioPipelineTest.VALIDATOR,AudioPipelineTest.SETTINGS,0);
        short[] blocked=mixer.mixFrame(AudioPipelineTest.snapWith(false,5),AudioPipelineTest.VALIDATOR,AudioPipelineTest.SETTINGS,20);
        assertEquals(0,mixer.fecAttempts()); assertFalse(mixer.isTalking(AudioPipelineTest.SPEAKER));
        for(short value:blocked) assertEquals(0,value);
        SpatialMixer allowed=new SpatialMixer();
        for(int i:new int[]{0,2,3}) allowed.enqueue(AudioPipelineTest.SPEAKER,1,i,0,4,packets[i],0,packets[i].length,i*20);
        allowed.mixFrame(AudioPipelineTest.snapWith(true,5),AudioPipelineTest.VALIDATOR,AudioPipelineTest.SETTINGS,0);
        allowed.mixFrame(AudioPipelineTest.snapWith(true,5),AudioPipelineTest.VALIDATOR,AudioPipelineTest.SETTINGS,20);
        assertEquals(1,allowed.fecAttempts()); assertEquals(1,allowed.decodedFrames());
        allowed.mixFrame(AudioPipelineTest.snapWith(true,5),AudioPipelineTest.VALIDATOR,AudioPipelineTest.SETTINGS,40);
        assertEquals(2,allowed.decodedFrames());
    }
}
