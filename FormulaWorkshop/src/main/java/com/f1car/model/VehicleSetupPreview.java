package com.f1car.model;

import java.util.*;

/**
 * Read-only evaluation of FormulaCar's normal path, for forward, level, straight-line motion.
 * It does not simulate grip, contact, lap times or the separate aero/damper studies.
 * Minie 9.0.3 VehicleWheel.getBrake() exposes the native braking impulse, not tyre force:
 * https://github.com/stephengold/Minie/blob/9.0.3/MinieLibrary/src/main/java/com/jme3/bullet/objects/VehicleWheel.java
 */
public final class VehicleSetupPreview {
    /** Freeze every physical source value; a preview never follows later mutations of VehicleSpec. */
    public record Source(String vehicle,String name,String physics,float massKg,float wheelRadius,float restLength,float stiffness,
                         float engineForce,float brakeInput,float maxSpeedMps,float downforceCoefficient,float dragCoefficient) {
        public Source {
            if(vehicle==null || !vehicle.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || !PartsCatalog.text(name,120) || name.isBlank()
                || physics==null || !physics.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || physics.startsWith("fw-aero-study-") || physics.startsWith("fw-damper-study-") || physics.startsWith("fw-friction-study-") || physics.startsWith("fw-vehicle-study-")) throw new IllegalArgumentException("Invalid setup source identity");
            for(float value:new float[]{massKg,wheelRadius,restLength,stiffness,engineForce,brakeInput,maxSpeedMps,downforceCoefficient,dragCoefficient}) if(!Float.isFinite(value) || value<0) throw new IllegalArgumentException("Invalid setup source value");
            if(massKg==0 || wheelRadius==0 || restLength==0 || stiffness==0 || maxSpeedMps==0) throw new IllegalArgumentException("Invalid setup source dimensions");
        }
        public static Source capture(VehicleSpec spec) {
            Objects.requireNonNull(spec);if(spec.aeroStudy()!=null || spec.dampingStudy()!=null || spec.frictionStudy()!=null || !spec.studyIdentityValid()) throw new IllegalArgumentException("Study previews need their own force model");
            return new Source(spec.id,spec.name,spec.physicsVersion,spec.massKg,spec.wheelRadius,spec.suspensionRestLength,spec.suspensionStiffness,spec.engineForce,spec.brakeForce,spec.maxSpeedMps,spec.downforceCoefficient,spec.dragCoefficient);
        }
        /** Allocation-free render check, with no hashing, files, native calls or simulation. */
        public boolean matches(VehicleSpec spec) {
            return spec!=null && spec.aeroStudy()==null && spec.dampingStudy()==null && spec.frictionStudy()==null && vehicle.equals(spec.id) && name.equals(spec.name) && physics.equals(spec.physicsVersion)
                && massKg==spec.massKg && wheelRadius==spec.wheelRadius && restLength==spec.suspensionRestLength && stiffness==spec.suspensionStiffness
                && engineForce==spec.engineForce && brakeInput==spec.brakeForce && maxSpeedMps==spec.maxSpeedMps && downforceCoefficient==spec.downforceCoefficient && dragCoefficient==spec.dragCoefficient;
        }
    }
    public record Axle(float stiffness,float compression,float rebound) {
        public Axle {finite(stiffness,compression,rebound);}
    }
    /** Full throttle and released-throttle/full brake are separate input cases. */
    public record Sample(float speedMps,float rearWheelDriveNewtons,float frontWheelBrakeInput,float rearWheelBrakeInput,
                         float dragNewtons,float downforceNewtons,boolean downforceLimited,boolean driveCut) {
        public Sample {finite(speedMps,rearWheelDriveNewtons,frontWheelBrakeInput,rearWheelBrakeInput,dragNewtons,downforceNewtons);}
        /** Power dissipated by the existing drag force at this constant forward speed. */
        public double dragPowerKilowatts() {return (double)dragNewtons*speedMps/1000;}
        /** Sum of the two wheel requests minus the applied aerodynamic drag input. */
        public double driveMinusDragNewtons() {return 2.0*rearWheelDriveNewtons-dragNewtons;}
        private boolean dragMeetsDrive() {return dragNewtons>0 && driveMinusDragNewtons()<=0;}
    }
    public record Comparison(VehicleSetupPreview a,VehicleSetupPreview b) {
        public Comparison {Objects.requireNonNull(a);Objects.requireNonNull(b);if(!a.source.equals(b.source)) throw new IllegalArgumentException("Setup sources differ");}
        /** Integer speeds both previews can evaluate without substituting different speeds. */
        public int maximumKph() {float limit=Math.min(a.forwardLimitMps,b.forwardLimitMps);double raw=Math.floor((double)limit*3.6);if(raw>DigitNumberEditor.MAX_VALUE) throw new IllegalArgumentException("Preview speed exceeds UI bound");int maximum=(int)raw;while(maximum>0 && maximum/3.6f>limit) maximum--;return maximum;}
    }
    private final Source source;
    private final Tuning tuning;
    private final Axle front,rear;
    private final float forwardLimitMps;
    public VehicleSetupPreview(Source source,Tuning tuning) {
        this.source=Objects.requireNonNull(source);this.tuning=Objects.requireNonNull(tuning);forwardLimitMps=source.maxSpeedMps()*tuning.power();
        float stiffness=source.stiffness()*tuning.suspension();
        // Keep FormulaCar's multiplication order: common stiffness first, then the axis multiplier.
        front=axle(stiffness,tuning.frontSpring(),tuning.frontDamping());rear=axle(stiffness,tuning.rearSpring(),tuning.rearDamping());
        finite(forwardLimitMps,source.maxSpeedMps()*1.18f);sample(0);sample(forwardLimitMps);
    }
    public static Comparison compare(VehicleSpec spec,Tuning a,Tuning b) {var source=Source.capture(spec);return new Comparison(new VehicleSetupPreview(source,a),new VehicleSetupPreview(source,b));}
    public Source source() {return source;}
    public Tuning tuning() {return tuning;}
    public Axle front() {return front;}
    public Axle rear() {return rear;}
    public float forwardLimitMps() {return forwardLimitMps;}

