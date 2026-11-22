package com.f1car.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.DoubleStream;

/** An index of stored marks and low-speed intervals supported by the original visual samples. */
public final class ReplayReviewTimeline {
    public static final double MIN_NEAR_STOP_SECONDS=.3;
    private static final double LEAD_IN_SECONDS=2;
    private static final int CANCELLATION_CHECK_INTERVAL=256;

    public enum Filter { ALL, INTERRUPTIONS, NEAR_STOPS, RECOVERY_WAITS }

    public record Mark(double time,double through,int driver,RaceReplay.Kind kind,RaceReplay.RecoveryWaitEnd waitEnd,
                       RaceReplay.RecoverySearchEvidence waitEvidence,RaceReplay.RecoveryWaitLocations waitLocations) {
        public boolean nearStop() {return kind==null && waitEnd==null;}
        public boolean interruption() {return kind!=null && kind.interruption();}
    }

    private final RaceReplay replay;
    private final Mark[] recordedMarks;
    private final Map<Mark,Integer> recordedIndices;
    private final boolean nearStopsAvailable;
    private final double[] eventGaps,resumedAfterGaps;
    private final Map<Filter,List<List<Mark>>> filtered;

    public ReplayReviewTimeline(RaceReplay replay) {
        this(replay,()->false);
    }

    ReplayReviewTimeline(RaceReplay replay,BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        this.replay=replay;
        nearStopsAvailable=replay.source()==RaceReplay.Source.LOCAL && !replay.qualifying() && replay.drivers().size()>1;
        var events=replay.events();
        var entries=new ArrayList<Mark>(events.size());
        var gaps=DoubleStream.builder();
        recordedMarks=new Mark[events.size()];
        recordedIndices=new IdentityHashMap<>(events.size());
        for(int index=0;index<events.size();index++) {
            if(index%CANCELLATION_CHECK_INTERVAL==0) checkCancelled(cancelled);
            var event=events.get(index);
            var mark=new Mark(event.time(),event.time(),event.driver(),event.kind(),null,null,null);
            recordedMarks[index]=mark;
            recordedIndices.put(mark,index);
            entries.add(mark);
            if(event.kind()==RaceReplay.Kind.GAP) gaps.add(event.time());
        }
        eventGaps=gaps.build().toArray();
        if(recoveryWaitsAvailable()) {
            var waits=replay.recoveryWaits();
            for(int index=0;index<waits.size();index++) {
                if(index%CANCELLATION_CHECK_INTERVAL==0) checkCancelled(cancelled);
                var wait=waits.get(index);
                entries.add(new Mark(wait.from(),wait.through(),wait.driver(),null,wait.end(),wait.evidence(),wait.locations()));
            }
        }
        resumedAfterGaps=nearStopsAvailable?collectNearStops(entries,cancelled):resumedGapTimes(replay.frames(),eventGaps,cancelled);
        checkCancelled(cancelled);
        // Stable sorting keeps recorded events in source order before waits and near-stops at a tied time.
        // Both representations of zero belong to the same recording instant.
        entries.sort(Comparator.comparingDouble(mark->mark.time()==0?0:mark.time()));
        filtered=groupMarks(entries,cancelled);
        checkCancelled(cancelled);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new CancellationException("Replay review preparation cancelled");
    }

    /** Prepare the filters as well as the marks before passing the index to the playback thread. */
    public static ReplayReviewTimeline prepare(RaceReplay replay,BooleanSupplier cancelled) {
        return new ReplayReviewTimeline(replay,cancelled);
    }

    boolean matchesReplay(RaceReplay source) {return replay==source;}

    public boolean nearStopsAvailable() {return nearStopsAvailable;}
    public boolean recoveryWaitsAvailable() {return replay.recoveryWaits()!=null;}

    public Mark recordedMark(int eventIndex) {return recordedMarks[eventIndex];}

    public int recordedEventIndex(Mark mark) {
        return recordedIndices.getOrDefault(Objects.requireNonNull(mark),-1);
    }

    public List<Mark> marks(Filter filter,int driverId) {
        int slot=driverSlot(driverId);
        if(slot<0) return List.of();
        return filtered.get(filter).get(slot);
    }

