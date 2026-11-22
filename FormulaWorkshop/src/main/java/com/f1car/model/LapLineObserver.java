package com.f1car.model;

/** Retains the preceding frame so an ordered gate crossing can be sampled at its own time. */
final class LapLineObserver {
    record Boundary(GhostLap.Sample pose, TelemetryLap.Driving driving, VehicleObservation observation) {}
    private static final Boundary MISSING = new Boundary(null, null, null);
    private double previousTime = Double.NaN;
    private GhostLap.Sample previousPose;
    private TelemetryLap.Driving previousDriving;
    private VehicleObservation previousObservation;

    boolean hasGap(double time) {
        return time - previousTime > TelemetryRecorder.MAX_OBSERVATION_GAP;
    }

    Boundary observe(boolean crossing, double line, double time, GhostLap.Sample pose,
                     TelemetryLap.Driving driving, VehicleObservation observation) {
        var boundary = crossing ? atLine(line, time, pose, driving, observation) : null;
        previousTime = time;
        previousPose = pose;
        previousDriving = driving;
        previousObservation = observation;
        return boundary;
    }

    private Boundary atLine(double line, double time, GhostLap.Sample pose, TelemetryLap.Driving driving,
                            VehicleObservation observation) {
        if (line == time) return new Boundary(pose, driving, observation);
        if (!(previousTime <= line && line < time) || hasGap(time)) return MISSING;
        if (line == previousTime) return new Boundary(previousPose, previousDriving, previousObservation);
        if (previousPose == null || pose == null) return MISSING;
        double span = time - previousTime;
        double offset = line - previousTime;
        float fraction = (float) (offset / span);
        return new Boundary(previousPose.interpolateObserved(pose, 0, span, offset),
                previousDriving == null || driving == null ? null : previousDriving.interpolate(driving, fraction),
                previousObservation == null || observation == null ? null : previousObservation.interpolate(observation, fraction));
    }
}
