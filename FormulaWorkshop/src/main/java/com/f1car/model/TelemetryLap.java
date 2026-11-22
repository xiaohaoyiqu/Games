package com.f1car.model;

import java.util.*;

/** A bounded 20 Hz observation, never a physics state or a source of lap awards. */
public record TelemetryLap(int version,String id,long recordedAt,Conditions conditions,int livery,double duration,List<Double> sectors,List<Sample> samples) {
    public static final int VERSION=1;
    public static final int MAX_SAMPLES=12002;
    public record Conditions(String trackId,String geometry,String physics,String performance,String controls,float length) {
        public Conditions {
            if(trackId==null || !trackId.matches("[a-z0-9-]{1,64}") || geometry==null || !geometry.matches("[a-f0-9]{64}") || physics==null || physics.isBlank() || physics.length()>120
                || !Tuning.validPerformanceKey(performance) || !ControlIdentity.valid(controls) || !Float.isFinite(length) || length<10 || length>10000) throw new IllegalArgumentException("Invalid telemetry conditions");
        }
        public static Conditions of(TrackLayout track,String physics,Tuning tuning) { return new Conditions(track.id,track.geometryKey(),physics,tuning.performanceKey(),"keyboard-v1",track.length); }
        public static Conditions of(TrackLayout track,String physics,Tuning tuning,String controls) { return new Conditions(track.id,track.geometryKey(),physics,tuning.performanceKey(),controls,track.length); }
        public boolean matchesTrack(TrackLayout track) {
            return track!=null && length==track.length && trackId.equals(track.id) && geometry.equals(track.geometryKey());
        }
        public String key() { return "telemetry-v1:"+trackId+":"+geometry+":"+physics+":"+performance+":"+controls+":time-trial-gates18:"+Float.toHexString(length); }
    }
    public record Driving(float speed,float throttle,float brake,float steer) {
        public Driving {
            if(!valid(speed,throttle,brake,steer)) throw new IllegalArgumentException("Invalid driving observation");
        }
        public static Driving capture(float speed,float throttle,float brake,float steer) {
            return valid(speed,throttle,brake,steer)?new Driving(speed,throttle,brake,steer):null;
        }
        private static boolean valid(float speed,float throttle,float brake,float steer) {
            return Float.isFinite(speed) && speed>=0 && speed<=200 && Float.isFinite(throttle) && Math.abs(throttle)<=1
                && Float.isFinite(brake) && brake>=0 && brake<=1 && Float.isFinite(steer) && Math.abs(steer)<=1;
        }
        Driving interpolate(Driving after,float fraction) {
            return new Driving(mix(speed,after.speed,fraction),mix(throttle,after.throttle,fraction),
                    mix(brake,after.brake,fraction),mix(steer,after.steer,fraction));
        }
    }
    public record Sample(double time,float distance,float x,float z,float yaw,Driving driving,VehicleObservation observation) {
        public Sample(double time,float distance,float x,float z,float yaw,Driving driving) { this(time,distance,x,z,yaw,driving,null); }
        public Sample {
            if(!Double.isFinite(time) || time<0 || time>600 || !Float.isFinite(distance) || distance<0 || distance>10000 || !Float.isFinite(x) || Math.abs(x)>10000 || !Float.isFinite(z) || Math.abs(z)>10000 || !Float.isFinite(yaw) || driving==null) throw new IllegalArgumentException("Invalid telemetry sample");
        }
        public Sample interpolate(Sample after,double at) {
            if(after.time()<=time || at<time-1e-8 || at>after.time()+1e-8) throw new IllegalArgumentException("Invalid telemetry interpolation");
            if(at<=time || at>=after.time()) {
                var endpoint=at<=time?this:after;
                return Double.compare(at,endpoint.time)==0?endpoint:
                        new Sample(at,endpoint.distance,endpoint.x,endpoint.z,endpoint.yaw,endpoint.driving,endpoint.observation);
            }
            float t=(float)Math.max(0,Math.min(1,(at-time)/(after.time()-time)));
            double turn=YawAngles.shortestChange(yaw,after.yaw());
            float heading=(float)YawAngles.principal(YawAngles.principal(yaw)+turn*t);
            return new Sample(at,mix(distance,after.distance(),t),mix(x,after.x(),t),mix(z,after.z(),t),heading,
                driving.interpolate(after.driving(),t),
                observation==null || after.observation()==null?null:observation.interpolate(after.observation(),t));
        }
    }
    private static float mix(float a,float b,float t) { return a+(b-a)*t; }
    public TelemetryLap {
        if(version!=VERSION || id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || recordedAt<0 || conditions==null || livery<0 || livery>4
            || !Double.isFinite(duration) || duration<=0 || duration>600 || sectors==null || sectors.size()!=3 || sectors.stream().anyMatch(value->value==null || !Double.isFinite(value) || value<=0)
            || Math.abs(sectors.stream().mapToDouble(Double::doubleValue).sum()-duration)>.002 || samples==null || samples.size()<2 || samples.size()>MAX_SAMPLES) throw new IllegalArgumentException("Invalid telemetry lap");
        sectors=List.copyOf(sectors);samples=List.copyOf(samples);
        if(samples.get(0).time()!=0 || samples.get(0).distance()!=0 || Math.abs(samples.get(samples.size()-1).time()-duration)>.00001 || Math.abs(samples.get(samples.size()-1).distance()-conditions.length())>.001) throw new IllegalArgumentException("Incomplete telemetry endpoints");
        double previous=-1;
        for(var sample:samples) {
            if(sample.time()<=previous || sample.time()>duration+.00001 || previous>=0 && sample.time()-previous>.051 || sample.distance()>conditions.length()+.001) throw new IllegalArgumentException("Incomplete telemetry sampling");
            previous=sample.time();
        }
    }
}
