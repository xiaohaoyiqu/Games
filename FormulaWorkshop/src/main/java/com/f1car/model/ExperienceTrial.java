package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.*;

/** Passive ordered-gate timing and 20 Hz recording over measured vehicle motion. */
public final class ExperienceTrial {
    public enum State { READY,RUNNING,SUCCESS,FAILED }
    public record Observation(float x,float y,float z,float vx,float vy,float vz,float yaw,float throttle,float brake,float steer) {
        public static Observation of(Vector3f p,Vector3f velocity,float yaw,float throttle,float brake,float steer) { return new Observation(p.x,p.y,p.z,velocity.x,velocity.y,velocity.z,yaw,throttle,brake,steer); }
        public Vector3f position() { return new Vector3f(x,y,z); }
        public float speed() { return (float)Math.hypot(vx,vz); }
        public boolean valid() {return validMeasurements() && validHeight(y);}
        private boolean validMeasurements() { return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                && Float.isFinite(vx) && Float.isFinite(vy) && Float.isFinite(vz)
                && Math.abs(x)<=1000 && Math.abs(z)<=1000 && speed()<=200
                && Float.isFinite(yaw) && Math.abs(yaw)<=Math.PI+.001 && Float.isFinite(throttle) && throttle>=-1 && throttle<=1
                && Float.isFinite(brake) && brake>=0 && brake<=1 && Float.isFinite(steer) && Math.abs(steer)<=1; }
        private TelemetryLap.Sample sample(double time,float distance) { return new TelemetryLap.Sample(time,distance,x,z,yaw,new TelemetryLap.Driving(speed(),throttle,brake,steer)); }
    }
    private final CircuitExperience.Course course;
    private final ExperienceRun.Conditions conditions;
    private final int livery;
    private final TrackLayout.Sample[] gates;
    private final List<Double> gateTimes=new ArrayList<>();
    private final List<TelemetryLap.Sample> samples=new ArrayList<>();
    private final Vector3f fromPosition=new Vector3f(),toPosition=new Vector3f(),roadSample=new Vector3f();
    private State state=State.READY;
    private Observation previous;
    private Vector3f timeoutPosition;
    private TrackLayout.Projection previousProjection;
    private double clock,nextSample=.05;
    private float progress,greatest;
    private String feedback="固定原厂性能与起始速度；通过全部有序路门。";
    private ExperienceRun result;
    public ExperienceTrial(CircuitExperience.Course course,String physics,String controls,int livery) {
        this.course=Objects.requireNonNull(course);conditions=ExperienceRun.Conditions.of(course,physics,controls);if(livery<0 || livery>4) throw new IllegalArgumentException("Invalid livery");this.livery=livery;
        gates=new TrackLayout.Sample[course.gates()+1];
        for(int i=0;i<gates.length;i++) gates[i]=course.gate(i);
    }
    public CircuitExperience.Course course() { return course; }
    public ExperienceRun.Conditions conditions() { return conditions; }
    public State state() { return state; }
    public boolean complete() { return state==State.SUCCESS || state==State.FAILED; }
    public double elapsed() { return clock; }
    public float progress() { return progress; }
    public int completedGates() { return gateTimes.size(); }
    public List<Double> gateTimes() { return List.copyOf(gateTimes); }
    public double gateTime(int index) { return gateTimes.get(index); }
    public int nextGate() { return Math.min(course.gates(),gateTimes.size()+1); }
    /** Copy a course gate without exposing the mutable vectors used to judge crossings. */
    public void gatePositionAndRight(int index, Vector3f position, Vector3f right) {
        var gate = gates[index];
        position.set(gate.position());
        right.set(gate.right());
    }
    public String feedback() { return feedback; }
    public Vector3f observedPosition() {
        if(timeoutPosition!=null) return timeoutPosition.clone();
        return previous==null?gates[0].position().clone():previous.position();
    }
    public void begin(Observation observation) {
        if(state!=State.READY) return;
        var spawn=gates[0];float angle=(float)Math.atan2(spawn.tangent().x,spawn.tangent().z);
        if(observation==null || !observation.valid() || observation.position().distance(spawn.position().add(0,.75f,0))>.15f || Math.abs(observation.speed()-CircuitExperience.ROLLING_SPEED)>.1f
            || Math.abs(Math.atan2(Math.sin(observation.yaw()-angle),Math.cos(observation.yaw()-angle)))>.02 || Math.abs(observation.vx()-spawn.tangent().x*CircuitExperience.ROLLING_SPEED)>.1f || Math.abs(observation.vz()-spawn.tangent().z*CircuitExperience.ROLLING_SPEED)>.1f || Math.abs(observation.vy())>.1f || observation.throttle()!=0 || observation.brake()!=0 || observation.steer()!=0)
            { fail("起始状态不符合本项目条件，请从固定起点重试。");return; }
        previous=observation;
        previousProjection=course.track().project(observation.position());
        samples.add(observation.sample(0,0));
        state=State.RUNNING;
    }
    public void update(float dt,Observation observation) {
        if(state!=State.RUNNING || dt==0) return;
        if(!Float.isFinite(dt) || dt<0 || dt>.1f || observation==null || !observation.validMeasurements()) { fail("观测不连续或异常，请重试。");return; }
        double frameEnd=clock+dt;
        if(frameEnd<=clock) { fail("观测时间未前进，请重试。");return; }
        var track=course.track();
        var a=fromPosition.set(previous.x(),previous.y(),previous.z());
        var b=toPosition.set(observation.x(),observation.y(),observation.z());
        float movement=a.distance(b);
        if(movement>200*dt+1) { fail("车辆位置跳变，本次不计成绩。");return; }
        int hint=previousProjection.segment();
        double end=Math.min(frameEnd,ExperienceRun.LIMIT);
        float through=frameEnd<=end?1:(float)((end-clock)/dt);
        TrackLayout.Projection projection=null;
        float finish=finishFraction(a,b,dt,movement);
        if(Float.isFinite(finish)) {
            var atFinish=track.project(interpolatePosition(a,b,finish),hint);
            // A nearby branch can cross the finish plane. Retain the usual progress check before shortening the observation.
            if(progress+progressDelta(atFinish)>=minimumGateProgress(course.gates())) {
                through=finish;
                end=clock+dt*finish;
                projection=atFinish;
            }
        }
        if(!validHeight(a.y*(1-through)+b.y*through)) {fail("观测不连续或异常，请重试。");return;}
        float limit=track.width*.5f+.6f;
        for(int i=1;i<=4;i++) {
            hint=track.findSegmentWithinDistance(interpolatePosition(a,b,i/4f*through),limit,hint);
            if(hint<0) {
                previous=observation;
                fail("离开有效道路，返回固定起点重试。");
                return;
            }
        }
        if(projection==null) projection=track.project(roadSample,hint);
        float delta=progressDelta(projection);
        if(Math.abs(delta)>movement*through*2+1) { previous=observation;fail("路线进度跳变，不能跳过路门。");return; }
        float nextProgress=progress+delta;greatest=Math.max(greatest,nextProgress);
        if(nextProgress< -1 || greatest-nextProgress>3) { previous=observation;fail("倒退超出本次路线容差，请重试。");return; }
        float lastFraction=-1;
        boolean finished=false;
        while(gateTimes.size()<course.gates()) {
            int index=gateTimes.size()+1;var gate=gates[index];
            float before=roadSample.set(a).subtractLocal(gate.position()).dot(gate.tangent());
            float after=roadSample.set(b).subtractLocal(gate.position()).dot(gate.tangent());
            if(before>=0 || after<0 || before==after || nextProgress<minimumGateProgress(index)) break;
            float fraction=-before/(after-before);
            double time=clock+dt*fraction;
            if(time>end) break;
            if(fraction<=lastFraction || Math.abs(interpolatePosition(a,b,fraction).subtractLocal(gate.position()).dot(gate.right()))>track.width*.5f+.5f) { previous=observation;fail("未按顺序通过有效路门，请重试。");return; }
            if(!gateTimes.isEmpty() && time<=gateTimes.get(gateTimes.size()-1)) { fail("路门计时异常，请重试。");return; }
            gateTimes.add(time);lastFraction=fraction;
            if(gateTimes.size()==course.gates()) { end=time;finished=true;break; }
        }
        if(!finished && nextProgress>course.length()*nextGate()/course.gates()+track.length/36) { previous=observation;fail("漏过下一路门，请从固定起点重试。");return; }
        if(!finished && end>=ExperienceRun.LIMIT) {
            timeoutPosition=interpolatePosition(a,b,through).clone();
            clock=ExperienceRun.LIMIT;
            fail("超过三分钟，本次不计成绩。");
            return;
        }
        var from=previous.sample(clock,Math.max(0,Math.min(course.length(),progress)));
        var to=observation.sample(frameEnd,Math.max(0,Math.min(course.length(),nextProgress)));
        if(finished) {
            var endpoint=from.interpolate(to,end);
            to=new TelemetryLap.Sample(end,course.length(),endpoint.x(),endpoint.z(),endpoint.yaw(),endpoint.driving());
        }
        while(nextSample<end) { samples.add(from.interpolate(to,nextSample));nextSample+=.05; }
        if(finished) {
            if(Math.abs(samples.get(samples.size()-1).time()-end)<1e-7) samples.set(samples.size()-1,to);else samples.add(to);
            clock=end;progress=course.length();previous=observation;state=State.SUCCESS;feedback="按顺序完成路线，独立成绩与操作记录已生成。";
            result=new ExperienceRun(1,UUID.randomUUID().toString(),System.currentTimeMillis(),conditions,livery,clock,gateTimes,samples);
        } else {
            clock=end;
            progress=nextProgress;
            previous=observation;
            previousProjection=projection;
            feedback="已通过路门 "+completedGates()+" / "+course.gates()+"，继续沿道路前进。";
        }
    }

