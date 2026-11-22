package com.f1car.model;

import java.util.*;

/** A driver's named setup and context, never a certified lap or an input preset. */
public record SetupCard(String id,String name,String notes,String vehicle,String physics,String trackId,
                        String trackName,String geometry,String controls,Tuning tuning) {
    public SetupCard {
        if(id==null || !id.matches("[a-f0-9-]{36}") || !UUID.fromString(id).toString().equals(id)
            || !text(name,40) || name.isBlank() || !text(notes,240)
            || !token(vehicle) || !token(physics) || !token(trackId) || !text(trackName,80) || trackName.isBlank()
            || geometry==null || !geometry.matches("[a-f0-9]{64}") || !ControlIdentity.valid(controls) || tuning==null)
            throw new IllegalArgumentException("Invalid named setup");
    }
    private static boolean text(String value,int max) { return value!=null && value.length()<=max && value.codePoints().noneMatch(Character::isISOControl); }
    private static boolean token(String value) { return value!=null && value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}"); }
    public static SetupCard capture(String name,String notes,VehicleSpec vehicle,TrackLayout track,String controls,Tuning tuning) {
        return new SetupCard(UUID.randomUUID().toString(),name.strip(),notes.strip(),vehicle.id,vehicle.physicsVersion,track.id,track.name,track.geometryKey(),controls,tuning);
    }
    public SetupCard withText(String name,String notes) { return new SetupCard(id,name.strip(),notes.strip(),vehicle,physics,trackId,trackName,geometry,controls,tuning); }

    public SetupCard mergeEditedText(SetupCard original,SetupCard draft) {
        String mergedName=draft.name.equals(original.name)?name:draft.name;
        String mergedNotes=draft.notes.equals(original.notes)?notes:draft.notes;
        return new SetupCard(id,mergedName,mergedNotes,vehicle,physics,trackId,trackName,geometry,controls,tuning);
    }

    public boolean sameConfiguration(SetupCard other) {
        return vehicle.equals(other.vehicle) && physics.equals(other.physics) && trackId.equals(other.trackId)
                && geometry.equals(other.geometry) && controls.equals(other.controls) && tuning.equals(other.tuning);
    }

    public SetupCard copy() { return new SetupCard(UUID.randomUUID().toString(),name,notes,vehicle,physics,trackId,trackName,geometry,controls,tuning); }
    public boolean sameTrack(TrackLayout track) { return trackId.equals(track.id) && geometry.equals(track.geometryKey()); }
    public String incompatibility(VehicleSpec spec) {
        return !vehicle.equals(spec.id)?"车型不同":!physics.equals(spec.physicsVersion)?"物理版本不同":"";
    }
    public Tuning performanceWithLivery(int livery) { return tuning.withLivery(livery); }
    public boolean canRebase(VehicleSpec spec) { return vehicle.equals(spec.id) && Set.of("fw-physics-2-speed-limits","fw-physics-3-brake-balance").contains(physics) && spec.physicsVersion.equals("fw-physics-4-axle-suspension"); }
    public SetupCard rebase(VehicleSpec spec) {
        if(!canRebase(spec)) throw new IllegalArgumentException("Unsupported setup migration");
        return new SetupCard(UUID.randomUUID().toString(),name,notes,vehicle,spec.physicsVersion,trackId,trackName,geometry,controls,new Tuning(tuning.power(),tuning.brakes(),tuning.suspension(),tuning.wing(),tuning.livery(),physics.equals("fw-physics-2-speed-limits")?.5f:tuning.brakeBias()));
    }
    public record Difference(String label,float current,float saved) {
        public float percentagePoints() { return (saved-current)*100; }
    }
    public List<Difference> differences(Tuning current) {
        return List.of(new Difference("动力输出",current.power(),tuning.power()),new Difference("制动力度",current.brakes(),tuning.brakes()),
            new Difference("悬挂配套",current.suspension(),tuning.suspension()),new Difference("尾翼方案",current.wing(),tuning.wing()),new Difference("前轴制动比例",current.brakeBias(),tuning.brakeBias()),
            new Difference("前轴弹簧倍率",current.frontSpring(),tuning.frontSpring()),new Difference("后轴弹簧倍率",current.rearSpring(),tuning.rearSpring()),new Difference("前轴阻尼倍率",current.frontDamping(),tuning.frontDamping()),new Difference("后轴阻尼倍率",current.rearDamping(),tuning.rearDamping()));
    }
}
