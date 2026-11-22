package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.*;

/** Authored corner landmarks. Distances refer to route anchors, not recommended brake points. */
public final class TrackGuide {
    public static final float ROAD_MARGIN_METERS=1;
    private static final float SCENERY_CLEARANCE_SQUARED=12*12;
    public record Section(TrackCatalog.Section definition,float distance) {}
    public record Marker(String section,String name,int meters,Vector3f position,Vector3f tangent,float distance) {
        public Marker {
            position=position.clone();
            tangent=tangent.clone();
        }

        @Override public Vector3f position() {return position.clone();}
        @Override public Vector3f tangent() {return tangent.clone();}
    }
    public record Approach(String name,float remaining) {}
    private final TrackLayout track;
    private final List<Section> sections;
    private final List<Marker> markers;
    private TrackGuide(TrackLayout track,List<TrackCatalog.Section> definitions) {
        this.track=Objects.requireNonNull(track);
        var controls=definitions.isEmpty()?List.<Vector3f>of():track.controlPoints();
        if(controls.isEmpty() || definitions.stream().anyMatch(section->section.anchor()>=controls.size())) {
            sections=List.of();
            markers=List.of();
            return;
        }
        sections=definitions.stream().map(definition->new Section(definition,track.project(controls.get(definition.anchor())).distance())).sorted(Comparator.comparingDouble(Section::distance)).toList();
        var positions=new ArrayList<Marker>();
        for(var section:sections) for(int meters:section.definition().boards()) {
            var sample=track.sample(section.distance()-meters);
            // Prefer the authored side; omit a board when neither verge has enough space.
            for(int side:new int[]{section.definition().side(),-section.definition().side()}) {
                var p=sample.position().add(sample.right().mult(side*(track.width*.5f+8)));
                if(!TrackScenery.clearOfRoad(track,p,5) || !clearOfWalls(track,p)) continue;
                positions.add(new Marker(section.definition().id(),section.definition().name(),meters,p,sample.tangent(),sample.distance()));break;
            }
        }
        markers=List.copyOf(positions);
    }
    public static TrackGuide forTrack(TrackLayout track) {
        return track.guide();
    }

    static TrackGuide build(TrackLayout track) {
        var entry=TrackCatalog.forLayout(track);
        var definitions=entry.sections();
        return new TrackGuide(track,!definitions.isEmpty() && entry.layout().geometryKey().equals(track.geometryKey())?definitions:List.of());
    }
    public List<Section> sections() { return sections; }
    public List<Marker> markers() { return markers; }

    /** Leaves the space near each distance board clear of decorative scenery. */
    public boolean closeToMarker(float x,float z) {
        for(var marker:markers) {
            var position=marker.position;
            // Match Vector3f.distanceSquared for a point on the ground, including float rounding.
            double dx=position.x-x,dy=position.y,dz=position.z-z;
            if((float)(dx*dx+dy*dy+dz*dz)<SCENERY_CLEARANCE_SQUARED) return true;
        }
        return false;
    }

    /** Use the actual position after a recovery or a new session. */
    public Optional<Approach> upcoming(Vector3f position) {
        if(sections.isEmpty() || !Vector3f.isValidVector(position)) return Optional.empty();
        return upcomingAt(track.project(position));
    }

    /** Accepts an existing projection on this guide's route. */
    public Optional<Approach> upcomingAt(TrackLayout.Projection projection) {
        if(sections.isEmpty() || projection==null || !Float.isFinite(projection.distance())
                || !(projection.separation()<=track.width*.5f+ROAD_MARGIN_METERS)) return Optional.empty();
        float distance=projection.distance();
        Section nearest=null;
        float remaining=Float.POSITIVE_INFINITY;
        for(var section:sections) { float delta=TrackLayout.wrap(section.distance()-distance,track.length);if(delta<remaining) { remaining=delta;nearest=section; } }
        return Optional.of(new Approach(Objects.requireNonNull(nearest).definition().name(),remaining));
    }
    private static boolean clearOfWalls(TrackLayout track,Vector3f p) {
        for(var wall:TrackScenery.solids(track)) if(TrackScenery.distanceTo(p,wall)<5) return false;
        return true;
    }
}
