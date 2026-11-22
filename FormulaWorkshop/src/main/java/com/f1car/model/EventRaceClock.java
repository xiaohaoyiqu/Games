package com.f1car.model;

/** Shared timing for the local event race display and its settlement. */
public record EventRaceClock(Limit limit, double remainingSeconds) {
    public static final int FIRST_FINISH_WAIT_SECONDS = 45;

    public enum Limit {
        FULL_RACE("本场比赛已达时间上限。"),
        FIRST_FINISH("首车完赛后的等候时间已到。"),
        ALL_FINISHED("全部车辆已完赛。");

        private final String description;

        Limit(String description) { this.description = description; }

        public String description() { return description; }
    }

    public static int fullRaceLimitSeconds(int laps) {
        return Math.max(240, laps * 180);
    }

    /** Pass positive infinity until a driver has finished. Use the raw finish time before penalties. */
    public static double deadlineSeconds(int laps, double firstFinish) {
        return Math.min(fullRaceLimitSeconds(laps), firstFinish + FIRST_FINISH_WAIT_SECONDS);
    }

    public static EventRaceClock read(int laps, double elapsed, int finishers, double firstFinish) {
        if (finishers == EventSeries.DRIVERS) return new EventRaceClock(Limit.ALL_FINISHED, 0);

        int fullLimit = fullRaceLimitSeconds(laps);
        double first = finishers > 0 ? firstFinish : Double.POSITIVE_INFINITY;
        Limit cause = first <= fullLimit - FIRST_FINISH_WAIT_SECONDS ? Limit.FIRST_FINISH : Limit.FULL_RACE;
        return new EventRaceClock(cause, Math.max(0, deadlineSeconds(laps, first) - elapsed));
    }

    public boolean readyToSettle() { return remainingSeconds == 0; }
    public int displayedSeconds() { return (int) Math.ceil(remainingSeconds); }
}
