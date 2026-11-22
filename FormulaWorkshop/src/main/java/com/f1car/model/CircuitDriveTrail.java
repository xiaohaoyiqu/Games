package com.f1car.model;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Bounded planar position samples for one driver's editor review. */
public final class CircuitDriveTrail {
    public static final int MAX_POINTS=128;
    private static final int OFFSET=10_000,BITS=15,COORDINATE_MASK=(1<<BITS)-1,JOINED=1<<(2*BITS);
    private static final int MAX_ENCODED=4*((MAX_POINTS*Integer.BYTES+2)/3);
    private static final double INITIAL_INTERVAL=.25,MAX_OBSERVATION_GAP=.5,MAX_OBSERVED_MOVE=55;

    public record Point(int xDecimetres,int zDecimetres,boolean joined) {
        public Point {
            if(xDecimetres < -OFFSET || xDecimetres > OFFSET || zDecimetres < -OFFSET || zDecimetres > OFFSET)
                throw new IllegalArgumentException("Circuit trail point outside observation bounds");
        }
        public float x() {return xDecimetres/10f;}
        public float z() {return zDecimetres/10f;}
    }

    /** Big-endian words with two 15-bit decimetre coordinates and one continuation bit. */
    public record Path(int car,String packed) {
        public Path {
            if(car<0 || car>3) throw new IllegalArgumentException("Invalid trail driver");
            unpack(packed);
        }
        public List<Point> points() {return unpack(packed);}
    }

    private final List<Point> sampled=new ArrayList<>();
    private CircuitDriveReview.Observation last;
    private double lastTime,lastSampleTime,interval=INITIAL_INTERVAL;
    private boolean interrupted,breakPending,tailSampled;

    void interrupt() {interrupted=true;}

    void observe(double time,CircuitDriveReview.Observation car) {
        if(last==null || interrupted || time-lastTime>MAX_OBSERVATION_GAP || car.recoveries()!=last.recoveries()
                || Math.hypot(car.x()-last.x(),car.z()-last.z())>MAX_OBSERVED_MOVE) breakPending=true;
        interrupted=false;
        last=car;
        lastTime=time;
        tailSampled=sampled.isEmpty() || time-lastSampleTime>=interval;
        if(!tailSampled) return;

        lastSampleTime=time;
        var point=point(car,!breakPending);
        if(samePlaceAsLast(point)) return;
        if(sampled.size()==MAX_POINTS-1) thinSamples();
        sampled.add(point);
        breakPending=false;
    }

    Path snapshot() {
        if(last==null) return null;
        var tail=tailSampled?null:point(last,!breakPending);
        if(tail!=null && samePlaceAsLast(tail)) tail=null;
        var bytes=ByteBuffer.allocate((sampled.size()+(tail==null?0:1))*Integer.BYTES);
        for(var point:sampled) put(bytes,point);
        if(tail!=null) put(bytes,tail);
        return new Path(last.car(),Base64.getEncoder().encodeToString(bytes.array()));
    }

    private boolean samePlaceAsLast(Point point) {
        if(!point.joined() || sampled.isEmpty()) return false;
        var previous=sampled.get(sampled.size()-1);
        return point.xDecimetres()==previous.xDecimetres() && point.zDecimetres()==previous.zDecimetres();
    }

    private void thinSamples() {
        int kept=1,size=sampled.size();
        boolean joined=true;
        for(int i=1;i<size;i++) {
            var point=sampled.get(i);
            joined &= point.joined();
            if(i%2!=0 && i!=size-1) continue;
            sampled.set(kept++,new Point(point.xDecimetres(),point.zDecimetres(),joined));
            joined=true;
        }
        sampled.subList(kept,size).clear();
        interval*=2;
    }

    private static Point point(CircuitDriveReview.Observation car,boolean joined) {
        return new Point(Math.round(car.x()*10),Math.round(car.z()*10),joined);
    }

    private static void put(ByteBuffer bytes,Point point) {
        int coordinates=((point.xDecimetres()+OFFSET)<<BITS)|(point.zDecimetres()+OFFSET);
        bytes.putInt(coordinates|(point.joined()?JOINED:0));
    }

    private static List<Point> unpack(String packed) {
        if(packed==null || packed.isEmpty() || packed.length()>MAX_ENCODED) throw new IllegalArgumentException("Invalid circuit trail length");
        byte[] data=Base64.getDecoder().decode(packed);
        if(data.length==0 || data.length%Integer.BYTES!=0 || data.length/Integer.BYTES>MAX_POINTS
                || !Base64.getEncoder().encodeToString(data).equals(packed)) throw new IllegalArgumentException("Invalid circuit trail data");
        var bytes=ByteBuffer.wrap(data);
        var points=new ArrayList<Point>(data.length/Integer.BYTES);
        while(bytes.hasRemaining()) {
            int word=bytes.getInt();
            boolean joined=(word&JOINED)!=0;
            if(word<0 || points.isEmpty() && joined) throw new IllegalArgumentException("Invalid circuit trail continuity");
            int x=((word>>>BITS)&COORDINATE_MASK)-OFFSET;
            int z=(word&COORDINATE_MASK)-OFFSET;
            points.add(new Point(x,z,joined));
        }
        return List.copyOf(points);
    }
}
