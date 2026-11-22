package com.f1car.network;

import java.util.concurrent.TimeUnit;

/** Game-thread estimator for application-level round trips on one room connection. */
final class RoomLatency {
    private static final long PROBE_INTERVAL = TimeUnit.SECONDS.toNanos(1);
    private static final long SAMPLE_TIMEOUT = TimeUnit.SECONDS.toNanos(5);
    private final long[] probes = new long[8];
    private final boolean[] pending = new boolean[probes.length];
    private int next;
    private boolean sentProbe;
    private long lastProbe, lastReply, latestMs;
    private double meanMs, deviationMs;
    private RaceRoom.LinkTiming timing;

    boolean beginProbe(long now) {
        if (sentProbe && now - lastProbe < PROBE_INTERVAL) return false;
        sentProbe = true;
        lastProbe = now;
        probes[next] = now;
        pending[next] = true;
        next = (next + 1) % probes.length;
        return true;
    }

    void reply(long token, long receivedAt) {
        for (int i = 0; i < probes.length; i++) {
            if (!pending[i] || probes[i] != token) continue;
            long elapsed = receivedAt - probes[i];
            if (elapsed < 0) return;
            pending[i] = false;
            if (elapsed >= SAMPLE_TIMEOUT || timing != null && receivedAt - lastReply < 0) return;

            double sampleMs = elapsed / 1_000_000.0;
            boolean first = timing == null || receivedAt - lastReply >= SAMPLE_TIMEOUT;
            if (first) {
                meanMs = sampleMs;
                deviationMs = 0;
            } else {
                // Initialize the displayed variation from the first observed difference.
                double differenceMs = Math.abs(meanMs - sampleMs);
                if (timing.variationMs() < 0) deviationMs = differenceMs;
                else deviationMs += (differenceMs - deviationMs) / 4;
                meanMs += (sampleMs - meanMs) / 8;
            }
            latestMs = TimeUnit.NANOSECONDS.toMillis(elapsed);
            lastReply = receivedAt;
            timing = new RaceRoom.LinkTiming(Math.round(meanMs), first ? -1 : Math.round(deviationMs));
            return;
        }
    }

    long latestMs() { return latestMs; }

    RaceRoom.LinkTiming timing(long now) {
        long age = now - lastReply;
        return age >= 0 && age < SAMPLE_TIMEOUT ? timing : null;
    }
}