    /** First whole km/h reaching the load cap within the selected common speed range. */
    public OptionalInt firstDownforceCapKph(int maximumKph) {
        if(maximumKph<0 || maximumKph/3.6f>forwardLimitMps)
            throw new IllegalArgumentException("Cap search is outside the forward limit");
        if(!sample(maximumKph/3.6f).downforceLimited()) return OptionalInt.empty();
        // The existing forward-only load is monotone before the cap, including the low-speed cutoff.
        int low=0,high=maximumKph;
        while(low<high) {
            int middle=low+(high-low)/2;
            if(sample(middle/3.6f).downforceLimited()) high=middle;
            else low=middle+1;
        }
        return OptionalInt.of(low);
    }

    /** First whole speed where aerodynamic drag reaches the drive request before the programmed cutoff. */
    public OptionalInt firstDragBalanceKph(int maximumKph) {
        if(maximumKph<0 || maximumKph/3.6f>forwardLimitMps)
            throw new IllegalArgumentException("Drag balance search is outside the forward limit");
        if(source.engineForce()==0 || !sample(maximumKph/3.6f).dragMeetsDrive()) return OptionalInt.empty();
        // The drive request is nonincreasing and the drag is nondecreasing. A cutoff-only match is excluded below.
        int low=0,high=maximumKph;
        while(low<high) {
            int middle=low+(high-low)/2;
            if(sample(middle/3.6f).dragMeetsDrive()) high=middle;
            else low=middle+1;
        }
        return sample(low/3.6f).driveCut()?OptionalInt.empty():OptionalInt.of(low);
    }

    public Sample sample(float speedMps) {
        if(!Float.isFinite(speedMps) || speedMps<0 || speedMps>forwardLimitMps) throw new IllegalArgumentException("Preview speed is outside the forward limit");
        float factor=Math.max(.12f,1-speedMps/(source.maxSpeedMps()*1.18f));float force=source.engineForce()*tuning.power()*factor;boolean cut=speedMps>=forwardLimitMps;if(cut) force=0;
        float impulse=source.brakeInput()*tuning.brakes();float frontBrake=2*impulse*tuning.brakeBias(),rearBrake=2*impulse*(1-tuning.brakeBias());
        float drag=0,load=0;boolean limited=false;
        if(speedMps>.4f) {float dragScale=source.dragCoefficient()*(.55f+.45f*tuning.wing())*speedMps;drag=dragScale*speedMps;float raw=source.downforceCoefficient()*tuning.wing()*speedMps*speedMps;load=Math.min(5500,raw);limited=raw>=5500;}
        return new Sample(speedMps,force,frontBrake,rearBrake,drag,load,limited,cut);
    }
    private static Axle axle(float stiffness,float spring,float damping) {return new Axle(stiffness*spring,.32f*2*(float)Math.sqrt(stiffness)*damping,.45f*2*(float)Math.sqrt(stiffness)*damping);}
    private static void finite(float... values) {for(float value:values) if(!Float.isFinite(value) || value<0) throw new IllegalArgumentException("Nonfinite setup preview result");}
}
