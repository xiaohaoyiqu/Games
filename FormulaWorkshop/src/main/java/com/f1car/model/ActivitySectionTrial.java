package com.f1car.model;

import java.util.*;

/** Observes actual ordered gate events; snapshots and projected distance never create evidence. */
public final class ActivitySectionTrial {
    private final ActivitySection section;private final int laps;
    private final List<Double> times=new ArrayList<>();private long sequence;private int lastGate=-1;private double clock,start=-1;private boolean failed;
    public ActivitySectionTrial(ActivitySection section,int laps) {this.section=Objects.requireNonNull(section);if(laps<1 || laps>5) throw new IllegalArgumentException("Invalid section laps");this.laps=laps;}
    public boolean failed() {return failed;}
    public boolean active() {return start>=0;}
    public int completed() {return times.size();}
    public List<Double> times() {return List.copyOf(times);}
    public double current(double now) {return start<0?0:Math.max(0,now-start);}
    public void update(LapProgress progress,double time) {
        if(failed) return;
        if(progress==null || progress.gateCount()!=ActivitySection.GATES || !Double.isFinite(time) || time<clock || time>ActivityTrial.LIMIT) {failed=true;return;}
        double previousClock=clock;
        clock=time;
        var latest=progress.latestCrossing();
        if(latest!=null && latest.sequence()<sequence) {failed=true;return;}
        if(latest!=null && latest.sequence()>sequence) {
            for(var event:progress.latestCrossings()) {
                if(event.sequence()<=sequence) continue;
                observe(event,previousClock,progress.finished());
                if(failed) return;
            }
        }
        if(start>=0 && time-start>section.limit()+.000001) failed=true;
    }

    private void observe(LapProgress.Crossing event,double previousClock,boolean finished) {
        if(event.sequence()!=sequence+1 || event.gate()!=(lastGate+1)%ActivitySection.GATES || event.time()>clock
                || event.time()<previousClock || !event.valid() || event.completedLaps()<0 || event.completedLaps()>laps) {
            failed=true;
            return;
        }
        sequence=event.sequence();
        lastGate=event.gate();
        if(event.firstStart()) {
            if(sequence!=1 || event.gate()!=0 || event.completedLaps()!=0) {failed=true;return;}
            if(section.startGate()==0) start=event.time();
            return;
        }
        int lap=event.gate()==0?event.completedLaps()-1:event.completedLaps();
        if(event.gate()==section.startGate() && event.gate()!=0) {
            if(lap!=times.size() || start>=0) {failed=true;return;}
            start=event.time();
        }
        if(event.gate()==section.endGate()%ActivitySection.GATES) {
            if(lap!=times.size() || start<0 || event.time()<=start) {failed=true;return;}
            double duration=event.time()-start;
            times.add(duration);
            start=-1;
            if(duration>section.limit()+.000001) failed=true;
        }
        if(event.gate()==0 && !finished && section.startGate()==0) start=event.time();
    }
}
