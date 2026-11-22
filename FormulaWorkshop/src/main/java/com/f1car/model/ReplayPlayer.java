package com.f1car.model;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Pure visual timeline. The original live world is held elsewhere and never mutated here. */
public final class ReplayPlayer {
    private final RaceReplay replay;
    private final boolean shortChallenge;
    private final TrackLayout track;
    private final RaceReplay.Driver[] drivers;
    private List<Integer> finalClassification=List.of();
    private final RaceReplay.Car[] intervalTargets;
    private final float[] intervalTurns;
    private final double[] intervalLapMarkers,intervalFinishMarkers;
    private int frameIndex,preparedIndex=-1;
    private int nextMarkerIndex=-1,intervalEventIndex=-1;
    private int currentEventIndex=-1;
    private boolean intervalGap,intervalNeedsInterpolation;
    private double intervalRoomStart=Double.NaN;
    private int intervalCameraCuts;
    private ReplayReviewTimeline reviewTimeline;
    private double time;
    private double sampledAt=Double.NaN;
    private RaceReplay.Frame sampledFrame;
    private List<RaceReplay.Car> sampledStandings;
    private int speed=2,follow;
    private boolean playing,overview;
    private long cameraCutVersion;
    private static final double[] SPEEDS={.25,.5,1,2,4};
    private static final Comparator<RaceReplay.Car> QUALIFYING_ORDER=(a,b)->QualifyingSession.compareTimes(
            a.qualifyingTime(),a.finishTime(),a.id(),b.qualifyingTime(),b.finishTime(),b.id());
    private int compareRaceCars(RaceReplay.Car a,RaceReplay.Car b,boolean classifiedRoomResult) {
        if(a.finished()!=b.finished()) return a.finished()?-1:1;
        if(a.finished()) {
            double left=a.finishTime()+a.penalty(),right=b.finishTime()+b.penalty();
            if(!shortChallenge) return EventSeries.compareFinishers(left,a.id(),right,b.id());
            int order=Double.compare(left,right);
            return order!=0?order:Integer.compare(a.id(),b.id());
        }
        if(!classifiedRoomResult && a.retired()!=b.retired()) return a.retired()?1:-1;
        int order=classifiedRoomResult?Float.compare(Math.max(0,b.distance()),Math.max(0,a.distance()))
                :Float.compare(b.distance(),a.distance());
        return order!=0?order:Integer.compare(a.id(),b.id());
    }
    public ReplayPlayer(RaceReplay replay) { this(replay,null); }
    public ReplayPlayer(RaceReplay replay,EventRoundBrief round) {
        this.replay=Objects.requireNonNull(replay);
        shortChallenge=isShortChallenge(replay);
        if(round!=null && !round.matchesReplay(replay)) throw new IllegalArgumentException("Replay does not match round results");
        track=replay.track().resolve();follow=replay.drivers().get(0).id();drivers=replay.drivers().toArray(RaceReplay.Driver[]::new);
        if(round!=null) useClassification(round.source().results().get(round.round()));
        intervalTargets=new RaceReplay.Car[drivers.length];
        intervalTurns=new float[drivers.length];
        intervalLapMarkers=new double[drivers.length];
        intervalFinishMarkers=new double[drivers.length];
    }
    private static boolean isShortChallenge(RaceReplay replay) {
        // Existing recordings store the authored challenge title and lap count alongside the roster.
        if(replay.source()!=RaceReplay.Source.LOCAL || replay.qualifying() || replay.drivers().size()!=2
                || replay.recoveryWaits()!=null) return false;
        for(var kind:RaceChallenge.Kind.values()) {
            if(kind.laps==replay.laps() && kind.title.equals(replay.title())) return true;
        }
        return false;
    }
    /** Library playback may find the linked round in the current event or in a completed event archive. */
    public boolean useLinkedEventResult(EventSeries.State event) {
        for(int round=0;round<event.results().size();round++) {
            if(EventSeries.matchesLinkedReplay(replay,event,round)) {
                useClassification(event.results().get(round));
                return true;
            }
        }
        return false;
    }
    private void useClassification(EventSeries.Result result) {
        finalClassification=result.race().stream().map(EventSeries.Finish::driver).toList();
        sampledStandings=null;
    }
    public RaceReplay.Driver driver(int id) {for(var driver:drivers) if(driver.id()==id) return driver;throw new IllegalArgumentException("Unknown replay driver");}
    public RaceReplay replay() { return replay; }
    public TrackLayout track() { return track; }
    public ReplayReviewTimeline reviewTimeline() {
        if(reviewTimeline==null) reviewTimeline=new ReplayReviewTimeline(replay);
        return reviewTimeline;
    }
    /** A cancelled build leaves the index available to build again on a later request. */
    public ReplayReviewTimeline reviewTimeline(BooleanSupplier cancelled) {
        if(reviewTimeline==null) reviewTimeline=ReplayReviewTimeline.prepare(replay,cancelled);
        return reviewTimeline;
    }
    public ReplayReviewTimeline preparedReviewTimeline() {return reviewTimeline;}
    public void acceptReviewTimeline(ReplayReviewTimeline prepared) {
        if(!prepared.matchesReplay(replay)) throw new IllegalArgumentException("Timeline does not match replay");
        if(reviewTimeline==null) reviewTimeline=prepared;
    }
    public double time() { return time; }
    public double speed() { return SPEEDS[speed]; }
    public boolean playing() { return playing; }
    public boolean overview() { return overview; }
    public int follow() { return follow; }
    /** Changes when a viewer should place the camera at the target without smoothing. */
    public long cameraCutVersion() { return cameraCutVersion; }
    public void toggle() { if(!playing && time>=replay.duration()) seek(0);playing=!playing; }
    public void pause() { playing=false; }
    public void speedNext() { speed=(speed+1)%SPEEDS.length; }
    public void overviewToggle() { overview=!overview;cameraCutVersion++; }
    public void nextCar() {
        for(int index=0;index<drivers.length;index++) {
            if(drivers[index].id()!=follow) continue;
            int next=drivers[(index+1)%drivers.length].id();
            if(!overview && next!=follow) cameraCutVersion++;
            follow=next;
            return;
        }
    }
    public void seek(double at) {
        if(!Double.isFinite(at)) return;
        currentEventIndex=-1;
        double next=Math.max(0,Math.min(replay.duration(),at));
        if(next!=time) {
            frameIndex=frameIndexAt(next);
            time=next;
            if(!overview) cameraCutVersion++;
        }
        playing=false;
    }
    public double adjacentFrameTime(boolean forward) {
        var frames=replay.frames();
        int target=frameIndex;
        if(forward) target=Math.min(frames.size()-1,target+1);
        else if(time==frames.get(target).time()) target=Math.max(0,target-1);
        return frames.get(target).time();
    }
    public void seekReview(double at,int driverId) {
        if(!Double.isFinite(at)) return;
        if(driverId>=0) {
            int next=driver(driverId).id();
            if(overview || next!=follow) cameraCutVersion++;
            follow=next;
            overview=false;
        }
        seek(at);
    }
    public void update(double dt) {
        if(!playing || !Double.isFinite(dt) || dt<=0) return;
        double next=Math.min(replay.duration(),time+Math.min(.25,dt)*speed());
        int nextFrame=frameIndexAt(next);
        if(!overview && nextFrame>frameIndex && crossedCameraCut(nextFrame)) cameraCutVersion++;
        frameIndex=nextFrame;
        if(next!=time) currentEventIndex=-1;
        time=next;
        if(time>=replay.duration()) playing=false;
    }

