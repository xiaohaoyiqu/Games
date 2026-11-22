package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** A pinned allowed pool and an authoritative budget, separate from the editable workbench budget. */
public record PartsActivityRule(int version,String vehicle,String physics,int budget,List<Allowed> allowed) {
    public static final int MAX_ALLOWED=64;
    public static final String PROTOCOL="activity-parts-pool-v1";
    public record Allowed(Slot slot,String part,String fingerprint) {
        public Allowed {if(slot==null || !PartsCatalog.validId(part) || fingerprint==null || !fingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid allowed activity part");}
    }
    public PartsActivityRule {
        if(version!=1 || vehicle==null || !vehicle.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || physics==null || !physics.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || budget<0 || budget>1000 || allowed==null || allowed.size()>MAX_ALLOWED || allowed.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid parts activity rule");
        allowed=allowed.stream().sorted(Comparator.comparing(Allowed::slot).thenComparing(Allowed::part)).toList();
        if(allowed.stream().map(Allowed::part).distinct().count()!=allowed.size() || !allowed.stream().map(Allowed::slot).collect(java.util.stream.Collectors.toSet()).equals(EnumSet.allOf(Slot.class))) throw new IllegalArgumentException("Every slot needs a unique allowed part");
    }
    public static PartsActivityRule capture(PartsCatalog catalog,String vehicle,String physics,int budget,Collection<String> ids) {
        return new PartsActivityRule(1,vehicle,physics,budget,ids.stream().map(id->{var part=catalog.find(id);if(part==null) throw new IllegalArgumentException("Allowed part is missing: "+id);return new Allowed(part.slot(),id,catalog.fingerprint(id));}).toList());
    }
    public String key() {var text=new StringBuilder(PROTOCOL).append('|').append(vehicle).append('|').append(physics).append('|').append(budget);for(var part:allowed) text.append('|').append(part.slot()).append(':').append(part.part()).append(':').append(part.fingerprint());return PartsCatalog.hash(text.toString());}
    public String incompatibility(PartsCatalog catalog,String currentVehicle,String currentPhysics) {
        if(!vehicle.equals(currentVehicle) || !physics.equals(currentPhysics)) return "限定部件的车型或物理版本不同；保留原卡，请另建当前条件。";
        for(var pinned:allowed) {var part=catalog.find(pinned.part());if(part==null) return "规则缺少部件："+pinned.part();if(part.slot()!=pinned.slot() || !pinned.fingerprint().equals(catalog.fingerprint(part.id()))) return "规则部件定义已变化："+part.name()+"；请按新条件另建卡。";}return "";
    }
    public PartsBuild normalize(PartsBuild build) {return build.budget(budget);}
    /** Check identities without trusting an uninstalled catalogue; execution additionally calls review. */
    public boolean acceptsIdentity(PartsBuild build) {
        if(build==null || !vehicle.equals(build.vehicle()) || !physics.equals(build.physics()) || build.budget()!=budget) return false;
        return build.slots().entrySet().stream().allMatch(entry->allowed.stream().anyMatch(part->part.slot()==entry.getKey() && part.part().equals(entry.getValue().part()) && part.fingerprint().equals(entry.getValue().fingerprint())));
    }
    public PartsBuild.Review review(PartsBuild build,PartsCatalog catalog,String vehicle,String physics,int livery) {
        var normalized=normalize(build);var checked=normalized.review(catalog,vehicle,physics,livery);var issues=new ArrayList<>(checked.issues());String mismatch=incompatibility(catalog,vehicle,physics);if(!mismatch.isEmpty()) issues.add(mismatch);
        for(var slot:Slot.values()) {var selection=normalized.slots().get(slot);if(allowed.stream().noneMatch(part->part.slot()==slot && part.part().equals(selection.part()) && part.fingerprint().equals(selection.fingerprint()))) issues.add(slot.title+"的所选部件不在活动允许池中："+selection.part());}
        return new PartsBuild.Review(checked.points(),issues,checked.candidate());
    }
    public String entryKey(PartsBuild build) {
        if(!acceptsIdentity(build)) throw new IllegalArgumentException("Entry does not match the pinned activity rule");var text=new StringBuilder(key());for(var slot:Slot.values()) {var selection=build.slots().get(slot);text.append('|').append(slot).append(':').append(selection.part()).append(':').append(selection.fingerprint());for(var field:slot.fields()) text.append(':').append(field).append('=').append(selection.values().get(field));}return PartsCatalog.hash(text.toString());
    }
    public static final class Cache {
        private PartsActivityRule rule;private PartsBuild build;private PartsCatalog catalog;private String vehicle,physics;private int livery,computations;private PartsBuild.Review result;
        public PartsBuild.Review get(PartsActivityRule next,PartsBuild draft,PartsCatalog directory,String model,String protocol,int paint) {if(rule!=next || build!=draft || catalog!=directory || !Objects.equals(vehicle,model) || !Objects.equals(physics,protocol) || livery!=paint) {result=next.review(draft,directory,model,protocol,paint);rule=next;build=draft;catalog=directory;vehicle=model;physics=protocol;livery=paint;computations++;}return result;}
        public int computations() {return computations;}
    }
}
