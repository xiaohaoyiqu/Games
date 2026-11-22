package com.f1car.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Collects failed-search observations without inferring how long an unobserved request stayed queued. */
final class ReplayRecoveryWaitRecorder {
    private static final Comparator<RaceReplay.Frame> FRAME_TIME=Comparator.comparingDouble(RaceReplay.Frame::time);
    private final List<RaceReplay.Driver> drivers;
    private final double[] start,lastFailure;
    private final int[] lastRecoveryCount;
    private final long[][] counts;
    private final boolean[] completeEvidence;
    private final RecoveryPlanner.FailedSites[] firstSites,lastSites;
    private final RaceReplay.Frame[] lastFailureFrames;
    private final TreeMap<Double,RaceReplay.Frame> closedFailureFrames=new TreeMap<>();
    private final List<RaceReplay.RecoveryWait> closed=new ArrayList<>();
    private int waiting,locationMaps;

    ReplayRecoveryWaitRecorder(List<RaceReplay.Driver> drivers) {
        this.drivers=drivers;
        start=new double[drivers.size()];
        lastFailure=new double[drivers.size()];
        lastRecoveryCount=new int[drivers.size()];
        counts=new long[drivers.size()][RecoveryPlanner.Failure.values().length];
        completeEvidence=new boolean[drivers.size()];
        firstSites=new RecoveryPlanner.FailedSites[drivers.size()];
        lastSites=new RecoveryPlanner.FailedSites[drivers.size()];
        lastFailureFrames=new RaceReplay.Frame[drivers.size()];
        Arrays.fill(start,-1);
    }

    boolean observe(RaceReplay.Frame frame,RaceReplay.Frame previous,int failedSearches,RecoveryPlanner.LocalLookup[] searches) {
        boolean searched=failedSearches!=ReplayRecorder.RECOVERY_UNOBSERVED;
        if(waiting==0 && (!searched || failedSearches==0)) return false;
        int previousWaiting=waiting;
        int failures=searched?failedSearches:0;
        // Close waits first so an unused map reservation is available to any new wait in this frame.
        for(int bits=waiting & ~failures;bits!=0;bits&=bits-1) {
            int slot=Integer.numberOfTrailingZeros(bits);
            int driver=drivers.get(slot).id();
            int recovered=frame.recoveryCount(driver);
            if(!searched && recovered<=lastRecoveryCount[slot]) continue;
            var end=recovered>lastRecoveryCount[slot]
                    ?RaceReplay.RecoveryWaitEnd.RECOVERED:RaceReplay.RecoveryWaitEnd.REQUEST_CLEARED;
            var lookup=searches==null?null:searches[slot];
            var placement=end==RaceReplay.RecoveryWaitEnd.RECOVERED && lookup!=null && lookup.placement()!=null
                    && recovered>previous.recoveryCount(driver)?lookup.placement():null;
            closed.add(wait(slot,frame.time(),end,placement));
            var lastFrame=lastFailureFrames[slot];
            if(lastFrame!=null) closedFailureFrames.putIfAbsent(lastFrame.time(),lastFrame);
            else if(firstSites[slot]!=null) locationMaps--;
            start[slot]=-1;
            firstSites[slot]=lastSites[slot]=null;
            lastFailureFrames[slot]=null;
            waiting&=~(1<<slot);
        }
        for(int bits=failures;bits!=0;bits&=bits-1) {
            int slot=Integer.numberOfTrailingZeros(bits);
            var lookup=searches==null?null:searches[slot];
            var sites=lookup==null?null:lookup.failedSites();
            if(start[slot]<0) {
                start[slot]=frame.time();
                Arrays.fill(counts[slot],0);
                completeEvidence[slot]=true;
                if(sites!=null && locationMaps<RaceReplay.MAX_RECOVERY_WAIT_MAPS) {
                    firstSites[slot]=sites;
                    locationMaps++;
                }
            }
            if(lookup==null) completeEvidence[slot]=false;
            else counts[slot][lookup.failure().ordinal()]++;
            lastSites[slot]=firstSites[slot]==null?null:sites;
            lastFailureFrames[slot]=lastSites[slot]==null?null:frame;
            lastFailure[slot]=frame.time();
            lastRecoveryCount[slot]=frame.recoveryCount(drivers.get(slot).id());
        }
        waiting|=failures;
        return previousWaiting!=waiting;
    }

