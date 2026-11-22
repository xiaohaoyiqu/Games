package com.f1car.model;

import com.f1car.model.CircuitSectionPractice.GateSpeeds;
import com.f1car.model.CircuitSectionPractice.InterruptionProgress;
import com.f1car.model.CircuitSectionPractice.TimedGate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** In-memory practice references for one interval in a frozen equipment comparison. */
public final class EquipmentSectionPracticeSession {
    private static final int MAX_CONDITIONS=16;
    private static final int RECENT_RUNS=3;

    public enum Car {
        STOCK("原厂"), A("A 圈参数"), B("B 圈参数");

        private final String label;

        Car(String label) {this.label=label;}
        public String label() {return label;}
    }

    public record Attempt(long number,List<TimedGate> gates) {
        public Attempt {
            gates=List.copyOf(gates);
            if(gates.size()<2) throw new IllegalArgumentException("A completed attempt needs two timing gates");
        }

        public double seconds() {return gates.get(gates.size()-1).elapsed();}

        public GateSpeeds speeds() {return new GateSpeeds(gates.get(0).speedMps(),gates.get(gates.size()-1).speedMps());}
    }

    public record SplitComparison(int number,float from,float to,double aSeconds,double bSeconds,double cumulativeDelta) {
        public double splitDelta() {return aSeconds-bSeconds;}
    }

    public record SplitReading(long attemptNumber,double seconds,GateSpeeds speeds) {}

    public record SubsectionReference(int number,float from,float to,CircuitSectionPractice.Start start,String controls,
                                      SplitReading a,SplitReading b) {
        public SubsectionReference {
            Objects.requireNonNull(start);
            ControlIdentity.require(controls);
            Objects.requireNonNull(a);
            Objects.requireNonNull(b);
        }

        public boolean matchesLaunch(CircuitSectionPractice.Start actualStart,String actualControls) {
            return start.equals(actualStart) && controls.equals(actualControls);
        }
    }

    /** Freezes the two selected original drives at the exact gates used for a subsequent subsection practice. */
    public static SubsectionReference subsectionReference(SplitComparison split,Attempt a,Attempt b,
                                                          CircuitSectionPractice.Start start,String controls) {
        var aReading=splitReading(a,split);
        var bReading=splitReading(b,split);
        if(aReading.seconds()!=split.aSeconds() || bReading.seconds()!=split.bSeconds())
            throw new IllegalArgumentException("Selected practice times differ from the comparison");
        return new SubsectionReference(split.number(),split.from(),split.to(),start,controls,aReading,bReading);
    }

    private static SplitReading splitReading(Attempt attempt,SplitComparison split) {
        var gates=attempt.gates();
        int number=split.number();
        if(number<1 || number>=gates.size()) throw new IllegalArgumentException("Selected practice gates differ from the comparison");
        var entry=gates.get(number-1);
        var exit=gates.get(number);
        if(entry.distance()!=split.from() || exit.distance()!=split.to())
            throw new IllegalArgumentException("Selected practice gates differ from the comparison");
        return new SplitReading(attempt.number(),exit.elapsed()-entry.elapsed(),new GateSpeeds(entry.speedMps(),exit.speedMps()));
    }

    /** Compares original gate times for two completed drives from the same interval and launch group. */
    public static List<SplitComparison> compare(Attempt a,Attempt b) {
        var aGates=a.gates();
        var bGates=b.gates();
        if(aGates.size()!=bGates.size() || aGates.get(0).distance()!=bGates.get(0).distance())
            throw new IllegalArgumentException("Practice timing gates differ");
        var rows=new ArrayList<SplitComparison>(aGates.size()-1);
        for(int index=1;index<aGates.size();index++) {
            var nextA=aGates.get(index);
            var nextB=bGates.get(index);
            if(nextA.distance()!=nextB.distance()) throw new IllegalArgumentException("Practice timing gates differ");
            var previousA=aGates.get(index-1);
            var previousB=bGates.get(index-1);
            rows.add(new SplitComparison(index,previousA.distance(),nextA.distance(),nextA.elapsed()-previousA.elapsed(),
                    nextB.elapsed()-previousB.elapsed(),nextA.elapsed()-nextB.elapsed()));
        }
        return List.copyOf(rows);
    }

    public record Progress(List<Attempt> recent) {
        public Progress {recent=List.copyOf(recent);}

        public long completedCount() {return recent.get(recent.size()-1).number();}

        public double medianSeconds() {
            if(recent.size()!=RECENT_RUNS) return Double.NaN;
            var sorted=recent.stream().mapToDouble(Attempt::seconds).sorted().toArray();
            return sorted[RECENT_RUNS/2];
        }
    }

