package com.f1car.model;

import java.util.*;

/** Observes real ordered lap counters. It never moves vehicles or creates lap progress. */
public final class ActivityTrial {
    public static final double LIMIT=1200;
    public record Observation(LapProgress progress,float distance,int recoveries,double penalty) { }
    private final ActivityRun run;
    private final ActivityCard.Stage stage;
    private final String id=UUID.randomUUID().toString();
    private final List<Double> laps=new ArrayList<>();
    private final RaceChallenge chase;
    private ActivityRun.Result result;
    private ActivityRun checkpoint;
    private double elapsed;
    private int completed;
    private String feedback;
    private final ActivityObjective objective;
    private final ActivitySectionTrial sectionTrial;
    private final double deadline;
    private boolean objectiveTimeExpired;
    private int contacts;private boolean contactActive;private double contactClearSince=-1;
    private long statusTick=-1;private int statusContacts=-1,statusCompleted=-1;private boolean statusComplete;private List<ActivityObjective.Condition> cachedStatus=List.of();
    public ActivityTrial(ActivityRun run) {
        if(run==null || run.complete()) throw new IllegalArgumentException("No pending activity stage");this.run=run;stage=run.card().stages().get(run.stage());objective=run.card().objective(run.stage());sectionTrial=objective==null || objective.section()==null?null:new ActivitySectionTrial(objective.section(),stage.laps());feedback=stage.goal();chase=stage.kind()==ActivityCard.Kind.CHASE?new RaceChallenge(RaceChallenge.Kind.CHASE):null;
        double stageLimit=chase==null?LIMIT:RaceChallenge.LIMIT;
        deadline=objective==null?stageLimit:Math.min(stageLimit,objective.finishDeadline());
    }
    public ActivityRun run() { return run; }
    public ActivityCard.Stage stage() { return stage; }
    public boolean complete() { return result!=null; }
    public ActivityRun.Result result() { if(result==null) throw new IllegalStateException("Activity still running");return result; }
    public ActivityRun checkpoint() {
        if(result==null) return run;
        if(checkpoint==null) checkpoint=run.accept(result);
        return checkpoint;
    }
    public String feedback() { return feedback; }
    public double elapsed() { return elapsed; }
    public double deadline() {return deadline;}
    public List<Double> laps() { return List.copyOf(laps); }
    public boolean observesContacts() {return objective!=null && objective.contactLimit()!=null;}
    public boolean observesSection() {return sectionTrial!=null;}
    private ActivityObjective.Evidence objectiveEvidence() {return objective==null?null:new ActivityObjective.Evidence(contacts,sectionTrial==null?null:sectionTrial.times());}
    public List<ActivityObjective.Condition> objectiveStatus() {
        if(objective==null) return List.of();long tick=(long)(elapsed*10);
        if(tick!=statusTick || contacts!=statusContacts || completed!=statusCompleted || complete()!=statusComplete) {
            cachedStatus=objective.assess(elapsed,laps,objectiveEvidence(),complete() && laps.size()==stage.laps());
            if(objectiveTimeExpired) {
                var rows=new ArrayList<>(cachedStatus);
                var time=rows.get(0);
                rows.set(0,new ActivityObjective.Condition(time.name(),ActivityObjective.Status.FAILED,time.detail()));
                cachedStatus=List.copyOf(rows);
            }
            if(sectionTrial!=null && (sectionTrial.active() || sectionTrial.failed())) {var rows=new ArrayList<>(cachedStatus);var previous=rows.get(rows.size()-1);String detail=sectionTrial.active()?String.format(Locale.ROOT,"当前 %.3f / %.3f 秒 · 已记 %d 圈",sectionTrial.current(elapsed),objective.section().limit(),sectionTrial.completed()):previous.detail();rows.set(rows.size()-1,new ActivityObjective.Condition(previous.name(),sectionTrial.failed()?ActivityObjective.Status.FAILED:previous.status(),detail));cachedStatus=List.copyOf(rows);}
            statusTick=tick;statusContacts=contacts;statusCompleted=completed;statusComplete=complete();
        }return cachedStatus;
    }
    public void abort(String message) { if(!complete()) end(false,0,0,message,elapsed); }
    public void update(double time,List<Observation> observed,boolean contact) {
        if(complete()) return;
        if(!Double.isFinite(time) || time<elapsed || time>LIMIT+.5 || observed==null || observed.size()!=stage.kind().drivers || observed.stream().anyMatch(o->o==null || o.progress()==null || !Float.isFinite(o.distance()) || o.distance()<0 || o.distance()>1000000 || o.recoveries()<0 || !Double.isFinite(o.penalty()) || o.penalty()<0 || o.penalty()>LIMIT)) { abort("活动观测异常，请重试本段。");return; }
        elapsed=Math.min(time,deadline);
        if(objective!=null && objective.contactLimit()!=null) {if(contact) {if(!contactActive) {contacts++;contactActive=true;}contactClearSince=-1;}else if(contactActive) {if(contactClearSince<0) contactClearSince=time;if(time-contactClearSince>=.25) contactActive=false;}}
        for(var o:observed) {
            var p=o.progress();if(p.completedLaps()<0 || p.completedLaps()>stage.laps() || p.finished() && (p.completedLaps()!=stage.laps() || !Double.isFinite(p.finishTime()) || p.finishTime()<=0 || p.finishTime()>time || p.finishTime()>LIMIT || !Double.isFinite(p.lastLap()) || p.lastLap()<=0 || p.lastLap()>p.finishTime())) { abort("活动圈数或时间记录异常。");return; }
            if(o.recoveries()>0 || o.penalty()>0) { abort("本段存在车辆恢复，请重试本段。");return; }
        }
        var player=observed.get(0).progress();
        if(player.finished() && player.finishTime()>deadline) {expire();return;}
        if(!player.validLap() || player.completedLaps()>0 && !player.lastLapValid()) { abort("本段路线无效，请完整通过有序路门后重试。");return; }
        if(player.completedLaps()<completed || player.completedLaps()>completed+1) { abort("活动进度发生跳变，请重试。");return; }
        if(sectionTrial!=null) {sectionTrial.update(player,elapsed);if(sectionTrial.failed()) {abort("路门分段超时或观测不连续，请完整重试本项。");return;}}
        if(player.completedLaps()>completed) {
            if(!Double.isFinite(player.lastLap()) || player.lastLap()<=0 || player.lastLap()>elapsed || laps.stream().mapToDouble(Double::doubleValue).sum()+player.lastLap()>elapsed+.05) { abort("活动圈速与计时不一致。");return; }
            completed=player.completedLaps();laps.add(player.lastLap());
        }
        if(objective!=null && !player.finished() && objective.failed(elapsed,laps,contacts)) {var failed=objective.assess(elapsed,laps,objectiveEvidence(),false).stream().filter(row->row.status()==ActivityObjective.Status.FAILED).findFirst().orElseThrow();abort("复合条件未满足："+failed.name()+" · "+failed.detail());return;}
        if(chase!=null) {
            chase.update(elapsed,player,observed.get(1).progress(),contact);
            if(chase.complete()) { boolean success=chase.state()==RaceChallenge.State.SUCCESS;end(success,success?player.finishTime():0,success?1:0,chase.feedback(),success?player.finishTime():elapsed);return; }
        } else if(player.finished()) {
            switch(stage.kind()) {
                case LAP -> { var target=stage.target();boolean success=target==null || player.lastLap()<=target.bronze()+.000001;end(success,player.lastLap(),0,target==null?run.card().partsRule()==null?"原厂有效圈已收录。":"限定部件有效圈已收录。":"作者参考 · "+target.medal(player.lastLap())+" · "+PracticeSession.format(player.lastLap()),player.finishTime()); }
                case CONSISTENCY -> { var consistency=new LapConsistency();laps.forEach(t->consistency.complete(t,true));end(consistency.success(),consistency.spread(),0,consistency.success()?"三个有效圈满足稳定目标。":"已完成三圈，极差未达到稳定目标；可重试本段。",player.finishTime()); }
                case RACE -> { var finishes=new ArrayList<EventSeries.Finish>();for(int i=0;i<observed.size();i++) { var o=observed.get(i);var p=o.progress();finishes.add(new EventSeries.Finish(i,p.finished(),p.finished()?p.finishTime():-1,0,o.distance())); }var ordered=EventSeries.orderedRace(finishes);int place=1;while(ordered.get(place-1).driver()!=0) place++;boolean success=place<=stage.targetPlace();end(success,player.finishTime(),place,"有效完赛 P"+place+"；目标前 "+stage.targetPlace()+" 名。",player.finishTime()); }
                default -> throw new IllegalStateException("Unexpected activity kind");
            }return;
        }
        if(time>=deadline) expire();
    }
    private void expire() {
        if(objective!=null && deadline==objective.finishDeadline()) {
            objectiveTimeExpired=true;
            abort("复合条件未满足：总用时已到，请在时限内完成活动。");
        } else abort("本段时间已到，请重试。");
    }
    private void end(boolean success,double value,int place,String detail,double duration) {
        elapsed=Math.min(LIMIT,duration);ActivityObjective.Evidence evidence=objectiveEvidence();
        if(objective!=null && success) {var failed=objective.assess(elapsed,laps,evidence,true).stream().filter(row->row.status()!=ActivityObjective.Status.PASSED).findFirst();if(failed.isPresent()) {success=false;detail="复合条件未满足："+failed.get().name()+" · "+failed.get().detail();}else detail="原目标与全部复合条件已满足。";}
        feedback=detail;result=new ActivityRun.Result(id,run.stage(),success,elapsed,value,place,laps,detail,evidence);
    }
}
