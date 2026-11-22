package com.f1car.model;

/** Short constant-Frenet predictions, not a vehicle dynamics model or a collision-free guarantee. */
public final class TrafficMotionWindow {
    public static final float HORIZON=1.2f,LATERAL_RATE=1.5f,LONGITUDINAL_MARGIN=5.5f,LATERAL_MARGIN=2.8f;
    public static final float SEPARATING_MIN_LATERAL=1.6f,SEPARATING_MIN_GAIN=.01f;
    public record Neighbor(float gap,float lateral,float alongSpeed,float lateralSpeed) {
        public Neighbor {require(gap,lateral,alongSpeed,lateralSpeed);}
    }
    /** Ego approaches target at the existing offset command rate, then stays there. */
    public static float planned(float alongSpeed,float from,float target,Neighbor other) {
        require(alongSpeed);
        double speed=(double)other.alongSpeed()-alongSpeed;
        double start=longitudinalStart(other.gap(),speed),end=longitudinalEnd(other.gap(),speed);
        return (float)plannedLateral(from,target,other,start,end);
    }
    /** A second hypothesis retains ego's actual observed lateral velocity. */
    public static float coasting(float alongSpeed,float lateral,float lateralSpeed,Neighbor other) {
        require(alongSpeed);
        double speed=(double)other.alongSpeed()-alongSpeed;
        double start=longitudinalStart(other.gap(),speed),end=longitudinalEnd(other.gap(),speed);
        return (float)coastingLateral(lateral,lateralSpeed,other,start,end);
    }
    static double longitudinalStart(float gap,double relativeSpeed) {
        return Math.max(0,entry(gap,relativeSpeed,LONGITUDINAL_MARGIN));
    }
    static double longitudinalEnd(float gap,double relativeSpeed) {
        return Math.min(HORIZON,exit(gap,relativeSpeed,LONGITUDINAL_MARGIN));
    }
    static double coastingLateral(float from,float lateralSpeed,Neighbor other,double start,double end) {
        require(from,lateralSpeed);
        return lateralOverlap((double)other.lateral()-from,(double)other.lateralSpeed()-lateralSpeed,start,end);
    }
    static double plannedLateral(float from,float target,Neighbor other,double start,double end) {
        require(from,target);
        if(start>end) return Double.POSITIVE_INFINITY;
        double change=(double)target-from;
        double duration=Math.abs(change)/LATERAL_RATE,rate=Math.signum(change)*LATERAL_RATE;
        double first=lateralOverlap((double)other.lateral()-from,(double)other.lateralSpeed()-rate,start,Math.min(end,duration));
        if(Double.isFinite(first)) return first;
        return duration<HORIZON?lateralOverlap((double)other.lateral()-target,other.lateralSpeed(),Math.max(start,duration),end):Double.POSITIVE_INFINITY;
    }
    public static float laneEntry(float lateral,float lateralSpeed,Neighbor other) {
        require(lateral,lateralSpeed);
        return (float)lateralOverlap((double)other.lateral()-lateral,(double)other.lateralSpeed()-lateralSpeed,0,HORIZON);
    }
    /** Narrow exception for an existing buffer overlap whose two gaps never shrink in either hypothesis. */
    public static boolean separating(float alongSpeed,float from,float target,float lateralSpeed,Neighbor other) {
        require(alongSpeed,from,target,lateralSpeed);
        double side=(double)other.lateral()-from,separation=Math.abs(side),gap=other.gap();
        if(alongSpeed<0 || other.alongSpeed()<0 || separation<SEPARATING_MIN_LATERAL || separation>LATERAL_MARGIN || Math.abs(gap)>LONGITUDINAL_MARGIN) return false;
        if(gap*((double)other.alongSpeed()-alongSpeed)<0) return false;
        double direction=Math.signum(side),change=(double)target-from,duration=Math.abs(change)/LATERAL_RATE,rate=Math.signum(change)*LATERAL_RATE;
        if(direction*change>=0) return false;
        if(direction*((double)other.lateralSpeed()-lateralSpeed)<0 || direction*((double)other.lateralSpeed()-rate)<0) return false;
        if(duration<HORIZON && direction*other.lateralSpeed()<0) return false;
        double plannedDelta=Math.signum(change)*Math.min(Math.abs(change),(double)LATERAL_RATE*HORIZON);
        return direction*((double)other.lateralSpeed()*HORIZON-plannedDelta)>=SEPARATING_MIN_GAIN;
    }
    private static double lateralOverlap(double lateral,double speed,double start,double end) {
        if(start>end) return Double.POSITIVE_INFINITY;
        start=Math.max(start,entry(lateral,speed,LATERAL_MARGIN));
        end=Math.min(end,exit(lateral,speed,LATERAL_MARGIN));
        return start<=end?start:Double.POSITIVE_INFINITY;
    }
    private static double entry(double c,double v,double margin) {
        if(v==0) return Math.abs(c)<=margin?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
        return (-Math.copySign(margin,v)-c)/v;
    }
    private static double exit(double c,double v,double margin) {
        if(v==0) return Math.abs(c)<=margin?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
        return (Math.copySign(margin,v)-c)/v;
    }
    private static void require(float value) {
        if(!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite traffic motion");
    }
    private static void require(float first,float second) {
        require(first);require(second);
    }
    private static void require(float first,float second,float third) {
        require(first,second);require(third);
    }
    private static void require(float first,float second,float third,float fourth) {
        require(first,second);require(third,fourth);
    }
    private TrafficMotionWindow() {}
}
