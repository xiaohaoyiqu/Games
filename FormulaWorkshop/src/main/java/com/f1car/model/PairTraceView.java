package com.f1car.model;

import java.util.*;

/** Frozen original runs, each attached to its actual complete snapshot. No aggregation or resampling. */
public final class PairTraceView {
    public enum Axis {TIME,ROUTE}
    public record RunSource(PairExperiment.Snapshot report,int index) {
        public RunSource {Objects.requireNonNull(report);if(index<0 || index>=report.runs().size()) throw new IllegalArgumentException("Missing original run");}
        public PairExperiment.Run run() {return report.runs().get(index);}
    }
    public record Point(double axis,float value,boolean connected) {
        public Point {if(!Double.isFinite(axis) || !Float.isFinite(value)) throw new IllegalArgumentException("Invalid trace point");}
    }
    public record Series(int index,PairExperiment.Condition condition,long attempt,int samples,double firstSeconds,double lastSeconds,double maximumGap,boolean positionsAvailable,List<List<Point>> channels) {
        public Series {channels=channels.stream().map(List::copyOf).toList();}
    }
    private final PairExperiment.Snapshot report;
    private final int selected,reference;
    private final Axis axis;
    private final List<Series> series;
    private final List<RunSource> sources;
    private final List<PairTraceCursor.RouteIndex> routeIndices;
    private final boolean crossSnapshot;
    private final boolean routeAvailable;
    private final PairTraceComparison comparison;
    private final double minimum,maximum;
    private final float speedMaximum;
    public PairTraceView(PairExperiment.Snapshot report,int selected,int reference,Axis axis) {
        this(new RunSource(report,selected),reference==-1?null:new RunSource(report,reference),axis);
    }
    public PairTraceView(RunSource selected,RunSource reference,Axis axis) {
        this(selected,reference,axis,null);
    }
    public static PairTraceView compare(PairTraceComparison comparison,Axis axis) {
        Objects.requireNonNull(comparison);
        return new PairTraceView(comparison.pick(0).original(),comparison.pick(1).original(),axis,comparison);
    }
    private PairTraceView(RunSource selected,RunSource reference,Axis axis,PairTraceComparison comparison) {
        Objects.requireNonNull(selected);Objects.requireNonNull(axis);
        if(comparison==null && reference!=null && (!PairTrialRevision.sameSource(selected.report(),reference.report()) || selected.run().attempt()==reference.run().attempt())) throw new IllegalArgumentException("Choose distinct original attempts from the same frozen experiment");
        if(comparison!=null && axis==Axis.ROUTE && !comparison.routeAvailable()) throw new IllegalArgumentException(comparison.routeRestriction());
        this.comparison=comparison;
        this.report=selected.report();this.selected=selected.index();this.reference=reference==null?-1:reference.index();this.axis=axis;
        sources=reference==null?List.of(selected):List.of(selected,reference);crossSnapshot=reference!=null && !selected.report().equals(reference.report());
        routeIndices=axis==Axis.ROUTE?sources.stream().map(source->new PairTraceCursor.RouteIndex(source.run().trace())).toList():List.of();
        var rows=new ArrayList<Series>();float speed=0;
        for(var source:sources) {rows.add(build(source.report(),source.index(),axis));for(var sample:source.run().trace().samples()) speed=Math.max(speed,sample.speed()*3.6f);}
        series=List.copyOf(rows);routeAvailable=series.stream().allMatch(Series::positionsAvailable) && (comparison==null || comparison.routeAvailable());speedMaximum=Math.max(20,(float)Math.ceil(speed/20)*20);
        if(axis==Axis.TIME) {minimum=0;maximum=Math.max(.05,series.stream().mapToDouble(Series::lastSeconds).max().orElse(.05));}
        else if(!routeAvailable) {minimum=0;maximum=1;}
        else {var values=series.stream().flatMap(row->row.channels().get(0).stream()).mapToDouble(Point::axis).summaryStatistics();minimum=values.getMin();maximum=Math.max(minimum+1,values.getMax());}
    }
    public PairExperiment.Snapshot report() {return report;}public int selected() {return selected;}public int reference() {return reference;}public Axis axis() {return axis;}
    public List<RunSource> sources() {return sources;}public RunSource source(int slot) {return sources.get(slot);}public boolean crossSnapshot() {return crossSnapshot;}
    public PairTraceComparison comparison() {return comparison;}
    public String routeRestriction() {return comparison==null?"所选组或对照组缺少可用原位置；保留时间轴，不补造位置。":comparison.routeRestriction();}
    public PairTraceView withAxis(Axis next) {return new PairTraceView(source(0),sources.size()>1?source(1):null,next,comparison);}
    public List<Series> series() {return series;}public boolean routeAvailable() {return routeAvailable;}public double minimum() {return minimum;}public double maximum() {return maximum;}public float speedMaximum() {return speedMaximum;}
    PairTraceCursor.RouteIndex routeIndex(int slot) {return routeIndices.get(slot);}
    static boolean connectedInTime(double before,double after) {return after-before<=TuningTrace.CONNECTED_GAP+1e-9;}
    private static Series build(PairExperiment.Snapshot report,int index,Axis axis) {
        var run=report.runs().get(index);var trace=run.trace();var channels=new ArrayList<List<Point>>();for(int i=0;i<4;i++) channels.add(new ArrayList<>());
        boolean positions=trace.positions()!=null && trace.positions().stream().anyMatch(p->p.aligned()==1);double previousTime=-1,previousRoute=Double.NEGATIVE_INFINITY,gap=0;boolean previousValid=false;
        for(int i=0;i<trace.samples().size();i++) {
            var sample=trace.samples().get(i);var position=trace.positions()==null?null:trace.positions().get(i);boolean valid=axis==Axis.TIME || position!=null && position.aligned()==1;
            if(i>0) gap=Math.max(gap,sample.seconds()-previousTime);
            double x=axis==Axis.TIME?sample.seconds():position==null?0:position.route();boolean connected=i>0 && previousValid
                    && connectedInTime(previousTime,sample.seconds()) && (axis==Axis.TIME || x>previousRoute);
            if(valid) {
                channels.get(0).add(new Point(x,sample.speed()*3.6f,connected));
                channels.get(1).add(new Point(x,sample.throttle()*100,connected));
                channels.get(2).add(new Point(x,sample.brake()*100,connected));
                channels.get(3).add(new Point(x,sample.steer()*100,connected));
            }
            previousTime=sample.seconds();previousRoute=x;previousValid=valid;
        }
        return new Series(index,run.condition(),run.attempt(),trace.samples().size(),trace.samples().get(0).seconds(),trace.samples().get(trace.samples().size()-1).seconds(),gap,positions,channels);
    }
}