    private boolean crossedCameraCut(int nextFrame) {
        var frames=replay.frames();
        int startFrame=frameIndex;
        if(preparedIndex==startFrame) {
            if(intervalGap) return true;
            if(intervalCameraCuts!=0) {
                var cars=frames.get(startFrame).cars();
                for(int slot=0;slot<cars.size();slot++) {
                    if((intervalCameraCuts & (1<<slot))!=0 && cars.get(slot).id()==follow) return true;
                }
            }
            // The displayed interval has already checked its end frame and all markers through it.
            if(++startFrame==nextFrame) return false;
        }
        var before=frames.get(startFrame);
        double end=frames.get(nextFrame).time();
        var events=replay.events();
        // A marker inside a held interval takes effect with the next observed pose.
        for(int index=firstEventAfterFrame(before.time());index<events.size();index++) {
            var event=events.get(index);
            if(event.time()>end) break;
            if(event.kind()==RaceReplay.Kind.GAP
                    || event.kind()==RaceReplay.Kind.RECOVERY && (event.driver()==-1 || event.driver()==follow)) return true;
        }
        var car=findCar(before.cars(),follow);
        for(int index=startFrame+1;index<=nextFrame;index++) {
            var after=frames.get(index);
            if(after.time()-before.time()>RaceReplay.MAX_CONTIGUOUS_INTERVAL) return true;
            var next=findCar(after.cars(),follow);
            if(next.cutsCameraFrom(car)) return true;
            before=after;
            car=next;
        }
        return false;
    }
    public void nextEvent() {
        nextEvent(false);
    }

