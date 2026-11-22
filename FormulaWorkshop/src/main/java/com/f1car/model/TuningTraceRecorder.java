package com.f1car.model;

import java.util.ArrayList;
import java.util.List;

/** Keeps actual frame-end observations. Slow frames stay gaps; no synthetic intermediate samples. */
public final class TuningTraceRecorder {
    private final List<TuningTrace.Sample> samples=new ArrayList<>();
    private final List<TuningTrace.Position> positions=new ArrayList<>();
    private double seconds;
    public void update(float dt,float speed,float throttle,float brake,float steer,boolean finished) {
        update(dt,speed,throttle,brake,steer,finished,null);
    }
    public void update(float dt,float speed,float throttle,float brake,float steer,boolean finished,TuningTrace.Position position) {
        if(!Float.isFinite(dt) || dt<=0 || dt>.25f || !Float.isFinite(speed) || speed<0 || speed>200
            || !Float.isFinite(throttle) || Math.abs(throttle)>1 || !Float.isFinite(brake) || brake<0 || brake>1
            || !Float.isFinite(steer) || Math.abs(steer)>1) throw new IllegalArgumentException("Invalid trace observation");
        if(seconds+dt>120) throw new IllegalArgumentException("Trial trace exceeds duration bound");
        if(!samples.isEmpty() && (positions.isEmpty() != (position==null))) throw new IllegalArgumentException("Mixed trace position availability");
        seconds+=dt;
        if(samples.isEmpty() || seconds-samples.get(samples.size()-1).seconds()>=TuningTrace.PERIOD-1e-8 || finished) {
            if(samples.size()>=TuningTrace.MAX_SAMPLES) throw new IllegalStateException("Trial trace capacity exceeded");
            samples.add(new TuningTrace.Sample(seconds,speed,throttle,brake,steer));
            if(position!=null) positions.add(position);
        }
    }
    public List<TuningTrace.Sample> samples() {return List.copyOf(samples);}
    public List<TuningTrace.Position> positions() {return List.copyOf(positions);}
}
