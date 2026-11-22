package com.f1car.model;

import java.util.*;

/** Named immutable build; local identity stays stable across explicit edits. */
public record PartsPlan(String id,String name,String notes,PartsBuild build) {
    public static final int CAPACITY=16;
    public PartsPlan {
        if(id==null || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") || !PartsCatalog.text(name,40) || name.isBlank() || !PartsCatalog.text(notes,120) || build==null) throw new IllegalArgumentException("Invalid parts plan card");
    }
    public static PartsPlan capture(String name,String notes,PartsBuild build) {return new PartsPlan(UUID.randomUUID().toString(),name.strip(),notes.strip(),build);}
    public PartsPlan withText(String name,String notes) {return new PartsPlan(id,name.strip(),notes.strip(),build);}
    public PartsPlan withBuild(PartsBuild value) {return new PartsPlan(id,name,notes,value);}
}
