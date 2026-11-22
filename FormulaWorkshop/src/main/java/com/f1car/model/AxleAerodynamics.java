package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.*;

/** Explicit study model: lumped coefficients, two body-frame wings, no CFD or tyre-load claim. */
public record AxleAerodynamics(float front,float rear,float liftCoefficient,float dragCoefficient,float frontShare,float frontY,float frontZ,float rearY,float rearZ,float loadLimit) {
    public static final float SPEED_LIMIT=150;
    public record Reading(float frontNewtons,float rearNewtons,float frontDragNewtons,float rearDragNewtons,float centralDragNewtons,float pitchNewtonMetres,Double frontFraction,boolean limited) {}
    /** Caller-owned reusable storage. A reading is allocated only on explicit request. */
    public static final class Frame {
        private final Vector3f frontForce=new Vector3f(),rearForce=new Vector3f(),centralForce=new Vector3f();
        private float frontLoad,rearLoad,frontDrag,rearDrag,centralDrag,pitch;private double dragPower;private boolean limited;
        public Vector3f frontForce(Vector3f store) {return Objects.requireNonNull(store).set(frontForce);}
        public Vector3f rearForce(Vector3f store) {return Objects.requireNonNull(store).set(rearForce);}
        public Vector3f centralForce(Vector3f store) {return Objects.requireNonNull(store).set(centralForce);}
        public double dragPower() {return dragPower;}
        public Reading reading() {float total=frontLoad+rearLoad;return new Reading(frontLoad,rearLoad,frontDrag,rearDrag,centralDrag,pitch,total>0?(double)frontLoad/total:null,limited);}
    }
    public AxleAerodynamics {
        if(!between(front,.65f,1.4f) || !between(rear,.65f,1.4f) || !between(liftCoefficient,0,5) || !between(dragCoefficient,0,2) || !between(frontShare,.1f,.9f) || !between(frontY,0,2) || !between(frontZ,.1f,4) || !between(rearY,0,2) || !between(rearZ,-4,-.1f) || !between(loadLimit,100,20000)) throw new IllegalArgumentException("Invalid axle aerodynamics study");
    }
    public static AxleAerodynamics baseline(VehicleSpec spec) {Objects.requireNonNull(spec);return new AxleAerodynamics(1,1,spec.downforceCoefficient,spec.dragCoefficient,1.79f/(2.02f+1.79f),.16f,2.02f,.96f,-1.79f,5500);}
    public AxleAerodynamics front(float value) {return new AxleAerodynamics(value,rear,liftCoefficient,dragCoefficient,frontShare,frontY,frontZ,rearY,rearZ,loadLimit);}
    public AxleAerodynamics rear(float value) {return new AxleAerodynamics(front,value,liftCoefficient,dragCoefficient,frontShare,frontY,frontZ,rearY,rearZ,loadLimit);}
    /** Redistributes the nominal lift between axles at unchanged total lift coefficient. */
    public AxleAerodynamics withLiftBalance(double requestedFrontFraction) {
        if(!Double.isFinite(requestedFrontFraction) || requestedFrontFraction<=0 || requestedFrontFraction>=1)
            throw new IllegalArgumentException("Front lift share must lie between zero and one");
        double rearShare=1f-frontShare;
        double nominal=(double)frontShare*front+rearShare*rear;
        float shiftedFront=(float)(nominal*requestedFrontFraction/frontShare);
        float shiftedRear=(float)(nominal*(1-requestedFrontFraction)/rearShare);
        return new AxleAerodynamics(shiftedFront,shiftedRear,liftCoefficient,dragCoefficient,frontShare,
            frontY,frontZ,rearY,rearZ,loadLimit);
    }
    public Vector3f frontOffset(Vector3f store) {return Objects.requireNonNull(store).set(0,frontY,frontZ);}
    public Vector3f rearOffset(Vector3f store) {return Objects.requireNonNull(store).set(0,rearY,rearZ);}
    public void calculate(Vector3f velocity,float wing,Frame output) {
        Objects.requireNonNull(velocity);Objects.requireNonNull(output);if(!Float.isFinite(velocity.x) || !Float.isFinite(velocity.y) || !Float.isFinite(velocity.z) || !between(wing,.65f,1.4f)) throw new IllegalArgumentException("Invalid aero velocity or common wing setting");
        double x=velocity.x,y=velocity.y,z=velocity.z,rawSpeed=Math.sqrt(x*x+y*y+z*z),speed=Math.min(rawSpeed,SPEED_LIMIT),scale=rawSpeed>0?speed/rawSpeed:1;
        x*=scale;y*=scale;z*=scale;double forward=Math.max(0,z),frontSetting=wing*front,rearSetting=wing*rear;
        double frontLoad=liftCoefficient*frontShare*frontSetting*forward*forward,rearLoad=liftCoefficient*(1-frontShare)*rearSetting*forward*forward,total=frontLoad+rearLoad,loadScale=total>loadLimit?loadLimit/total:1;
        output.frontLoad=(float)(frontLoad*loadScale);output.rearLoad=(float)(rearLoad*loadScale);
        output.frontDrag=(float)(.45*dragCoefficient*frontShare*frontSetting*frontSetting*forward*forward);output.rearDrag=(float)(.45*dragCoefficient*(1-frontShare)*rearSetting*rearSetting*forward*forward);
        double central=.55*dragCoefficient*speed;output.centralForce.set((float)(-central*x),(float)(-central*y),(float)(-central*z));output.centralDrag=(float)(central*speed);
        output.frontForce.set(0,-output.frontLoad,-output.frontDrag);output.rearForce.set(0,-output.rearLoad,-output.rearDrag);
        output.pitch=frontZ*output.frontLoad-frontY*output.frontDrag+rearZ*output.rearLoad-rearY*output.rearDrag;
        output.dragPower=(double)output.centralForce.x*velocity.x+(double)output.centralForce.y*velocity.y+((double)output.centralForce.z-output.frontDrag-output.rearDrag)*velocity.z;
        output.limited=rawSpeed>SPEED_LIMIT || total>loadLimit;
    }
    public String key() {return String.join(":",List.of(Float.toHexString(front),Float.toHexString(rear),Float.toHexString(liftCoefficient),Float.toHexString(dragCoefficient),Float.toHexString(frontShare),Float.toHexString(frontY),Float.toHexString(frontZ),Float.toHexString(rearY),Float.toHexString(rearZ),Float.toHexString(loadLimit)));}
    String physicsIdentity(String base,VehicleSpec spec) {
        if(base==null || !base.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || spec.id==null || !spec.id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")) throw new IllegalArgumentException("Invalid aero baseline identity");
        var text=new StringBuilder("axle-aero-study-1:").append(base).append(':').append(spec.id).append(':').append(key());
        for(float value:new float[]{spec.massKg,spec.wheelRadius,spec.suspensionRestLength,spec.suspensionStiffness,spec.engineForce,spec.brakeForce,spec.maxSpeedMps,spec.downforceCoefficient,spec.dragCoefficient}) {if(!Float.isFinite(value) || value<0) throw new IllegalArgumentException("Invalid study vehicle specification");text.append(':').append(Float.toHexString(value));}
        if(spec.massKg<=0 || spec.wheelRadius<=0 || spec.suspensionRestLength<=0 || spec.suspensionStiffness<=0 || spec.maxSpeedMps<=0) throw new IllegalArgumentException("Invalid study vehicle dimensions");
        return "fw-aero-study-1-"+PartsCatalog.hash(text.toString());
    }
    private static boolean between(float value,float low,float high) {return Float.isFinite(value) && value>=low && value<=high;}
}
