package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.awt.geom.Line2D;

/** Shared obstacle footprints for pixel maps, collision geometry, road checks and recovery. */
public final class TrackScenery {
    private static final double MIN_ROAD_CLEARANCE=2;
    public record Wall(Vector3f position,Vector3f tangent,float length,float width) {
        public Wall {
            position=position.clone();
            tangent=tangent.clone();
        }
        @Override public Vector3f position() {return position.clone();}
        @Override public Vector3f tangent() {return tangent.clone();}
    }
    record Footprints(List<Wall> pitWalls,List<Wall> buildings,List<Wall> solids) {}

    private TrackScenery() {}
    public static List<Wall> pitWalls(TrackLayout track) {
        return track.sceneryFootprints().pitWalls();
    }
    public static Vector3f paddock(TrackLayout track,int index) {
        var sample=track.sample(25+index*22);
        return sample.position().add(sample.right().mult(-(track.width*.5f+28)));
    }
    public static List<Wall> buildings(TrackLayout track) {
        return track.sceneryFootprints().buildings();
    }
    /** Shared horizontal collision footprints, including buildings, for recovery and validation. */
    public static List<Wall> solids(TrackLayout track) {
        return track.sceneryFootprints().solids();
    }

    static Footprints buildFootprints(TrackLayout track) {
        var barriers=new ArrayList<Wall>();
        var structures=new ArrayList<Wall>();
        var scene=track.scene();
        if(scene==null) {
            for(int i=0;i<12;i++) {
                var sample=track.sample(25+i*8);
                var position=sample.position().add(sample.right().mult(-(track.width*.5f+3)));
                barriers.add(new Wall(position,sample.tangent(),7.6f,1));
            }
        } else {
            for(var item:scene.items()) {
                switch(item.kind()) {
                    case BARRIER -> barriers.add(footprint(item));
                    case BUILDING -> structures.add(footprint(item));
                    default -> {}
                }
            }
        }
        var pitWalls=List.copyOf(barriers);
        var buildings=List.copyOf(structures);
        if(pitWalls.isEmpty()) return new Footprints(pitWalls,buildings,buildings);
        if(buildings.isEmpty()) return new Footprints(pitWalls,buildings,pitWalls);
        barriers.addAll(buildings);
        return new Footprints(pitWalls,buildings,List.copyOf(barriers));
    }

    private static Wall footprint(CircuitScene.Item item) {
        return new Wall(item.position(),item.tangent(),item.length(),item.width());
    }

    public static float distanceTo(Vector3f position,Wall wall) {
        var origin=wall.position;
        var tangent=wall.tangent;
        float dx=position.x-origin.x,dy=position.y-origin.y,dz=position.z-origin.z;
        float along=dx*tangent.x+dy*tangent.y+dz*tangent.z;
        float across=dx*tangent.z+dy*0f+dz*(-tangent.x);
        return (float)Math.hypot(Math.max(0,Math.abs(across)-wall.width()*.5f),Math.max(0,Math.abs(along)-wall.length()*.5f));
    }
    /** Clearance from the actual oriented rectangle to the sampled road envelope. */
    public static double roadClearance(TrackLayout track,CircuitScene.Item item) {
        if(!item.solid()) return track.project(item.position()).separation()-track.width*.5f-item.clearance();
        var footprint=new RoadRectangle(item);
        double closest=Double.POSITIVE_INFINITY;
        for(int i=0;i<track.points.size()-1;i++) {
            closest=Math.min(closest,footprint.distanceSquared(track,i,closest));
            if(closest==0) break;
        }
        return Math.sqrt(closest)-track.width*.5;
    }

