package com.f1car.mini4wd;

import java.util.List;
import java.util.Objects;

/** Compares two frozen assemblies on one route without driving input or record writes. */
public final class MiniAssemblyComparison {
    public enum Outcome { RUNNING,FINISHED,DERAILED,LIMIT }

    public record Plan(MiniTrack track,String trackName,MiniSetup a,String bName,MiniSetup b) {
        public Plan {
            Objects.requireNonNull(track);
            Objects.requireNonNull(trackName);
            Objects.requireNonNull(bName);
            if(a==null || b==null || !a.ready() || !b.ready() || !track.inspect().ready())
                throw new IllegalArgumentException("Two assembled cars and a connected route are required");
        }
    }
    public record Reading(Outcome outcome,double seconds,double routeProgress,double peakSpeed,
                          MiniTrack.Kind stoppedAt,MiniTrack.Point stoppedPosition,List<Double> laps) {
        public Reading {laps=List.copyOf(laps);}
    }

    public static final int MAX_SIMULATED_SECONDS=300;
    private static final int STEPS_PER_UPDATE=96;
    private static final double STEP=.01;
    private static final int MAX_STEPS=MAX_SIMULATED_SECONDS*100;

    private static final class Run {
        final MiniTrial trial;
        double peak;
        Reading frozen;

        Run(MiniTrack.Inspection route,MiniSetup setup) {trial=new MiniTrial(route,setup,0);}

        void advance() {
            if(frozen!=null) return;
            trial.update(STEP,false);
            peak=Math.max(peak,trial.speed());
            if(trial.result()==MiniTrial.Result.FINISHED) frozen=reading(Outcome.FINISHED);
            else if(trial.result()==MiniTrial.Result.DERAILED) frozen=reading(Outcome.DERAILED);
        }

        Reading reading(Outcome outcome) {
            var stop=outcome==Outcome.DERAILED?trial.segment().kind():null;
            var position=stop==null?null:trial.segment().point(trial.fraction());
            return new Reading(outcome,trial.elapsed(),trial.progress()/trial.targetLaps(),peak,stop,position,trial.lapTimes());
        }

        Reading reading() {return frozen==null?reading(Outcome.RUNNING):frozen;}
        void endAtLimit() {if(frozen==null) frozen=reading(Outcome.LIMIT);}
    }

    private final Plan plan;
    private final Run a,b;
    private int steps;

    public MiniAssemblyComparison(Plan plan) {
        this.plan=Objects.requireNonNull(plan);
        var inspection=plan.track().inspect();
        a=new Run(inspection,plan.a());
        b=new Run(inspection,plan.b());
    }

    public Plan plan() {return plan;}
    public Reading a() {return a.reading();}
    public Reading b() {return b.reading();}
    public boolean complete() {return a.frozen!=null && b.frozen!=null;}
    public Double timeDifference() {
        if(!complete() || a.frozen.outcome()!=Outcome.FINISHED || b.frozen.outcome()!=Outcome.FINISHED) return null;
        return b.frozen.seconds()-a.frozen.seconds();
    }

    /** A bounded amount of work per game update, irrespective of the render interval. */
    public void advance() {
        int target=Math.min(MAX_STEPS,steps+STEPS_PER_UPDATE);
        while(steps<target && !complete()) {
            a.advance();
            b.advance();
            steps++;
        }
        if(steps==MAX_STEPS) {
            a.endAtLimit();
            b.endAtLimit();
        }
    }
}
