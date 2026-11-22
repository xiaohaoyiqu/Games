package com.f1car.model;

import java.util.*;

/** Preview-only migration through the existing explicit rebase policy; originals remain pinned. */
public record PartsPlanMigration(PartsPlan original,PartsBuild candidate,List<String> changes,List<String> issues) {
    public PartsPlanMigration {Objects.requireNonNull(original);Objects.requireNonNull(candidate);changes=List.copyOf(changes);issues=List.copyOf(issues);}
    public static PartsPlanMigration prepare(PartsPlan original,PartsCatalog catalog,String vehicle,String physics) {
        var result=original.build().rebase(catalog,vehicle,physics);var review=result.build().review(catalog,vehicle,physics,0);
        return new PartsPlanMigration(original,result.build(),result.changes(),review.issues());
    }
    public boolean changed() {return !original.build().equals(candidate);}
    public PartsPlan copy() {
        String name=original.name();int end=Math.min(35,name.length());if(end>0 && Character.isHighSurrogate(name.charAt(end-1))) end--;
        return PartsPlan.capture(name.substring(0,end)+" · 迁移",original.notes(),candidate);
    }
}