    /** Preserve an explicit choice, then prefer an exact mark or an interval at the playhead. */
    public int selectionIndex(Filter filter,int driverId,double time,Mark preferred) {
        var visible=marks(filter,driverId);
        if(visible.isEmpty()) return -1;
        if(preferred!=null) {
            int selected=markIndex(visible,preferred);
            if(selected>=0) return selected;
        }
        int next=firstMarkAtOrAfter(visible,time);
        if(next==0 || next<visible.size() && visible.get(next).time()==time) return next;
        var active=coveringMark(filter,driverId,time);
        return active==null?Math.min(next,visible.size()-1):markIndex(visible,active);
    }

    private Mark coveringMark(Filter filter,int driverId,double time) {
        boolean includeStops=nearStopsAvailable && (filter==Filter.ALL || filter==Filter.NEAR_STOPS);
        boolean includeWaits=recoveryWaitsAvailable() && !replay.recoveryWaits().isEmpty()
                && (filter==Filter.ALL || filter==Filter.RECOVERY_WAITS);
        if(!(includeStops || includeWaits) || !Double.isFinite(time) || time<0 || time>replay.duration() || heldAt(time))
            return null;
        int from=driverId==-1?1:driverSlot(driverId);
        int through=driverId==-1?replay.drivers().size():from;
        var stops=includeStops?filtered.get(Filter.NEAR_STOPS):null;
        var waits=includeWaits?filtered.get(Filter.RECOVERY_WAITS):null;
        Mark best=null;
        for(int slot=from;slot<=through;slot++) {
            if(waits!=null) best=later(best,coveringIn(waits.get(slot),time));
            if(stops!=null) best=later(best,coveringIn(stops.get(slot),time));
        }
        return best;
    }

    private static Mark later(Mark best,Mark candidate) {
        if(candidate==null) return best;
        if(best==null || candidate.time()>best.time()
                || candidate.time()==best.time() && candidate.waitEnd()!=null && best.waitEnd()==null) return candidate;
        return best;
    }

    /** Waits and near-stops of a single type are non-overlapping for one driver. */
    private static Mark coveringIn(List<Mark> intervals,double time) {
        int previous=firstMarkAtOrAfter(intervals,time)-1;
        if(previous<0) return null;
        var mark=intervals.get(previous);
        // Near-stops and unfinished waits end with an observed sample of the same condition.
        boolean observedThrough=mark.nearStop() || mark.waitEnd()==RaceReplay.RecoveryWaitEnd.LAST_OBSERVATION;
        return time<mark.through() || time==mark.through() && observedThrough?mark:null;
    }

    private int markIndex(List<Mark> rows,Mark target) {
        double time=target.time();
        int low=firstMarkAtOrAfter(rows,time);
        if(low==rows.size() || rows.get(low).time()!=time) return -1;
        if(rows.get(low)==target) return low;
        if(target.kind()==null) {
            // Waits and near-stops follow all recorded events at the same instant.
            for(int i=firstMarkAtOrAfter(rows,Math.nextUp(time))-1;i>=low && rows.get(i).kind()==null;i--) {
                if(rows.get(i)==target) return i;
            }
            return -1;
        }
        int expected=recordedEventIndex(target);
        if(expected<0) return -1;
        int high=rows.size();
        while(low<high) {
            int middle=(low+high)/2;
            var row=rows.get(middle);
            int event=row.time()==time?recordedEventIndex(row):-1;
            if(event>=0 && event<expected) low=middle+1;
            else high=middle;
        }
        return low<rows.size() && rows.get(low)==target?low:-1;
    }

    private static int firstMarkAtOrAfter(List<Mark> rows,double time) {
        int lower=0,upper=rows.size();
        while(lower<upper) {
            int middle=(lower+upper)/2;
            if(rows.get(middle).time()<time) lower=middle+1;
            else upper=middle;
        }
        return lower;
    }

