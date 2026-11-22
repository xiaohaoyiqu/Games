package com.f1car.model;

import java.util.*;

/** An author's immutable selection, never a new measurement or a performance certificate. */
public record TuningLapStudy(String id,String author,long createdAt,String notes,Report report,Lap a,Lap b) {
    public record Report(TuningExperiment.Snapshot snapshot,TuningPurpose purpose,TrialTraceShare provider,List<TuningTrace> traces) {
        public Report(TuningExperiment.Snapshot snapshot,TuningPurpose purpose,TrialTraceShare provider) {this(snapshot,purpose,provider,provider==null?List.of():provider.traces());}
        public Report {
            if(snapshot==null || !snapshot.complete() || purpose!=null && !purpose.matches(snapshot)
                || provider!=null && (!provider.report().equals(snapshot) || !Objects.equals(provider.purpose(),purpose))) throw new IllegalArgumentException("Invalid frozen study report");
            traces=new FrozenTuningTraces(snapshot,traces).traces();
            if(provider!=null && !provider.traces().equals(traces)) throw new IllegalArgumentException("Provider trace content differs");
        }
        public boolean received() {return provider!=null;}
        public FrozenTuningTraces frozenTraces() {return new FrozenTuningTraces(snapshot,traces);}
    }
    public record Lap(EquipmentLap recording,EquipmentLapShare provider) {
        public Lap {if(recording==null || provider!=null && !provider.recording().equals(recording)) throw new IllegalArgumentException("Invalid frozen study lap");}
        public boolean received() {return provider!=null;}
    }
    public TuningLapStudy {
        if(id==null || !id.matches("lap-study-[a-f0-9]{32}") || !PartsCatalog.text(author,40) || author.isBlank() || createdAt<0
            || notes==null || notes.length()>320 || !PartsCatalog.text(notes.replace("\n",""),320) || report==null || a==null || b==null
            || !new TuningLapComparison(report.snapshot(),report.purpose()).pairIssues(a.recording(),b.recording()).isEmpty()) throw new IllegalArgumentException("Invalid report/lap study");
    }
    public static TuningLapStudy capture(String author,String notes,Report report,Lap a,Lap b) {
        return new TuningLapStudy("lap-study-"+UUID.randomUUID().toString().replace("-",""),author,System.currentTimeMillis(),notes,report,a,b);
    }
}
