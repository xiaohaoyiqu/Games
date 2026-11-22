package com.f1car.model;

import java.util.Objects;

/** A terminal author trial and the particular observation used to choose its route. */
public record CircuitObservationRetest(Source source,Result result) {
    public record Source(String mapId,long savedAt,String physicsVersion,CircuitDriveReview.Snapshot snapshot,int markerIndex) {
        public static final long PENDING=-1;

        public Source {
            if(mapId==null || !mapId.matches("user-[a-f0-9]{32}") || savedAt<PENDING || physicsVersion==null
                    || physicsVersion.isBlank() || physicsVersion.length()>120
                    || snapshot==null || snapshot.status()==CircuitDriveReview.Status.RUNNING
                    || markerIndex<0 || markerIndex>=snapshot.markers().size()) {
                throw new IllegalArgumentException("Invalid circuit observation source");
            }
        }

        public CircuitDriveReview.Marker marker() {return snapshot.markers().get(markerIndex);}

        public boolean matchesSaved(long stamp,String physics,CircuitDriveReview.Snapshot candidate) {
            return savedAt==stamp && physicsVersion.equals(physics) && snapshot.equals(candidate);
        }

        public boolean sameObservation(Source other) {
            return markerIndex==other.markerIndex && mapId.equals(other.mapId) && (savedAt==PENDING
                    ? other.savedAt==PENDING && snapshot==other.snapshot && physicsVersion.equals(other.physicsVersion)
                    : matchesSaved(other.savedAt,other.physicsVersion,other.snapshot));
        }
    }

    public record Result(String driveKey,String physicsVersion,String controls,CircuitSectionPractice.Range range,
                         CircuitSectionPractice.Start start,Integer split,CircuitSectionPractice.State state,Double elapsed,String failure) {
        public Result {
            Objects.requireNonNull(range);
            Objects.requireNonNull(start);
            ControlIdentity.require(controls);
            if(driveKey==null || !driveKey.matches("[a-f0-9]{64}") || physicsVersion==null || physicsVersion.isBlank()
                    || physicsVersion.length()>120 || split==null || split<0 || split>0 && (range.splitCount()<2 || split>range.splitCount())
                    || elapsed==null || !Double.isFinite(elapsed) || elapsed<0 || elapsed>CircuitTrial.LIMIT || failure==null || failure.length()>160
                    || !(state==CircuitSectionPractice.State.FINISHED && elapsed>0 && failure.isEmpty()
                         || state==CircuitSectionPractice.State.INVALID && !failure.isBlank())) {
                throw new IllegalArgumentException("Invalid circuit observation retest");
            }
        }
    }

    public CircuitObservationRetest {
        Objects.requireNonNull(source);
        Objects.requireNonNull(result);
    }

    public CircuitObservationRetest withSource(Source saved) {
        return new CircuitObservationRetest(saved,result);
    }
}
