package com.f1car.model;

/** Portable provider declaration plus an unchanged complete source-bearing lap; never authentication. */
public record EquipmentLapShare(int version,String author,String notes,EquipmentLap recording) {
    public EquipmentLapShare {
        if(version!=1 || !PartsCatalog.text(author,40) || author.isBlank() || notes==null || notes.length()>320 || !PartsCatalog.text(notes.replace("\n",""),320) || recording==null) throw new IllegalArgumentException("Invalid equipment lap share");
    }
    public String id() {return recording.id();}
}
