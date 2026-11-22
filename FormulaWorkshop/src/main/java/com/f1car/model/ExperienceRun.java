package com.f1car.model;

import java.util.*;

/** One actual valid attempt, never stitched from several sectors or regenerated as physics input. */
public record ExperienceRun(int version,String id,long recordedAt,Conditions conditions,int livery,double duration,
                            List<Double> gateTimes,List<TelemetryLap.Sample> samples) {
    public static final double LIMIT=180;
    public static final int MAX_SAMPLES=3602;
    public record Conditions(String trackId,String geometry,String physics,String controls,String performance,int part,
                             float start,float length,float trackLength,float rollingSpeed,String protocol,CircuitExperience.Targets targets) {
        public Conditions {
            if(trackId==null || !trackId.matches("[a-z0-9][a-z0-9-]{0,63}") || geometry==null || !geometry.matches("[a-f0-9]{64}") || physics==null || physics.isBlank() || physics.length()>120 || physics.chars().anyMatch(Character::isISOControl)
                || !ControlIdentity.valid(controls) || !Tuning.STOCK.performanceKey().equals(performance) || part<0 || part>3 || !Float.isFinite(start) || !Float.isFinite(length) || !Float.isFinite(trackLength) || trackLength<10 || trackLength>10000
                || Math.abs(start-(part==3?0:trackLength*part/3))>.001f || Math.abs(length-(part==3?trackLength:trackLength/3))>.001f || rollingSpeed!=CircuitExperience.ROLLING_SPEED || !CircuitExperience.PROTOCOL.equals(protocol) || targets==null) throw new IllegalArgumentException("Invalid experience conditions");
        }
        public static Conditions of(CircuitExperience.Course course,String physics,String controls) { return new Conditions(course.track().id,course.track().geometryKey(),physics,controls,Tuning.STOCK.performanceKey(),course.part(),course.start(),course.length(),course.track().length,CircuitExperience.ROLLING_SPEED,CircuitExperience.PROTOCOL,course.targets()); }
        public int gates() { return part==3?18:6; }
        public String key() { return "experience-v1:"+trackId+":"+geometry+":"+physics+":"+controls+":"+performance+":"+part+":"+Float.toHexString(start)+":"+Float.toHexString(length)+":"+Float.toHexString(rollingSpeed)+":"+protocol+":"+targets; }
    }
    public record Loss(int gate,double delta) { }
    public ExperienceRun {
        if(version!=1 || id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || recordedAt<0 || conditions==null || livery<0 || livery>4 || !Double.isFinite(duration) || duration<=0 || duration>LIMIT
            || gateTimes==null || gateTimes.size()!=conditions.gates() || samples==null || samples.size()<2 || samples.size()>MAX_SAMPLES) throw new IllegalArgumentException("Invalid experience run");
        gateTimes=List.copyOf(gateTimes);samples=List.copyOf(samples);double previous=0;
        for(Double time:gateTimes) { if(time==null || !Double.isFinite(time) || time<=previous || time>duration+1e-6) throw new IllegalArgumentException("Unordered experience gates");previous=time; }
        if(Math.abs(previous-duration)>1e-6 || samples.get(0).time()!=0 || samples.get(0).distance()!=0 || Math.abs(samples.get(samples.size()-1).time()-duration)>1e-6 || Math.abs(samples.get(samples.size()-1).distance()-conditions.length())>.001) throw new IllegalArgumentException("Incomplete experience run");
        previous=-1;
        var initial=samples.get(0).driving();if(Math.abs(initial.speed()-conditions.rollingSpeed())>.1 || initial.throttle()!=0 || initial.brake()!=0 || initial.steer()!=0) throw new IllegalArgumentException("Incorrect recorded launch state");
        for(var sample:samples) {
            if(sample.time()<=previous || previous>=0 && sample.time()-previous>.0501 || sample.time()>duration+1e-6 || sample.distance()<0 || sample.distance()>conditions.length()+.001 || Math.abs(sample.x())>1000 || Math.abs(sample.z())>1000 || Math.abs(sample.yaw())>Math.PI+.001) throw new IllegalArgumentException("Invalid experience recording");
            previous=sample.time();
        }
    }
    public String key() { return conditions.key(); }
    public CircuitExperience.Medal medal() { return conditions.targets().medal(duration); }
    public TelemetryLap.Sample at(double time) {
        if(!Double.isFinite(time) || time<0 || time>duration) return null;
        if(time==0) return samples.get(0);
        var last=samples.get(samples.size()-1);
        // Saved runs permit a small rounding difference between the finish and the last sample.
        if(time==duration || time>=last.time()) return last;
        int low=0,high=samples.size()-1;while(low+1<high) { int middle=(low+high)/2;if(samples.get(middle).time()<=time) low=middle;else high=middle; }
        var before=samples.get(low);
        return time==before.time()?before:before.interpolate(samples.get(high),time);
    }
    public Optional<Loss> largestLoss(ExperienceRun reference) {
        if(reference==null) return Optional.empty();if(!conditions.equals(reference.conditions)) throw new IllegalArgumentException("Experience conditions differ");
        double previous=0,referencePrevious=0,maximum=.001;Loss result=null;
        for(int i=0;i<gateTimes.size();i++) { double time=gateTimes.get(i),other=reference.gateTimes.get(i),delta=(time-previous)-(other-referencePrevious);if(delta>maximum) { maximum=delta;result=new Loss(i+1,delta); }previous=time;referencePrevious=other; }
        return Optional.ofNullable(result);
    }
}
