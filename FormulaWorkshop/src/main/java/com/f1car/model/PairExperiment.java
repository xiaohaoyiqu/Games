package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Four explicitly driven conditions, three randomized rounds. Descriptive observations only. */
public final class PairExperiment {
    public enum Condition {
        BASELINE("原草稿"),FIRST("只改第一项"),SECOND("只改第二项"),COMBINED("同时改变");
        public final String title;Condition(String title) {this.title=title;}
    }
    public record Plan(VehicleSetupPreview.Source model,String modelIdentity,FittedParts equipment,Field first,int firstPercent,Field second,int secondPercent,int livery) {
        public Plan {
            if(model==null || modelIdentity==null || !modelIdentity.matches("[a-f0-9]{64}") || equipment==null || first==null || second==null || first==second || livery<0 || livery>4
                || !equipment.compatible(model.vehicle(),model.physics())) throw new IllegalArgumentException("Invalid four-condition source");
            var baseline=equipment.build().tuning(livery);
            if(firstPercent==first.percent(baseline) || secondPercent==second.percent(baseline)) throw new IllegalArgumentException("Both selected fields must change");
            for(var field:List.of(first,second)) {int value=field==first?firstPercent:secondPercent;var range=equipment.range(field);if(value<range.min() || value>range.max()) throw new IllegalArgumentException("Four-condition setting outside frozen equipment range");}
            for(var condition:Condition.values()) if(!equipment.accepts(settings(equipment,first,firstPercent,second,secondPercent,livery,condition))) throw new IllegalArgumentException("Four-condition setup outside frozen source");
        }
        public static Plan capture(PartsRangePreview range,Field first,int firstPercent,Field second,int secondPercent,String modelIdentity) {
            var model=range.source();return new Plan(model,modelIdentity,FittedParts.freeze(range.build(),range.catalog(),model.vehicle(),model.physics()),first,firstPercent,second,secondPercent,range.garage().livery());
        }
        public Tuning tuning(Condition condition) {return settings(equipment,first,firstPercent,second,secondPercent,livery,Objects.requireNonNull(condition));}
        private static Tuning settings(FittedParts source,Field first,int x,Field second,int y,int livery,Condition condition) {
            var value=source.build().tuning(livery);
            if(condition==Condition.FIRST || condition==Condition.COMBINED) value=value.withPercent(first,x);
            if(condition==Condition.SECOND || condition==Condition.COMBINED) value=value.withPercent(second,y);
            return value;
        }
    }
    /** Original frame observations, attached by the containing report and successful run. */
    public record Trace(List<TuningTrace.Sample> samples,List<TuningTrace.Position> positions) {
        public Trace {
            if(samples==null || samples.isEmpty() || samples.size()>TuningTrace.MAX_SAMPLES || positions!=null && positions.size()!=samples.size()) throw new IllegalArgumentException("Invalid four-condition trace");
            samples=List.copyOf(samples);positions=positions==null?null:List.copyOf(positions);double previous=0;
            for(int i=0;i<samples.size();i++) {double seconds=samples.get(i).seconds();if(seconds<=previous || i>0 && i<samples.size()-1 && seconds-previous<TuningTrace.PERIOD-1e-8) throw new IllegalArgumentException("Invalid four-condition timeline");previous=seconds;}
        }
    }
    public record Run(Condition condition,TuningTrial.Measurement measurement,long recordedAt,long attempt,Trace trace) {
        public Run {if(condition==null || measurement==null || recordedAt<0 || attempt<1 || trace==null) throw new IllegalArgumentException("Invalid four-condition run");}
    }
    public record Group(Condition condition,int count,Double median,Double spread,Double entry) {}
    public record Contrast(String label,double delta) {}
    public record Snapshot(int version,String id,long createdAt,TuningExperiment.Conditions conditions,Plan plan,long seed,List<Condition> order,List<Run> runs,int failedAttempts,long lastAttempt) {
        public Snapshot {
            if(version!=1 || id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || createdAt<0 || conditions==null || !PartsCatalog.text(conditions.trackName(),80) || plan==null
                || !plan.model().physics().equals(conditions.physics()) || order==null || order.size()!=12 || runs==null || runs.size()>12 || failedAttempts<0 || failedAttempts>1_000_000 || lastAttempt<0 || lastAttempt<(long)runs.size()+failedAttempts) throw new IllegalArgumentException("Invalid four-condition report");
            order=List.copyOf(order);runs=List.copyOf(runs);
            for(int round=0;round<3;round++) if(!EnumSet.copyOf(order.subList(round*4,round*4+4)).equals(EnumSet.allOf(Condition.class))) throw new IllegalArgumentException("Each round must contain all four conditions");
            var attempts=new HashSet<Long>();for(int i=0;i<runs.size();i++) {var run=runs.get(i);if(run.condition()!=order.get(i) || run.attempt()>lastAttempt || !attempts.add(run.attempt())) throw new IllegalArgumentException("Four-condition run identity differs");}
        }
        public boolean complete() {return runs.size()==12;}
        public Tuning tuning(int index) {return plan.tuning(order.get(index));}
        private List<TuningTrial.Measurement> measurements(Condition condition) {return runs.stream().filter(run->run.condition()==condition).map(Run::measurement).toList();}
        public List<Group> groups() {
            var values=new ArrayList<Group>();for(var condition:Condition.values()) {var data=measurements(condition);var stats=data.stream().mapToDouble(TuningTrial.Measurement::value).summaryStatistics();values.add(new Group(condition,data.size(),complete()?median(data.stream().mapToDouble(TuningTrial.Measurement::value).toArray()):null,complete()?stats.getMax()-stats.getMin():null,complete()?median(data.stream().mapToDouble(TuningTrial.Measurement::entry).toArray()):null));}return List.copyOf(values);
        }
        public boolean comparableEntry() {
            if(!complete()) return false;if(conditions.exercise()==TuningTrial.Exercise.ACCELERATION) return true;
            var entries=groups().stream().mapToDouble(Group::entry).summaryStatistics();var all=runs.stream().mapToDouble(run->run.measurement().entry()).summaryStatistics();return entries.getMax()-entries.getMin()<=2 && all.getMax()-all.getMin()<=6;
        }
        public List<Contrast> contrasts() {
            if(!complete()) return List.of();var groups=groups();double base=groups.get(0).median(),first=groups.get(1).median(),second=groups.get(2).median(),both=groups.get(3).median();
            return List.of(new Contrast("第一项：只改第一项 − 原草稿",first-base),new Contrast("第一项：同时改变 − 只改第二项",both-second),new Contrast("第二项：只改第二项 − 原草稿",second-base),new Contrast("第二项：同时改变 − 只改第一项",both-first));
        }
        private static double median(double[] values) {Arrays.sort(values);return values[values.length/2];}
    }
    private Snapshot snapshot;
    public PairExperiment(TrackLayout track,String controls,TuningTrial.Exercise exercise,Plan plan,long seed) {
        snapshot=new Snapshot(1,UUID.randomUUID().toString(),System.currentTimeMillis(),new TuningExperiment.Conditions(track.id,track.name,track.geometryKey(),plan.model().physics(),ControlIdentity.require(controls),exercise),plan,seed,schedule(seed),List.of(),0,0);
    }
    private PairExperiment(Snapshot snapshot) {this.snapshot=Objects.requireNonNull(snapshot);}
    public static PairExperiment resume(Snapshot snapshot) {return new PairExperiment(snapshot);}
    public Snapshot snapshot() {return snapshot;}
    public static List<Condition> schedule(long seed) {
        var random=new SplittableRandom(seed);var order=new ArrayList<Condition>();for(int round=0;round<3;round++) {var block=new ArrayList<>(List.of(Condition.values()));for(int i=3;i>0;i--) Collections.swap(block,i,random.nextInt(i+1));order.addAll(block);}return List.copyOf(order);
    }
    public void record(int index,TuningTrial trial) {
        if(trial==null || index<0 || index>=12 || index>snapshot.runs().size() || !trial.complete() || trial.exercise()!=snapshot.conditions().exercise() || !trial.track().id.equals(snapshot.conditions().trackId()) || !trial.track().geometryKey().equals(snapshot.conditions().geometry()) || !trial.tuning().equals(snapshot.tuning(index))) throw new IllegalArgumentException("Trial differs from frozen four-condition plan");
        if(snapshot.lastAttempt()==Long.MAX_VALUE) throw new IllegalStateException("Four-condition attempt limit reached");long attempt=snapshot.lastAttempt()+1;var runs=new ArrayList<>(snapshot.runs());int failed=snapshot.failedAttempts();
        if(trial.state()==TuningTrial.State.SUCCESS) {var run=new Run(snapshot.order().get(index),trial.measurement(),System.currentTimeMillis(),attempt,new Trace(trial.trace(),trial.tracePositions()));if(index<runs.size()) runs.set(index,run);else runs.add(run);}else failed=Math.min(1_000_000,failed+1);
        snapshot=new Snapshot(1,snapshot.id(),snapshot.createdAt(),snapshot.conditions(),snapshot.plan(),snapshot.seed(),snapshot.order(),runs,failed,attempt);
    }
}