    /** Whether the footprint lies strictly closer than the editor's two-metre road buffer. */
    public static boolean intrudesOnRoad(TrackLayout track,CircuitScene.Item item) {
        if(!item.solid()) return roadClearance(track,item)<MIN_ROAD_CLEARANCE;
        var footprint=new RoadRectangle(item);
        double roadHalfWidth=track.width*.5;
        double reach=roadHalfWidth+MIN_ROAD_CLEARANCE;
        double reachSquared=reach*reach;
        // The local axes share one scale. Convert the outer circle back to world coordinates;
        // the route query pads it for the float coordinate transforms used by the edge check.
        double scale=Math.hypot(footprint.tangent.x,footprint.tangent.z);
        float radius=(float)((Math.hypot(footprint.halfX,footprint.halfZ)+reach)/scale);
        return track.anySegmentNear(footprint.origin,radius,index->
                Math.sqrt(footprint.distanceSquared(track,index,reachSquared))-roadHalfWidth<MIN_ROAD_CLEARANCE);
    }

    private static final class RoadRectangle {
        private final Vector3f origin,tangent,right;
        private final Vector3f a=new Vector3f(),b=new Vector3f();
        private final double halfX,halfZ;
        private final double[][] corners;

        RoadRectangle(CircuitScene.Item item) {
            origin=item.position();
            tangent=item.tangent();
            right=new Vector3f(tangent.z,0,-tangent.x);
            halfX=item.width()*.5;
            halfZ=item.length()*.5;
            corners=new double[][]{{-halfX,-halfZ},{halfX,-halfZ},{halfX,halfZ},{-halfX,halfZ}};
        }

        double distanceSquared(TrackLayout track,int index,double limitSquared) {
            track.copyPoint(index,a).subtractLocal(origin);
            track.copyPoint(index+1,b).subtractLocal(origin);
            double ax=a.dot(right),az=a.dot(tangent),bx=b.dot(right),bz=b.dot(tangent);
            if(Math.abs(ax)<=halfX && Math.abs(az)<=halfZ || Math.abs(bx)<=halfX && Math.abs(bz)<=halfZ) return 0;
            // A padded local rectangle gives a lower bound; retain exact edge distances for every possible improvement.
            double gapX=Math.max(0,Math.max(Math.min(ax,bx)-halfX-1e-4,-halfX-Math.max(ax,bx)-1e-4));
            double gapZ=Math.max(0,Math.max(Math.min(az,bz)-halfZ-1e-4,-halfZ-Math.max(az,bz)-1e-4));
            if(gapX*gapX+gapZ*gapZ>limitSquared) return Double.POSITIVE_INFINITY;
            double closest=Double.POSITIVE_INFINITY;
            for(int edge=0;edge<4;edge++) {
                var c=corners[edge];var d=corners[(edge+1)%4];
                if(Line2D.linesIntersect(ax,az,bx,bz,c[0],c[1],d[0],d[1])) return 0;
                closest=Math.min(closest,Math.min(Math.min(Line2D.ptSegDistSq(ax,az,bx,bz,c[0],c[1]),Line2D.ptSegDistSq(ax,az,bx,bz,d[0],d[1])),Math.min(Line2D.ptSegDistSq(c[0],c[1],d[0],d[1],ax,az),Line2D.ptSegDistSq(c[0],c[1],d[0],d[1],bx,bz))));
            }
            return closest;
        }
    }
    public static boolean clearOfRoad(TrackLayout track,Vector3f position,float clearance) {
        return track.project(position).separation()>track.width*.5f+clearance;
    }
    /** Roadside lesson spawns avoid real wall footprints and ambiguous neighboring road branches. */
    public static Optional<TrackLayout.Sample> rejoinSpawn(TrackLayout track) {
        var walls=solids(track);
        for(float distance:new float[]{-18,-26,-34}) for(int side:new int[]{1,-1}) {
            var sample=track.sample(distance);var position=sample.position().add(sample.right().mult(side*(track.width*.5f+5)));var projection=track.project(position);
            float arc=Math.abs(TrackLayout.wrap(projection.distance()-distance+track.length*.5f,track.length)-track.length*.5f);
            if(projection.separation()<track.width*.5f+3 || arc>10) continue;
            boolean free=true;
            for(var wall:walls) {
                if(distanceTo(position,wall)<2.5f) { free=false;break; }
            }
            if(free) return Optional.of(new TrackLayout.Sample(position,sample.tangent(),sample.right(),distance));
        }
        return Optional.empty();
    }
}
