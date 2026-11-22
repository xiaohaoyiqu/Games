package com.f1car.model;

import java.util.*;

/** Available original attempts over one immutable report. Missing curves remain missing. */
public record FrozenTuningTraces(TuningExperiment.Snapshot report,List<TuningTrace> traces) {
    public FrozenTuningTraces {
        Objects.requireNonNull(report);traces=List.copyOf(traces);if(traces.size()>6) throw new IllegalArgumentException("Too many frozen traces");
        var indices=new HashSet<Integer>();for(var trace:traces) if(!indices.add(trace.index()) || !trace.matches(report,trace.index())) throw new IllegalArgumentException("Frozen trace differs from report attempt");
    }
    public TuningTrace find(int index) {return traces.stream().filter(trace->trace.index()==index).findFirst().orElse(null);}
    public List<Integer> missing() {var indices=new ArrayList<Integer>();for(int i=0;i<report.runs().size();i++) if(find(i)==null) indices.add(i);return List.copyOf(indices);}
    public int exactBindings() {return (int)traces.stream().filter(trace->trace.version()==3).count();}
}
