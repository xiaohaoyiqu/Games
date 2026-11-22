package com.f1car.model;

import com.google.gson.Gson;
import java.util.List;

/** Local, bounded observations attached to one frozen successful attempt. Not an exchange format. */
public record TuningTrace(int version,String report,int index,String binding,List<Sample> samples,List<Position> positions) {
    public static final int MAX_SAMPLES=2402;
    public static final double PERIOD=.05,CONNECTED_GAP=.075;
    public record Position(float x,float z,float route,int aligned) {
        public Position(float x,float z,float route,boolean aligned) {this(x,z,route,aligned?1:0);}
        public Position {if(!Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>2000 || Math.abs(z)>2000 || !Float.isFinite(route) || route< -10000 || route>100000 || aligned<0 || aligned>1) throw new IllegalArgumentException("Invalid trace position");}
    }
    public TuningTrace(int version,String report,int index,String binding,List<Sample> samples) {this(version,report,index,binding,samples,null);}
    public record Sample(double seconds,float speed,float throttle,float brake,float steer) {
        public Sample {
            if(!Double.isFinite(seconds) || seconds<=0 || seconds>120 || !Float.isFinite(speed) || speed<0 || speed>200
                || !Float.isFinite(throttle) || Math.abs(throttle)>1 || !Float.isFinite(brake) || brake<0 || brake>1
                || !Float.isFinite(steer) || Math.abs(steer)>1) throw new IllegalArgumentException("Invalid trial trace sample");
        }
    }
    private record Binding(String id,long createdAt,TuningExperiment.Conditions conditions,String a,String b,
                           List<TuningExperiment.Slot> order,int index,TuningExperiment.Run run) {}
    private record ExactBinding(String id,long createdAt,TuningExperiment.Conditions conditions,Tuning a,Tuning b,
                                List<TuningExperiment.Slot> order,int index,TuningExperiment.Run run) {}
    public TuningTrace {
        if(version<1 || version>3 || version==1 && positions!=null || version==2 && positions==null || positions!=null && (samples==null || positions.size()!=samples.size()) || !ActivityCard.identity(report) || index<0 || index>=6 || binding==null || !binding.matches("[a-f0-9]{64}")
            || samples==null || samples.isEmpty() || samples.size()>MAX_SAMPLES) throw new IllegalArgumentException("Invalid trial trace");
        samples=List.copyOf(samples);double previous=0;
        if(positions!=null) positions=List.copyOf(positions);
        for(int i=0;i<samples.size();i++) {
            double time=samples.get(i).seconds();
            if(time<=previous || i>0 && i<samples.size()-1 && time-previous<PERIOD-1e-8) throw new IllegalArgumentException("Invalid trial trace timeline");
            previous=time;
        }
    }
    public static TuningTrace capture(TuningExperiment.Snapshot report,int index,List<Sample> samples) {
        return new TuningTrace(1,report.id(),index,key(report,index),samples);
    }
    public static TuningTrace capture(TuningExperiment.Snapshot report,int index,List<Sample> samples,List<Position> positions) {
        return new TuningTrace(2,report.id(),index,key(report,index),samples,positions);
    }
    public static TuningTrace captureExact(TuningExperiment.Snapshot report,int index,List<Sample> samples,List<Position> positions) {
        return new TuningTrace(3,report.id(),index,exactKey(report,index),samples,positions);
    }
    public boolean matches(TuningExperiment.Snapshot snapshot,int run) {
        return snapshot!=null && run==index && report.equals(snapshot.id()) && run<snapshot.runs().size() && binding.equals(version==3?exactKey(snapshot,run):key(snapshot,run));
    }
    public double duration() {return samples.get(samples.size()-1).seconds();}
    public double maximumGap() {
        double result=0;for(int i=1;i<samples.size();i++) result=Math.max(result,samples.get(i).seconds()-samples.get(i-1).seconds());return result;
    }
    private static String key(TuningExperiment.Snapshot report,int index) {
        if(index<0 || index>=report.runs().size()) throw new IllegalArgumentException("Missing trace attempt");
        return PartsCatalog.hash(new Gson().toJson(new Binding(report.id(),report.createdAt(),report.conditions(),report.a().performanceKey(),report.b().performanceKey(),report.order(),index,report.runs().get(index))));
    }
    private static String exactKey(TuningExperiment.Snapshot report,int index) {
        if(index<0 || index>=report.runs().size()) throw new IllegalArgumentException("Missing trace attempt");
        return PartsCatalog.hash(new Gson().toJson(new ExactBinding(report.id(),report.createdAt(),report.conditions(),report.a().withLivery(0),report.b().withLivery(0),report.order(),index,report.runs().get(index))));
    }
}
