package com.f1car.model;

import java.util.*;

/** Reads original samples only. A click never interpolates across time, distance or missing data. */
public final class PairTraceCursor {
    public enum Status {
        ORIGINAL("原样本"),NEAREST("连续段内最近原样本"),GAP("此处没有连续原样本"),
        OUTSIDE("超出原采样范围"),NO_POSITION("没有可对齐的原位置"),AMBIGUOUS("此位置对应多个原时刻");
        public final String title;Status(String title) {this.title=title;}
    }
    public record Reading(PairTraceView.RunSource source,Status status,int sampleIndex,TuningTrace.Sample sample,TuningTrace.Position position,Double axis) {
        public Reading {
            Objects.requireNonNull(source);Objects.requireNonNull(status);
            boolean available=status==Status.ORIGINAL || status==Status.NEAREST;
            if(available!=(sample!=null) || available && (sampleIndex<0 || sampleIndex>=source.run().trace().samples().size())
                || !available && (sampleIndex!=-1 || position!=null || axis!=null) || axis!=null && !Double.isFinite(axis)) throw new IllegalArgumentException("Invalid original-sample reading");
            if(available) {var trace=source.run().trace();if(!sample.equals(trace.samples().get(sampleIndex)) || !Objects.equals(position,trace.positions()==null?null:trace.positions().get(sampleIndex))) throw new IllegalArgumentException("Reading differs from original sample");}
        }
        public int runIndex() {return source.index();}
        public boolean available() {return sample!=null;}
    }
    public record Selection(PairTraceView view,Double requested,List<Reading> readings) {
        public Selection {Objects.requireNonNull(view);readings=List.copyOf(readings);if(requested!=null && !Double.isFinite(requested) || readings.size()!=view.sources().size()) throw new IllegalArgumentException("Invalid cursor axis or sources");for(int slot=0;slot<readings.size();slot++) if(!view.source(slot).equals(readings.get(slot).source())) throw new IllegalArgumentException("Cursor source differs");}
    }
    public static Selection at(PairTraceView view,double requested) {
        Objects.requireNonNull(view);if(!Double.isFinite(requested)) throw new IllegalArgumentException("Invalid cursor axis");
        var selected=find(view,0,requested);
        return new Selection(view,requested,view.sources().size()==1?List.of(selected):List.of(selected,find(view,1,requested)));
    }
    /** Explicit sample selection preserves its own time, including when its route position is missing. */
    public static Selection atSample(PairTraceView view,int sampleIndex) {
        Objects.requireNonNull(view);var trace=view.source(0).run().trace();
        if(sampleIndex<0 || sampleIndex>=trace.samples().size()) throw new IllegalArgumentException("Missing original sample");
        var position=trace.positions()==null?null:trace.positions().get(sampleIndex);
        Double axis=view.axis()==PairTraceView.Axis.TIME?Double.valueOf(trace.samples().get(sampleIndex).seconds()):position!=null && position.aligned()==1?Double.valueOf(position.route()):null;
        var readings=new ArrayList<Reading>();readings.add(original(view,view.source(0),sampleIndex,Status.ORIGINAL));
        if(view.sources().size()>1) readings.add(axis==null?missing(view.source(1),Status.NO_POSITION):find(view,1,axis));
        return new Selection(view,axis,readings);
    }

    private static Reading find(PairTraceView view,int slot,double requested) {
        var source=view.source(slot);
        if(view.axis()==PairTraceView.Axis.ROUTE) return view.routeIndex(slot).at(view,source,requested);
        var samples=source.run().trace().samples();
        int low=0,high=samples.size();
        while(low<high) {
            int middle=(low+high)/2;
            if(samples.get(middle).seconds()<requested) low=middle+1;
            else high=middle;
        }
        if(low==samples.size()) return missing(source,Status.OUTSIDE);
        double after=samples.get(low).seconds();
        if(after==requested) return original(view,source,low,Status.ORIGINAL);
        if(low==0) return missing(source,Status.OUTSIDE);
        double before=samples.get(low-1).seconds();
        if(!PairTraceView.connectedInTime(before,after)) return missing(source,Status.GAP);
        return original(view,source,nearest(low,before,after,requested),Status.NEAREST);
    }

