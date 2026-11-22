package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;

/** Small vector definitions only; no raster, XML, executable or external references. */
public final class PaintRegionsCodec {
    public static final int MAX_BYTES=32000;
    private PaintRegionsCodec() {}
    static JsonElement tree(PaintRegions value) {return new Gson().toJsonTree(value);}
    static PaintRegions decode(PartsJson.Node root) throws IOException {try {root.object("version","vehicle","atlas","frameSize","regions");root.child("version").integer(1,1);int size=root.child("frameSize").integer(16,256);var regions=new ArrayList<PaintRegions.Region>();var values=root.child("regions").array(1,6);for(int i=0;i<values.size();i++) {var item=root.child("regions").index(i).object("target","polygons");var polygons=new ArrayList<PaintRegions.Polygon>();var shapes=item.child("polygons").array(1,4);for(int j=0;j<shapes.size();j++) {var polygon=item.child("polygons").index(j).object("points");var points=new ArrayList<PaintRegions.Point>();var vertices=polygon.child("points").array(3,12);for(int k=0;k<vertices.size();k++) {var p=polygon.child("points").index(k).object("x","y");points.add(new PaintRegions.Point(p.child("x").integer(0,size),p.child("y").integer(0,size)));}polygons.add(new PaintRegions.Polygon(points));}regions.add(new PaintRegions.Region(item.child("target").enumeration(CarLivery.Target.class),polygons));}return new PaintRegions(1,root.child("vehicle").token(),root.child("atlas").text(96,true),size,regions);}catch(IllegalArgumentException error) {throw new IOException("Invalid paint-region geometry",error);}}
    public static PaintRegions read(InputStream input) throws IOException {return decode(PartsJson.read(input,MAX_BYTES,10));}
}
