package com.f1car.model;

import java.util.*;

/** Passive fields from Bullet's completed wheel update. No derived tyre force or slip ratio. */
public record NativeWheelObservation(long frame,float stepSeconds,List<Wheel> wheels) {
    public static final int WHEELS=4,STRIDE=12,VALUES=WHEELS*STRIDE;
    public record Point(float x,float y,float z) {
        public Point {if(!bounded(x,1_000_000) || !bounded(y,1_000_000) || !bounded(z,1_000_000)) throw new IllegalArgumentException("Invalid native contact point");}
    }
    public record Normal(float x,float y,float z) {
        public Normal {float squared=x*x+y*y+z*z;if(!Float.isFinite(squared) || Math.abs(squared-1)>.02f) throw new IllegalArgumentException("Invalid native contact normal");}
    }
    /**
     * suspensionNewtons is m_wheelsSuspensionForce, before the per-wheel force cap.
     * cappedSuspensionNewtons follows the cap used for the suspension impulse; neither
     * field is the tyre's longitudinal/lateral force. skidFraction is m_skidInfo,
     * Bullet's friction scaling, not a physical slip ratio or a percentage of grip.
     */
    public record Contact(Point point,Normal normal,float suspensionNewtons,float maximumNewtons,float skidFraction) {
        public Contact {Objects.requireNonNull(point);Objects.requireNonNull(normal);if(!positiveBounded(suspensionNewtons,100_000_000) || !positiveBounded(maximumNewtons,100_000_000) || !positiveBounded(skidFraction,1)) throw new IllegalArgumentException("Invalid native wheel contact fields");}
        public float cappedSuspensionNewtons() {return Math.min(suspensionNewtons,maximumNewtons);}
        public boolean forceLimited() {return suspensionNewtons>maximumNewtons;}
    }
    /** Wheel order is front-left, front-right, rear-left, rear-right, as in FormulaCar. */
    public record Wheel(int index,float suspensionLength,float restLength,Contact contact) {
        public Wheel {if(index<0 || index>=WHEELS || !bounded(suspensionLength,10) || !Float.isFinite(restLength) || restLength<=0 || restLength>10) throw new IllegalArgumentException("Invalid native suspension geometry");}
        public boolean supported() {return contact!=null;}
        public float compression() {return restLength-suspensionLength;}
    }
    public NativeWheelObservation {
        if(frame<=0 || !Float.isFinite(stepSeconds) || stepSeconds<=0 || stepSeconds>.25f || wheels==null || wheels.size()!=WHEELS) throw new IllegalArgumentException("Invalid native wheel frame");
        wheels=List.copyOf(wheels);for(int i=0;i<WHEELS;i++) if(wheels.get(i).index()!=i) throw new IllegalArgumentException("Native wheel order differs");
    }
    /** Internal JNI layout 1. Unsupported wheels deliberately have no contact metrics. */
    public static NativeWheelObservation decode(long frame,float stepSeconds,float[] data) {
        if(data==null || data.length!=VALUES) throw new IllegalArgumentException("Invalid native wheel frame length");
        var wheels=new ArrayList<Wheel>(WHEELS);for(int i=0;i<WHEELS;i++) {
            int at=i*STRIDE;if(data[at]!=0 && data[at]!=1) throw new IllegalArgumentException("Invalid native support flag");
            Contact contact=data[at]==0?null:new Contact(new Point(data[at+6],data[at+7],data[at+8]),new Normal(data[at+9],data[at+10],data[at+11]),data[at+3],data[at+4],data[at+5]);
            wheels.add(new Wheel(i,data[at+1],data[at+2],contact));
        }
        return new NativeWheelObservation(frame,stepSeconds,wheels);
    }
    public int supportedWheels() {return (int)wheels.stream().filter(Wheel::supported).count();}
    private static boolean bounded(float value,float maximum) {return Float.isFinite(value) && Math.abs(value)<=maximum;}
    private static boolean positiveBounded(float value,float maximum) {return Float.isFinite(value) && value>=0 && value<=maximum;}
}