    List<RaceReplay.RecoveryWait> snapshot() {
        var entries=new ArrayList<>(closed);
        for(int slot=0;slot<drivers.size();slot++) {
            if(start[slot]>=0) entries.add(wait(slot,lastFailure[slot],RaceReplay.RecoveryWaitEnd.LAST_OBSERVATION,null));
        }
        entries.sort(Comparator.comparingDouble(RaceReplay.RecoveryWait::from).thenComparingInt(RaceReplay.RecoveryWait::driver));
        return entries;
    }

    boolean fitsFrameBudget(List<RaceReplay.Frame> frames,RaceReplay.Frame previousToKeep,int failedSearches) {
        // The incoming observation covers any last failure recorded at its own time.
        int free=RaceReplay.MAX_FRAMES-frames.size()-(previousToKeep==null?1:2);
        return free>=locationMaps || missingFailureFrames(frames,previousToKeep,Math.max(0,failedSearches)).size()<=free;
    }

    /** Returns the chronological input to the immutable replay snapshot. */
    List<RaceReplay.Frame> snapshotFrames(List<RaceReplay.Frame> frames,RaceReplay.Frame tail) {
        var missing=missingFailureFrames(frames,tail,0);
        if(missing.isEmpty() && tail==null) return frames;
        var merged=new ArrayList<RaceReplay.Frame>(frames.size()+missing.size()+(tail==null?0:1));
        int index=0;
        for(var failure:missing) {
            while(index<frames.size() && frames.get(index).time()<failure.time()) merged.add(frames.get(index++));
            merged.add(failure);
        }
        if(index==0) merged.addAll(frames);
        else while(index<frames.size()) merged.add(frames.get(index++));
        if(tail!=null) merged.add(tail);
        return merged;
    }

    private List<RaceReplay.Frame> missingFailureFrames(List<RaceReplay.Frame> frames,RaceReplay.Frame extra,int replacedFailures) {
        if(locationMaps==0) return List.of();
        var retained=new TreeMap<>(closedFailureFrames);
        for(int slot=0;slot<lastFailureFrames.length;slot++) {
            var frame=lastFailureFrames[slot];
            if(frame!=null && (replacedFailures & (1<<slot))==0) retained.putIfAbsent(frame.time(),frame);
        }
        var missing=new ArrayList<RaceReplay.Frame>(retained.size());
        for(var frame:retained.values()) {
            if((extra==null || frame.time()!=extra.time()) && Collections.binarySearch(frames,frame,FRAME_TIME)<0)
                missing.add(frame);
        }
        return missing;
    }

    private RaceReplay.RecoveryWait wait(int slot,double through,RaceReplay.RecoveryWaitEnd end,RecoveryPlanner.Placement placement) {
        var locations=firstSites[slot]==null || lastSites[slot]==null?null
                :new RaceReplay.RecoveryWaitLocations(firstSites[slot],lastFailure[slot],lastSites[slot],placement);
        return new RaceReplay.RecoveryWait(drivers.get(slot).id(),start[slot],through,end,evidence(slot),locations);
    }

    private RaceReplay.RecoverySearchEvidence evidence(int slot) {
        if(!completeEvidence[slot]) return null;
        var searchCounts=counts[slot];
        return new RaceReplay.RecoverySearchEvidence(searchCounts[RecoveryPlanner.Failure.NO_STATIC_CANDIDATE.ordinal()],
                searchCounts[RecoveryPlanner.Failure.TRAFFIC_PREDICTION.ordinal()],
                searchCounts[RecoveryPlanner.Failure.INCOMPLETE_TRAFFIC.ordinal()]);
    }

}
