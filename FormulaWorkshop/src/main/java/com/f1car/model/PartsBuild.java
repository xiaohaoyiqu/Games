package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** A portable, pinned four-slot plan. It never changes a car merely by being loaded or edited. */
public record PartsBuild(int version,String vehicle,String physics,int budget,Map<Slot,Selection> slots) {
    public record Selection(String part,String fingerprint,Map<Field,Integer> values) {
        public Selection {
            if(!PartsCatalog.validId(part) || fingerprint==null || !fingerprint.matches("[a-f0-9]{64}") || values==null) throw new IllegalArgumentException("Invalid part selection");
            var copy=new EnumMap<Field,Integer>(Field.class);copy.putAll(values);
            copy.forEach((field,value)->{if(value==null || value<field.min || value>field.max) throw new IllegalArgumentException("Unsupported draft value");});values=Collections.unmodifiableMap(copy);
        }
        public static Selection initial(Part part,PartsCatalog catalog) {
            var values=new EnumMap<Field,Integer>(Field.class);part.settings().forEach((field,range)->values.put(field,range.initial()));return new Selection(part.id(),catalog.fingerprint(part.id()),values);
        }
    }
    public record Review(int points,List<String> issues,Tuning candidate) {
        public Review {issues=List.copyOf(issues);Objects.requireNonNull(candidate);}
        public boolean valid() {return issues.isEmpty();}
    }
    public record Rebased(PartsBuild build,List<String> changes) {public Rebased {changes=List.copyOf(changes);}}
    public PartsBuild {
        if(version!=1 || vehicle==null || !vehicle.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || physics==null || !physics.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || budget<0 || budget>1000 || slots==null) throw new IllegalArgumentException("Invalid parts plan");
        var copy=new EnumMap<Slot,Selection>(Slot.class);copy.putAll(slots);
        if(!copy.keySet().equals(EnumSet.allOf(Slot.class))) throw new IllegalArgumentException("All four slots are required");
        copy.forEach((slot,selection)->{if(selection==null || !selection.values().keySet().equals(Set.copyOf(slot.fields()))) throw new IllegalArgumentException("Draft fields do not match slot");});slots=Collections.unmodifiableMap(copy);
    }
    public static PartsBuild stock(PartsCatalog catalog,String vehicle,String physics) {
        var slots=new EnumMap<Slot,Selection>(Slot.class);for(var slot:Slot.values()) slots.put(slot,Selection.initial(catalog.find(slot.stockId()),catalog));return new PartsBuild(1,vehicle,physics,12,slots);
    }
    public PartsBuild select(PartsCatalog catalog,Part part) {var next=new EnumMap<Slot,Selection>(Slot.class);next.putAll(slots);next.put(part.slot(),Selection.initial(part,catalog));return new PartsBuild(version,vehicle,physics,budget,next);}
    public PartsBuild change(PartsCatalog catalog,Slot slot,Field field,int amount) {
        var selection=slots.get(slot);var part=catalog.find(selection.part());
        if(part==null || part.slot()!=slot || !selection.fingerprint().equals(catalog.fingerprint(part.id())) || !slot.fields().contains(field)) return this;
        var values=new EnumMap<Field,Integer>(Field.class);values.putAll(selection.values());values.put(field,part.settings().get(field).clamp((long)values.get(field)+amount));
        var next=new EnumMap<Slot,Selection>(Slot.class);next.putAll(slots);next.put(slot,new Selection(part.id(),selection.fingerprint(),values));return new PartsBuild(version,vehicle,physics,budget,next);
    }
    public PartsBuild budget(int value) {return new PartsBuild(version,vehicle,physics,value,slots);}
    /** Manual input must be valid as entered; unlike step buttons it is never silently clamped. */
    public PartsBuild setValue(PartsCatalog catalog,Slot slot,Field field,int value) {
        Objects.requireNonNull(catalog);Objects.requireNonNull(slot);Objects.requireNonNull(field);
        var selection=slots.get(slot);var part=catalog.find(selection.part());
        if(part==null || part.slot()!=slot || !slot.fields().contains(field) || !selection.fingerprint().equals(catalog.fingerprint(part.id()))) throw new IllegalArgumentException("Part changed; review the draft again");
        var range=part.settings().get(field);
        if(value<range.min() || value>range.max()) throw new IllegalArgumentException("Value outside selected part range");
        if(selection.values().get(field)==value) return this;
        var values=new EnumMap<Field,Integer>(Field.class);values.putAll(selection.values());values.put(field,value);
        var next=new EnumMap<Slot,Selection>(Slot.class);next.putAll(slots);next.put(slot,new Selection(part.id(),selection.fingerprint(),values));return new PartsBuild(version,vehicle,physics,budget,next);
    }
    public Review review(PartsCatalog catalog,String currentVehicle,String currentPhysics,int livery) {
        var issues=new ArrayList<String>();var selected=new HashSet<String>();slots.values().forEach(selection->selected.add(selection.part()));int points=0;
        if(!vehicle.equals(currentVehicle)) issues.add("车型不同：先为当前车型新建方案。");
        if(!physics.equals(currentPhysics)) issues.add("物理版本不同：保留原方案，当前版本需另建并重新试车。");
        for(var slot:Slot.values()) {
            var selection=slots.get(slot);var part=catalog.find(selection.part());
            if(part==null) {issues.add(slot.title+"缺少部件："+selection.part());continue;}
            if(part.slot()!=slot) {issues.add(slot.title+"的部件类型不符。");continue;}
            points+=part.points();
            if(!selection.fingerprint().equals(catalog.fingerprint(part.id()))) issues.add(part.name()+"定义已改变，请先重建草稿并查看变化。");
            for(var field:slot.fields()) {int value=selection.values().get(field);var range=part.settings().get(field);if(value<range.min() || value>range.max()) issues.add(part.name()+"的"+field.title+"超出部件范围。");}
            part.requires().stream().sorted().filter(id->!selected.contains(id)).forEach(id->issues.add(part.name()+"需要："+name(catalog,id)));
            part.excludes().stream().sorted().filter(selected::contains).forEach(id->issues.add(part.name()+"不能与"+name(catalog,id)+"组合。"));
        }
        if(points>budget) issues.add("方案点数 "+points+" 超过上限 "+budget+"；请选择其他部件或调整试验上限。");
        return new Review(points,issues,tuning(livery));
    }
    public Tuning tuning(int livery) {var values=new EnumMap<Field,Integer>(Field.class);slots.values().forEach(selection->values.putAll(selection.values()));return new Tuning(value(values,Field.POWER),value(values,Field.BRAKES),value(values,Field.SUSPENSION),value(values,Field.WING),livery,value(values,Field.BRAKE_BIAS),value(values,Field.FRONT_SPRING),value(values,Field.REAR_SPRING),value(values,Field.FRONT_DAMPING),value(values,Field.REAR_DAMPING));}
    private static float value(Map<Field,Integer> values,Field field) {return values.get(field)/100f;}
    private static String name(PartsCatalog catalog,String id) {var part=catalog.find(id);return part==null?id+"（依赖尚未导入）":part.name();}
    /** Explicit preview repair; caller must present changes before adopting this new plan. */
    public Rebased rebase(PartsCatalog catalog,String currentVehicle,String currentPhysics) {
        var next=new EnumMap<Slot,Selection>(Slot.class);var changes=new ArrayList<String>();
        for(var slot:Slot.values()) {
            var old=slots.get(slot);var part=catalog.find(old.part());
            if(part==null || part.slot()!=slot) {String reason=part==null?"缺失部件":"部件类型已改变";part=catalog.find(slot.stockId());changes.add(slot.title+"："+reason+"，改为"+part.name());next.put(slot,Selection.initial(part,catalog));continue;}
            var values=new EnumMap<Field,Integer>(Field.class);for(var field:slot.fields()) {int before=old.values().get(field),after=part.settings().get(field).clamp(before);values.put(field,after);if(before!=after) changes.add(field.title+"："+before+"% → "+after+"%");}
            if(!old.fingerprint().equals(catalog.fingerprint(part.id()))) changes.add(part.name()+"：采用当前部件定义。");
            next.put(slot,new Selection(part.id(),catalog.fingerprint(part.id()),values));
        }
        if(!vehicle.equals(currentVehicle) || !physics.equals(currentPhysics)) changes.add("按当前车型／物理另建方案；不携带成绩或资格。");
        return new Rebased(new PartsBuild(1,currentVehicle,currentPhysics,budget,next),changes);
    }
}
