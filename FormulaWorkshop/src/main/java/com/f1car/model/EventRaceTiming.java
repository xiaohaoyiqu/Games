package com.f1car.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Timing checkpoints for one local start. Gaps refer to a shared gate on the same race lap. */
public final class EventRaceTiming {
    public record Car(int driver,boolean finished,double elapsed,double penalty,float distance) {
        public double total() {return finished?elapsed+penalty:Double.POSITIVE_INFINITY;}
    }
    public record GateGap(int checkpoint,double seconds) {}
    public record Row(Car car,GateGap gap) {}

    private final List<Integer> grid;
    private final double[][] crossingTimes;
    private final int[] checkpoints=new int[EventSeries.DRIVERS];
    private final LapProgress.Crossing[] observedTail=new LapProgress.Crossing[EventSeries.DRIVERS];
    private final GateGap[][] commonGates=new GateGap[EventSeries.DRIVERS][EventSeries.DRIVERS];
    private List<Row> settled=List.of();

    public EventRaceTiming(List<Integer> grid,int laps) {
        this.grid=List.copyOf(grid);
        if(grid.size()!=EventSeries.DRIVERS || grid.stream().distinct().count()!=EventSeries.DRIVERS
                || grid.stream().anyMatch(driver->driver<0 || driver>=EventSeries.DRIVERS)
                || laps!=1 && laps!=3 && laps!=5) throw new IllegalArgumentException("Invalid local event grid or lap count");
        crossingTimes=new double[EventSeries.DRIVERS][laps*LapProgress.RACE_GATES+1];
        for(var times:crossingTimes) Arrays.fill(times,Double.NaN);
        Arrays.fill(checkpoints,-1);
    }

    public void observe(int driver,LapProgress.Crossing crossing) {
        if(!settled.isEmpty() || crossing==null || driver<0 || driver>=EventSeries.DRIVERS) return;
        int gates=LapProgress.RACE_GATES;
        var times=crossingTimes[driver];
        if(crossing.gate()<0 || crossing.gate()>=gates || crossing.completedLaps()<0 || crossing.completedLaps()>(times.length-1)/gates
                || crossing.firstStart()!=(crossing.completedLaps()==0 && crossing.gate()==0)
                || !Double.isFinite(crossing.time()) || crossing.time()<0) return;
        int mark=crossing.completedLaps()*gates+crossing.gate();
        int last=checkpoints[driver];
        if(mark>=times.length || mark<=last || last>=0 && crossing.time()<times[last]) return;
        times[mark]=crossing.time();
        checkpoints[driver]=mark;
        for(int other=0;other<crossingTimes.length;other++) {
            if(other==driver) continue;
            double otherTime=crossingTimes[other][mark];
            if(Double.isNaN(otherTime)) continue;
            // Each driver's accepted checkpoint numbers increase, so this is a newer common gate.
            commonGates[driver][other]=new GateGap(mark,crossing.time()-otherTime);
            commonGates[other][driver]=new GateGap(mark,otherTime-crossing.time());
        }
    }

    public void observeCrossings(int driver,List<LapProgress.Crossing> crossings) {
        for(var crossing:crossings) observe(driver,crossing);
    }

    /** Lap progress retains its latest immutable batch until it crosses another gate. */
    public void observeProgress(int driver,LapProgress progress) {
        if(!settled.isEmpty() || driver<0 || driver>=EventSeries.DRIVERS) return;
        var tail=progress.latestCrossing();
        if(tail==null || observedTail[driver]==tail) return;
        observeCrossings(driver,progress.latestCrossings());
        observedTail[driver]=tail;
    }

    public List<Row> order(List<Car> cars,boolean onGrid) {
        if(!settled.isEmpty()) return settled;
        if(cars.isEmpty()) return List.of();
        var order=new ArrayList<>(cars);
        if(onGrid) {
            order.sort(Comparator.comparingInt(car->grid.indexOf(car.driver())));
            return order.stream().map(car->new Row(car,null)).toList();
        }
        order.sort(EventRaceTiming::compare);
        int leader=order.get(0).driver();
        return order.stream().map(car->new Row(car,car.finished() || car.driver()==leader?null:commonGates[car.driver()][leader])).toList();
    }

    /** The published or pending result is already in classification order. */
    public void settle(EventSeries.Result result) {
        if(!settled.isEmpty()) return;
        settled=result.race().stream().map(row->new Row(new Car(row.driver(),row.finished(),row.elapsed(),row.penalty(),row.distance()),null)).toList();
    }

    public List<Row> settled() {return settled;}

    private static int compare(Car a,Car b) {
        if(a.finished()!=b.finished()) return a.finished()?-1:1;
        if(a.finished()) return EventSeries.compareFinishers(a.total(),a.driver(),b.total(),b.driver());
        int result=Float.compare(b.distance(),a.distance());
        return result!=0?result:Integer.compare(a.driver(),b.driver());
    }
}
