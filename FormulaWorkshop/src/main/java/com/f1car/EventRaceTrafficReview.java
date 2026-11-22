package com.f1car;

import com.f1car.model.EventSeries;
import com.f1car.model.WorldTrafficBraking;
import com.f1car.model.WorldTrafficRestart;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded observations from one explicitly observed local starting grid. */
final class EventRaceTrafficReview {
    private static final int DRIVERS = EventSeries.DRIVERS;
    private static final int PAIRS = DRIVERS * (DRIVERS - 1) / 2;
    private static final double SUSTAINED_NEAR_STOP_SECONDS = .3;

    enum End { RUNNING, SETTLED, LEFT }
    record DriverFrame(float speed, boolean finished, boolean recoveryPending, WorldTrafficBraking.Decision actual,
            WorldTrafficBraking.Decision launch, WorldTrafficRestart.Phase release) {}
    record Driver(double actualRiskSeconds, double launchRiskSeconds, double clearWaitSeconds,
            double incompleteObservationSeconds, double longestNearStopSeconds,
            long sustainedNearStops, long movementsAfterNearStop, long gates, int recoveries, double recoveryWaitSeconds,
            double longestRecoveryWaitSeconds, long recoverySearches, long incompleteTrafficRecoverySearches,
            long recoveryCandidateCacheHits) {}
    record Pair(int first, int second, long contactEpisodes) {}
    record Snapshot(String eventId, int round, End end, long frames, double seconds, long contactQueryNanos, long recoverySearchNanos,
            List<Driver> drivers, List<Pair> pairs) {}

    private static final class DriverCounter {
        double actual, launch, clearWait, incomplete, nearStop, longestNearStop, recoveryWait, currentRecoveryWait, longestRecoveryWait;
        long sustainedNearStops, movementsAfterNearStop, gates, recoverySearches, incompleteTrafficRecoverySearches, recoveryCandidateCacheHits;
        int recoveries;

        void sample(float dt, DriverFrame frame) {
            if (frame.recoveryPending()) {
                nearStop = 0;
                return;
            }
            currentRecoveryWait = 0;
            if (frame.finished() || !Float.isFinite(frame.speed()) || frame.speed() < 0) {
                nearStop = 0;
            } else if (frame.speed() > WorldTrafficRestart.MAX_PROBE_SPEED) {
                if (nearStop >= SUSTAINED_NEAR_STOP_SECONDS) movementsAfterNearStop++;
                nearStop = 0;
            } else {
                boolean previouslySustained = nearStop >= SUSTAINED_NEAR_STOP_SECONDS;
                nearStop += dt;
                if (!previouslySustained && nearStop >= SUSTAINED_NEAR_STOP_SECONDS) sustainedNearStops++;
                longestNearStop = Math.max(longestNearStop, nearStop);
            }
            if (frame.finished() || frame.actual() == null) return;
            if (frame.actual().blocked()) actual += dt;
            if (frame.launch() != null && frame.launch().blocked()) launch += dt;
            if (incomplete(frame.actual()) || frame.launch() != null && incomplete(frame.launch())) {
                incomplete += dt;
            } else if (frame.release() == WorldTrafficRestart.Phase.WAITING_CLEAR) {
                clearWait += dt;
            }
        }

        private static boolean incomplete(WorldTrafficBraking.Decision reading) {
            return !reading.available() || reading.skippedBodies() != 0;
        }

        Driver snapshot() {
            return new Driver(actual, launch, clearWait, incomplete, longestNearStop,
                    sustainedNearStops, movementsAfterNearStop, gates, recoveries, recoveryWait,
                    longestRecoveryWait, recoverySearches, incompleteTrafficRecoverySearches, recoveryCandidateCacheHits);
        }
    }

    private final String eventId;
    private final int round;
    private final DriverCounter[] drivers = new DriverCounter[DRIVERS];
    private final long[] episodes = new long[PAIRS];
    private End end = End.RUNNING;
    private long frames, contactQueryNanos, recoverySearchNanos;
    private double seconds;
    private int contacts;

    EventRaceTrafficReview(EventSeries.State source) {
        eventId = source.eventId();
        round = source.round();
        Arrays.setAll(drivers, index -> new DriverCounter());
    }

    /** Bits enumerate the unordered pairs in driver order, starting with (0, 1). */
    void sample(float dt, List<DriverFrame> observations, int touching, long queryNanos) {
        if (end != End.RUNNING || !Float.isFinite(dt) || dt <= 0) return;
        if (observations.size() != DRIVERS || touching < 0 || touching >= (1 << PAIRS) || queryNanos < 0) {
            throw new IllegalArgumentException("Invalid event traffic observations");
        }
        for (int driver = 0; driver < DRIVERS; driver++) {
            if (observations.get(driver) == null) throw new IllegalArgumentException("Missing event traffic observation");
        }
        int started = touching & ~contacts;
        for (int pair = 0; pair < PAIRS; pair++) {
            if ((started & (1 << pair)) != 0) episodes[pair]++;
        }
        contacts = touching;
        frames++;
        seconds += dt;
        contactQueryNanos += queryNanos;
        for (int driver = 0; driver < DRIVERS; driver++) drivers[driver].sample(dt, observations.get(driver));
    }

    void crossedGates(int driver,int count) {
        if (end == End.RUNNING) drivers[driver].gates+=count;
    }

    void checkedRecovery(int driver, float dt, long searchNanos, boolean found, boolean incompleteTraffic, boolean reusedCandidates) {
        if (end != End.RUNNING || !Float.isFinite(dt) || dt <= 0) return;
        if (searchNanos < 0) throw new IllegalArgumentException("Invalid recovery search duration");
        var counter=drivers[driver];
        recoverySearchNanos += searchNanos;
        counter.recoverySearches++;
        if(incompleteTraffic) counter.incompleteTrafficRecoverySearches++;
        if(reusedCandidates) counter.recoveryCandidateCacheHits++;
        if (!found) {
            counter.recoveryWait += dt;
            counter.currentRecoveryWait += dt;
            counter.longestRecoveryWait = Math.max(counter.longestRecoveryWait,counter.currentRecoveryWait);
        }
    }

    void recovered(int driver) {
        if (end != End.RUNNING) return;
        drivers[driver].recoveries++;
        drivers[driver].nearStop = 0;
        drivers[driver].currentRecoveryWait = 0;
        int pair = 0;
        for (int first = 0; first < DRIVERS; first++) {
            for (int second = first + 1; second < DRIVERS; second++, pair++) {
                if (driver == first || driver == second) contacts &= ~(1 << pair);
            }
        }
    }

    void end(End status) {
        if (end == End.RUNNING) end = status;
    }

    Snapshot snapshot() {
        var pairs = new ArrayList<Pair>(PAIRS);
        for (int first = 0; first < DRIVERS; first++) {
            for (int second = first + 1; second < DRIVERS; second++) {
                pairs.add(new Pair(first, second, episodes[pairs.size()]));
            }
        }
        return new Snapshot(eventId, round, end, frames, seconds, contactQueryNanos, recoverySearchNanos,
                Arrays.stream(drivers).map(DriverCounter::snapshot).toList(), List.copyOf(pairs));
    }
}
