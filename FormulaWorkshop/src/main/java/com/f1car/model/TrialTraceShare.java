package com.f1car.model;

import java.util.*;

/** Standalone provider observations, viewed without writing local reports or results. */
public record TrialTraceShare(String author,String notes,TuningExperiment.Snapshot report,TuningPurpose purpose,List<TuningTrace> traces) {
    public TrialTraceShare {
        if(!PartsCatalog.text(author,40) || !PartsCatalog.text(notes,240) || report==null || traces==null || traces.isEmpty() || traces.size()>6
            || purpose!=null && !purpose.matches(report)) throw new IllegalArgumentException("Invalid shared trial traces");
        traces=List.copyOf(traces);var indices=new HashSet<Integer>();
        for(var trace:traces) if(!indices.add(trace.index()) || !trace.matches(report,trace.index())) throw new IllegalArgumentException("Trace differs from shared report");
    }
    public TuningTrace find(int index) {return traces.stream().filter(trace->trace.index()==index).findFirst().orElse(null);}
}
