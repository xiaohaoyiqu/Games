package com.f1car.model;

import com.f1car.persistence.TelemetryStore;
import com.jme3.math.Vector3f;
import java.util.*;

/** Passive observer of real ordered-gate progress and vehicle commands in time trial. */
public final class TelemetryRecorder {
    static final double MAX_OBSERVATION_GAP=.25;
    private final TrackLayout track;
    private final Vector3f projectionPosition=new Vector3f();
    private final TelemetryLap.Conditions conditions;
    private final TelemetryStore store;
    private final int livery;
    private final EquipmentLap.Source source;
    private EquipmentLap lastEquipmentLap;
    private List<TelemetryLap.Sample> samples;
    private TelemetryLap.Sample previous;
    final LapLineObserver lineObserver=new LapLineObserver();
    private int completed;
    private int projectionSegment=-1;
    private boolean started;
    private double next=.05;
    private String notice="";
    public TelemetryRecorder(TrackLayout track,String physics,Tuning tuning,TelemetryStore store) { this(track,physics,tuning,store,ControlIdentity.KEYBOARD); }
    public TelemetryRecorder(TrackLayout track,String physics,Tuning tuning,TelemetryStore store,String controls) {this(track,physics,tuning,store,controls,null);}
    public TelemetryRecorder(TrackLayout track,String physics,Tuning tuning,TelemetryStore store,String controls,EquipmentLap.Source source) { this.track=track;conditions=TelemetryLap.Conditions.of(track,physics,tuning,controls);livery=tuning.livery();this.store=Objects.requireNonNull(store);if(source!=null && (!source.tuning().equals(tuning) || !source.matches(conditions,livery))) throw new IllegalArgumentException("Session source differs");this.source=source; }
    public TelemetryLap.Conditions conditions() { return conditions; }
    public int sampleCount() { return samples==null?0:samples.size(); }
    public EquipmentLap lastEquipmentLap() {return lastEquipmentLap;}
    public String consumeNotice() { String value=notice;notice="";return value; }
    public void update(LapProgress progress,double time,GhostLap.Sample pose,TelemetryLap.Driving driving,TimeTrialSession.Result result) {
        update(progress,time,pose,driving,result,null);
    }
    public void update(LapProgress progress,double time,GhostLap.Sample pose,TelemetryLap.Driving driving,TimeTrialSession.Result result,VehicleObservation observation) {
        boolean crossing=progress.started() && (!started || progress.completedLaps()>completed);
        var boundary=lineObserver.observe(crossing,progress.lapStartTime(),time,pose,driving,observation);
        update(progress,time,pose,driving,result,observation,boundary);
    }
    void update(LapProgress progress,double time,GhostLap.Sample pose,TelemetryLap.Driving driving,
            TimeTrialSession.Result result,VehicleObservation observation,LapLineObserver.Boundary boundary) {
        if(!progress.started()) return;
        boolean observed=pose!=null && driving!=null;
        boolean crossed=progress.completedLaps()>completed;
        if(!started || crossed) {
            var linePose=boundary.pose();
            var lineDriving=boundary.driving();
            var lineObservation=boundary.observation();
            boolean lineObserved=linePose!=null && lineDriving!=null;
            if(crossed && started && lineObserved) finish(result,linePose,lineDriving,lineObservation);
            started=true;samples=lineObserved?new ArrayList<>():null;next=.05;
            previous=lineObserved?sample(0,0,linePose,lineDriving,lineObservation):null;
            if(samples!=null) samples.add(previous);
        }
        completed=progress.completedLaps();double elapsed=progress.currentLap(time);
        if(samples==null) return;
        if(!observed || !progress.validLap() || !Double.isFinite(elapsed) || elapsed>600 || elapsed<previous.time()-1e-8) { samples=null;return; }
        projectionPosition.set(pose.x(),0,pose.z());
        var projection=progress.trackProjection(track,projectionPosition);
        if(projection==null) projection=track.project(projectionPosition,projectionSegment);
        projectionSegment=projection.segment();
        float distance=projection.distance();
        // Nearest-segment projection can select the other end of a closed centreline before
        // the directional start plane is crossed (or just after it). Gates alone end a lap.
        // Keep only that adjacent-gate seam local; ordinary jumps and time gaps still fail.
        float seam=Math.min(55,track.length/18);
        if(track.closed && progress.nextGate()==0 && previous.distance()>track.length-seam && distance<seam) distance=track.length;
        else if(track.closed && progress.nextGate()==1 && previous.distance()<seam && distance>track.length-seam) distance=0;
        if(Math.abs(distance-previous.distance())>55 || elapsed-previous.time()>MAX_OBSERVATION_GAP) { samples=null;notice="本圈遥测不连续，不加入对比；原有记录保留。";return; }
        observe(sample(elapsed,distance,pose,driving,observation));
    }
    private void finish(TimeTrialSession.Result result,GhostLap.Sample pose,TelemetryLap.Driving driving,VehicleObservation observation) {
        if(samples==null || result==null || !result.valid() || driving==null || track.length-previous.distance()>55 || result.duration()-previous.time()>MAX_OBSERVATION_GAP) return;
        try {
            var end=sample(result.duration(),track.length,pose,driving,observation);observe(end);
            if(Math.abs(samples.get(samples.size()-1).time()-end.time())<.00001) samples.set(samples.size()-1,end);else samples.add(end);
            var lap=new TelemetryLap(TelemetryLap.VERSION,UUID.randomUUID().toString(),System.currentTimeMillis(),conditions,livery,result.duration(),result.sectors(),samples);
            if(source!=null) lastEquipmentLap=new EquipmentLap(1,lap,source);
            if(!store.record(lap)) notice=store.warning();
        } catch(IllegalArgumentException error) { notice="本圈遥测采样不完整，原有记录保留。"; }
    }
    private TelemetryLap.Sample sample(double time,float distance,GhostLap.Sample pose,TelemetryLap.Driving driving,VehicleObservation observation) { return new TelemetryLap.Sample(time,distance,pose.x(),pose.z(),pose.yaw(),driving,observation); }
    private void observe(TelemetryLap.Sample current) {
        if(current.time()<=previous.time()) return;
        while(next<=current.time()+1e-9 && samples.size()<TelemetryLap.MAX_SAMPLES-1) { samples.add(previous.interpolate(current,Math.min(next,current.time())));next+=.05; }
        previous=current;
    }
}
