package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Conditional one-field exploration of the existing solver-input model, never an optimizer or measurement. */
public final class PartsRangePreview {
    public enum Role {GARAGE,MINIMUM,INITIAL,SELECTED,MAXIMUM}
    public record Column(Role role,VehicleSetupPreview setup) {public Column {Objects.requireNonNull(role);Objects.requireNonNull(setup);}}
    public record Reading(Column column,VehicleSetupPreview.Sample sample) {public Reading {Objects.requireNonNull(column);Objects.requireNonNull(sample);}}
    public enum PairRole {BASELINE,FIRST,SECOND,COMBINED}
    public record PairColumn(PairRole role,PartsBuild build,VehicleSetupPreview setup) {}
    public record PairReading(PairColumn column,VehicleSetupPreview.Sample sample) {}
    /** Four model evaluations at explicitly selected values, not four measured trials. */
    public static final class Pair {
        private final List<PairColumn> columns;private final int maximumKph;
        private Pair(List<PairColumn> columns) {
            this.columns=List.copyOf(columns);int maximum=Integer.MAX_VALUE;var first=columns.get(0).setup();
            for(var column:columns) maximum=Math.min(maximum,new VehicleSetupPreview.Comparison(first,column.setup()).maximumKph());maximumKph=maximum;
        }
        public List<PairColumn> columns() {return columns;}public int maximumKph() {return maximumKph;}
        public PartsBuild candidate() {return columns.get(3).build();}
        public List<PairReading> atKph(int speed) {if(speed<0 || speed>maximumKph) throw new IllegalArgumentException("Speed outside the common range");return columns.stream().map(column->new PairReading(column,column.setup().sample(speed/3.6f))).toList();}
    }
    public static final class Sweep {
        private final Field field;private final Range range;private final int selected,maximumKph;private final List<Column> columns;
        private Sweep(Field field,Range range,int selected,List<Column> columns) {
            this.field=field;this.range=range;this.selected=selected;this.columns=List.copyOf(columns);
            int maximum=Integer.MAX_VALUE;var first=columns.get(0).setup();for(var column:columns) maximum=Math.min(maximum,new VehicleSetupPreview.Comparison(first,column.setup()).maximumKph());maximumKph=maximum;
        }
        public Field field() {return field;}public Range range() {return range;}public int selected() {return selected;}public int maximumKph() {return maximumKph;}public List<Column> columns() {return columns;}
        public List<Reading> atKph(int speed) {if(speed<0 || speed>maximumKph) throw new IllegalArgumentException("Speed outside the common range");float metres=speed/3.6f;return columns.stream().map(column->new Reading(column,column.setup().sample(metres))).toList();}
    }
    private final VehicleSetupPreview.Source source;private final PartsBuild build;private final PartsCatalog catalog;private final Tuning garage,baseline;
    private final Map<Slot,Part> definitions;
    public PartsRangePreview(VehicleSetupPreview.Source source,Tuning garage,PartsBuild build,PartsCatalog catalog) {
        this.source=Objects.requireNonNull(source);this.garage=Objects.requireNonNull(garage);this.build=Objects.requireNonNull(build);this.catalog=Objects.requireNonNull(catalog);
        if(!build.vehicle().equals(source.vehicle()) || !build.physics().equals(source.physics())) throw new IllegalArgumentException("Range study vehicle/physics differ");
        var parts=new EnumMap<Slot,Part>(Slot.class);
        for(var slot:Slot.values()) {var selected=build.slots().get(slot);var part=catalog.find(selected.part());if(part==null || part.slot()!=slot || !part.fingerprint().equals(selected.fingerprint())) throw new IllegalArgumentException("Missing or revised range definition");for(var field:slot.fields()) {int value=selected.values().get(field);var range=part.settings().get(field);if(value<range.min() || value>range.max()) throw new IllegalArgumentException("Draft value outside its pinned range");}parts.put(slot,part);}
        definitions=Collections.unmodifiableMap(parts);baseline=build.tuning(garage.livery());new VehicleSetupPreview(source,garage);new VehicleSetupPreview(source,baseline);
    }
    public VehicleSetupPreview.Source source() {return source;}public PartsBuild build() {return build;}public PartsCatalog catalog() {return catalog;}public Tuning garage() {return garage;}public Tuning baseline() {return baseline;}
    public Part part(Field field) {return definitions.get(Objects.requireNonNull(field).slot());}
    public Range range(Field field) {return part(field).settings().get(field);}
    public int draftPercent(Field field) {return build.slots().get(field.slot()).values().get(field);}
    private PartsBuild pairCandidate(Field first,int firstPercent,Field second,int secondPercent) {
        Objects.requireNonNull(first);Objects.requireNonNull(second);if(first==second) throw new IllegalArgumentException("Choose two different fields");
        return build.setValue(catalog,first.slot(),first,firstPercent).setValue(catalog,second.slot(),second,secondPercent);
    }
    public Pair pair(Field first,int firstPercent,Field second,int secondPercent) {
        var combined=pairCandidate(first,firstPercent,second,secondPercent);
        var onlyFirst=build.setValue(catalog,first.slot(),first,firstPercent);var onlySecond=build.setValue(catalog,second.slot(),second,secondPercent);
        return new Pair(List.of(pairColumn(PairRole.BASELINE,build),pairColumn(PairRole.FIRST,onlyFirst),pairColumn(PairRole.SECOND,onlySecond),pairColumn(PairRole.COMBINED,combined)));
    }
    private PairColumn pairColumn(PairRole role,PartsBuild value) {return new PairColumn(role,value,new VehicleSetupPreview(source,value.tuning(garage.livery())));}
    /** A real A/B compares the baseline with the joint candidate; the two intermediate columns are model-only. */
    public TuningPurpose.Draft pairBrief(Field first,int firstPercent,Field second,int secondPercent) {
        var candidate=pairCandidate(first,firstPercent,second,secondPercent);
        if(firstPercent==draftPercent(first) || secondPercent==draftPercent(second)) throw new IllegalArgumentException("A paired trial requires both fields to change");
        return new TuningPurpose.Draft(new TuningPurpose.Intent("同时改变"+first.title+"（"+draftPercent(first)+"% → "+firstPercent+"%）和"+second.title+"（"+draftPercent(second)+"% → "+secondPercent+"%），本项目表现如何？",
            "比较原草稿与组合候选各三次有效测量的中位数和波动。","组合 A/B 不分离两项贡献，也不测交互强度、整圈收益或最优设置。"),
            TuningPurpose.Origin.capture("双参数试车 · 原草稿",build,catalog),TuningPurpose.Origin.capture("双参数试车 · 组合候选",candidate,catalog));
    }
    /** A real trial compares the entire original draft with exactly one changed value, never the garage. */
    public TuningPurpose.Draft trialBrief(Field field,int selected) {
        Objects.requireNonNull(field);
        if(selected==draftPercent(field)) throw new IllegalArgumentException("A single-field trial requires a change");
        var candidate=build.setValue(catalog,field.slot(),field,selected);
        return new TuningPurpose.Draft(new TuningPurpose.Intent("只改变"+field.title+"："+draftPercent(field)+"% → "+selected+"%，本项目表现如何？",
            "比较各三次有效测量的中位数与波动。","此项目不代表其它项目、整圈收益或最优设置。"),
            TuningPurpose.Origin.capture("单项试车 · 原草稿",build,catalog),TuningPurpose.Origin.capture("单项试车 · 候选",candidate,catalog));
    }
    public Sweep sweep(Field field,int selected) {
        Objects.requireNonNull(field);var range=range(field);if(selected<range.min() || selected>range.max()) throw new IllegalArgumentException("Preview value outside equipment range");
        return new Sweep(field,range,selected,List.of(new Column(Role.GARAGE,new VehicleSetupPreview(source,garage)),column(Role.MINIMUM,field,range.min()),column(Role.INITIAL,field,range.initial()),column(Role.SELECTED,field,selected),column(Role.MAXIMUM,field,range.max())));
    }
    private Column column(Role role,Field field,int percent) {return new Column(role,new VehicleSetupPreview(source,baseline.withPercent(field,percent)));}
}