    private Map<Filter,List<List<Mark>>> groupMarks(List<Mark> marks,BooleanSupplier cancelled) {
        int slots=replay.drivers().size()+1;
        var groups=new EnumMap<Filter,List<List<Mark>>>(Filter.class);
        for(var filter:Filter.values()) {
            var bySlot=new ArrayList<List<Mark>>(slots);
            for(int slot=0;slot<slots;slot++) bySlot.add(new ArrayList<>());
            groups.put(filter,bySlot);
        }
        var all=groups.get(Filter.ALL);
        all.set(0,marks);
        for(int index=0;index<marks.size();index++) {
            if(index%CANCELLATION_CHECK_INTERVAL==0) checkCancelled(cancelled);
            var mark=marks.get(index);
            int slot=driverSlot(mark.driver());
            if(slot>0) all.get(slot).add(mark);

            List<List<Mark>> category;
            if(mark.waitEnd()!=null) category=groups.get(Filter.RECOVERY_WAITS);
            else if(mark.nearStop()) category=groups.get(Filter.NEAR_STOPS);
            else if(mark.interruption()) category=groups.get(Filter.INTERRUPTIONS);
            else continue;
            category.get(0).add(mark);
            if(slot>0) category.get(slot).add(mark);
        }
        groups.replaceAll((filter,bySlot)->bySlot.stream().map(List::copyOf).toList());
        return Map.copyOf(groups);
    }

    private int driverSlot(int driverId) {
        if(driverId==-1) return 0;
        var drivers=replay.drivers();
        for(int i=0;i<drivers.size();i++) if(drivers.get(i).id()==driverId) return i+1;
        return -1;
    }

    /** Locate an actual sample without crossing the wait start or a recording gap. */
    public OptionalDouble recoverySceneTime(Mark mark,boolean lastFailure) {
        if(mark.waitLocations()==null) return OptionalDouble.empty();
        double search=lastFailure?mark.waitLocations().lastTime():mark.time();
        if(search==mark.through() && mark.waitEnd()!=RaceReplay.RecoveryWaitEnd.LAST_OBSERVATION)
            return OptionalDouble.empty();
        var frames=replay.frames();
        int next=firstFrameAfter(search);
        double recorded=frames.get(next-1).time();
        if(recorded==search) return OptionalDouble.of(recorded);
        if(recorded<mark.time() || search-recorded>RaceReplay.MAX_CONTIGUOUS_INTERVAL) return OptionalDouble.empty();
        if(next<frames.size() && frames.get(next).time()-recorded>RaceReplay.MAX_CONTIGUOUS_INTERVAL)
            return OptionalDouble.empty();
        if(latestGapAtOrBefore(eventGaps,search)>recorded) return OptionalDouble.empty();
        return OptionalDouble.of(recorded);
    }

    private boolean heldAt(double time) {
        var frames=replay.frames();
        int next=firstFrameAfter(time);
        double previous=frames.get(next-1).time();
        if(time==previous || next==frames.size()) return false;
        double following=frames.get(next).time();
        return following-previous>RaceReplay.MAX_CONTIGUOUS_INTERVAL
                || latestGapAtOrBefore(eventGaps,following)>previous;
    }

    /** Pre-roll begins in the same observed run of frames as the mark. */
    public double leadIn(double time) {
        if(!Double.isFinite(time) || time<=0) return 0;
        double target=Math.min(time,replay.duration());
        double start=Math.max(0,target-LEAD_IN_SECONDS);
        return Math.max(start,latestGapAtOrBefore(resumedAfterGaps,target));
    }

    private static double[] resumedGapTimes(List<RaceReplay.Frame> frames,double[] eventGaps,BooleanSupplier cancelled) {
        var times=DoubleStream.builder();
        int gap=0;
        for(int i=1;i<frames.size();i++) {
            if((i-1)%CANCELLATION_CHECK_INTERVAL==0) checkCancelled(cancelled);
            double before=frames.get(i-1).time();
            double time=frames.get(i).time();
            while(gap<eventGaps.length && eventGaps[gap]<=before) gap++;
            if(time-before>RaceReplay.MAX_CONTIGUOUS_INTERVAL || gap<eventGaps.length && eventGaps[gap]<=time)
                times.add(time);
        }
        return times.build().toArray();
    }

    private static double latestGapAtOrBefore(double[] times,double time) {
        int lower=0,upper=times.length;
        while(lower<upper) {
            int middle=(lower+upper)/2;
            if(times[middle]<=time) lower=middle+1;
            else upper=middle;
        }
        return lower==0?Double.NEGATIVE_INFINITY:times[lower-1];
    }

    private int firstFrameAfter(double time) {
        var frames=replay.frames();
        int lower=1,upper=frames.size();
        while(lower<upper) {
            int middle=(lower+upper)/2;
            if(frames.get(middle).time()<=time) lower=middle+1;
            else upper=middle;
        }
        return lower;
    }

