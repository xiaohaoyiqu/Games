package com.f1car.model;

import java.util.*;

/** One complete local lap and the vehicle/setup source frozen when its session began. */
public record EquipmentLap(int version,TelemetryLap lap,Source source) {
    public record Source(String vehicle,String physics,Tuning tuning,FittedParts installed) {
        public Source {
            if(vehicle==null || !vehicle.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || physics==null || !physics.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || tuning==null
                || installed!=null && (!installed.compatible(vehicle,physics) || !installed.accepts(tuning))) throw new IllegalArgumentException("Invalid frozen lap source");
        }
        public boolean matches(TelemetryLap.Conditions conditions,int livery) {return physics.equals(conditions.physics()) && tuning.performanceKey().equals(conditions.performance()) && tuning.livery()==livery;}
    }
    public EquipmentLap {
        if(version!=1 || lap==null || source==null || !source.matches(lap.conditions(),lap.livery())) throw new IllegalArgumentException("Equipment source differs from lap");
    }
    public String id() {return lap.id();}
    public static List<String> differences(EquipmentLap a,EquipmentLap b) {
        Objects.requireNonNull(a);Objects.requireNonNull(b);var x=a.lap().conditions();var y=b.lap().conditions();var issues=new ArrayList<String>();
        if(a.id().equals(b.id()) && !a.equals(b)) issues.add("同一圈身份含不同内容，不能视为两份独立记录。");
        if(!a.source().vehicle().equals(b.source().vehicle())) issues.add("记录的车型不同。");
        if(!x.trackId().equals(y.trackId()) || !x.geometry().equals(y.geometry()) || Float.compare(x.length(),y.length())!=0) issues.add("路线身份、几何或圈长不同。");
        if(!x.physics().equals(y.physics())) issues.add("物理版本不同。");
        if(!x.controls().equals(y.controls())) issues.add("记录的操作身份不同。");
        return List.copyOf(issues);
    }
}