    public void nextEvent(boolean followDriver) {
        var next=nextEventMarker();
        if(next!=null) seekEvent(nextMarkerIndex,followDriver);
        else if(time<replay.duration()) seek(replay.duration());
        else pause();
    }

    public int currentEventIndex() {return currentEventIndex;}

    public void seekEvent(int index,boolean followDriver) {
        var event=replay.events().get(index);
        if(event.time()>replay.duration()) return;
        seekReview(event.time(),followDriver?event.driver():-1);
        currentEventIndex=index;
    }

    public RaceReplay.Event nextEventMarker() {
        var events=replay.events();
        nextMarkerIndex=currentEventIndex>=0?currentEventIndex+1:firstEventAfter(time,nextMarkerIndex);
        if(nextMarkerIndex==events.size()) return null;
        var next=events.get(nextMarkerIndex);
        return next.time()<=replay.duration()?next:null;
    }

    private int firstEventAfterFrame(double time) {
        intervalEventIndex=firstEventAfter(time,intervalEventIndex);
        return intervalEventIndex;
    }

    private int firstEventAfter(double after,int index) {
        var events=replay.events();
        if(index>=0 && (index==0 || events.get(index-1).time()<=after)
                && (index==events.size() || events.get(index).time()>after)) return index;
        int low=0,high=events.size();
        while(low<high) {
            int middle=(low+high)/2;
            if(events.get(middle).time()<=after) low=middle+1;
            else high=middle;
        }
        return low;
    }

    public RaceReplay.Frame frame() {
        if(sampledAt!=time) {
            var next=sample();
            if(sampledStandings!=null && (next.cars()!=sampledFrame.cars()
                    || (time>=replay.duration())!=(sampledAt>=replay.duration()))) sampledStandings=null;
            sampledFrame=next;
            sampledAt=time;
        }
        return sampledFrame;
    }

    private RaceReplay.Frame sample() {
        var frames=replay.frames();
        var before=frames.get(frameIndex);
        if(time==before.time() || frameIndex==frames.size()-1) return before;
        var after=frames.get(frameIndex+1);
        if(preparedIndex!=frameIndex) prepareInterval(before,after);
        if(intervalGap) return before;
        float fraction=(float)((time-before.time())/(after.time()-before.time()));
        var cars=before.cars();
        if(intervalNeedsInterpolation) {
            var interpolated=new ArrayList<RaceReplay.Car>(cars.size());
            for(int index=0;index<cars.size();index++) {
                var car=cars.get(index);
                var target=intervalTargets[index];
                boolean passed=intervalLapMarkers[index]>0 && time>=intervalLapMarkers[index];
                interpolated.add(target==null?car:car.interpolateContinuous(target,fraction,intervalTurns[index],passed));
            }
            cars=interpolated;
        }
        if(before.countdown()>0 && after.countdown()==0 && before.elapsed()==0 && after.elapsed()>0
                && replay.source()!=RaceReplay.Source.LOCAL) {
            if(Double.isNaN(intervalRoomStart)) return new RaceReplay.Frame(time,before.elapsed(),before.countdown(),cars);
            return new RaceReplay.Frame(time,Math.max(0,time-intervalRoomStart),(float)Math.max(0,intervalRoomStart-time),cars);
        }
        return new RaceReplay.Frame(time,before.elapsed()+(after.elapsed()-before.elapsed())*fraction,Math.max(0,before.countdown()+(after.countdown()-before.countdown())*fraction),cars);
    }

    private int frameIndexAt(double at) {
        var frames=replay.frames();
        int last=frames.size()-1;
        if(at>=frames.get(last).time()) return last;
        int current=Math.min(frameIndex,last-1);
        double start=frames.get(current).time();
        double end=frames.get(current+1).time();
        if(at>=start && at<end) return current;
        if(at>=end && current+1<last && at<frames.get(current+2).time()) return current+1;
        if(at<start && current>0 && at>=frames.get(current-1).time()) return current-1;
        int low=0,high=last;
        while(low+1<high) {
            int middle=(low+high)/2;
            if(frames.get(middle).time()<=at) low=middle;
            else high=middle;
        }
        return low;
    }

