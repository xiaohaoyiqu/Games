package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/**
 * A whole-build proposal using the garage's actual values, not the equipment's saved baseline.
 * Parts still constrain existing physical parameters; equal numbers do not certify a benefit.
 * Reference: https://documentation.beamng.com/modding/vehicle/sections/variables/
 */
public final class PartsGarageTransfer {
    public enum Adjustment {KEPT,MINIMUM,MAXIMUM}
    public record Value(Field field,int garage,int draft,int proposed,Range source,Range target,Adjustment adjustment) {
        public Value {
            Objects.requireNonNull(field);Objects.requireNonNull(source);Objects.requireNonNull(target);Objects.requireNonNull(adjustment);
            if(garage<field.min || garage>field.max || draft<field.min || draft>field.max || proposed!=target.clamp(garage)
                || source.min()<field.min || source.max()>field.max || target.min()<field.min || target.max()>field.max
                || adjustment!=(garage<target.min()?Adjustment.MINIMUM:garage>target.max()?Adjustment.MAXIMUM:Adjustment.KEPT)) throw new IllegalArgumentException("Invalid garage transfer value");
        }
    }
    private final PartsBuild before,after;
    private final PartsCatalog catalog;
    private final Tuning garage;
    private final FittedParts installed;
    private final Map<Slot,Part> targets;
    private final List<Value> values;
    private PartsGarageTransfer(PartsBuild before,PartsBuild after,PartsCatalog catalog,Tuning garage,FittedParts installed,Map<Slot,Part> targets,List<Value> values) {
        this.before=before;this.after=after;this.catalog=catalog;this.garage=garage;this.installed=installed;this.targets=Collections.unmodifiableMap(new EnumMap<>(targets));this.values=List.copyOf(values);
    }
    /** Preparation never repairs pins, chooses parts, increases budgets or writes the garage. */
    public static PartsGarageTransfer prepare(PartsBuild before,PartsCatalog catalog,Tuning garage,FittedParts installed) {
        Objects.requireNonNull(before);Objects.requireNonNull(catalog);Objects.requireNonNull(garage);
        if(installed!=null && (!installed.compatible(before.vehicle(),before.physics()) || !installed.accepts(garage))) throw new IllegalArgumentException("Garage differs from installed equipment");
        var targets=new EnumMap<Slot,Part>(Slot.class);var slots=new EnumMap<Slot,PartsBuild.Selection>(Slot.class);var values=new ArrayList<Value>();
        for(var slot:Slot.values()) {
            var selection=before.slots().get(slot);var target=catalog.find(selection.part());
            if(target==null || target.slot()!=slot || !target.fingerprint().equals(selection.fingerprint())) throw new IllegalArgumentException("Target definition is missing or revised");
            targets.put(slot,target);var settings=new EnumMap<Field,Integer>(Field.class);
            for(var field:slot.fields()) {
                int current=field.percent(garage);
                // Existing profiles can contain sub-percent values. Never round those implicitly.
                if(Float.compare(field.ratio(garage),current/100f)!=0) throw new IllegalArgumentException("Garage contains a non-integer percentage: "+field);
                var source=installed==null?new Range(field.min,field.max,current):installed.range(field);var range=target.settings().get(field);int proposed=range.clamp(current);
                values.add(new Value(field,current,selection.values().get(field),proposed,source,range,current<range.min()?Adjustment.MINIMUM:current>range.max()?Adjustment.MAXIMUM:Adjustment.KEPT));settings.put(field,proposed);
            }
            slots.put(slot,new PartsBuild.Selection(selection.part(),selection.fingerprint(),settings));
        }
        return new PartsGarageTransfer(before,new PartsBuild(before.version(),before.vehicle(),before.physics(),before.budget(),slots),catalog,garage,installed,targets,values);
    }
    public PartsBuild before() {return before;}
    public PartsBuild after() {return after;}
    public PartsCatalog catalog() {return catalog;}
    public Tuning garage() {return garage;}
    public FittedParts installed() {return installed;}
    public Part target(Slot slot) {return targets.get(slot);}
    public List<Value> values() {return values;}
    public long adjustedCount() {return values.stream().filter(value->value.adjustment()!=Adjustment.KEPT).count();}
    public long changedSettings() {return values.stream().filter(value->value.draft()!=value.proposed()).count();}
}
