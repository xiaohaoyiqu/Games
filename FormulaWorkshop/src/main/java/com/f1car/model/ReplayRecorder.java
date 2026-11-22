package com.f1car.model;

import java.util.*;

/** Samples at about 20 Hz, retaining discontinuities immediately between regular observations. */
public final class ReplayRecorder {
    public static final int RECOVERY_UNOBSERVED=-1;
    private final String id=UUID.randomUUID().toString(),title,physics;
    private final long recordedAt=System.currentTimeMillis();
    private final RaceReplay.Source source;
    private final RaceReplay.Track track;
    private final List<RaceReplay.Driver> drivers;
    private final int laps;
    private final boolean qualifying;
    private final List<RaceReplay.Frame> frames=new ArrayList<>();
    private final List<RaceReplay.Event> events=new ArrayList<>();
    private final List<RaceReplay.Event> frameEvents=new ArrayList<>();
    private final ReplayRecoveryWaitRecorder waitRecorder;
    private RaceReplay.Frame latest;
    private RaceReplay cachedSnapshot;
    private boolean limited;
    public ReplayRecorder(String title,String physics,RaceReplay.Source source,TrackLayout track,int laps,boolean qualifying,
                          List<RaceReplay.Driver> drivers) {
        this(title,physics,source,track,laps,qualifying,drivers,false);
    }
    public ReplayRecorder(String title,String physics,RaceReplay.Source source,TrackLayout track,int laps,boolean qualifying,
                          List<RaceReplay.Driver> drivers,boolean captureRecoveryWaits) {
        this.title=title;this.physics=physics;this.source=source;this.track=RaceReplay.Track.freeze(track);
        this.laps=laps;this.qualifying=qualifying;this.drivers=List.copyOf(drivers);
        if(source!=RaceReplay.Source.LOCAL && drivers.stream().anyMatch(driver->driver.appearance()!=null))
            throw new IllegalArgumentException("Network capture does not include custom appearance");
        if(captureRecoveryWaits && (source!=RaceReplay.Source.LOCAL || qualifying))
            throw new IllegalArgumentException("Recovery waits require a local race");
        waitRecorder=captureRecoveryWaits?new ReplayRecoveryWaitRecorder(this.drivers):null;
    }
    public boolean limited() { return limited; }

    /** Older and duplicate samples are discarded before any of their readings are used. */
    public boolean isStaleObservation(double time) {
        return time>=0 && latest!=null && time<=latest.time();
    }

    public void observe(RaceReplay.Frame frame) {
        observe(frame,RECOVERY_UNOBSERVED);
    }
    /** Each set bit records a failed placement search for the driver in that roster slot. */
    public void observe(RaceReplay.Frame frame,int failedRecoverySearches) {
        observe(frame,failedRecoverySearches,null,null);
    }
    /** Each searched driver contributes its result in roster order. The array is read synchronously. */
    public void observe(RaceReplay.Frame frame,RecoveryPlanner.LocalLookup[] searches) {
        observeLocal(frame,Objects.requireNonNull(searches),null);
    }

