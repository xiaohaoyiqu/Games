package com.f1car.model;

import java.util.*;

/**
 * Explicit research-only compression/rebound multipliers, composed with the existing axis setting.
 * Bounds are an engineering trial envelope, not a calibrated stability or optimality claim.
 * Minie 9.0.3 VehicleWheel exposes separate compression and relaxation setters; Bullet selects the
 * branch from suspension-relative velocity. These are engine coefficients, not measured tyre loads.
 * Reference: https://arxiv.org/abs/2605.05235 (scenario-dependent asymmetric damping).
 */
public record AxleDamping(float frontCompression,float frontRebound,float rearCompression,float rearRebound) {
    public static final float MIN=.8f,MAX=1.2f;
    public static final AxleDamping NEUTRAL=new AxleDamping(1,1,1,1);
    public record Wheel(float compression,float rebound) {
        public Wheel {if(!positive(compression) || !positive(rebound)) throw new IllegalArgumentException("Invalid damper coefficients");}
    }
    public record Coefficients(Wheel front,Wheel rear) {
        public Coefficients {Objects.requireNonNull(front);Objects.requireNonNull(rear);}
        public Wheel wheel(int index) {if(index<0 || index>3) throw new IllegalArgumentException("Wheel index must be 0 through 3");return index<2?front:rear;}
    }
    public AxleDamping {
        for(float factor:new float[]{frontCompression,frontRebound,rearCompression,rearRebound})
            if(!Float.isFinite(factor) || factor<MIN || factor>MAX) throw new IllegalArgumentException("Invalid split damping multiplier");
    }
    public Coefficients coefficients(VehicleSpec spec,Tuning tuning) {
        Objects.requireNonNull(spec);Objects.requireNonNull(tuning);float stiffness=spec.suspensionStiffness*tuning.suspension();
        if(!positive(spec.suspensionStiffness) || !positive(stiffness)) throw new IllegalArgumentException("Invalid suspension stiffness");
        float root=(float)Math.sqrt(stiffness);
        // Preserve the legacy multiplication order at neutral factors. Axis springs do not alter damping.
        return new Coefficients(new Wheel(.32f*2*root*tuning.frontDamping()*frontCompression,.45f*2*root*tuning.frontDamping()*frontRebound),
            new Wheel(.32f*2*root*tuning.rearDamping()*rearCompression,.45f*2*root*tuning.rearDamping()*rearRebound));
    }
    public String key() {return Float.toHexString(frontCompression)+":"+Float.toHexString(frontRebound)+":"+Float.toHexString(rearCompression)+":"+Float.toHexString(rearRebound);}
    String physicsIdentity(String base,VehicleSpec spec) {
        if(base==null || !base.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || spec.id==null || !spec.id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")) throw new IllegalArgumentException("Invalid damper baseline identity");
        var source=new StringBuilder("axle-damper-study-1:").append(base).append(':').append(spec.id).append(':').append(key());
        for(float value:new float[]{spec.massKg,spec.wheelRadius,spec.suspensionRestLength,spec.suspensionStiffness,spec.engineForce,spec.brakeForce,spec.maxSpeedMps,spec.downforceCoefficient,spec.dragCoefficient}) {
            if(!Float.isFinite(value) || value<0) throw new IllegalArgumentException("Invalid damper study specification");source.append(':').append(Float.toHexString(value));
        }
        if(!positive(spec.massKg) || !positive(spec.wheelRadius) || !positive(spec.suspensionRestLength) || !positive(spec.suspensionStiffness) || !positive(spec.maxSpeedMps)) throw new IllegalArgumentException("Invalid study dimensions");
        return "fw-damper-study-1-"+PartsCatalog.hash(source.toString());
    }
    private static boolean positive(float value) {return Float.isFinite(value) && value>0;}
}
