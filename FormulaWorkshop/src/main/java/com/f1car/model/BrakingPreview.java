package com.f1car.model;

import java.util.Objects;

/** Local curvature preview with a conservative braking envelope, not an optimized racing line. */
public final class BrakingPreview {
    private static final int SAMPLE_SPACING=12,MAX_LOOK_AHEAD=800;
    private final TrackLayout track;
    private final TrackLayout.TargetSpeedSamples samples;

    public BrakingPreview(TrackLayout track) {
        this.track=Objects.requireNonNull(track);
        samples=track.new TargetSpeedSamples(MAX_LOOK_AHEAD/SAMPLE_SPACING+1);
    }

    public float speedLimit(float distance,float speed,float pace,float brakes) {
        return speedLimit(track,samples,distance,speed,pace,brakes);
    }

    public static float speedLimit(TrackLayout track,float distance,float speed,float pace,float brakes) {
        Objects.requireNonNull(track);
        return speedLimit(track,null,distance,speed,pace,brakes);
    }

    private static float speedLimit(TrackLayout track,TrackLayout.TargetSpeedSamples samples,
            float distance,float speed,float pace,float brakes) {
        if(!Float.isFinite(distance) || !Float.isFinite(speed) || speed<0 || !Float.isFinite(pace) || pace<=0 || pace>2 || !Float.isFinite(brakes) || brakes<=0 || brakes>2)
            throw new IllegalArgumentException("Invalid braking preview");
        // This is a planning assumption; actual braking remains in FormulaCar / Bullet.
        float deceleration=6.5f*brakes,approach=Math.max(speed,TrackLayout.MAX_TARGET_SPEED*pace);
        float horizon=Math.max(90,Math.min(MAX_LOOK_AHEAD,approach*approach/(2*deceleration)+30));
        float limit=cornerSpeed(track,samples,0,distance+5)*pace;
        float slowestCorner=TrackLayout.MIN_TARGET_SPEED*pace;
        float slowestSquared=slowestCorner*slowestCorner;
        // Squaring the float ceiling in double keeps its exact value until the final square root.
        double tightestSquared=(double)limit*limit;
        for(int ahead=SAMPLE_SPACING,slot=1;ahead<=horizon;ahead+=SAMPLE_SPACING,slot++) {
            // One sample interval provides margin for the discretized curvature observation.
            float brakingDistance=ahead-SAMPLE_SPACING;
            float brakingTerm=2*deceleration*brakingDistance;
            // Every remaining sample has at least this corner speed and braking distance.
            if(slowestSquared+brakingTerm>=tightestSquared) break;
            float corner=cornerSpeed(track,samples,slot,distance+ahead)*pace;
            tightestSquared=Math.min(tightestSquared,corner*corner+brakingTerm);
        }
        // Square root and float rounding are monotone for these nonnegative candidates.
        return (float)Math.sqrt(tightestSquared);
    }

    private static float cornerSpeed(TrackLayout track,TrackLayout.TargetSpeedSamples samples,int slot,float distance) {
        return samples==null?track.targetSpeed(distance):samples.at(slot,distance);
    }
}
