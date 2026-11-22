package com.f1car.mini4wd;

import com.f1car.mini4wd.MiniTrack.Rail;
import java.util.List;
import java.util.ArrayList;

/** The rollers follow the rails. Excess speed on a bend or ramp ends the run. */
public final class MiniTrial {
    public static final int LAPS = 3;
    public static final double CURVE_LIMIT = 5.85;
    public static final double RAMP_LIMIT = 5.45;

    public enum Motor {
        CONTROL("稳健", 4.15), BALANCED("均衡", 4.85), SPEED("竞速", 5.65);

        public final String label;
        public final double speed;
        Motor(String label, double speed) { this.label = label; this.speed = speed; }
    }

    public enum Result { RUNNING, FINISHED, DERAILED }

    private record Course(List<Rail> route,double lapLength,boolean closed,int lastRamp,int lastBoostObstacle) {}

    private final Course course;
    private final MiniSetup setup;
    private final MiniSetup.Performance performance;
    private int index;
    private double along;
    private double distance, elapsed, speed;
    private double battery = 1;
    private boolean boosting;
    private Result result = Result.RUNNING;
    private List<Double> lapTimes = List.of();
    private double lastLapAt;
    private long revision;

    public MiniTrial(MiniTrack.Inspection inspection, Motor motor) {
        this(inspection, MiniSetup.stock().withMotor(motor), 0);
    }

    public MiniTrial(MiniTrack.Inspection inspection, MiniSetup setup, double laneOffset) {
        this(prepareCourse(inspection,setup,laneOffset),setup,setup.performance());
    }

    private MiniTrial(Course course,MiniSetup setup,MiniSetup.Performance performance) {
        this.course=course;
        this.setup=setup;
        this.performance=performance;
        along=course.closed()?course.route().get(0).length()/2:0;
    }

    MiniTrial restart() {return new MiniTrial(course,setup,performance);}

    private static Course prepareCourse(MiniTrack.Inspection inspection,MiniSetup setup,double laneOffset) {
        if (!inspection.ready()) throw new IllegalArgumentException("A connected track is required");
        if (!setup.ready()) throw new IllegalArgumentException("A complete compatible Mini 4WD assembly is required");
        var route=laneOffset==0?inspection.route():inspection.route().stream().<Rail>map(rail->new LaneRail(rail,laneOffset)).toList();
        double length=route.stream().mapToDouble(Rail::length).sum();
        int ramp=-1,obstacle=-1;
        for(int i=0;i<route.size();i++) {
            var kind=route.get(i).kind();
            if(kind==MiniTrack.Kind.RAMP) ramp=i;
            if(kind==MiniTrack.Kind.RAMP || kind==MiniTrack.Kind.CURVE) obstacle=i;
        }
        return new Course(route,length,inspection.closed(),ramp,obstacle);
    }

    public Result result() { return result; }
    public MiniSetup setup() { return setup; }
    public int laps() { return Math.min(targetLaps(), (int) ((distance + 1e-8) / course.lapLength())); }
    public int targetLaps() { return course.closed() ? LAPS : 1; }
    public double elapsed() { return elapsed; }
    public double speed() { return speed; }
    public double battery() { return battery; }
    public double distance() { return distance; }
    public double progress() { return distance / course.lapLength(); }
    public List<Double> lapTimes() { return lapTimes; }
    long revision() { return revision; }
    public void retire() {
        if (result == Result.RUNNING) {
            result = Result.DERAILED;
            revision++;
        }
    }
    public boolean boosting() { return boosting && result == Result.RUNNING; }
    public Rail segment() { return course.route().get(index); }
    public double fraction() { return along / segment().length(); }

    public void update(double seconds, boolean boostHeld) {
        updateSteps(seconds,boostHeld,false);
    }

    void updateAi(double seconds) {
        updateSteps(seconds,false,true);
    }

    private void updateSteps(double seconds,boolean boostHeld,boolean automatic) {
        if(!Double.isFinite(seconds) || seconds<=0 || result!=Result.RUNNING) return;
        while (seconds > 0 && result == Result.RUNNING) {
            double step = Math.min(seconds, .01);
            advance(step,automatic?safeToBoost():boostHeld);
            seconds -= step;
        }
        revision++;
    }

