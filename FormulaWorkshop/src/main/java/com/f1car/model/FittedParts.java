package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/**
 * Explicitly fitted functional definitions. Removing or revising a catalogue cannot mutate them.
 * Reference: https://beamng.com/game/support/portal/gameplay/veh-config/ (parts determine tuning).
 * This constrains existing FormulaCar parameters; it introduces no new force or score identity.
 */
public record FittedParts(PartsBuild build,Map<Slot,Part> definitions) {
    public FittedParts {
        Objects.requireNonNull(build);Objects.requireNonNull(definitions);
        var copy=new EnumMap<Slot,Part>(Slot.class);copy.putAll(definitions);
        if(!copy.keySet().equals(EnumSet.allOf(Slot.class))) throw new IllegalArgumentException("Fitting requires four definitions");
        var selected=new HashSet<String>();build.slots().values().forEach(value->selected.add(value.part()));int points=0;
        if(selected.size()!=Slot.values().length) throw new IllegalArgumentException("Duplicate fitted part identity");
        for(var slot:Slot.values()) {
            var part=Objects.requireNonNull(copy.get(slot));var selection=build.slots().get(slot);
            if(part.slot()!=slot || !part.id().equals(selection.part()) || !part.fingerprint().equals(selection.fingerprint())) throw new IllegalArgumentException("Fitting definition differs from selected part");
            for(var field:slot.fields()) {int value=selection.values().get(field);var range=part.settings().get(field);if(value<range.min() || value>range.max()) throw new IllegalArgumentException("Fitting value exceeds part range");}
            if(!selected.containsAll(part.requires()) || !Collections.disjoint(selected,part.excludes())) throw new IllegalArgumentException("Incompatible fitted parts");
            points+=part.points();
        }
        if(points>build.budget()) throw new IllegalArgumentException("Fitted parts exceed budget");
        definitions=Collections.unmodifiableMap(copy);
    }
    public static FittedParts freeze(PartsBuild build,PartsCatalog catalog,String vehicle,String physics) {
        var review=build.review(catalog,vehicle,physics,0);if(!review.valid()) throw new IllegalArgumentException(review.issues().get(0));
        var definitions=new EnumMap<Slot,Part>(Slot.class);for(var slot:Slot.values()) definitions.put(slot,catalog.find(build.slots().get(slot).part()));
        return new FittedParts(build,definitions);
    }
    public Range range(Field field) {return definitions.get(field.slot()).settings().get(field);}
    public boolean compatible(String vehicle,String physics) {return build.vehicle().equals(vehicle) && build.physics().equals(physics);}
    /** Compare actual model values, including legacy sub-percent values, rather than rounded score keys. */
    public boolean accepts(Tuning tuning) {
        Objects.requireNonNull(tuning);
        for(var field:Field.values()) {var range=range(field);float value=field.ratio(tuning)*100;
            if(value<range.min()-.00002f || value>range.max()+.00002f) return false;}
        return true;
    }
    public Tuning initialTuning(int livery) {return build.tuning(livery);}
    /**
     * Capture current settings without rounding legacy sub-percent values into different physics.
     * Equipment identity must survive a copied settings sheet; a related regression is documented in
     * https://www.gran-turismo.com/us/gt7/news/00_8607273.html (Car Settings, engine swaps).
     */
    public FittedParts snapshot(Tuning tuning) {
        if(!accepts(tuning)) throw new IllegalArgumentException("Current tuning exceeds fitted equipment range");
        var slots=new EnumMap<Slot,PartsBuild.Selection>(Slot.class);
        for(var slot:Slot.values()) {
            var before=build.slots().get(slot);var values=new EnumMap<Field,Integer>(Field.class);
            for(var field:slot.fields()) {int percent=field.percent(tuning);
                if(Float.compare(field.ratio(tuning),percent/100f)!=0) throw new IllegalArgumentException("Current tuning cannot be represented as exact integer percentages");
                values.put(field,percent);
            }
            slots.put(slot,new PartsBuild.Selection(before.part(),before.fingerprint(),values));
        }
        var captured=new PartsBuild(build.version(),build.vehicle(),build.physics(),build.budget(),slots);
        return captured.equals(build)?this:new FittedParts(captured,definitions);
    }
    /** A checkpoint may change values, never installed definitions, vehicle, physics or budget. */
    public boolean sameEquipment(FittedParts other) {
        return other!=null && definitions.equals(other.definitions()) && build.version()==other.build().version()
            && build.vehicle().equals(other.build().vehicle()) && build.physics().equals(other.build().physics()) && build.budget()==other.build().budget();
    }
}