    public record Interruption(long number,double elapsed,String reason,InterruptionProgress progress) {}

    public record Interruptions(long count,List<Interruption> recent) {
        public Interruptions {recent=List.copyOf(recent);}
    }

    private record Conditions(CircuitSectionPractice.Start start,String controls) {
        private Conditions {
            Objects.requireNonNull(start);
            ControlIdentity.require(controls);
        }
    }
    private record Reference(CircuitSectionPractice.History history,CircuitSectionPractice lastAttempt,Progress progress) {}
    private record InterruptionHistory(CircuitSectionPractice lastAttempt,Interruptions results) {}

    private final TelemetryLap.Conditions conditions;
    private final EquipmentLap.Source a,b;
    private final float from,to;
    private final EquipmentSectionPracticeSession parent;
    private final LinkedHashMap<Conditions,EnumMap<Car,Reference>> references=new LinkedHashMap<>();
    private final LinkedHashMap<Conditions,EnumMap<Car,InterruptionHistory>> interrupted=new LinkedHashMap<>();
    private CircuitSectionPractice.Start lastStart;
    private Car lastCar=Car.STOCK;

    public EquipmentSectionPracticeSession(EquipmentLap a,EquipmentLap b,float from,float to) {
        this(a.lap().conditions(),a.source(),b.source(),from,to,null);
        if(a.id().equals(b.id()) || !EquipmentLap.differences(a,b).isEmpty())
            throw new IllegalArgumentException("Invalid equipment practice interval");
    }

    private EquipmentSectionPracticeSession(TelemetryLap.Conditions conditions,EquipmentLap.Source a,EquipmentLap.Source b,
                                            float from,float to,EquipmentSectionPracticeSession parent) {
        if(!Float.isFinite(from) || !Float.isFinite(to) || from<0 || from>=to || to>conditions.length())
            throw new IllegalArgumentException("Invalid equipment practice interval");
        this.conditions=conditions;
        this.a=a;
        this.b=b;
        this.from=from;
        this.to=to;
        this.parent=parent;
    }

    public TelemetryLap.Conditions conditions() {return conditions;}
    public float from() {return from;}
    public float to() {return to;}
    public Car lastCar() {return lastCar;}

    public boolean subsectionOf(EquipmentSectionPracticeSession interval) {return interval!=null && parent==interval;}

    public EquipmentSectionPracticeSession subsection(TrackLayout track,SplitComparison split,
                                                        CircuitSectionPractice.Start start,Car preferred) {
        return subsection(track,split.number(),split.from(),split.to(),start,preferred);
    }

    public boolean canPracticeInterruption(Car car,CircuitSectionPractice.Start start,String controls,Interruption run) {
        var records=interruptions(car,start,controls);
        if(run==null || records==null || records.recent().stream().noneMatch(value->value==run)) return false;
        var progress=run.progress();
        var passed=progress.lastPassed();
        return passed!=null && (passed.gate().distance()>from || progress.nextGate().distance()<to);
    }

    public EquipmentSectionPracticeSession interruptionSubsection(TrackLayout track,Car car,CircuitSectionPractice.Start start,
                                                                  String controls,Interruption run) {
        if(!canPracticeInterruption(car,start,controls,run)) return null;
        var progress=run.progress();
        return subsection(track,progress.nextGate().index(),progress.lastPassed().gate().distance(),
                progress.nextGate().distance(),start,car);
    }

    private EquipmentSectionPracticeSession subsection(TrackLayout track,int gate,float fromGate,float toGate,
                                                        CircuitSectionPractice.Start start,Car preferred) {
        if(!conditions.matchesTrack(track)) throw new IllegalArgumentException("Practice route differs from telemetry");
        var practice=CircuitSectionPractice.fromTelemetry(track,from,to,start);
        if(practice.splitCount()<2 || !practice.matchesSplitGates(gate,fromGate,toGate))
            throw new IllegalArgumentException("Subsection must use adjacent gates in the original interval");
        var section=new EquipmentSectionPracticeSession(conditions,a,b,fromGate,toGate,this);
        section.lastStart=start;
        section.lastCar=Objects.requireNonNull(preferred);
        return section;
    }

    public CircuitSectionPractice.Start lastStart(TrackLayout track) {
        return lastStart==null?CircuitSectionPractice.Start.defaults(track):lastStart;
    }

    public boolean available(Car car,String vehicle,String physics) {
        return car==Car.STOCK || a.vehicle().equals(vehicle) && a.physics().equals(physics);
    }

