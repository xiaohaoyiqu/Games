package com.f1car.network;

/** Game-thread schedule for the rooms' 20 Hz snapshots and continuous driving input. */
public final class RoomSendClock {
    private static final double PERIOD = .05;
    private static final double BOUNDARY_EPSILON = 1e-12;
    private double remainder;

    public void reset() {
        remainder = 0;
    }

    /** One due send carries the latest state, even when several intervals elapsed during this poll. */
    public boolean advance(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return false;
        remainder += seconds;
        if (remainder < PERIOD - BOUNDARY_EPSILON) return false;
        remainder %= PERIOD;
        if (remainder >= PERIOD - BOUNDARY_EPSILON) remainder = 0;
        return true;
    }
}
