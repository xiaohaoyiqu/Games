package com.f1car.model;

import java.util.*;

/** Six recent valid laps and the best observed lap share one atomic archive per condition. */
public record TelemetryBook(int version,TelemetryLap.Conditions conditions,List<TelemetryLap> recent,TelemetryLap best) {
    public static final int VERSION=1;
    public TelemetryBook {
        if(version!=VERSION || conditions==null || recent==null || recent.size()>6) throw new IllegalArgumentException("Invalid telemetry archive");
        recent=List.copyOf(recent);
        if(best==null ? !recent.isEmpty() : !best.conditions().equals(conditions))
            throw new IllegalArgumentException("Inconsistent telemetry archive");
        var ids=new HashSet<String>();
        for(var lap:recent) {
            if(!ids.add(lap.id()) || !lap.conditions().equals(conditions) || lap.duration()<best.duration())
                throw new IllegalArgumentException("Inconsistent telemetry archive");
            if(lap.id().equals(best.id())) {
                if(!lap.equals(best)) throw new IllegalArgumentException("Conflicting telemetry identity");
                best=lap;
            }
        }
    }
    public static TelemetryBook empty(TelemetryLap.Conditions conditions) { return new TelemetryBook(VERSION,conditions,List.of(),null); }
    public TelemetryBook add(TelemetryLap lap) {
        if(!conditions.equals(lap.conditions())) throw new IllegalArgumentException("Telemetry conditions differ");
        for(var saved:all()) if(saved.id().equals(lap.id())) { if(!saved.equals(lap)) throw new IllegalArgumentException("Conflicting telemetry identity");return this; }
        var laps=new ArrayList<TelemetryLap>();laps.add(lap);laps.addAll(recent);if(laps.size()>6) laps.remove(laps.size()-1);
        return new TelemetryBook(VERSION,conditions,laps,best==null || lap.duration()<best.duration()?lap:best);
    }
    public List<TelemetryLap> all() {
        if (best == null) return recent;
        // The constructor keeps the same lap instance when the best is already in recent.
        for (var lap : recent) if (lap == best) return recent;
        var laps=new ArrayList<>(recent);
        laps.add(best);
        return List.copyOf(laps);
    }
}