    public Tuning tuning(Car car,int livery) {
        return switch(car) {
            case STOCK -> Tuning.STOCK.withLivery(livery);
            case A -> a.tuning().withLivery(livery);
            case B -> b.tuning().withLivery(livery);
        };
    }

    public CircuitSectionPractice newAttempt(TrackLayout track,CircuitSectionPractice.Start start,Car car,String controls) {
        if(!conditions.matchesTrack(track)) throw new IllegalArgumentException("Practice route differs from telemetry");
        var reference=matchingReference(car,start,controls);
        if(reference==null) return CircuitSectionPractice.fromTelemetry(track,from,to,start);
        var prior=reference.lastAttempt();
        if(prior.track()==track) return prior.freshAttempt(start,reference.history());
        return CircuitSectionPractice.fromTelemetry(track,from,to,start,reference.history());
    }

    public void noteLaunch(Car car,CircuitSectionPractice.Start start) {
        Objects.requireNonNull(car);
        lastStart=Objects.requireNonNull(start);
        lastCar=car;
    }

    public void rememberFinish(Car car,CircuitSectionPractice trial,String controls) {
        if(trial.state()!=CircuitSectionPractice.State.FINISHED || !usesInterval(trial)) return;
        var key=new Conditions(trial.start(),controls);
        var group=references.get(key);
        if(group==null) group=new EnumMap<>(Car.class);
        var previous=group.get(car);
        if(previous!=null && previous.lastAttempt()==trial) return;
        var recent=new ArrayList<Attempt>();
        long number=1;
        if(previous!=null) {
            recent.addAll(previous.progress().recent());
            if(recent.size()==RECENT_RUNS) recent.remove(0);
            number+=previous.progress().completedCount();
        }
        recent.add(new Attempt(number,trial.completedGates()));
        var history=trial.historyAfterFinish(previous==null?null:previous.history());
        group.put(car,new Reference(history,trial,new Progress(recent)));
        references.remove(key);
        references.put(key,group);
        if(references.size()>MAX_CONDITIONS) references.remove(references.keySet().iterator().next());
    }

    public void rememberInterruption(Car car,CircuitSectionPractice trial,String controls) {
        if(trial.state()!=CircuitSectionPractice.State.INVALID || !usesInterval(trial)) return;
        var key=new Conditions(trial.start(),controls);
        var group=interrupted.get(key);
        if(group==null) group=new EnumMap<>(Car.class);
        var previous=group.get(car);
        if(previous!=null && previous.lastAttempt()==trial) return;
        var recent=new ArrayList<Interruption>();
        long count=1;
        if(previous!=null) {
            recent.addAll(previous.results().recent());
            if(recent.size()==RECENT_RUNS) recent.remove(0);
            count+=previous.results().count();
        }
        recent.add(new Interruption(count,trial.elapsed(),trial.failure(),trial.interruptionProgress()));
        group.put(car,new InterruptionHistory(trial,new Interruptions(count,recent)));
        interrupted.remove(key);
        interrupted.put(key,group);
        if(interrupted.size()>MAX_CONDITIONS) interrupted.remove(interrupted.keySet().iterator().next());
    }

    private boolean usesInterval(CircuitSectionPractice trial) {
        return trial.range()==null && trial.focusedSplit()==0 && trial.startDistance()==from && trial.finishDistance()==to
                && conditions.matchesTrack(trial.track());
    }

    public CircuitSectionPractice.ReferenceTimes referenceTimes(Car car,CircuitSectionPractice.Start start,String controls) {
        var reference=matchingReference(car,start,controls);
        return reference==null?null:reference.history().times();
    }

    public Progress progress(Car car,CircuitSectionPractice.Start start,String controls) {
        var reference=matchingReference(car,start,controls);
        return reference==null?null:reference.progress();
    }

    public Interruptions interruptions(Car car,CircuitSectionPractice.Start start,String controls) {
        var group=interrupted.get(new Conditions(start,controls));
        var history=group==null?null:group.get(car);
        return history==null?null:history.results();
    }

    public boolean hasEquipmentRuns(CircuitSectionPractice.Start start,String controls) {
        var key=new Conditions(start,controls);
        return hasEquipmentChoice(references.get(key)) || hasEquipmentChoice(interrupted.get(key));
    }

    private static boolean hasEquipmentChoice(EnumMap<Car,?> group) {
        return group!=null && (group.containsKey(Car.A) || group.containsKey(Car.B));
    }

    private Reference matchingReference(Car car,CircuitSectionPractice.Start start,String controls) {
        var group=references.get(new Conditions(start,controls));
        return group==null?null:group.get(car);
    }
}
