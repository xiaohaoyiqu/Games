package com.f1car.network;

/** Presentation time for a pair of host observations, using the socket reader's local timestamps. */
public final class RoomSnapshotTiming {
    // Five nominal broadcasts. Beyond this gap the intervening path is unknown.
    private static final long MAX_GAP_NANOS = 250_000_000L;
    static final double MAX_SPAN_SECONDS=MAX_GAP_NANOS/1_000_000_000.0;

    private RoomSnapshotTiming() {}

    static boolean overdue(long receipt, long now) {
        return now - receipt > MAX_GAP_NANOS;
    }

    static boolean requiresBrake(long frameStartedAt,long now) {
        return now-frameStartedAt>=RaceSession.INPUT_TIMEOUT_NANOS;
    }

    public static double fraction(double hostSpanSeconds, long previousReceipt, long receipt, long now) {
        long age = now - receipt;
        if (!continuous(hostSpanSeconds,previousReceipt,receipt) || age<0) return 1;
        double span = Math.max(.02, hostSpanSeconds);
        return Math.min(1, age / 1_000_000_000.0 / span);
    }
    static boolean continuous(double hostSpanSeconds,long previousReceipt,long receipt) {
        return receipt-previousReceipt>=0 && !overdue(previousReceipt,receipt)
                && hostSpanSeconds>0 && hostSpanSeconds<=MAX_SPAN_SECONDS;
    }
}
