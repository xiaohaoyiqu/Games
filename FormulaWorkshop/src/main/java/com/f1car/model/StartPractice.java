package com.f1car.model;

import java.util.SplittableRandom;

/** Keyboard launch drill; reaction and acceleration are separate game measurements. */
public final class StartPractice {
    public enum State { LIGHTS, GO, LAUNCHED, SUCCESS, FAILED }
    private final double goAt;
    private double clock,reaction=-1,launchTime=-1;
    private double previousDisplacement;
    private State state=State.LIGHTS;
    private String feedback="等待红灯全部熄灭，再按油门";
    public StartPractice(long seed) { goAt=3+new SplittableRandom(seed).nextDouble(1,3); }
    public State state() { return state; }
    public String feedback() { return feedback; }
    public boolean green() { return clock>=goAt; }
    public int lights() { return green()?0:Math.min(5,1+(int)(clock/.6)); }
    public double reaction() { return reaction; }
    public double performanceElapsed() { return Math.max(0,clock-goAt); }
    public double accelerationTime() { return state==State.SUCCESS?clock-launchTime:-1; }
    public boolean complete() { return state==State.SUCCESS || state==State.FAILED; }
    double redFraction(float dt) {
        return clock<goAt && dt>0?Math.min(1,(goAt-clock)/dt):0;
    }
    /** Endpoint-only observations place a reported crossing at the end of the interval. */
    public void update(float dt,float throttle,float displacement,boolean crossedFinish,boolean valid) {
        if(complete() || dt==0) return;
        double red=redFraction(dt);
        double atSignal=Math.fma(red,displacement-previousDisplacement,previousDisplacement);
        boolean rolled=red>0 && Math.max(Math.abs(previousDisplacement),Math.abs(atSignal))>.7f;
        previousDisplacement=displacement;
        updateObserved(dt,throttle,rolled,crossedFinish?1:Double.NaN,valid && Float.isFinite(displacement));
    }
    void updateObserved(float dt,float throttle,boolean rolledWhileRed,double finishFraction,boolean valid) {
        if(complete() || dt==0) return;
        if(!Float.isFinite(dt) || dt<0 || !Float.isFinite(throttle) || throttle<0 || throttle>1) {
            fail("起跑观测异常，按 R 重试。");
            return;
        }
        double from=clock,to=from+dt,deadline=goAt+20;
        clock=Math.min(to,deadline);
        if(!valid) { fail("离开起跑路线，按 R 重试。");return; }
        if(state==State.LIGHTS) {
            if(rolledWhileRed || to<goAt && throttle>.1f) {
                fail("抢跑：等红灯熄灭后再按油门。");
                return;
            }
            if(to<goAt) return;
            state=State.GO;feedback="灯灭，开始起步！";
        }
        if(state==State.GO && throttle>.1f && to<=deadline) {
            reaction=to-goAt;launchTime=to;state=State.LAUNCHED;
            feedback=String.format(java.util.Locale.ROOT,"起步反应 %.3f s · 继续驶过前方终点门",reaction);
        }
        double crossing=Math.fma(dt,finishFraction,from);
        if(state==State.LAUNCHED && crossing>launchTime && crossing<=deadline) {
            clock=crossing;
            state=State.SUCCESS;
            feedback=String.format(java.util.Locale.ROOT,"起步反应 %.3f s · 加速区用时 %.3f s",reaction,clock-launchTime);
        } else if(to>=deadline) fail("起步时间已到，按 R 再试一次。");
    }
    private void fail(String message) { state=State.FAILED;feedback=message; }
}
