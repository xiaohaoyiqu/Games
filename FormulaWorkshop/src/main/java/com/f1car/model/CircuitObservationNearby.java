package com.f1car.model;

import com.f1car.model.CircuitInspector.Location;
import com.jme3.math.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selectable map features near a recorded world position, in the reviewed geometry only. */
public final class CircuitObservationNearby {
    public static final int RADIUS_METERS=24;
    private static final int MAX_ROUTE_POINTS=2,MAX_SCENERY=4;
    private static final Comparator<Target> BY_DISTANCE=Comparator.comparingDouble(Target::meters).thenComparingInt(Target::index);

    public record Target(int index,Location center,CircuitScene.Kind kind,double meters) {
        public boolean routePoint() {return kind==null;}
        public boolean solid() {return kind==CircuitScene.Kind.BARRIER || kind==CircuitScene.Kind.BUILDING;}
    }

    public record RoadReadout(Location center,float centerlineMeters,float beyondHalfWidthMeters) {}
    public record RoadCandidate(CircuitObservationPractice.Candidate choice,RoadReadout readout,float directionX,float directionZ) {}

    private final CircuitDocument document;
    private final Location observation;
    private final List<Target> routePoints,scenery;
    private final TrackLayout road;
    private final RoadReadout roadReadout;
    private final List<RoadCandidate> roadCandidates;

    public CircuitObservationNearby(CircuitDocument document,Location observation) {
        this.document=Objects.requireNonNull(document);
        this.observation=Objects.requireNonNull(observation);
        var points=new ArrayList<Target>();
        for(int index=0;index<document.anchors().size();index++) {
            var point=document.anchors().get(index);
            double meters=Math.hypot(point.x()-observation.x(),point.z()-observation.z());
            if(meters<=RADIUS_METERS) points.add(new Target(index,new Location(point.x(),point.z()),null,meters));
        }
        routePoints=nearest(points,MAX_ROUTE_POINTS);

        var objects=new ArrayList<Target>();
        var items=document.scene().items();
        var position=new Vector3f(observation.x(),0,observation.z());
        for(int index=0;index<items.size();index++) {
            var item=items.get(index);
            double meters=item.solid()?TrackScenery.distanceTo(position,new TrackScenery.Wall(item.position(),item.tangent(),item.length(),item.width()))
                    :Math.hypot(item.x()-observation.x(),item.z()-observation.z());
            if(meters<=RADIUS_METERS) objects.add(new Target(index,new Location(item.x(),item.z()),item.kind(),meters));
        }
        scenery=nearest(objects,MAX_SCENERY);
        road=roadPreview(document);
        if(road==null) {
            roadReadout=null;
            roadCandidates=List.of();
        } else {
            var projection=road.project(position);
            roadReadout=readout(road.sample(projection.distance()),projection.separation(),road.width);
            roadCandidates=CircuitObservationPractice.around(road,observation).stream().map(choice->{
                var sample=road.sample(choice.distance());
                return new RoadCandidate(choice,readout(sample,choice.separation(),road.width),sample.tangent().x,sample.tangent().z);
            }).toList();
        }
    }

    private static RoadReadout readout(TrackLayout.Sample sample,float separation,float width) {
        var point=sample.position();
        return new RoadReadout(new Location(point.x,point.z),separation,Math.max(0,separation-width*.5f));
    }

    private static TrackLayout roadPreview(CircuitDocument document) {
        try {return document.roadPreview();}
        catch(IllegalArgumentException invalid) {return null;}
    }

    private static List<Target> nearest(List<Target> values,int limit) {
        values.sort(BY_DISTANCE);
        return List.copyOf(values.subList(0,Math.min(limit,values.size())));
    }

    public Location observation() {return observation;}
    public List<Target> routePoints() {return routePoints;}
    public List<Target> scenery() {return scenery;}
    public TrackLayout road() {return road;}
    public RoadReadout roadReadout() {return roadReadout;}
    public List<RoadCandidate> roadCandidates() {return roadCandidates;}

    public boolean matches(CircuitDocument current) {
        return document==current || current!=null && document.id().equals(current.id())
                && Float.compare(document.width(),current.width())==0 && document.anchors().equals(current.anchors())
                && document.scene().equals(current.scene());
    }

    boolean contains(Target target) {return target!=null && (target.routePoint()?routePoints:scenery).contains(target);}
}
