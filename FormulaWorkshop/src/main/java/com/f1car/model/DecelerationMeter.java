package com.f1car.model;

/** Observed planar speed loss, including impacts; this does not infer the driver's intent. */
public final class DecelerationMeter {
    private float previous=-1;
    private double filtered,peak,strongTime;
    public void update(float dt,float speed) {
        if(!Float.isFinite(dt) || dt<=0 || dt>.25f || !Float.isFinite(speed) || speed<0 || speed>200) throw new IllegalArgumentException("Invalid deceleration observation");
        if(previous>=0) {
            double loss=((double)previous-speed)/dt;filtered+=(loss-filtered)*-Math.expm1(-dt/.15);peak=Math.max(peak,Math.max(0,filtered));
            if(filtered>6 && Math.max(previous,speed)>3) strongTime+=dt;
        }
        previous=speed;
    }
    public double strongTime() { return strongTime; }
    public double peak() { return peak; }
}
