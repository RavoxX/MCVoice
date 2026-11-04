package dev.mcvoice.client.audio;

/** Deterministic synthetic fixture; emits metrics only, never PCM or voice packets. */
public final class LossRecoveryBenchmark {
    private static volatile long checksum;
    public static void main(String[] args) throws Exception {
        byte[][] packets=LossRecoveryTest.packets(300);
        short[][] reference=new short[packets.length][960];
        OpusCodec.Decoder full=new OpusCodec.Decoder();
        for(int i=0;i<packets.length;i++) full.decode(packets[i],0,packets[i].length,reference[i]);
        for(int i=0;i<3;i++) { run(packets,reference,false); run(packets,reference,true); }
        for(boolean fec:new boolean[]{false,true}) {
            long start=System.nanoTime(),error=0;
            for(int iteration=0;iteration<20;iteration++) error+=run(packets,reference,fec);
            double ms=(System.nanoTime()-start)/1e6;
            System.out.println("{\"fec\":"+fec+",\"frames\":6000,\"lost_frames\":560,\"missing_frame_mse\":"+(error/(560.0*960))+",\"decode_ms\":"+ms+"}");
        }
    }
    private static long run(byte[][] packets,short[][] reference,boolean fec) {
        OpusCodec.Decoder decoder=new OpusCodec.Decoder(); short[] out=new short[960]; long error=0;
        for(int frame=0;frame<packets.length;frame++) {
            if(frame>=20 && frame%10==0) {
                if(fec) decoder.decodeFec(packets[frame+1],0,packets[frame+1].length,out);
                else decoder.decode(null,0,0,out);
                for(int sample=0;sample<960;sample++) {
                    long difference=out[sample]-reference[frame][sample]; error+=difference*difference;
                }
            } else decoder.decode(packets[frame],0,packets[frame].length,out);
            checksum+=out[0];
        }
        return error;
    }
}