    private void prepareInterval(RaceReplay.Frame before,RaceReplay.Frame after) {
        preparedIndex=frameIndex;
        intervalGap=after.time()-before.time()>RaceReplay.MAX_CONTIGUOUS_INTERVAL;
        if(intervalGap) return;
        intervalRoomStart=replay.source()==RaceReplay.Source.LOCAL?Double.NaN:before.roomStartBetween(after);
        var events=replay.events();
        boolean timedMarkers=replay.source()==RaceReplay.Source.LOCAL && before.sharesRaceClockWith(after);
        Arrays.fill(intervalLapMarkers,0);
        if(timedMarkers) Arrays.fill(intervalFinishMarkers,0);
        int held=0;
        for(int index=firstEventAfterFrame(before.time());index<events.size();index++) {
            var event=events.get(index);
            if(event.time()>after.time()) break;
            if(event.kind()==RaceReplay.Kind.GAP) {intervalGap=true;return;}
            if(event.kind()==RaceReplay.Kind.RECOVERY) {
                // An unassigned recovery interrupts every car, as in the review timeline.
                if(event.driver()==-1) held=-1;
                else for(int slot=0;slot<before.cars().size();slot++) {
                    if(before.cars().get(slot).id()==event.driver()) held|=1<<slot;
                }
            } else if(timedMarkers && event.time()<after.time()
                    && (event.kind()==RaceReplay.Kind.LAP || event.kind()==RaceReplay.Kind.FINISH)) {
                var markers=event.kind()==RaceReplay.Kind.LAP?intervalLapMarkers:intervalFinishMarkers;
                for(int slot=0;slot<before.cars().size();slot++) {
                    if(before.cars().get(slot).id()==event.driver()) markers[slot]=markers[slot]==0?event.time():-1;
                }
            }
        }
        intervalCameraCuts=held;
        intervalNeedsInterpolation=false;
        for(int index=0;index<before.cars().size();index++) {
            intervalTargets[index]=null;
            int bit=1<<index;
            if((held & bit)!=0) continue;
            var car=before.cars().get(index);
            var target=after.cars().get(index);
            if(target.id()!=car.id()) target=findCar(after.cars(),car.id());
            if(car.equals(target)) continue;
            if(target.cutsCameraFrom(car)) {
                intervalCameraCuts|=bit;
                continue;
            }
            double lap=intervalLapMarkers[index];
            boolean advancesLap=lap>0 && !car.finished() && target.laps()==car.laps()+1;
            if(target.finished()!=car.finished()) {
                double rawFinish=after.elapsed()-(after.time()-lap);
                advancesLap=advancesLap && target.finished() && intervalFinishMarkers[index]==lap
                        && Math.abs(target.finishTime()-rawFinish)<=1e-7;
                if(!advancesLap) continue;
            }
            if(!car.sameParticipation(target)) continue;
            if(!advancesLap) intervalLapMarkers[index]=0;
            intervalTargets[index]=target;
            intervalTurns[index]=car.yawChangeTo(target);
            intervalNeedsInterpolation=true;
        }
    }

    private static RaceReplay.Car findCar(List<RaceReplay.Car> cars,int id) {
        for(var car:cars) if(car.id()==id) return car;
        throw new IllegalArgumentException("Unknown replay car");
    }

    public RaceReplay.Car followed() { return findCar(frame().cars(),follow); }

    public List<RaceReplay.Car> standings() {
        var current=frame();
        if(sampledStandings==null) sampledStandings=rank(current.cars());
        return sampledStandings;
    }

    private List<RaceReplay.Car> rank(List<RaceReplay.Car> cars) {
        var result=new ArrayList<>(cars);
        if(time>=replay.duration() && !finalClassification.isEmpty()) {
            result.sort(Comparator.comparingInt(car->finalClassification.indexOf(car.id())));
            return List.copyOf(result);
        }
        boolean roomResult=time>=replay.duration() && replay.end()==RaceReplay.End.COMPLETED && replay.source()!=RaceReplay.Source.LOCAL;
        result.sort(replay.qualifying()?QUALIFYING_ORDER:(a,b)->compareRaceCars(a,b,roomResult));
        return List.copyOf(result);
    }
}