    private float finishFraction(Vector3f a,Vector3f b,float dt,float movement) {
        int last=course.gates();
        if(progress+(movement*2+1)<minimumGateProgress(last)) return Float.NaN;
        float finish=LapProgress.crossingFraction(a,b,gates[last],course.track().width);
        if(!Float.isFinite(finish) || clock+dt*finish>ExperienceRun.LIMIT) return Float.NaN;
        float previousFraction=-1;
        for(int index=nextGate();index<last;index++) {
            float fraction=LapProgress.crossingFraction(a,b,gates[index],course.track().width);
            if(!(fraction>previousFraction && fraction<finish)) return Float.NaN;
            previousFraction=fraction;
        }
        return finish;
    }

    private float minimumGateProgress(int index) {return course.length()*index/course.gates()-course.track().length/36;}

    private float progressDelta(TrackLayout.Projection projection) {
        float length=course.track().length;
        float delta=projection.distance()-previousProjection.distance();
        if(delta>length*.5f) delta-=length;
        if(delta< -length*.5f) delta+=length;
        return delta;
    }

    private Vector3f interpolatePosition(Vector3f a,Vector3f b,float fraction) {
        float before=1-fraction;
        return roadSample.set(a.x*before+b.x*fraction,a.y*before+b.y*fraction,a.z*before+b.z*fraction);
    }

    private static boolean validHeight(float y) {return y>=-3 && y<=8;}

    public ExperienceRun result() { if(state!=State.SUCCESS) throw new IllegalStateException("No valid completed experience run");return result; }
    public void fail(String reason) { if(!complete()) { state=State.FAILED;feedback=Objects.requireNonNull(reason); } }
}
