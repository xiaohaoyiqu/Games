package com.f1car.model;

/**
 * Explicit solver-friction research profile, not a tyre compound or calibrated road coefficient.
 * Bullet bounds combined longitudinal/lateral impulse using suspension force * dt * frictionSlip:
 * https://github.com/stephengold/Libbulletjme/blob/22.0.3/src/main/native/bullet3/BulletDynamics/Vehicle/btRaycastVehicle.cpp
 * The percent envelope/budget is this project's trial design, not physical grip conservation.
 * No load sensitivity, temperature, wear, pressure or separate longitudinal/lateral tuning is added.
 */
public record AxleFriction(int frontPercent,int rearPercent) {
    public static final int MIN_PERCENT=80,MAX_PERCENT=120,BUDGET_PERCENT=200;
    public static final float BASE_SLIP=2.5f;
    public static final AxleFriction NEUTRAL=new AxleFriction(100,100);
    public record Coefficients(float front,float rear) {
        public Coefficients {if(!Float.isFinite(front) || !Float.isFinite(rear) || front<2 || front>3 || rear<2 || rear>3 || front+rear>5.000001f) throw new IllegalArgumentException("Invalid study friction coefficients");}
        public float wheel(int index) {if(index<0 || index>3) throw new IllegalArgumentException("Wheel index must be 0 through 3");return index<2?front:rear;}
    }
    public AxleFriction {if(frontPercent<MIN_PERCENT || frontPercent>MAX_PERCENT || rearPercent<MIN_PERCENT || rearPercent>MAX_PERCENT || frontPercent+rearPercent>BUDGET_PERCENT) throw new IllegalArgumentException("Friction study exceeds its allocation envelope");}
    /** Move allocation between axles without silently repairing an independently supplied pair. */
    public static AxleFriction balanced(int frontPercent) {return new AxleFriction(frontPercent,BUDGET_PERCENT-frontPercent);}
    public Coefficients coefficients() {return new Coefficients(BASE_SLIP*(frontPercent/100f),BASE_SLIP*(rearPercent/100f));}
    public String key() {return "base="+Float.toHexString(BASE_SLIP)+":front="+frontPercent+":rear="+rearPercent;}
    String physicsIdentity(String base,VehicleSpec spec) {
        if(base==null || !base.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || spec.id==null || !spec.id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")) throw new IllegalArgumentException("Invalid friction study baseline");
        var source=new StringBuilder("axle-friction-study-1:").append(base).append(':').append(spec.id).append(':').append(key());
        for(float value:new float[]{spec.massKg,spec.wheelRadius,spec.suspensionRestLength,spec.suspensionStiffness,spec.engineForce,spec.brakeForce,spec.maxSpeedMps,spec.downforceCoefficient,spec.dragCoefficient}) {
            if(!Float.isFinite(value) || value<0) throw new IllegalArgumentException("Invalid friction study source");source.append(':').append(Float.toHexString(value));
        }
        if(spec.massKg<=0 || spec.wheelRadius<=0 || spec.suspensionRestLength<=0 || spec.suspensionStiffness<=0 || spec.maxSpeedMps<=0) throw new IllegalArgumentException("Invalid friction study dimensions");
        return "fw-friction-study-1-"+PartsCatalog.hash(source.toString());
    }
}
