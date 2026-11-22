package com.f1car.model;

import java.util.Objects;

/** Pursuit input and an AI-only envelope relative to current-lane following, not contact safety. */
public final class AiSteering {
    private AiSteering() {}

    /** Same chord, wheelbase and speed-dependent normalization as the existing driver. */
    public static float demand(float localX,float localZ,float speed) {
        if(!Float.isFinite(localX) || !Float.isFinite(localZ) || !Float.isFinite(speed) || speed<0) throw new IllegalArgumentException("Invalid pursuit input");
        float angle=(float)Math.atan2(2*2.68f*localX,Math.max(4,localX*localX+localZ*localZ));
        float maxSteer=Math.max(.11f,.44f/(1+speed*.044f));
        return Math.max(-1,Math.min(1,angle/maxSteer));
    }

    /** The anchor follows the actual lateral coordinate at the same lookahead on the road. */
    public static Bounds bounds(float following,TrafficCommandGuard.Restriction restriction) {
        if(!Float.isFinite(following) || following< -1 || following>1) throw new IllegalArgumentException("Invalid following input");
        Objects.requireNonNull(restriction);
        return new Bounds(restriction.left()?following:-1,restriction.right()?following:1);
    }

    public record Bounds(float lower,float upper) {
        public Bounds {
            if(!Float.isFinite(lower) || !Float.isFinite(upper) || lower< -1 || upper>1 || lower>upper) throw new IllegalArgumentException("Invalid steering bounds");
        }
        public float clamp(float input) {
            if(!Float.isFinite(input)) throw new IllegalArgumentException("Invalid steering input");
            return Math.max(lower,Math.min(upper,input));
        }
        /** Clamp after the original smoothing too: old actuator memory can otherwise exceed the envelope. */
        public float smooth(float previous,float demand,float dt) {
            if(!Float.isFinite(previous) || previous< -1 || previous>1 || !Float.isFinite(dt) || dt<0) throw new IllegalArgumentException("Invalid steering history or time");
            return clamp(previous+(clamp(demand)-previous)*Math.min(1,dt*7.5f));
        }
    }
}