    private double[] collectNearStops(List<Mark> entries,BooleanSupplier cancelled) {
        var drivers=replay.drivers();
        var spans=new StopSpan[drivers.size()];
        for(int i=0;i<spans.length;i++) spans[i]=new StopSpan(drivers.get(i).id());
        if(recoveryWaitsAvailable()) {
            var waits=replay.recoveryWaits();
            for(int index=0;index<waits.size();index++) {
                if(index%CANCELLATION_CHECK_INTERVAL==0) checkCancelled(cancelled);
                var wait=waits.get(index);
                spans[driverSlot(wait.driver())-1].waits.add(wait);
            }
        }
        var events=replay.events();
        int eventIndex=0,allDrivers=(1<<spans.length)-1;
        double duration=replay.duration();
        var gaps=DoubleStream.builder();
        RaceReplay.Frame previous=null;
        var frames=replay.frames();
        for(int frameIndex=0;frameIndex<frames.size();frameIndex++) {
            if(frameIndex%CANCELLATION_CHECK_INTERVAL==0) checkCancelled(cancelled);
            var frame=frames.get(frameIndex);
            int interrupted=0;
            boolean explicitGap=false;
            while(eventIndex<events.size() && events.get(eventIndex).time()<=frame.time()) {
                var event=events.get(eventIndex++);
                if(event.kind()==RaceReplay.Kind.GAP) {
                    explicitGap=true;
                    interrupted=allDrivers;
                }
                else if(event.kind()==RaceReplay.Kind.RECOVERY) {
                    int slot=driverSlot(event.driver());
                    interrupted|=slot==0?allDrivers:1<<(slot-1);
                }
            }
            double previousTime=previous==null?-1:previous.time();
            boolean frameGap=previous!=null && frame.time()-previousTime>RaceReplay.MAX_CONTIGUOUS_INTERVAL;
            if(previous!=null && (frameGap || explicitGap)) gaps.add(frame.time());
            boolean sampleGap=frameGap || previous!=null && frame.elapsed()<=previous.elapsed();
            for(int i=0;i<spans.length;i++) {
                var span=spans[i];
                var car=frame.cars().get(i);
                if(car.id()!=span.driver) car=carAt(frame,span.driver);
                span.observe(frame,car,previousTime,sampleGap || (interrupted & (1<<i))!=0);
            }
            previous=frame;
        }
        // Stable ordering for stops with the same start time follows the replay roster.
        for(var span:spans) {
            span.close(duration);
            entries.addAll(span.marks);
        }
        return gaps.build().toArray();
    }

    private static final class StopSpan {
        private final int driver;
        private final List<RaceReplay.RecoveryWait> waits=new ArrayList<>();
        private final List<Mark> marks=new ArrayList<>();
        private RaceReplay.Car previousCar;
        private int waitIndex;
        private double started=-1;

        StopSpan(int driver) {this.driver=driver;}

        void observe(RaceReplay.Frame frame,RaceReplay.Car car,double previousTime,boolean interrupted) {
            while(waitIndex<waits.size()) {
                var wait=waits.get(waitIndex);
                // A resolved wait's end frame is the first sample eligible for a new low-speed interval.
                if(wait.end()==RaceReplay.RecoveryWaitEnd.LAST_OBSERVATION || wait.through()>frame.time()) break;
                if(previousTime>=0 && wait.through()>previousTime) interrupted=true;
                waitIndex++;
            }
            boolean waiting=waitIndex<waits.size() && waits.get(waitIndex).from()<=frame.time();
            boolean eligible=!waiting && nearStopSample(frame,car);
            if(started>=0 && (!eligible || interrupted || !previousCar.continuous(car))) close(previousTime);
            if(eligible && started<0) started=frame.time();
            previousCar=car;
        }

        void close(double time) {
            if(started>=0 && time-started>=MIN_NEAR_STOP_SECONDS-1e-9)
                marks.add(new Mark(started,time,driver,null,null,null,null));
            started=-1;
        }
    }

    private static boolean nearStopSample(RaceReplay.Frame frame,RaceReplay.Car car) {
        return frame.countdown()==0 && car.connected() && !car.finished() && !car.retired()
                && car.speed()<=WorldTrafficRestart.MAX_PROBE_SPEED;
    }

    private static RaceReplay.Car carAt(RaceReplay.Frame frame,int driver) {
        for(var car:frame.cars()) if(car.id()==driver) return car;
        throw new IllegalArgumentException("Replay roster changed");
    }
}
