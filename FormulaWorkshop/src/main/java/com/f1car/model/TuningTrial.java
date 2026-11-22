package com.f1car.model;

import com.jme3.math.Vector3f;

/** Measurements from actual driving observations; never changes the vehicle or awards a lap. */
public final class TuningTrial {
    public enum Exercise {
        ACCELERATION("0–60 km/h 加速","s","从静止全油门直行，保持不制动、不转向"),
        BRAKING("70 km/h 制动","m","通过黄色测速门后，以 68–72 km/h 松油门、按住 SPACE 全制动"),
        FLOW("连续弯试车","s","首门开始计时，依次通过全部蓝色路门；六次入口速度尽量接近");
        public final String title,unit,instruction;
        Exercise(String title,String unit,String instruction) { this.title=title;this.unit=unit;this.instruction=instruction; }
    }
    public enum State { READY,RUNNING,SUCCESS,FAILED }
    public record Measurement(double value,float entry,float exit,float throttle,float brake,float steeringRms,VehicleObservation.Summary observation) {
        public Measurement(double value,float entry,float exit,float throttle,float brake,float steeringRms) { this(value,entry,exit,throttle,brake,steeringRms,null); }
        public Measurement {
            if(!Double.isFinite(value) || value<=0 || value>600 || !Float.isFinite(entry) || entry<0 || entry>720 || !Float.isFinite(exit) || exit<0 || exit>720
                || !Float.isFinite(throttle) || throttle< -1 || throttle>1 || !Float.isFinite(brake) || brake<0 || brake>1 || !Float.isFinite(steeringRms) || steeringRms<0 || steeringRms>1) throw new IllegalArgumentException("Invalid setup measurement");
        }
    }
    private final Exercise exercise;
    private final TrackLayout track;
    private final Tuning tuning;
    private final PracticeSession flow;
    private State state=State.READY;
    private String feedback;
    private double clock,startTime,value,observed,throttleTotal,brakeTotal,steerTotal;
    private float previousSpeed,entry,exit,brakeZ,stopped;
    private boolean entered,measuring;
    private final VehicleObservation.Accumulator observations=new VehicleObservation.Accumulator();
    private final TuningTraceRecorder trace=new TuningTraceRecorder();
    private Float previousRoute;
    private float unwrappedRoute;
    private int routeSegment=-1;
    public TuningTrial(Exercise exercise,TrackLayout track,Tuning tuning) {
        this.exercise=java.util.Objects.requireNonNull(exercise);this.track=java.util.Objects.requireNonNull(track);this.tuning=java.util.Objects.requireNonNull(tuning);
        flow=exercise==Exercise.FLOW?new PracticeSession(PracticeSession.Lesson.FLOW,track):null;feedback=exercise.instruction;
    }
    public Exercise exercise() { return exercise; }
    public TrackLayout track() { return track; }
    public Tuning tuning() { return tuning; }
    public State state() { return state; }
    public boolean complete() { return state==State.SUCCESS || state==State.FAILED; }
    public String feedback() { return feedback; }
    public PracticeSession flow() { return flow; }
    public double elapsed() { return clock; }
    public java.util.List<TuningTrace.Sample> trace() {
        if(state!=State.SUCCESS) throw new IllegalStateException("No successful trace");
        return trace.samples();
    }
    public java.util.List<TuningTrace.Position> tracePositions() {if(state!=State.SUCCESS) throw new IllegalStateException("No successful trace");return trace.positions();}
    private TuningTrace.Position tracePosition(Vector3f previous,Vector3f p) {
        if(flow==null) return new TuningTrace.Position(p.x,p.z,p.z+100,true);
        var projected=track.project(p,routeSegment);routeSegment=projected.segment();
        float route=projected.distance();boolean aligned=projected.separation()<=track.width*.5f+1;
        if(previousRoute==null) unwrappedRoute=route;
        else {float delta=route-previousRoute;if(track.closed) {if(delta< -track.length*.5f) delta+=track.length;else if(delta>track.length*.5f) delta-=track.length;}
            if(Math.abs(delta)>Math.max(5,previous.distance(p)*2+1)) aligned=false;
            unwrappedRoute+=delta;
        }
        previousRoute=route;return new TuningTrace.Position(p.x,p.z,unwrappedRoute,aligned);
    }
    public PracticeSession.Spawn spawn() { return flow!=null?flow.spawn():new PracticeSession.Spawn(new Vector3f(-65,0,-100),Vector3f.UNIT_Z.clone()); }
    public Measurement measurement() {
        if(state!=State.SUCCESS) throw new IllegalStateException("No successful measurement");
        return new Measurement(value,entry,exit,observed>0?(float)(throttleTotal/observed):0,observed>0?(float)(brakeTotal/observed):0,observed>0?(float)Math.sqrt(steerTotal/observed):0,observations.summary());
    }
    public void update(float dt,Vector3f previous,Vector3f p,float speed,float throttle,float brake,float steer) {
        update(dt,previous,p,speed,throttle,brake,steer,null);
    }
    public void update(float dt,Vector3f previous,Vector3f p,float speed,float throttle,float brake,float steer,VehicleObservation observation) {
        if(complete() || dt==0) return;
        if(!Float.isFinite(dt) || dt<0 || dt>.25f || !Vector3f.isValidVector(previous) || !Vector3f.isValidVector(p) || !Float.isFinite(speed) || speed<0 || speed>200
            || !Float.isFinite(throttle) || Math.abs(throttle)>1 || !Float.isFinite(brake) || brake<0 || brake>1 || !Float.isFinite(steer) || Math.abs(steer)>1) { fail("试车观测异常，请从固定起点重试。");return; }
        clock+=dt;
        if(clock>120 || previous.distance(p)>55 || p.y< -3 || p.y>8) { fail("超时或车辆离开有效范围，请重试本组。");return; }
        double measuredSeconds=dt;
        if(flow!=null) {
            double measuredBefore=flow.gates==0?0:flow.elapsed;
            flow.controls(throttle);flow.update(dt,previous,p,speed,brake);feedback=flow.feedback;
            if(flow.state==PracticeSession.State.FAILED) { fail(flow.feedback);return; }
            if(flow.gates>0) { measuring=true;state=State.RUNNING; }
            measuredSeconds=flow.elapsed-measuredBefore;
            if(flow.state==PracticeSession.State.SUCCESS) {
                value=flow.elapsed;entry=flow.entrySpeed;exit=flow.exitSpeed;state=State.SUCCESS;
            }
        } else {
            if(Math.abs(p.x+65)>4 || p.z< -105 || p.z>60 || p.z<previous.z-.05f) { fail("离开直线试车道或倒车，请重试本组。");return; }
            if(exercise==Exercise.ACCELERATION) {
                if(!measuring && throttle>.1f) {
                    if(speed>.5f) { fail("加速测量必须从静止开始。");return; }
                    measuring=true;state=State.RUNNING;startTime=clock-dt;
                }
                if(measuring) {
                    if(throttle<.95f || brake>.05f || Math.abs(steer)>.08f) { fail("加速测量需全油门直行，不制动；本次不收录。");return; }
                    float target=60/3.6f;
                    if(speed>=target) { value=clock-dt+dt*Math.max(0,Math.min(1,(target-previousSpeed)/Math.max(.0001f,speed-previousSpeed)))-startTime;exit=60;state=State.SUCCESS;feedback="加速测量完成。"; }
                    else feedback="全油门直行，达到 60 km/h 后自动收录。";
                }
            } else {
                if(!entered && previous.z< -18 && p.z>=-18) entered=true;
                if(!measuring && brake>.1f) {
                    if(!entered || speed*3.6f<68 || speed*3.6f>72 || brake<.95f || throttle>.05f || Math.abs(steer)>.08f) { fail("测速门后以 68–72 km/h 松油门并全制动；本次不收录。");return; }
                    measuring=true;state=State.RUNNING;brakeZ=p.z;entry=speed*3.6f;
                }
                if(measuring) {
                    if(brake<.95f || throttle>.05f || Math.abs(steer)>.08f) { fail("请持续按住 SPACE 全制动并保持直线；本次不收录。");return; }
                    stopped=speed<.5f?stopped+dt:0;
                    if(stopped>=.35f) { value=Math.max(0,p.z-brakeZ);if(value<=0) { fail("缺少可用制动距离，请重试。");return; }exit=speed*3.6f;state=State.SUCCESS;feedback="制动测量完成。"; }
                } else if(p.z>20) { fail("已越过制动测量区域，请重试本组。");return; }
            }
        }
        if(measuring) {
            observed+=measuredSeconds;
            throttleTotal+=throttle*measuredSeconds;
            brakeTotal+=brake*measuredSeconds;
            steerTotal+=(double)steer*steer*measuredSeconds;
            observations.add((float)measuredSeconds,observation);
            // Trace timestamps and positions describe actual frame ends, including the final readout.
            trace.update(dt,speed,throttle,brake,steer,state==State.SUCCESS,tracePosition(previous,p));
        }
        previousSpeed=speed;
    }
    private void fail(String message) { state=State.FAILED;feedback=message; }
}
