package com.f1car.model;

import java.util.Objects;

/** World-risk release memory and a low-speed launch hypothesis, not a physics trajectory or safety proof. */
public final class WorldTrafficRestart {
    public static final float MAX_PROBE_SPEED=1,PLAN_ACCELERATION=4;
    public static final double CLEAR_SECONDS=.3;
    public enum Phase { OPEN, BRAKING, RESTART_BLOCKED, WAITING_CLEAR, OBSERVATION_UNAVAILABLE }
    public record Decision(Phase phase,double clearSeconds) {
        public boolean blocked() {return phase!=Phase.OPEN;}
        public boolean holdsRelease() {return phase!=Phase.OPEN && phase!=Phase.BRAKING;}
        public float speedLimit(float speed) {
            speed(speed);
            return holdsRelease()?(speed<=MAX_PROBE_SPEED?0:speed):Float.POSITIVE_INFINITY;
        }
        public float limitPlan(float planned,float speed) {
            if(!Float.isFinite(planned) || planned<0) throw new IllegalArgumentException("Invalid restart speed plan");
            return Math.min(planned,speedLimit(speed));
        }
    }
    private static final Decision OPEN=new Decision(Phase.OPEN,0);
    private Phase phase=Phase.OPEN;
    private double clearSeconds;
    public void reset() {phase=Phase.OPEN;clearSeconds=0;}
    public Decision observationLost() {return arm(Phase.OBSERVATION_UNAVAILABLE);}

    /** Zero/bad time never expires memory, but a newly observed risk can still arm it. */
    public Decision update(float dt,WorldTrafficBraking.Decision actual,WorldTrafficBraking.Decision probe) {
        Objects.requireNonNull(actual);
        if(actual.blocked()) return arm(Phase.BRAKING);
        if(probe!=null && probe.blocked()) return arm(Phase.RESTART_BLOCKED);
        if(phase==Phase.OPEN) return OPEN;
        if(!actual.releaseObservationComplete() || probe!=null && !probe.releaseObservationComplete()) return arm(Phase.OBSERVATION_UNAVAILABLE);
        double step=Float.isFinite(dt)?Math.max(0,Math.min(.1,(double)dt)):0;
        clearSeconds=Math.min(CLEAR_SECONDS,clearSeconds+step);
        if(clearSeconds>=CLEAR_SECONDS) {reset();return OPEN;}
        phase=Phase.WAITING_CLEAR;
        return new Decision(phase,clearSeconds);
    }
    private Decision arm(Phase next) {phase=next;clearSeconds=0;return new Decision(next,0);}

    /** Use the possible end-of-window plan speed throughout the probe; this intentionally overstates launch. */
    public static TrafficBodyMotion.Body probe(TrafficBodyMotion.Body self,float speed,float desiredSpeed) {
        speed(speed);speed(desiredSpeed);
        if(self==null || speed>MAX_PROBE_SPEED) return null;
        double along=(double)self.vx()*self.forwardX()+(double)self.vz()*self.forwardZ();
        double next=Math.min(desiredSpeed,Math.max(0,along)+(double)PLAN_ACCELERATION*TrafficMotionWindow.HORIZON);
        if(desiredSpeed<=0 || next<=along) return null;
        double extra=next-along;
        return new TrafficBodyMotion.Body(self.x(),self.y(),self.z(),(float)(self.vx()+extra*self.forwardX()),self.vy(),(float)(self.vz()+extra*self.forwardZ()),self.forwardX(),self.forwardZ(),self.yawRate());
    }
    private static void speed(float value) {if(!Float.isFinite(value) || value<0) throw new IllegalArgumentException("Invalid restart speed");}
}