    private static int nearest(int right,double leftAxis,double rightAxis,double requested) {
        return requested<=leftAxis+(rightAxis-leftAxis)*.5?right-1:right;
    }

    /** Disjoint runs of original samples, each with strictly increasing route position. */
    static final class RouteIndex {
        private record Span(int first,int last,double minimum,double maximum) {
            Span(List<TuningTrace.Position> positions,int first,int last) {
                this(first,last,positions.get(first).route(),positions.get(last).route());
            }
        }
        private final List<Span> spans;
        private final List<TuningTrace.Position> positions;
        private final double minimum,maximum;

        RouteIndex(PairExperiment.Trace trace) {
            positions=trace.positions();
            spans=routeSpans(trace);
            minimum=spans.stream().mapToDouble(Span::minimum).min().orElse(Double.POSITIVE_INFINITY);
            maximum=spans.stream().mapToDouble(Span::maximum).max().orElse(Double.NEGATIVE_INFINITY);
        }

        private static List<Span> routeSpans(PairExperiment.Trace trace) {
            var positions=trace.positions();
            if(positions==null) return List.of();
            var samples=trace.samples();
            var result=new ArrayList<Span>();
            int first=-1;
            for(int index=0;index<positions.size();index++) {
                var position=positions.get(index);
                boolean aligned=position.aligned()==1;
                if(first>=0 && (!aligned || position.route()<=positions.get(index-1).route()
                        || !PairTraceView.connectedInTime(samples.get(index-1).seconds(),samples.get(index).seconds()))) {
                    result.add(new Span(positions,first,index-1));
                    first=-1;
                }
                if(aligned && first<0) first=index;
            }
            if(first>=0) result.add(new Span(positions,first,positions.size()-1));
            return List.copyOf(result);
        }

        Reading at(PairTraceView view,PairTraceView.RunSource source,double requested) {
            if(spans.isEmpty()) return missing(source,Status.NO_POSITION);
            if(requested<minimum || requested>maximum) return missing(source,Status.OUTSIDE);
            int chosen=-1;
            Status status=Status.GAP;
            for(var span:spans) {
                if(requested<span.minimum() || requested>span.maximum()) continue;
                int index=firstAtOrAbove(span,requested);
                if(index>span.last()) continue;
                double right=positions.get(index).route();
                boolean exact=Double.compare(right,requested)==0;
                if(!exact) {
                    if(index==span.first()) continue;
                    double left=positions.get(index-1).route();
                    if(requested<=left || requested>=right) continue;
                    index=nearest(index,left,right,requested);
                }
                // Each original index belongs to one span, so a second hit is a separate visit.
                if(chosen>=0) return missing(source,Status.AMBIGUOUS);
                chosen=index;
                status=exact?Status.ORIGINAL:Status.NEAREST;
            }
            return chosen<0?missing(source,Status.GAP):original(view,source,chosen,status);
        }

        private int firstAtOrAbove(Span span,double requested) {
            int low=span.first(),high=span.last()+1;
            while(low<high) {
                int middle=(low+high)/2;
                if(Double.compare(positions.get(middle).route(),requested)<0) low=middle+1;
                else high=middle;
            }
            return low;
        }
    }
    private static Reading original(PairTraceView view,PairTraceView.RunSource source,int sampleIndex,Status status) {
        var trace=source.run().trace();var sample=trace.samples().get(sampleIndex);var position=trace.positions()==null?null:trace.positions().get(sampleIndex);
        Double axis=view.axis()==PairTraceView.Axis.TIME?Double.valueOf(sample.seconds()):position!=null && position.aligned()==1?Double.valueOf(position.route()):null;
        return new Reading(source,status,sampleIndex,sample,position,axis);
    }
    private static Reading missing(PairTraceView.RunSource source,Status status) {return new Reading(source,status,-1,null,null,null);}
    private PairTraceCursor() {}
}
