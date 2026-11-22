package com.f1car.model;

import com.f1car.persistence.GhostStore;
import com.f1car.persistence.TelemetryStore;
import java.util.ArrayList;
import java.util.List;

/** Observes the existing ordered-gate lap counter; the ghost cannot advance progress. */
public final class TimeTrialSession {
    public record Result(double duration,boolean valid,List<Double> sectors,List<Double> deltas,double delta,boolean saved) {
        public Result { sectors=List.copyOf(sectors); deltas=List.copyOf(deltas); }
    }
    private final GhostStore store;
    private final String key,trackName;
    private final int livery;
    private GhostLap best,reference,overflowBest;
    private GhostLap selectedReference,completedLap;
    private GhostLap.Playback ghostPlayback;
    private String selectedLabel="个人最佳",referenceLabel="个人最佳",lastReferenceLabel="个人最佳",selectedSource,referenceSource,completedReplyTo;
    private final TelemetryLap.Conditions conditions;
    private final String routeGeometry;
    private final List<Double> cumulative=new ArrayList<>();
    private List<Double> sectorTimes=List.of();
    private Recorder recorder;
    private Result last;
    private boolean started,currentValid=true;
    private int completed;
    private double elapsed,lapStart;
    private final LapLineObserver lineObserver;
    private List<LapProgress.Crossing> readCrossings=List.of();
    private String notice="";
    private final TelemetryRecorder telemetry;
    public TimeTrialSession(TrackLayout track,String physicsVersion,Tuning tuning,GhostStore store) {
        this(track,physicsVersion,tuning,store,null);
    }
    public TimeTrialSession(TrackLayout track,String physicsVersion,Tuning tuning,GhostStore store,TelemetryStore telemetryStore) {
        this(track,physicsVersion,tuning,store,telemetryStore,ControlIdentity.KEYBOARD);
    }
    public TimeTrialSession(TrackLayout track,String physicsVersion,Tuning tuning,GhostStore store,TelemetryStore telemetryStore,String controls) {
        this(track,physicsVersion,tuning,store,telemetryStore,controls,null);
    }
    public TimeTrialSession(TrackLayout track,String physicsVersion,Tuning tuning,GhostStore store,TelemetryStore telemetryStore,String controls,EquipmentLap.Source source) {
        key=GhostLap.key(track,physicsVersion,tuning,controls);livery=tuning.livery();this.store=store;
        trackName=track.name;
        conditions=TelemetryLap.Conditions.of(track,physicsVersion,tuning,controls);
        routeGeometry=track.exchangeGeometryKey();
        notice=refreshPersonalBest();
        telemetry=telemetryStore==null?null:new TelemetryRecorder(track,physicsVersion,tuning,telemetryStore,controls,source);
        lineObserver=telemetry==null?new LapLineObserver():telemetry.lineObserver;
    }
    public String key() { return key; }
    public GhostLap best() { return best; }
    public String refreshPersonalBest() {
        var published=store.load(key);
        if(store.warning().isEmpty()) {
            best=published;
            if(overflowBest!=null && published!=null && published.duration()<=overflowBest.duration()) overflowBest=null;
        }
        return store.warning();
    }
    public GhostLap pendingBest() {
        var held=store.pendingBest(key);
        if(held==null) return overflowBest;
        return overflowBest!=null && overflowBest.duration()<held.lap().duration()?overflowBest:held.lap();
    }
    public TelemetryLap.Conditions conditions() { return conditions; }
    public String routeGeometry() { return routeGeometry; }
    public GhostLap completedLap() { return completedLap; }
    public String completedReplyTo() { return completedReplyTo; }
    public String referenceLabel() { return referenceLabel; }
    public String lastReferenceLabel() { return lastReferenceLabel; }
    public String selectedLabel() { return selectedLabel; }
    public String selectedSource() { return selectedSource; }
    public boolean rivalReference() { return referenceSource!=null; }
    public void selectReference(GhostLap lap,String label,String source) {
        if(lap!=null && (!key.equals(lap.key()) || label==null || label.isBlank() || label.length()>60 || source==null || !source.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("Invalid reference");
        selectedReference=lap;selectedLabel=lap==null?"个人最佳":label;selectedSource=lap==null?null:source;
    }
    public Result last() { return last; }
    public double elapsed() { return elapsed; }
    public boolean started() { return started; }
    public boolean valid() { return currentValid; }
    public int recordedSamples() { return recorder==null ? 0 : recorder.samples.size(); }
    public String consumeNotice() { String message=notice;notice="";String extra=telemetry==null?"":telemetry.consumeNotice();return extra.isEmpty()?message:message.isEmpty()?extra:message+"；"+extra; }
    public TelemetryRecorder telemetry() { return telemetry; }
    public boolean retryBest() {
        var candidate=pendingBest();
        if(candidate==null) return false;
        var status=saveBest(candidate);
        if(status==GhostStore.Saved.SAVED && candidate==completedLap) {
            last=new Result(last.duration(),last.valid(),last.sectors(),last.deltas(),last.delta(),true);
        }
        return status!=GhostStore.Saved.FAILED;
    }
    public List<Double> sectorTimes() { return sectorTimes; }
    public double sectorDelta(int index) { return reference==null || index<0 || index>=cumulative.size() ? Double.NaN : cumulative.get(index)-reference.cumulative(index); }
    public GhostLap.Sample ghost() {return ghostPlayback==null?null:ghostPlayback.at(elapsed);}
    public int ghostLivery() { return reference==null ? livery : reference.livery(); }

    public boolean startsLapWith(LapProgress progress) {
        return progress.started() && (!started || progress.completedLaps()>completed);
    }

    public void update(LapProgress progress,double time,GhostLap.Sample pose) {
        update(progress,time,pose,null);
    }
    public void update(LapProgress progress,double time,GhostLap.Sample pose,TelemetryLap.Driving driving) {
        update(progress,time,pose,driving,null);
    }
    public void update(LapProgress progress,double time,GhostLap.Sample pose,TelemetryLap.Driving driving,VehicleObservation observation) {
        boolean crossed=progress.completedLaps()>completed;
        boolean newLap=startsLapWith(progress);
        // Even an exact line observation cannot fill a gap in the lap just completed.
        if(lineObserver.hasGap(time)) recorder=null;
        var boundary=lineObserver.observe(newLap,progress.lapStartTime(),time,pose,driving,observation);
        if(!progress.started()) return;
        var crossings=progress.latestCrossings();
        if(crossed) {
            if(crossings!=readCrossings) captureSectors(crossings,completed);
            finish(progress.lastLap(),boundary.pose()!=null && progress.lastLapValid(),boundary.pose());
        }
        if(newLap) {
            var nextReference=selectedReference==null?best:selectedReference;
            if(nextReference!=reference) ghostPlayback=nextReference==null?null:new GhostLap.Playback(nextReference);
            started=true;reference=nextReference;referenceLabel=selectedLabel;referenceSource=selectedSource;elapsed=0;cumulative.clear();
            sectorTimes=List.of();
            lapStart=progress.lapStartTime();
            var startPose=boundary.pose();
            recorder=startPose==null?null:new Recorder(startPose.withTime(0));
        }
        if(pose==null) progress.invalidate();
        completed=progress.completedLaps();elapsed=progress.currentLap(time);
        currentValid=progress.validLap() && elapsed<=GhostLap.MAX_DURATION && recorder!=null;
        if(currentValid) recorder.observe(elapsed,pose);
        else recorder=null;
        if(crossings!=readCrossings) captureSectors(crossings,completed);
        readCrossings=crossings;
        if(telemetry!=null) telemetry.update(progress,time,pose,driving,crossed?last:null,observation,boundary);
    }

    private void captureSectors(List<LapProgress.Crossing> crossings,int lap) {
        int before=cumulative.size();
        for(var event:crossings) {
            if(event.completedLaps()!=lap) continue;
            if(event.gate()==6 && cumulative.isEmpty()) cumulative.add(event.time()-lapStart);
            if(event.gate()==12 && cumulative.size()==1) cumulative.add(event.time()-lapStart);
        }
        if (cumulative.size()!=before) {
            double first=cumulative.get(0);
            sectorTimes=cumulative.size()==1?List.of(first):List.of(first,cumulative.get(1)-first);
        }
    }

    private void finish(double duration,boolean valid,GhostLap.Sample pose) {
        lastReferenceLabel=referenceLabel;
        boolean completeSectors=cumulative.size()==2 && cumulative.get(0)>0 && cumulative.get(1)>cumulative.get(0)
                && duration>cumulative.get(1);
        boolean usable=valid && recorder!=null && completeSectors && duration<=GhostLap.MAX_DURATION;
        double delta=reference==null ? Double.NaN : duration-reference.duration();
        List<Double> sectors=List.of(),deltas=List.of();
        if(completeSectors) {
            sectors=List.of(cumulative.get(0),cumulative.get(1)-cumulative.get(0),duration-cumulative.get(1));
            // A stored ghost may have rounded sectors. The finish compares the recorded lap durations.
            deltas=List.of(sectorDelta(0),sectorDelta(1),delta);
        }
        boolean saved=false;
        completedLap=null;completedReplyTo=null;
        if(usable) {
            try {
                recorder.observe(duration,pose);recorder.end(duration,pose);
                GhostLap candidate=new GhostLap(GhostLap.VERSION,key,livery,duration,sectors,recorder.samples);
                completedLap=candidate;completedReplyTo=referenceSource;
                var pendingBest=pendingBest();
                if((best==null || duration<best.duration()) && (pendingBest==null || duration<pendingBest.duration()))
                    saved=saveBest(candidate)==GhostStore.Saved.SAVED;
            } catch(IllegalArgumentException error) { notice="本圈轨迹数据无效，之前的最佳圈保留。";usable=false; }
        }
        if(!usable) notice="本圈无效或轨迹不完整，不更新最佳圈。";
        last=new Result(duration,usable,sectors,deltas,delta,saved);
    }
    private GhostStore.Saved saveBest(GhostLap candidate) {
        var write=store.writeBest(candidate);
        if(write.published()!=null) {
            best=write.published();
            overflowBest=null;
            notice=write.status()==GhostStore.Saved.SAVED
                    ?"新的计时挑战最佳圈 · "+PracticeSession.format(candidate.duration())+" · 幽灵已保存"
                    :"已保留磁盘上用时更短或相同的个人最佳圈。";
            return write.status();
        }
        boolean retained=store.retainPending(new GhostStore.PendingBest(trackName,conditions,candidate));
        overflowBest=retained?null:candidate;
        notice=store.warning()+(retained?" 可在幽灵库重试本圈。"
                :" 待保存最佳圈已满，请在离开本场之前重试本圈。");
        return GhostStore.Saved.FAILED;
    }
    private static final class Recorder {
        final List<GhostLap.Sample> samples=new ArrayList<>();
        GhostLap.Sample previous;
        double previousTime,next=.05;
        Recorder(GhostLap.Sample first) { samples.add(first);previous=first; }
        void observe(double time,GhostLap.Sample pose) {
            if(time<=previousTime) return;
            while(next<=time+1e-9 && samples.size()<GhostLap.MAX_SAMPLES-1) {
                samples.add(previous.interpolateObserved(pose,previousTime,time,Math.min(next,time)));
                next+=.05;
            }
            previous=pose;
            previousTime=time;
        }
        void end(double time,GhostLap.Sample pose) {
            var end=pose.withTime(time);
            if(Math.abs(samples.get(samples.size()-1).time()-time)<.00001) samples.set(samples.size()-1,end);else samples.add(end);
        }
    }
}
