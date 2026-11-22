package com.f1car.model;

import java.util.ArrayList;
import java.util.List;

/** Rolling consecutive valid laps. Thresholds are provisional game rules, not real-driver benchmarks. */
public final class LapConsistency {
    public static final int REQUIRED_LAPS=3;
    private final List<Double> laps=new ArrayList<>();
    public void invalidate() { laps.clear(); }
    public void complete(double duration,boolean valid) {
        if(!valid || !Double.isFinite(duration) || duration<=0) { invalidate();return; }
        laps.add(duration);if(laps.size()>REQUIRED_LAPS) laps.remove(0);
    }
    public List<Double> laps() { return List.copyOf(laps); }
    public double mean() { return laps.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
    public double spread() { return laps.size()<2?0:laps.stream().mapToDouble(Double::doubleValue).max().orElse(0)-laps.stream().mapToDouble(Double::doubleValue).min().orElse(0); }
    public double tolerance() { return Math.max(1.5,mean()*.04); }
    public boolean success() { return laps.size()==REQUIRED_LAPS && spread()<=tolerance(); }
}
