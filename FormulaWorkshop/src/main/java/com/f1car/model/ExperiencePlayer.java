package com.f1car.model;

import java.util.Objects;

/** Read-only cursor over an actual saved attempt. It cannot move a car or award progress. */
public final class ExperiencePlayer {
    private final ExperienceRun run;
    private double time;
    private double sampledAt = Double.NaN;
    private TelemetryLap.Sample sampled;
    private boolean playing=true;
    private double speed=1;
    public ExperiencePlayer(ExperienceRun run) { this.run=Objects.requireNonNull(run); }
    public ExperienceRun run() { return run; }
    public double time() { return time; }
    public boolean playing() { return playing; }
    public double speed() { return speed; }
    public TelemetryLap.Sample sample() {
        if (time != sampledAt) {
            sampled = run.at(time);
            sampledAt = time;
        }
        return sampled;
    }
    public void toggle() { if(time>=run.duration()) { time=0;playing=true; }else playing=!playing; }
    public void speedCycle() { speed=speed==.5?1:speed==1?2:.5; }
    public void seek(double value) { if(!Double.isFinite(value)) throw new IllegalArgumentException("Invalid experience cursor");time=Math.max(0,Math.min(run.duration(),value)); }
    public void pauseAt(double value) {
        seek(value);
        playing=false;
    }
    public void update(float dt) { if(!Float.isFinite(dt) || dt<0 || !playing) return;time=Math.min(run.duration(),time+Math.min(.1,dt)*speed);if(time>=run.duration()) playing=false; }
}