    /** Lap crossings use session time and the same roster order as the current local observation. */
    public void observeLocal(RaceReplay.Frame frame,RecoveryPlanner.LocalLookup[] searches,double[] lapCrossings) {
        if(source!=RaceReplay.Source.LOCAL || searches!=null && searches.length!=drivers.size()
                || lapCrossings!=null && lapCrossings.length!=frame.cars().size())
            throw new IllegalArgumentException("Invalid local replay observations");
        if(searches==null) {
            observe(frame,RECOVERY_UNOBSERVED,null,lapCrossings);
            return;
        }
        int failedSearches=0;
        for(int slot=0;slot<searches.length;slot++) {
            if(searches[slot]!=null && searches[slot].failure()!=null) failedSearches|=1<<slot;
        }
        observe(frame,failedSearches,searches,lapCrossings);
    }
    private void observe(RaceReplay.Frame frame,int failedRecoverySearches,RecoveryPlanner.LocalLookup[] searches,double[] lapCrossings) {
        if(limited || isStaleObservation(frame.time())) return;
        if(frame.cars().size()!=drivers.size()) throw new IllegalArgumentException("Incomplete replay roster");
        if(failedRecoverySearches!=RECOVERY_UNOBSERVED && (waitRecorder==null || failedRecoverySearches<0
                || failedRecoverySearches>=(1<<drivers.size()) || frame.countdown()>0))
            throw new IllegalArgumentException("Invalid recovery search observation");
        if(frame.time()>RaceReplay.MAX_DURATION || frames.size()>=RaceReplay.MAX_FRAMES-1) { limited=true;return; }
        if(latest!=null) {
            frameEvents.clear();
            if(latest.countdown()>0 && frame.countdown()==0) {
                double start=source==RaceReplay.Source.LOCAL?Double.NaN:latest.roomStartBetween(frame);
                frameEvents.add(new RaceReplay.Event(Double.isFinite(start)?start:frame.time(),RaceReplay.Kind.START,-1));
            }
            boolean observationGap=frame.time()-latest.time()>RaceReplay.MAX_CONTIGUOUS_INTERVAL;
            if(observationGap) frameEvents.add(new RaceReplay.Event(frame.time(),RaceReplay.Kind.GAP,-1));
            var previous=latest.cars();
            var current=frame.cars();
            var stored=frames.get(frames.size()-1);
            boolean preserveInterval=frame.time()-stored.time()>RaceReplay.MAX_CONTIGUOUS_INTERVAL;
            boolean stateChanged=false;
            boolean preciseCrossing=false;
            for(int slot=0;slot<current.size();slot++) {
                var car=current.get(slot);
                var before=slot<previous.size()?previous.get(slot):null;
                if(before==null || before.id()!=car.id()) before=findPreviousCar(previous,car.id());
                double lapTime=frame.time();
                if(car.laps()>before.laps()) {
                    if(lapCrossings!=null && car.laps()==before.laps()+1 && before.poseContinuous(car))
                        lapTime=lapMarkerTime(frame,lapCrossings[slot],car);
                    frameEvents.add(new RaceReplay.Event(lapTime,RaceReplay.Kind.LAP,car.id()));
                    preciseCrossing|=lapTime<frame.time();
                }
                // Across missing observations a distant endpoint can be ordinary driving.
                if(car.recovery()>before.recovery() || (!observationGap && car.jumpedFrom(before)))
                    frameEvents.add(new RaceReplay.Event(frame.time(),RaceReplay.Kind.RECOVERY,car.id()));
                if(stored!=latest && !preserveInterval) {
                    var retained=stored.cars().get(slot);
                    if(retained.id()!=car.id()) retained=findPreviousCar(stored.cars(),car.id());
                    preserveInterval=car.jumpedFrom(retained);
                }
                stateChanged|=car.finished()!=before.finished() || !car.sameParticipation(before) || car.recovery()!=before.recovery()
                        || car.valid()!=before.valid() || car.penalty()!=before.penalty()
                        || car.lastValid()!=before.lastValid() || car.lapTime()!=before.lapTime();
                if(car.finished() && !before.finished()) frameEvents.add(new RaceReplay.Event(lapTime,RaceReplay.Kind.FINISH,car.id()));
                if(car.retired() && !before.retired()) frameEvents.add(new RaceReplay.Event(frame.time(),RaceReplay.Kind.RETIRE,car.id()));
                if(!car.connected() && before.connected()) frameEvents.add(new RaceReplay.Event(frame.time(),RaceReplay.Kind.DISCONNECT,car.id()));
            }
            if(events.size()+frameEvents.size()>RaceReplay.MAX_EVENTS) { limited=true;return; }
            // Keep the last real pose when decimation would extend or invent a playback cut.
            boolean newEvents=!frameEvents.isEmpty();
            boolean keepPrevious=stored!=latest && (preserveInterval || newEvents || stateChanged);
            if(waitRecorder!=null && !waitRecorder.fitsFrameBudget(frames,keepPrevious?latest:null,failedRecoverySearches)) {
                limited=true;
                return;
            }
            boolean waitChanged=observeWaits(frame,failedRecoverySearches,searches);
            if(preciseCrossing) frameEvents.sort(Comparator.comparingDouble(RaceReplay.Event::time));
            if(keepPrevious) frames.add(latest);
            if(newEvents) events.addAll(frameEvents);
            if(waitChanged || newEvents || stateChanged || frame.time()-frames.get(frames.size()-1).time()>=.05-1e-8) frames.add(frame);
        } else {
            if(frame.time()!=0) throw new IllegalArgumentException("Replay must start at zero");
            for(var driver:drivers) findPreviousCar(frame.cars(),driver.id());
            observeWaits(frame,failedRecoverySearches,searches);
            frames.add(frame);
        }
        latest=frame;
        cachedSnapshot=null;
    }

    private double lapMarkerTime(RaceReplay.Frame frame,double sessionTime,RaceReplay.Car car) {
        if(!Double.isFinite(sessionTime) || sessionTime<=latest.elapsed() || sessionTime>=frame.elapsed()
                || !latest.sharesRaceClockWith(frame)
                || car.finished() && car.finishTime()!=sessionTime) return frame.time();
        double replayTime=frame.time()-(frame.elapsed()-sessionTime);
        return replayTime>latest.time() && replayTime<frame.time()?replayTime:frame.time();
    }

    private static RaceReplay.Car findPreviousCar(List<RaceReplay.Car> previous,int id) {
        for(var car:previous) if(car.id()==id) return car;
        throw new NoSuchElementException("Unknown replay car " + id);
    }

    private boolean observeWaits(RaceReplay.Frame frame,int failedRecoverySearches,RecoveryPlanner.LocalLookup[] searches) {
        return waitRecorder!=null && waitRecorder.observe(frame,latest,failedRecoverySearches,searches);
    }
    public RaceReplay snapshot(RaceReplay.End end) {
        if(latest==null || latest.time()<=0) return null;
        var actualEnd=limited?RaceReplay.End.LIMIT:end;
        if(cachedSnapshot!=null && cachedSnapshot.end()==actualEnd) return cachedSnapshot;
        var tail=frames.get(frames.size()-1).time()<latest.time()?latest:null;
        // RaceReplay takes the immutable copy. Only assemble a separate list for additional frames.
        List<RaceReplay.Frame> snapshotFrames=frames;
        if(waitRecorder!=null) snapshotFrames=waitRecorder.snapshotFrames(frames,tail);
        else if(tail!=null) {
            snapshotFrames=new ArrayList<>(frames.size()+1);
            snapshotFrames.addAll(frames);
            snapshotFrames.add(tail);
        }
        int version=1;
        for(var driver:drivers) if(driver.appearance()!=null) version=Math.max(version,driver.appearance().design().version()==2?3:2);
        if(waitRecorder!=null) version=RaceReplay.RECOVERY_WAIT_VERSION;
        cachedSnapshot=new RaceReplay(version,id,recordedAt,title,physics,source,track,laps,qualifying,drivers,
                actualEnd,snapshotFrames,events,waitRecorder==null?null:waitRecorder.snapshot());
        return cachedSnapshot;
    }
}