    private void advance(double dt, boolean boostHeld) {
        boosting = boostHeld && battery > 0;
        double target = performance.speed() + (boosting ? 1.55 : 0);
        if (setup.brake() == MiniSetup.Brake.STRONG && approachingRamp()) target = Math.min(target, performance.rampLimit() - .3);
        speed = speed < target ? Math.min(target, speed + performance.acceleration() * dt) : Math.max(target, speed - performance.braking() * dt);

        double remaining = speed * dt;
        double traveled = 0;
        double lapLength=course.lapLength();
        while (remaining > 1e-9) {
            Rail segment = segment();
            double limit = switch (segment.kind()) {
                case CURVE -> performance.curveLimit();
                case RAMP -> performance.rampLimit();
                default -> Double.POSITIVE_INFINITY;
            };
            if (speed > limit) {
                result = Result.DERAILED;
                break;
            }

            double nextLap = (lapTimes.size() + 1) * lapLength;
            double movement = Math.min(remaining, Math.min(segment.length() - along, nextLap - distance));
            traveled += movement;
            remaining -= movement;
            along += movement;
            distance += movement;
            if (nextLap - distance < 1e-8) {
                double crossedAt = elapsed + traveled / speed;
                var times=new ArrayList<>(lapTimes);
                times.add(crossedAt - lastLapAt);
                lapTimes=List.copyOf(times);
                lastLapAt = crossedAt;
            }
            if (lapLength * targetLaps() - distance < 1e-8) {
                result = Result.FINISHED;
                break;
            }
            if (segment.length() - along < 1e-9) {
                index = (index + 1) % course.route().size();
                along = 0;
            }
        }
        double active=result==Result.RUNNING?dt:traveled/speed;
        if(boosting) battery=Math.max(0,battery-active*.30/performance.capacity());
        else if(!boostHeld) battery=Math.min(1,battery+active*.14/performance.capacity());
        elapsed+=active;
    }

    public boolean safeToBoost() {
        if (battery < .15) return false;
        return clearAhead(false);
    }

    private boolean approachingRamp() { return !clearAhead(true); }

    private boolean clearAhead(boolean rampsOnly) {
        int lastObstacle=rampsOnly?course.lastRamp():course.lastBoostObstacle();
        boolean closed=course.closed();
        if(lastObstacle<0 || !closed && index>lastObstacle) return true;
        var route=course.route();
        double remaining = Math.max(1, speed * 1.0);
        int cursor = index;
        double available = route.get(cursor).length() - along;
        for (int checked = 0; checked < route.size(); checked++) {
            var kind = route.get(cursor).kind();
            if (kind == MiniTrack.Kind.RAMP || !rampsOnly && kind == MiniTrack.Kind.CURVE) return false;
            remaining -= available;
            if (remaining <= 0 || !closed && cursor == route.size() - 1) return true;
            cursor = (cursor + 1) % route.size();
            available = route.get(cursor).length();
        }
        return true;
    }

    private static final class LaneRail implements Rail {
        private final Rail base;
        private final double offset, length;

        LaneRail(Rail base, double offset) {
            this.base = base;
            this.offset = offset;
            double distance = 0;
            var previous = point(0);
            int steps = base.kind() == MiniTrack.Kind.CURVE ? Math.max(24, (int) (base.length() * 20)) : 1;
            for (int i = 1; i <= steps; i++) {
                var p = point((double) i / steps);
                distance += MiniShape.distance(previous, p);
                previous = p;
            }
            length = distance;
        }
        @Override public MiniTrack.Kind kind() { return base.kind(); }
        @Override public double length() { return length; }
        @Override public MiniTrack.Point point(double t) {
            var p = base.point(t);
            double heading = base.heading(t);
            return new MiniTrack.Point(p.x() - Math.sin(heading) * offset, p.y() + Math.cos(heading) * offset);
        }
        @Override public double heading(double t) {
            var a = point(Math.max(0, t - .001));
            var b = point(Math.min(1, t + .001));
            return Math.atan2(b.y() - a.y(), b.x() - a.x());
        }
        @Override public Rail reversed() { return new LaneRail(base.reversed(), -offset); }
    }
}
