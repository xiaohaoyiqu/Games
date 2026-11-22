package com.f1car.model;

import java.util.*;

/** Current-buffer target constraint, not a velocity, contact or collision-free guarantee. */
public final class TrafficCommandGuard {
    private TrafficCommandGuard() {}
    /** Route-relative occupied sides, independent of the current target or steering demand. */
    public record Restriction(boolean left,boolean right) {
        public static final Restriction NONE=new Restriction(false,false);
    }
    public static Restriction restriction(float actual,float edge,List<TrafficMotionWindow.Neighbor> traffic) {
        if(!Float.isFinite(actual) || !Float.isFinite(edge) || edge<0) throw new IllegalArgumentException("Invalid traffic corridor");
        Objects.requireNonNull(traffic);
        boolean withinCorridor=Math.abs(actual)<=edge,left=false,right=false;
        for(var other:traffic) {
            Objects.requireNonNull(other);
            double side=(double)other.lateral()-actual;
            if(!withinCorridor || Math.abs(other.gap())>TrafficMotionWindow.LONGITUDINAL_MARGIN || Math.abs(side)>TrafficMotionWindow.LATERAL_MARGIN) continue;
            left|=side<=0;right|=side>=0;
        }
        return left || right?new Restriction(left,right):Restriction.NONE;
    }
    public static float clamp(float actual,float proposed,float edge,List<TrafficMotionWindow.Neighbor> traffic) {
        if(!Float.isFinite(actual) || !Float.isFinite(proposed) || !Float.isFinite(edge) || edge<0) throw new IllegalArgumentException("Invalid traffic command");
        return clampWithRestriction(actual,proposed,edge,restriction(actual,edge,traffic));
    }
    public static float clampWithRestriction(float actual,float proposed,float edge,Restriction restriction) {
        if(!Float.isFinite(actual) || !Float.isFinite(proposed) || !Float.isFinite(edge) || edge<0) throw new IllegalArgumentException("Invalid traffic command");
        Objects.requireNonNull(restriction);
        float lower=restriction.left()?Math.max(-edge,actual):-edge;
        float upper=restriction.right()?Math.min(edge,actual):edge;
        // Outside the existing target range, retain return planning without claiming feasibility here.
        return Math.abs(actual)<=edge?Math.max(lower,Math.min(upper,proposed)):proposed;
    }
}
