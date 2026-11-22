package com.f1car.model;

import java.util.*;

/** Six controlled runs with frozen setups. Differences are descriptive, never a causal claim. */
public final class TuningExperiment {
    public enum Slot { A,B }
    public enum Assessment { NEED_RUNS,ENTRY_MISMATCH,SAME_SETUP,SCATTER,OBSERVED_DIFFERENCE }
    public record Conditions(String trackId,String trackName,String geometry,String physics,String controls,TuningTrial.Exercise exercise) {
        public Conditions {
            if(trackId==null || !trackId.matches("[a-z0-9][a-z0-9-]{0,63}") || trackName==null || trackName.isBlank() || trackName.length()>80 || geometry==null || !geometry.matches("[a-f0-9]{64}") || physics==null || physics.isBlank() || physics.length()>120 || !ControlIdentity.valid(controls) || exercise==null) throw new IllegalArgumentException("Invalid setup experiment conditions");
        }
    }
    public record Run(Slot slot,TuningTrial.Measurement measurement,long recordedAt,long attempt) {
        public Run { Objects.requireNonNull(slot);Objects.requireNonNull(measurement);if(recordedAt<0 || attempt<1) throw new IllegalArgumentException("Invalid setup attempt"); }
    }
    public record Snapshot(int version,String id,long createdAt,Conditions conditions,Tuning a,Tuning b,List<Slot> order,List<Run> runs,int failedAttempts) {
        public Snapshot {
            if(version!=1 || id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || createdAt<0 || conditions==null || a==null || b==null || order==null || order.size()!=6 || runs==null || runs.size()>6 || failedAttempts<0 || failedAttempts>1_000_000) throw new IllegalArgumentException("Invalid setup experiment");
            order=List.copyOf(order);runs=List.copyOf(runs);
            if(order.stream().filter(slot->slot==Slot.A).count()!=3 || order.get(0)==order.get(1) || order.get(2)==order.get(3) || order.get(4)==order.get(5)) throw new IllegalArgumentException("Unbalanced setup order");
            if(runs.stream().map(Run::attempt).distinct().count()!=runs.size()) throw new IllegalArgumentException("Duplicate setup attempt");
            for(int i=0;i<runs.size();i++) if(runs.get(i).slot()!=order.get(i)) throw new IllegalArgumentException("Setup run order mismatch");
        }
        public Tuning tuning(int index) { return order.get(index)==Slot.A?a:b; }
        public boolean complete() { return runs.size()==6; }
        public List<TuningTrial.Measurement> measurements(Slot slot) { return runs.stream().filter(run->run.slot()==slot).map(Run::measurement).toList(); }
        public double median(Slot slot) { return medianOf(measurements(slot).stream().mapToDouble(TuningTrial.Measurement::value).toArray()); }
        public double spread(Slot slot) { var values=measurements(slot).stream().mapToDouble(TuningTrial.Measurement::value).summaryStatistics();return values.getCount()==0?0:values.getMax()-values.getMin(); }
        public double entry(Slot slot) { return medianOf(measurements(slot).stream().mapToDouble(TuningTrial.Measurement::entry).toArray()); }
        public double delta() { return median(Slot.B)-median(Slot.A); }
        public int changedSettings() { return (a.power()!=b.power()?1:0)+(a.brakes()!=b.brakes()?1:0)+(a.suspension()!=b.suspension()?1:0)+(a.wing()!=b.wing()?1:0)+(a.brakeBias()!=b.brakeBias()?1:0)
            +(a.frontSpring()!=b.frontSpring()?1:0)+(a.rearSpring()!=b.rearSpring()?1:0)+(a.frontDamping()!=b.frontDamping()?1:0)+(a.rearDamping()!=b.rearDamping()?1:0); }
        public Assessment assessment() {
            if(!complete()) return Assessment.NEED_RUNS;
            if(conditions.exercise()!=TuningTrial.Exercise.ACCELERATION && (Math.abs(entry(Slot.A)-entry(Slot.B))>2 || runs.stream().mapToDouble(run->run.measurement().entry()).max().orElse(0)-runs.stream().mapToDouble(run->run.measurement().entry()).min().orElse(0)>6)) return Assessment.ENTRY_MISMATCH;
            if(changedSettings()==0) return Assessment.SAME_SETUP;
            return Math.abs(delta())<=Math.max(.001,Math.max(spread(Slot.A),spread(Slot.B)))?Assessment.SCATTER:Assessment.OBSERVED_DIFFERENCE;
        }
        private static double medianOf(double[] values) { if(values.length==0) return Double.NaN;Arrays.sort(values);return (values[(values.length-1)/2]+values[values.length/2])*.5; }
    }
    private Snapshot snapshot;
    private long attempts;
    public TuningExperiment(TrackLayout track,String physics,TuningTrial.Exercise exercise,Tuning a,Tuning b,boolean reverse) {
        this(track,physics,exercise,a,b,reverse,ControlIdentity.KEYBOARD);
    }
    public TuningExperiment(TrackLayout track,String physics,TuningTrial.Exercise exercise,Tuning a,Tuning b,boolean reverse,String controls) {
        var order=reverse?List.of(Slot.B,Slot.A,Slot.A,Slot.B,Slot.B,Slot.A):List.of(Slot.A,Slot.B,Slot.B,Slot.A,Slot.A,Slot.B);
        snapshot=new Snapshot(1,UUID.randomUUID().toString(),System.currentTimeMillis(),new Conditions(track.id,track.name,track.geometryKey(),physics,ControlIdentity.require(controls),exercise),a,b,order,List.of(),0);
    }
    public Snapshot snapshot() { return snapshot; }
    public void record(int index,TuningTrial trial) {
        if(index<0 || index>snapshot.runs().size() || index>=6 || !trial.complete() || trial.exercise()!=snapshot.conditions().exercise() || !trial.track().id.equals(snapshot.conditions().trackId()) || !trial.track().geometryKey().equals(snapshot.conditions().geometry()) || !trial.tuning().equals(snapshot.tuning(index))) throw new IllegalArgumentException("Setup trial does not match frozen plan");
        var runs=new ArrayList<>(snapshot.runs());int failed=snapshot.failedAttempts();attempts++;
        if(trial.state()==TuningTrial.State.SUCCESS) { var run=new Run(snapshot.order().get(index),trial.measurement(),System.currentTimeMillis(),attempts);if(index<runs.size()) runs.set(index,run);else runs.add(run); }
        else failed=Math.min(1_000_000,failed+1);
        snapshot=new Snapshot(1,snapshot.id(),snapshot.createdAt(),snapshot.conditions(),snapshot.a(),snapshot.b(),snapshot.order(),runs,failed);
    }
}
