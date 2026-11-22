package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** A reviewed one-slot change. Reusing numbers never certifies equal behaviour between parts. */
public final class PartsReplacement {
    public enum Adjustment {KEPT,MINIMUM,MAXIMUM}
    public record Value(Field field,int before,int after,Range range,Adjustment adjustment) {
        public Value {
            Objects.requireNonNull(field);Objects.requireNonNull(range);Objects.requireNonNull(adjustment);
            if(before<field.min || before>field.max || range.min()<field.min || range.max()>field.max || after!=range.clamp(before)
                || adjustment!=(before<range.min()?Adjustment.MINIMUM:before>range.max()?Adjustment.MAXIMUM:Adjustment.KEPT))
                throw new IllegalArgumentException("Invalid part replacement value");
        }
    }
    private final PartsBuild before,after;
    private final Part target;
    private final List<Value> values;
    private final List<String> sourceIssues;
    private PartsReplacement(PartsBuild before,PartsBuild after,Part target,List<Value> values,List<String> sourceIssues) {
        this.before=before;this.after=after;this.target=target;this.values=List.copyOf(values);this.sourceIssues=List.copyOf(sourceIssues);
    }
    /** Out-of-range values are proposed at the nearest boundary; adoption is a separate action. */
    public static PartsReplacement preserving(PartsBuild before,PartsCatalog catalog,String targetId) {
        Objects.requireNonNull(before);Objects.requireNonNull(catalog);
        if(!PartsCatalog.validId(targetId)) throw new IllegalArgumentException("Invalid replacement part identity");
        var target=catalog.find(targetId);
        if(target==null) throw new IllegalArgumentException("Replacement part is not in the current catalogue");
        var slot=target.slot();var source=before.slots().get(slot);var previous=catalog.find(source.part());var issues=new ArrayList<String>();
        if(previous==null) issues.add("原部件定义缺失：只保留草稿中的旧数值，不推断原部件效果。");
        else if(previous.slot()!=slot) issues.add("原部件的槽位已改变：旧草稿原文保留，本次仅替换所选槽位。");
        else if(!source.fingerprint().equals(catalog.fingerprint(previous.id()))) issues.add("原部件定义已修订：旧指纹保留在原草稿中，本次仅按新部件范围比较数值。");
        var settings=new EnumMap<Field,Integer>(Field.class);var values=new ArrayList<Value>();
        for(var field:slot.fields()) {
            int old=source.values().get(field);var range=target.settings().get(field);int next=range.clamp(old);settings.put(field,next);
            values.add(new Value(field,old,next,range,old<range.min()?Adjustment.MINIMUM:old>range.max()?Adjustment.MAXIMUM:Adjustment.KEPT));
        }
        var slots=new EnumMap<Slot,PartsBuild.Selection>(Slot.class);slots.putAll(before.slots());slots.put(slot,new PartsBuild.Selection(target.id(),catalog.fingerprint(target.id()),settings));
        var after=new PartsBuild(before.version(),before.vehicle(),before.physics(),before.budget(),slots);
        return new PartsReplacement(before,after,target,values,issues);
    }
    public PartsBuild before() {return before;}
    public PartsBuild after() {return after;}
    public Part target() {return target;}
    public List<Value> values() {return values;}
    public List<String> sourceIssues() {return sourceIssues;}
    public long adjustedCount() {return values.stream().filter(value->value.adjustment()!=Adjustment.KEPT).count();}
    public boolean changed() {return !before.equals(after);}
}
