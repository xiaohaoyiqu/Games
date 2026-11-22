package com.f1car.model;

import com.jme3.math.Vector3f;

/** Deliberately game-like stability score. No fluid or physiological simulation. */
public final class CupChallenge {
    public static final double TIME_LIMIT = 110;
    public static final double FINISH_DEADLINE = Math.nextDown(TIME_LIMIT);
    public float spill;
    public double time;
    private final Vector3f previousVelocity=new Vector3f(),acceleration=new Vector3f(),filteredAcceleration=new Vector3f();
    private boolean hasPrevious,observationsComplete=true;

    public void update(float dt, Vector3f velocity) {
        updateThrough(dt,velocity,TIME_LIMIT);
    }

    /** Use the full velocity observation but integrate only through the finish or time limit. */
    public void updateThrough(float dt,Vector3f velocity,double stopTime) {
        if(!Float.isFinite(dt) || dt<=0 || !Double.isFinite(stopTime) || !observationsComplete) return;
        double end=Math.min(time+dt,Math.min(stopTime,TIME_LIMIT));
        if(end<=time) return;
        float active=(float)(end-time);
        time=end;
        if(!Vector3f.isValidVector(velocity)) {
            observationsComplete=false;
            return;
        }
        if (hasPrevious) {
            acceleration.set(velocity).subtractLocal(previousVelocity).divideLocal(dt);
            acceleration.y = 0;
            if(!Float.isFinite(acceleration.lengthSquared())) {
                observationsComplete=false;
                return;
            }
            filteredAcceleration.interpolateLocal(acceleration, Math.min(1, active * 4));
            spill = Math.min(100, spill + Math.max(0, filteredAcceleration.length() - 6.8f) * active * .55f);
        }
        previousVelocity.set(velocity);
        hasPrevious=true;
    }
    public boolean observationsComplete() {return observationsComplete;}
    public int score() { return Math.max(0, Math.round(1000 - spill * 7 - (float) time * 2)); }
    public boolean timedOut() { return time >= TIME_LIMIT; }
    public String medal() { return spill < 25 && time < 85 ? "平稳大师" : spill < 60 ? "杯架合格" : "维修组有话想说"; }
}
