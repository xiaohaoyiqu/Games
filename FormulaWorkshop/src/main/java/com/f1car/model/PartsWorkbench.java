package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** UI edits are bounded immutable drafts; expensive review happens only after a relevant change. */
public final class PartsWorkbench {
    public record State(PartsBuild build,List<PartsBuild> undo,PartsComparison.Reference reference) {
        public State {
            Objects.requireNonNull(build);if(undo==null || undo.size()>24) throw new IllegalArgumentException("Invalid workbench history");
            undo=List.copyOf(undo);
        }
    }
    private PartsCatalog catalog;
    private PartsBuild build;
    private final Deque<PartsBuild> undo=new ArrayDeque<>();
    private PartsBuild reviewedBuild;private PartsCatalog reviewedCatalog;private PartsBuild.Review review;
    private String reviewedVehicle,reviewedPhysics;private int reviewedLivery,reviewCount;
    private PartsComparison.Reference reference;
    private long revision;
    private PartsBuild comparedBuild;private PartsCatalog comparedCatalog;private PartsComparison.Reference comparedReference;
    private String comparedVehicle,comparedPhysics;private Tuning comparedGarage;private PartsComparison comparison;private int comparisonCount;
    public PartsWorkbench(PartsCatalog catalog,PartsBuild build) {this.catalog=Objects.requireNonNull(catalog);this.build=Objects.requireNonNull(build);}
    public PartsBuild build() {return build;}
    public PartsCatalog catalog() {return catalog;}
    public long revision() {return revision;}
    public State state() {return new State(build,List.copyOf(undo),reference);}
    public static PartsWorkbench restore(PartsCatalog catalog,State state) {
        var bench=new PartsWorkbench(catalog,state.build());bench.undo.addAll(state.undo());bench.reference=state.reference();return bench;
    }
    public boolean canUndo() {return !undo.isEmpty();}
    public int reviewComputations() {return reviewCount;}
    public PartsComparison.Reference reference() {return reference;}
    public void reference(PartsComparison.Reference value) {if(!Objects.equals(reference,value)) {reference=value;revision++;}}
    public int comparisonComputations() {return comparisonCount;}
    public PartsComparison comparison(String vehicle,String physics,Tuning garage) {
        if(comparedBuild!=build || comparedCatalog!=catalog || !Objects.equals(comparedReference,reference) || !Objects.equals(comparedVehicle,vehicle) || !Objects.equals(comparedPhysics,physics) || !Objects.equals(comparedGarage,garage)) {
            comparison=PartsComparison.prepare(catalog,reference,build,vehicle,physics,garage);comparedBuild=build;comparedCatalog=catalog;comparedReference=reference;comparedVehicle=vehicle;comparedPhysics=physics;comparedGarage=garage;comparisonCount++;
        }return comparison;
    }
    public void catalog(PartsCatalog value) {catalog=Objects.requireNonNull(value);}
    public void adopt(PartsBuild value) {Objects.requireNonNull(value);if(build.equals(value)) return;undo.addLast(build);while(undo.size()>24) undo.removeFirst();build=value;revision++;}
    public void select(String id) {
        var part=catalog.find(id);if(part==null) return;var selected=build.slots().get(part.slot());
        if(selected.part().equals(id) && selected.fingerprint().equals(catalog.fingerprint(id))
            && part.settings().entrySet().stream().allMatch(row->{int value=selected.values().get(row.getKey());return value>=row.getValue().min() && value<=row.getValue().max();})) return;
        adopt(build.select(catalog,part));
    }
    /** Restoring defaults is explicit and affects only this slot, with one undo step. */
    public boolean resetSelected(Slot slot) {
        var selected=build.slots().get(slot);var part=catalog.find(selected.part());
        if(part==null || part.slot()!=slot || !selected.fingerprint().equals(catalog.fingerprint(part.id()))) return false;
        adopt(build.select(catalog,part));return true;
    }
    public void change(Slot slot,Field field,int delta) {adopt(build.change(catalog,slot,field,delta));}
    public void setValue(Slot slot,Field field,int value) {adopt(build.setValue(catalog,slot,field,value));}
    public void undo() {if(!undo.isEmpty()) {build=undo.removeLast();revision++;}}
    public PartsBuild.Review review(String vehicle,String physics,int livery) {
        if(reviewedBuild!=build || reviewedCatalog!=catalog || !Objects.equals(reviewedVehicle,vehicle) || !Objects.equals(reviewedPhysics,physics) || reviewedLivery!=livery) {
            review=build.review(catalog,vehicle,physics,livery);reviewedBuild=build;reviewedCatalog=catalog;reviewedVehicle=vehicle;reviewedPhysics=physics;reviewedLivery=livery;reviewCount++;
        }
        return review;
    }
}
