package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.UUID;

/** Player-selected commissions. Data is measured from the existing vehicle, never fabricated. */
public final class WorkshopSession {
    public enum Kind {
        PARK("W01","精准停车交接","技师 · 许岚","以 36–60 km/h 通过黄色门，用 SPACE 制动，在绿色内框停稳 2 秒。"),
        SOFT("W02","轻载区段送件","试车工程师 · 林澈","通过全部连续弯路门，强减速累计不超过 2 秒；提前松油门控制节奏。"),
        CUP("W03","杯架稳定委托","后勤工程师 · 阿岑","安装 X05 杯架，110 秒内完成有效整圈，观察虚拟洒水量。");
        public final String id,title,engineer,brief;
        Kind(String id,String title,String engineer,String brief) { this.id=id;this.title=title;this.engineer=engineer;this.brief=brief; }
    }
    public enum State { READY,RUNNING,SUCCESS,FAILED }
    private final String id=UUID.randomUUID().toString();
    private final Kind kind;
    private final TrackLayout track;
    private final Tuning tuning;
    private final WorkshopReport.Conditions conditions;
    private final PracticeSession flow;
    private final CupChallenge cup;
    private final LapProgress lap;
    private final DecelerationMeter meter=new DecelerationMeter();
    private State state=State.READY;
    private String feedback;
    private double clock,entry=-1,error=-1,hold;
    private boolean entered;
    private WorkshopReport report;
    public WorkshopSession(Kind kind,TrackLayout track,String physics,Tuning tuning) {
        this(kind,track,physics,tuning,ControlIdentity.KEYBOARD);
    }
    public WorkshopSession(Kind kind,TrackLayout track,String physics,Tuning tuning,String controls) {
        this.kind=java.util.Objects.requireNonNull(kind);this.track=java.util.Objects.requireNonNull(track);this.tuning=java.util.Objects.requireNonNull(tuning);conditions=WorkshopReport.Conditions.of(kind,track,physics,controls);
        flow=kind==Kind.SOFT?new PracticeSession(PracticeSession.Lesson.FLOW,track):null;cup=kind==Kind.CUP?new CupChallenge():null;lap=kind==Kind.CUP?new LapProgress(track,1):null;feedback=kind.brief;
    }
    public Kind kind() { return kind; }
    public State state() { return state; }
    public boolean complete() { return state==State.SUCCESS || state==State.FAILED; }
    public String feedback() { return feedback; }
    public double elapsed() { return clock; }
    public double strongTime() { return meter.strongTime(); }
    public double hold() { return hold; }
    public CupChallenge cup() { return cup; }
    public PracticeSession flow() { return flow; }
    public LapProgress lap() { return lap; }
    public PracticeSession.Spawn spawn() { if(flow!=null) return flow.spawn();if(kind==Kind.PARK) return new PracticeSession.Spawn(new Vector3f(-65,0,-100),Vector3f.UNIT_Z.clone());var s=track.sample(-18);return new PracticeSession.Spawn(s.position(),s.tangent()); }
    public void update(float dt,Vector3f previous,Vector3f position,Vector3f velocity) {
        if(complete() || dt==0) return;
        if(!Float.isFinite(dt) || dt<0 || dt>.25f || !Vector3f.isValidVector(previous) || !Vector3f.isValidVector(position) || !Vector3f.isValidVector(velocity) || velocity.length()>200) { fail("观测异常，请从固定起点重试。");return; }
        double from=clock;
        clock+=dt;if(clock>120 || previous.distance(position)>55 || position.y< -3 || position.y>8) { fail("超时或车辆离开有效范围，请重试。");return; }
        float speed=(float)Math.hypot(velocity.x,velocity.z);
        if(kind==Kind.PARK) {
            if(Math.abs(position.x+65)>8 || position.z< -120 || position.z>45 || position.z<previous.z-.1f) { fail("离开交接路线或驶过交接区，请重试。");return; }
            if(!entered && previous.z< -18 && position.z>=-18) {
                entered=true;entry=speed*3.6;
                if(entry<36 || entry>60) { fail("测速门入口需为 36–60 km/h；本次不计完成。");return; }
            }
            if(speed>.8f) state=State.RUNNING;
            boolean inside=Math.abs(position.x+65)<=4 && Math.abs(position.z-28)<=10;
            hold=entered && inside && speed<.4f?hold+dt:0;
            if(hold>=2) { error=Math.hypot(position.x+65,position.z-28);succeed("已在交接区停稳，停车偏差已记录。"); }
            else feedback=entered?String.format(java.util.Locale.ROOT,"交接区内停稳 %.1f / 2.0 s；速度需低于 1.4 km/h。",hold):"先以 36–60 km/h 通过黄色测速门，再进入绿色交接区。";
        } else if(flow!=null) {
            flow.update(dt,previous,position,speed,0);if(flow.gates>0) meter.update(dt,speed);state=State.RUNNING;
            feedback=String.format(java.util.Locale.ROOT,"路门 %d/%d · 强减速 %.2f / 2.00 s；碰撞也可能消耗预算。",flow.gates,flow.courseGateCount(),meter.strongTime());
            if(meter.strongTime()>2+1e-6) fail("强减速时间超过 2 秒。提前松油门；速度骤降也可能来自碰撞，请结合线路观察。");
            else if(flow.complete()) { if(flow.state==PracticeSession.State.SUCCESS) succeed("在强减速预算内通过全部路门。");else fail(flow.feedback); }
        } else {
            state=State.RUNNING;
            lap.updateTimed(previous,position,from,clock,CupChallenge.FINISH_DEADLINE);
            cup.updateThrough(dt,velocity,lap.finished()?lap.finishTime():clock);
            clock=cup.time;
            feedback=String.format(java.util.Locale.ROOT,"有效圈 %d/1 · 虚拟洒水 %.1f%% · 限时 110 s",lap.completedLaps(),cup.spill);
            if(!cup.observationsComplete()) fail("杯架观测中断，请从起点重试。");
            else if(lap.finished()) { if(lap.lastLapValid() && !cup.timedOut()) succeed("完成有效整圈，虚拟洒水量已记录。");else fail("本圈无效或超时，请从起点重试。"); }
            else if(cup.timedOut()) fail("杯架委托限时 110 秒，请保持合法路线并重试。");
        }
    }
    private void fail(String reason) { state=State.FAILED;feedback=reason; }
    private void succeed(String reason) { state=State.SUCCESS;feedback=reason; }
    public WorkshopReport report() {
        if(!complete()) throw new IllegalStateException("Workshop attempt is still active");
        if(report==null) {
            var metrics=new WorkshopReport.Metrics(entry,error,hold,meter.strongTime(),meter.peak(),cup==null?-1:cup.spill);var grade=state==State.SUCCESS?WorkshopReport.gradeFor(kind,metrics):WorkshopReport.Grade.RETRY;
            report=new WorkshopReport(1,id,System.currentTimeMillis(),kind,conditions,tuning,grade,clock,metrics,feedback);
        }
        return report;
    }
}
