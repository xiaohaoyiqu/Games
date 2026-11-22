package com.f1car.network;

import java.util.ArrayList;
import java.util.List;

/** Guest presentation of at most two consecutive host movements. Raw results stay on the room. */
final class RoomFramePlayback {
    private RaceSession.Frame older, previous, latest;
    private long startedAt, receivedAt;
    private double span, carry, duration;
    private double lastShownRemaining=Double.POSITIVE_INFINITY;

    void clear() {
        older=previous=latest=null;
        span=carry=duration=0;
        lastShownRemaining=Double.POSITIVE_INFINITY;
    }

    long startedAt() {return startedAt;}

    RaceRoom.FrameDelay delay(int localId,long now) {
        if(latest==null || latest.complete() || !RoomSnapshotTiming.overdue(startedAt,now)) return RaceRoom.FrameDelay.NONE;
        if(!latest.acceptsInputFrom(localId)) return RaceRoom.FrameDelay.WATCHING;
        return RoomSnapshotTiming.requiresBrake(startedAt,now) ? RaceRoom.FrameDelay.BRAKE_ONLY : RaceRoom.FrameDelay.WAITING;
    }

    void accept(RaceSession.Frame next,long firstByte,long receipt) {
        double nextSpan=latest==null?0:(double)(next.sequence()-latest.sequence())/RaceSession.TICKS_PER_SECOND;
        if(latest==null || next.complete() || latest.complete() || next.session()!=latest.session()
                || next.trackIndex()!=latest.trackIndex() || !next.trackKey().equals(latest.trackKey())
                // Include time spent receiving the older pose before using it as an interpolation origin.
                || !RoomSnapshotTiming.continuous(nextSpan,startedAt,receipt)) {
            clear();
        } else {
            // Retain the unshown part of the previous segment, bounded by the available observations.
            double remaining=Math.min(span,remainingAt(receipt));
            older=previous;
            previous=latest;
            span=nextSpan;
            carry=Math.min(remaining,RoomSnapshotTiming.MAX_SPAN_SECONDS-span);
            // A queued arrival can predate the last render. Never replay time already shown.
            lastShownRemaining=Math.min(span+carry,lastShownRemaining+span);
            // Catch up at up to 1.5 times host playback speed following a burst.
            duration=Math.max(Math.max(.02,span),(span+carry)/1.5);
        }
        latest=next;
        startedAt=firstByte;
        receivedAt=receipt;
    }

    private double remainingAt(long now) {
        long age=now-receivedAt;
        if(duration==0 || age<0) return 0;
        return (span+carry)*Math.max(0,1-age/1_000_000_000.0/duration);
    }

    RaceSession.Frame frame(long now) {
        double remaining=Math.min(remainingAt(now),lastShownRemaining);
        lastShownRemaining=remaining;
        if(remaining<=0) return latest;
        if(remaining>span && older!=null) {
            double previousSpan=(double)(previous.sequence()-older.sequence())/RaceSession.TICKS_PER_SECOND;
            float amount=(float)Math.max(0,1-(remaining-span)/previousSpan);
            return interpolatePoses(older,previous,latest,amount);
        }
        return interpolatePoses(previous,latest,latest,(float)Math.max(0,1-remaining/span));
    }

    boolean caughtUp() {return lastShownRemaining==0;}

    private static RaceSession.Frame interpolatePoses(RaceSession.Frame start,RaceSession.Frame end,RaceSession.Frame latest,float amount) {
        List<RaceSession.CarState> cars = null;
        for (int i = 0; i < latest.cars().size(); i++) {
            var car = latest.cars().get(i);
            var endpoint=end==latest?car:driverAt(end,car.id());
            if(end!=latest && !continuousCar(endpoint,car)) continue;
            var before=driverAt(start,car.id());
            var pose=endpoint.pose();
            if(continuousCar(before,endpoint) && !before.pose().equals(pose))
                pose=amount==0?before.pose():pose.between(before.pose(),amount);
            if(pose.equals(car.pose())) continue;
            if (cars == null) cars = new ArrayList<>(latest.cars());
            cars.set(i, new RaceSession.CarState(car.id(), car.name(), car.tuning(), car.connected(), car.retired(),
                    car.recovery(), pose, car.progress(), car.penalty(), car.throttle(), car.braking(), car.steering(), car.distance()));
        }
        return cars == null ? latest : new RaceSession.Frame(latest.session(), latest.sequence(), latest.settings(),
                latest.elapsed(), latest.countdown(), latest.complete(), cars, latest.trackIndex(), latest.trackKey());
    }
    private static RaceSession.CarState driverAt(RaceSession.Frame frame,int id) {
        for(var car:frame.cars()) if(car.id()==id) return car;
        return null;
    }
    private static boolean continuousCar(RaceSession.CarState before,RaceSession.CarState after) {
        return before!=null && before.connected()==after.connected() && before.recovery()==after.recovery()
                && before.retired()==after.retired() && before.progress().finished()==after.progress().finished()
                && before.pose().distanceSquared(after.pose())<=55*55;
    }
}
