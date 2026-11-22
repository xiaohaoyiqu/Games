package com.f1car.model;

import java.util.*;

/** One flying lap per real car, after an untimed approach to the start line. */
public final class QualifyingSession {
    public static final double LIMIT=240;
    private final EventSeries.Qualifying[] times=new EventSeries.Qualifying[EventSeries.DRIVERS];
    private List<Integer> gridOrder;
    private double clock;

    public static boolean validTime(int laps,boolean finished,boolean valid,double lap,double setAt) {
        return laps==1 && finished && valid && Double.isFinite(lap) && lap>0 && setAt>=lap && setAt<=LIMIT;
    }

    /** Callers pass a positive classified lap or the no-time value. */
    public static int compareTimes(double lapA,double setAtA,int driverA,double lapB,double setAtB,int driverB) {
        if ((lapA>0)!=(lapB>0)) return lapA>0?-1:1;
        if (lapA>0) {
            int time=Long.compare(Math.round(lapA*1000),Math.round(lapB*1000));
            if (time!=0) return time;
            int achieved=Double.compare(setAtA,setAtB);
            if (achieved!=0) return achieved;
        }
        return Integer.compare(driverA,driverB);
    }

    public void retire(int driver) {
        if(driver<0 || driver>=times.length) throw new IllegalArgumentException("Invalid driver");
        if(times[driver]==null) times[driver]=EventSeries.Qualifying.noTime(driver);
    }
    public void update(double elapsed,List<LapProgress> progress) {
        if(!Double.isFinite(elapsed) || elapsed<clock || progress==null || progress.size()!=times.length)
            throw new IllegalArgumentException("Invalid qualifying observation");
        for(int driver=0;driver<times.length;driver++)
            if(progress.get(driver)==null) throw new IllegalArgumentException("Invalid qualifying observation");
        clock=elapsed;
        for(int driver=0;driver<times.length;driver++) {
            if(times[driver]!=null) continue;
            var lap=progress.get(driver);
            if(lap.finished()) {
                if(lap.finishTime()<=clock && validTime(lap.completedLaps(),true,lap.lastLapValid(),lap.lastLap(),lap.finishTime())) {
                    times[driver]=new EventSeries.Qualifying(driver,lap.lastLap(),lap.finishTime());
                    gridOrder=null;
                } else retire(driver);
            } else if(clock>=LIMIT) retire(driver);
        }
    }
    public boolean complete() {
        for(var time:times) if(time==null) return false;
        return true;
    }
    public double remaining() { return Math.max(0,LIMIT-clock); }
    public EventSeries.Qualifying time(int driver) { return times[driver]; }
    /** Pending and invalid attempts both lack a grid time. time(driver) retains their individual status. */
    public List<Integer> gridOrder() {
        if(gridOrder==null) {
            var rows=new ArrayList<EventSeries.Qualifying>(times.length);
            for(int driver=0;driver<times.length;driver++)
                rows.add(times[driver]==null?EventSeries.Qualifying.noTime(driver):times[driver]);
            gridOrder=EventSeries.orderedQualifying(rows).stream().map(EventSeries.Qualifying::driver).toList();
        }
        return gridOrder;
    }
    public List<EventSeries.Qualifying> results() {
        if(!complete()) throw new IllegalStateException("Qualifying still running");
        return gridOrder().stream().map(this::time).toList();
    }
}
