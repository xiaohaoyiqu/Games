package com.f1car.model;

import java.util.*;

/** Original canonical sprite-space geometry, pinned to encoded atlas bytes; never a collision mask. */
public record PaintRegions(int version,String vehicle,String atlas,int frameSize,List<Region> regions) {
    public record Point(int x,int y) {public Point {if(x<0 || x>256 || y<0 || y>256) throw new IllegalArgumentException("Invalid mask coordinate");}}
    public record Polygon(List<Point> points) {
        public Polygon {points=List.copyOf(points);if(points.size()<3 || points.size()>12 || new HashSet<>(points).size()!=points.size()) throw new IllegalArgumentException("Invalid mask polygon");long area=0;for(int i=0;i<points.size();i++) {var a=points.get(i);var b=points.get((i+1)%points.size());area+=(long)a.x()*b.y()-(long)a.y()*b.x();for(int j=i+1;j<points.size();j++) {int next=(i+1)%points.size(),other=(j+1)%points.size();if(j==next || other==i) continue;if(intersects(a,b,points.get(j),points.get(other))) throw new IllegalArgumentException("Self-intersecting mask polygon");}}if(area==0) throw new IllegalArgumentException("Empty mask polygon");}
    }
    public record Region(CarLivery.Target target,List<Polygon> polygons) {public Region {if(target==null || target==CarLivery.Target.ALL || polygons==null || polygons.isEmpty() || polygons.size()>4) throw new IllegalArgumentException("Invalid paint region");polygons=List.copyOf(polygons);}}
    public PaintRegions {
        if(version!=1 || vehicle==null || !vehicle.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || atlas==null || !atlas.matches("car-png-sha256-[a-f0-9]{64}") || frameSize<16 || frameSize>256 || regions==null || regions.isEmpty() || regions.size()>6) throw new IllegalArgumentException("Invalid paint regions");regions=List.copyOf(regions);if(regions.stream().map(Region::target).distinct().count()!=regions.size()) throw new IllegalArgumentException("Duplicate paint region");for(var region:regions) for(var polygon:region.polygons()) for(var point:polygon.points()) if(point.x()>frameSize || point.y()>frameSize) throw new IllegalArgumentException("Mask outside canonical frame");
    }
    public boolean matches(String model,String identity,int size) {return vehicle.equals(model) && atlas.equals(identity) && frameSize==size;}
    public boolean supports(CarLivery.Target target) {return target==CarLivery.Target.ALL || regions.stream().anyMatch(region->region.target()==target);}
    private static long turn(Point a,Point b,Point c) {return (long)(b.x()-a.x())*(c.y()-a.y())-(long)(b.y()-a.y())*(c.x()-a.x());}
    private static boolean on(Point a,Point b,Point p) {return turn(a,b,p)==0 && p.x()>=Math.min(a.x(),b.x()) && p.x()<=Math.max(a.x(),b.x()) && p.y()>=Math.min(a.y(),b.y()) && p.y()<=Math.max(a.y(),b.y());}
    private static boolean intersects(Point a,Point b,Point c,Point d) {long x=turn(a,b,c),y=turn(a,b,d),z=turn(c,d,a),w=turn(c,d,b);return Long.signum(x)*Long.signum(y)<0 && Long.signum(z)*Long.signum(w)<0 || on(a,b,c) || on(a,b,d) || on(c,d,a) || on(c,d,b);}
}
