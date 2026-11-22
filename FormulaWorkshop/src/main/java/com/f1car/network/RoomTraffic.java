package com.f1car.network;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Socket workers count transferred application bytes. The game thread samples the room totals. */
final class RoomTraffic {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long STALE = 5 * SECOND;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private boolean connected;
    private long sampledAt, previousReceived, previousSent;
    private RaceRoom.TransferRate rate;

    void received(int bytes) { received.addAndGet(bytes); }
    void sent(int bytes) { sent.addAndGet(bytes); }

    void sample(long now, boolean hasPeers) {
        if (!hasPeers) {
            connected = false;
            rate = null;
            return;
        }
        long elapsed = now - sampledAt;
        if (connected && elapsed >= 0 && elapsed < SECOND) return;
        long read = received.get();
        long written = sent.get();
        if (connected && elapsed >= SECOND && elapsed < STALE) {
            double seconds = (double) elapsed / SECOND;
            rate = new RaceRoom.TransferRate(Math.round((read - previousReceived) / seconds),
                    Math.round((written - previousSent) / seconds));
        } else {
            rate = null;
        }
        previousReceived = read;
        previousSent = written;
        sampledAt = now;
        connected = true;
    }

    RaceRoom.TransferRate rate(long now) {
        long age = now - sampledAt;
        return age >= 0 && age < STALE ? rate : null;
    }
}
