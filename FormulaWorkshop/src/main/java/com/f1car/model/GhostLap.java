package com.f1car.model;

import java.util.List;

/** A visual trajectory, never a physics snapshot or a source of lap awards. */
public record GhostLap(int version, String key, int livery, double duration, List<Double> sectors, List<Sample> samples) {
    public static final int VERSION = 1, MAX_SAMPLES = 12_002;
    public static final double MAX_DURATION = 600;
    public record Sample(double time, float x, float z, float yaw) {
        public Sample {
            if (!Double.isFinite(time) || time < 0 || !validPosition(x,z) || !Float.isFinite(yaw))
                throw new IllegalArgumentException("Invalid ghost pose");
        }
        private static boolean validPosition(float x,float z) {
            return Float.isFinite(x) && Float.isFinite(z) && Math.abs(x)<=10000 && Math.abs(z)<=10000;
        }
        public static Sample capture(float x,float z,float forwardX,float forwardZ) {
            float planarHeading=forwardX*forwardX+forwardZ*forwardZ;
            if(!validPosition(x,z) || !Float.isFinite(planarHeading) || planarHeading<=1e-8f) return null;
            return new Sample(0,x,z,(float)Math.atan2(forwardX,forwardZ));
        }
        public Sample interpolate(Sample after, double at) {
            return interpolateObserved(after,time,after.time,at);
        }
        // Live poses carry coordinates; the recorder supplies their times on the lap clock.
        Sample interpolateObserved(Sample after,double from,double to,double at) {
            return interpolate(after,from,to,at,yawChangeTo(after));
        }
        Sample withTime(double at) {
            return Double.compare(at,time)==0?this:new Sample(at,x,z,yaw);
        }
        private double yawChangeTo(Sample after) {
            return YawAngles.shortestChange(yaw,after.yaw);
        }
        private Sample interpolate(Sample after,double from,double to,double at,double turn) {
            if(to>from && (at<=from || at>=to)) {
                return (at<=from?this:after).withTime(at);
            }
            double fraction = Math.max(0,Math.min(1,(at-from)/(to-from)));
            return new Sample(at,(float)(x+(after.x-x)*fraction),(float)(z+(after.z-z)*fraction),
                    (float)(YawAngles.principal(yaw)+turn*fraction));
        }
    }
    public GhostLap {
        if (version != VERSION || key == null || key.isBlank() || key.length() > 1024 || livery < 0 || livery > 4
            || !Double.isFinite(duration) || duration <= 0 || duration > MAX_DURATION || sectors == null || sectors.size()!=3
            || sectors.stream().anyMatch(t -> t == null || !Double.isFinite(t) || t <= 0)
            || Math.abs(sectors.stream().mapToDouble(Double::doubleValue).sum()-duration) > .002
            || samples == null || samples.size()<2 || samples.size()>MAX_SAMPLES) throw new IllegalArgumentException("Invalid ghost lap");
        sectors = List.copyOf(sectors); samples = List.copyOf(samples);
        if (Math.abs(samples.get(0).time)>.00001 || Math.abs(samples.get(samples.size()-1).time-duration)>.00001) throw new IllegalArgumentException("Missing ghost endpoints");
        double last=-1;
        for (Sample sample:samples) { if (sample.time<=last || sample.time>duration+.00001) throw new IllegalArgumentException("Ghost times must increase"); last=sample.time; }
    }
    public Sample at(double time) {
        if(!contains(time)) return null;
        int index=intervalAt(time);
        return samples.get(index).interpolate(samples.get(index+1),time);
    }

    private boolean contains(double time) {return Double.isFinite(time) && time>=0 && time<=duration;}

    private int intervalAt(double time) {
        int low=0,high=samples.size()-1;
        while (low+1<high) { int middle=(low+high)/2; if (samples.get(middle).time<=time) low=middle; else high=middle; }
        return low;
    }

    /** Cursor owned by a viewer of one immutable lap. */
    static final class Playback {
        private final GhostLap lap;
        private int interval=-1;
        private Sample before,after,pose;
        private double turn;

        Playback(GhostLap lap) {this.lap=lap;}

        Sample at(double time) {
            if(!lap.contains(time)) return null;
            if(pose!=null && Double.compare(time,pose.time)==0) return pose;
            if(!includes(interval,time)) {
                int next=interval+1;
                if(!includes(next,time)) next=lap.intervalAt(time);
                before=lap.samples.get(next);
                after=lap.samples.get(next+1);
                turn=before.yawChangeTo(after);
                interval=next;
            }
            pose=before.interpolate(after,before.time,after.time,time,turn);
            return pose;
        }

        private boolean includes(int index,double time) {
            int last=lap.samples.size()-2;
            // The validated endpoints may differ slightly from zero and duration.
            return index>=0 && index<=last
                    && (index==0 || time>=lap.samples.get(index).time)
                    && (index==last || time<lap.samples.get(index+1).time);
        }
    }
    public double cumulative(int sector) { double result=0; for(int i=0;i<=sector;i++) result+=sectors.get(i); return result; }
    public static String key(TrackLayout track,String physicsVersion,Tuning tuning) {
        return key(track,physicsVersion,tuning,ControlIdentity.KEYBOARD);
    }
    public static String key(TrackLayout track,String physicsVersion,Tuning tuning,String controls) {
        return "time-trial:v1:"+track.id+":"+track.geometryKey()+":"+physicsVersion+":"+tuning.performanceKey()+":"+ControlIdentity.require(controls)+":sectors3-gates18";
    }
}
