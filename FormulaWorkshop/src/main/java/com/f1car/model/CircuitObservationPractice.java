package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Suggests adjustable practice ranges from one editor observation. */
public final class CircuitObservationPractice {
    private static final float ROADSIDE_ALLOWANCE_METRES=8,NEAR_TIE_METRES=5;
    private static final int HALF_RANGE_PERCENT=5;

    public record Candidate(float distance,float separation,CircuitSectionPractice.Range range) {}
    private record Projection(float distance,float separation) {}

    private CircuitObservationPractice() {}

    public static List<Candidate> around(TrackLayout track,CircuitInspector.Location observation) {
        if(track==null || !track.closed || observation==null) return List.of();
        var projections=new ArrayList<Projection>();
        float firstFraction=0,lastFraction=0;
        var start = new Vector3f();
        var edge = new Vector3f();
        var toObservation = new Vector3f();
        var along = new Vector3f();
        for(int i=0;i<track.points.size()-1;i++) {
            track.copyPoint(i, start);
            track.copyPoint(i + 1, edge).subtractLocal(start);
            toObservation.set(observation.x()-start.x,0,observation.z()-start.z);
            float fraction=Math.max(0,Math.min(1,toObservation.dot(edge)/edge.lengthSquared()));
            float length=edge.length();
            if(i==0) firstFraction=fraction;
            if((fraction>0 && fraction<1) || (i>0 && lastFraction==1 && fraction==0)) {
                float separation=toObservation.subtractLocal(edge.mult(fraction, along)).length();
                projections.add(new Projection(track.pointDistance(i)+fraction*length,separation));
            }
            lastFraction=fraction;
        }
        if(lastFraction==1 && firstFraction==0) {
            var first=track.points.get(0);
            projections.add(new Projection(0,(float)Math.hypot(observation.x()-first.x,observation.z()-first.z)));
        }
        if(projections.isEmpty()) return List.of();

        projections.sort(Comparator.comparingDouble(Projection::separation).thenComparingDouble(Projection::distance));
        float limit=Math.min(track.width*.5f+ROADSIDE_ALLOWANCE_METRES,projections.get(0).separation()+NEAR_TIE_METRES);
        float minimumRouteSeparation=Math.max(3,Math.min(12,track.length*.01f));
        var candidates=new ArrayList<Candidate>();
        for(var projection:projections) {
            if(projection.separation()>limit) break;
            var range=centred(projection.distance(),track.length);
            boolean nearby=candidates.stream().anyMatch(candidate->candidate.range().equals(range)
                    || arcDistance(candidate.distance(),projection.distance(),track.length)<minimumRouteSeparation);
            if(!nearby) candidates.add(new Candidate(projection.distance(),projection.separation(),range));
        }
        return List.copyOf(candidates);
    }

    private static float arcDistance(float a,float b,float length) {
        float difference=Math.abs(a-b);
        return Math.min(difference,length-difference);
    }

    private static CircuitSectionPractice.Range centred(float distance,float length) {
        int centre=Math.round(distance/length*100)%100;
        int from=Math.floorMod(centre-HALF_RANGE_PERCENT,100);
        int to=(centre+HALF_RANGE_PERCENT)%100;
        return new CircuitSectionPractice.Range(from,to==0?100:to);
    }
}
