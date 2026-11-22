package com.f1car.model;

import com.jme3.math.Vector3f;

/** Bounds horizontal speed while preserving vertical motion and sideways handling. */
public final class SpeedLimiter {
    public static final float REVERSE_MPS=8;
    private SpeedLimiter() { }
    public static boolean apply(Vector3f velocity,Vector3f forward,float maximum) {
        if(!Float.isFinite(maximum) || maximum<=0) throw new IllegalArgumentException("Invalid speed limit");
        if(!Float.isFinite(velocity.lengthSquared())) { velocity.set(0,0,0);return true; }
        float length=(float)Math.hypot(forward.x,forward.z),fx=length>.001f?forward.x/length:0,fz=length>.001f?forward.z/length:1;
        boolean changed=false;float signed=velocity.x*fx+velocity.z*fz;
        if(signed<-REVERSE_MPS) { float excess=signed+REVERSE_MPS;velocity.x-=fx*excess;velocity.z-=fz*excess;changed=true; }
        float horizontal=(float)Math.hypot(velocity.x,velocity.z);
        if(horizontal>maximum) { float factor=maximum/horizontal;velocity.x*=factor;velocity.z*=factor;changed=true; }
        return changed;
    }
}
