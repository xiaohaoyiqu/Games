package com.f1car.model;

import java.util.*;

/**
 * Bounded, in-memory research window. One call per completed physics step, not render frame.
 * Samples represent their completed interval as a constant; boundaries clip time only and do
 * not interpolate contact events. Means/RMS are ordinary time-weighted values, not ISO weighting.
 * Force statistics are conditional on known support; airborne time and missing time stay separate.
 * This is not a trial report: track, driver inputs and arbitrary native wheel overrides are not
 * bound by Context. Do not use it alone as complete evidence for comparing or adopting setups.
 */
public final class WheelObservationWindow {
    public static final double MAX_SECONDS=120;
    public enum Status {OPEN,COMPLETE,STOPPED,RECOVERED,READER_DISABLED,READER_RESTARTED,SOURCE_CHANGED,CLOSED}
    public record Context(String vehicle,String physics,Tuning tuning,List<Float> physical,AxleAerodynamics aero,AxleDamping damping,AxleFriction friction) {
        public Context {
            if(vehicle==null || !vehicle.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || physics==null || !physics.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || tuning==null || physical==null || physical.size()!=9) throw new IllegalArgumentException("Invalid wheel window context");
            physical=List.copyOf(physical);for(int i=0;i<9;i++) {float value=physical.get(i);if(!Float.isFinite(value) || value<0 || (i<4 || i==6) && value==0) throw new IllegalArgumentException("Invalid wheel window physical source");}
        }
        public static Context capture(VehicleSpec spec,Tuning tuning) {
            Objects.requireNonNull(spec);if(!spec.studyIdentityValid()) throw new IllegalArgumentException("Missing or invalid study configuration");
            return new Context(spec.id,spec.physicsVersion,tuning,List.of(spec.massKg,spec.wheelRadius,spec.suspensionRestLength,spec.suspensionStiffness,spec.engineForce,spec.brakeForce,spec.maxSpeedMps,spec.downforceCoefficient,spec.dragCoefficient),spec.aeroStudy(),spec.dampingStudy(),spec.frictionStudy());
        }
        /** Scalar/immutable equality only: no per-step hash, I/O or model reconstruction. */
        public boolean matches(VehicleSpec spec,Tuning current) {
            return spec!=null && tuning.equals(current) && vehicle.equals(spec.id) && physics.equals(spec.physicsVersion)
                && physical.get(0)==spec.massKg && physical.get(1)==spec.wheelRadius && physical.get(2)==spec.suspensionRestLength && physical.get(3)==spec.suspensionStiffness
                && physical.get(4)==spec.engineForce && physical.get(5)==spec.brakeForce && physical.get(6)==spec.maxSpeedMps && physical.get(7)==spec.downforceCoefficient && physical.get(8)==spec.dragCoefficient
                && Objects.equals(aero,spec.aeroStudy()) && Objects.equals(damping,spec.dampingStudy()) && Objects.equals(friction,spec.frictionStudy());
        }
    }
    public record Geometry(double meanCompression,double rmsCompression,double peakAbsoluteCompression) {
        public Geometry {if(!finite(meanCompression) || Math.abs(meanCompression)>20 || !nonnegative(rmsCompression) || rmsCompression+1e-8<Math.abs(meanCompression) || !nonnegative(peakAbsoluteCompression) || peakAbsoluteCompression+1e-8<rmsCompression || peakAbsoluteCompression>20) throw new IllegalArgumentException("Invalid wheel geometry summary");}
    }
    public record ContactStatistics(double meanRawNewtons,double rawDeviation,double rawPeakNewtons,double meanCappedNewtons,double cappedDeviation,double cappedPeakNewtons,
                                    double meanSkidFraction,double minimumSkidFraction,double forceLimitedSeconds) {
        public ContactStatistics {if(!nonnegative(meanRawNewtons) || !nonnegative(rawDeviation) || !nonnegative(rawPeakNewtons) || rawPeakNewtons+1e-6<meanRawNewtons || !nonnegative(meanCappedNewtons) || !nonnegative(cappedDeviation) || !nonnegative(cappedPeakNewtons) || cappedPeakNewtons+1e-6<meanCappedNewtons || meanCappedNewtons>meanRawNewtons+1e-6 || !nonnegative(meanSkidFraction) || meanSkidFraction>1 || !nonnegative(minimumSkidFraction) || minimumSkidFraction>meanSkidFraction+1e-10 || !nonnegative(forceLimitedSeconds)) throw new IllegalArgumentException("Invalid wheel contact summary");}
    }
    public record WheelSummary(int index,double supportedSeconds,Geometry geometry,ContactStatistics contact) {
        public WheelSummary {if(index<0 || index>=4 || !nonnegative(supportedSeconds) || (supportedSeconds==0)!=(contact==null) || contact!=null && contact.forceLimitedSeconds()>supportedSeconds+1e-8) throw new IllegalArgumentException("Invalid wheel window summary");}
    }
    public record Summary(Context context,Status status,double startSeconds,double endSeconds,double elapsedSeconds,double observedSeconds,double missingSeconds,
                          long observedSteps,long missingSteps,long duplicateSteps,long mismatchedSteps,List<WheelSummary> wheels) {
        public Summary {
            Objects.requireNonNull(context);if(status==null || status==Status.OPEN || !interval(startSeconds,endSeconds) || !nonnegative(elapsedSeconds) || elapsedSeconds>endSeconds+1e-8 || !nonnegative(observedSeconds) || !nonnegative(missingSeconds)
                || Math.abs(observedSeconds+missingSeconds-Math.max(0,elapsedSeconds-startSeconds))>1e-7 || observedSteps<0 || missingSteps<0 || duplicateSteps<0 || mismatchedSteps<0 || duplicateSteps>missingSteps || mismatchedSteps>missingSteps-duplicateSteps
                || status==Status.COMPLETE && elapsedSeconds!=endSeconds || wheels==null || wheels.size()!=4) throw new IllegalArgumentException("Invalid wheel window coverage");
            wheels=List.copyOf(wheels);for(int i=0;i<4;i++) {var wheel=wheels.get(i);if(wheel.index()!=i || wheel.supportedSeconds()>observedSeconds+1e-8 || (observedSeconds==0)!=(wheel.geometry()==null)) throw new IllegalArgumentException("Inconsistent wheel window coverage");}
        }
        public double plannedSeconds() {return endSeconds-startSeconds;}
        public double coveredSeconds() {return observedSeconds+missingSeconds;}
        /** Fraction of visited measurement time, not of the whole requested duration after a stop. */
        public Double observedFraction() {return coveredSeconds()==0?null:observedSeconds/coveredSeconds();}
        /** Support fraction over observed time only; consult missingSeconds and status separately. */
        public Double supportFraction(int wheel) {if(wheel<0 || wheel>=4) throw new IllegalArgumentException("Invalid wheel index");return observedSeconds==0?null:Math.min(1,wheels.get(wheel).supportedSeconds()/observedSeconds);}
    }
    private static final class Moments {
        double seconds,mean,m2,peak;
        void add(double weight,double value) {double next=seconds+weight,delta=value-mean;mean+=delta*(weight/next);m2+=delta*(value-mean)*weight;seconds=next;peak=Math.max(peak,Math.abs(value));}
        double deviation() {return Math.sqrt(Math.max(0,m2/seconds));}
        double rms() {return Math.min(peak,Math.sqrt(mean*mean+Math.max(0,m2/seconds)));}
    }
    private static final class Totals {
        final Moments compression=new Moments(),raw=new Moments(),capped=new Moments();
        double skidMean,skidMinimum=1,limitedSeconds;
        void add(double weight,NativeWheelObservation.Wheel wheel) {
            compression.add(weight,wheel.compression());var contact=wheel.contact();if(contact==null) return;
            raw.add(weight,contact.suspensionNewtons());capped.add(weight,contact.cappedSuspensionNewtons());skidMean+=(contact.skidFraction()-skidMean)*(weight/raw.seconds);skidMinimum=Math.min(skidMinimum,contact.skidFraction());if(contact.forceLimited()) limitedSeconds+=weight;
        }
        WheelSummary summary(int index) {return new WheelSummary(index,raw.seconds,compression.seconds==0?null:new Geometry(compression.mean,compression.rms(),compression.peak),raw.seconds==0?null:new ContactStatistics(raw.mean,raw.deviation(),raw.peak,capped.mean,capped.deviation(),capped.peak,Math.max(skidMinimum,Math.min(1,skidMean)),skidMinimum,limitedSeconds));}
    }
    private final Context context;
    private final double start,end;
    private final Totals[] wheels={new Totals(),new Totals(),new Totals(),new Totals()};
    private double elapsed,observed;
    private long lastFrame,observedSteps,missingSteps,duplicateSteps,mismatchedSteps;
    private volatile Summary published;
    public WheelObservationWindow(Context context,double startSeconds,double endSeconds,long precedingFrame) {
        this.context=Objects.requireNonNull(context);if(!interval(startSeconds,endSeconds) || precedingFrame<0) throw new IllegalArgumentException("Invalid wheel observation window");start=startSeconds;end=endSeconds;lastFrame=precedingFrame;
    }
    public Status status() {var value=published;return value==null?Status.OPEN:value.status();}
    /** Cached terminal summary only; active or never-started data is not represented by zeros. */
    public Summary summary() {return published;}
    public synchronized void advance(float dt,NativeWheelObservation frame,VehicleSpec currentSpec,Tuning currentTuning) {
        if(published!=null) return;if(!Float.isFinite(dt) || dt<0 || dt>.25f) throw new IllegalArgumentException("Invalid wheel window step");if(dt==0) return;
        if(!context.matches(currentSpec,currentTuning)) {finish(Status.SOURCE_CHANGED);return;}
        boolean duplicate=false,mismatch=false;
        if(frame!=null) {duplicate=frame.frame()<=lastFrame;if(!duplicate) {lastFrame=frame.frame();mismatch=Float.compare(frame.stepSeconds(),dt)!=0;}}
        double previous=elapsed;elapsed=Math.min(end,elapsed+dt);double weight=Math.max(0,elapsed-Math.max(start,previous));
        if(weight>0) {
            if(frame==null || duplicate || mismatch) {missingSteps++;if(duplicate) duplicateSteps++;else if(mismatch) mismatchedSteps++;}
            else {observedSteps++;observed+=weight;for(int i=0;i<4;i++) wheels[i].add(weight,frame.wheels().get(i));}
        }
        if(elapsed>=end) finish(Status.COMPLETE);
    }
    public synchronized void interrupt(Status reason) {if(reason==null || reason==Status.OPEN || reason==Status.COMPLETE) throw new IllegalArgumentException("Invalid wheel window interruption");if(published==null) finish(reason);}
    private void finish(Status status) {
        double covered=Math.max(0,elapsed-start),known=Math.min(covered,observed);var values=new ArrayList<WheelSummary>(4);for(int i=0;i<4;i++) values.add(wheels[i].summary(i));
        published=new Summary(context,status,start,end,elapsed,known,Math.max(0,covered-known),observedSteps,missingSteps,duplicateSteps,mismatchedSteps,values);
    }
    private static boolean finite(double value) {return Double.isFinite(value);}
    private static boolean nonnegative(double value) {return finite(value) && value>=0;}
    private static boolean interval(double start,double end) {return finite(start) && finite(end) && start>=0 && end>start && end<=MAX_SECONDS;}
}
