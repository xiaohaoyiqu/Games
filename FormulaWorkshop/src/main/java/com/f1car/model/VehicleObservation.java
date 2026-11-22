package com.f1car.model;

/** Passive native readings. Compression is rest minus current length, never tyre force or contact. */
public record VehicleObservation(float frontCompression,float rearCompression,float frontSteer,float yawRate) {
    public VehicleObservation {
        if(!bounded(frontCompression,.5f) || !bounded(rearCompression,.5f) || !bounded(frontSteer,1.6f) || !bounded(yawRate,100))
            throw new IllegalArgumentException("Invalid vehicle observation");
    }
    private static boolean bounded(float value,float limit) { return Float.isFinite(value) && Math.abs(value)<=limit; }
    public VehicleObservation interpolate(VehicleObservation after,float t) {
        if(after==null || !Float.isFinite(t) || t<0 || t>1) throw new IllegalArgumentException("Invalid observation interpolation");
        return new VehicleObservation(mix(frontCompression,after.frontCompression,t),mix(rearCompression,after.rearCompression,t),mix(frontSteer,after.frontSteer,t),mix(yawRate,after.yawRate,t));
    }
    private static float mix(float a,float b,float t) { return a+(b-a)*t; }
    public record Summary(double seconds,float frontMean,float rearMean,float steerRms,float yawRms,float yawPeak) {
        public Summary {
            if(!Double.isFinite(seconds) || seconds<=0 || seconds>121 || !bounded(frontMean,.5f) || !bounded(rearMean,.5f)
                || !bounded(steerRms,1.6f) || steerRms<0 || !bounded(yawRms,100) || yawRms<0 || !bounded(yawPeak,100) || yawPeak<yawRms)
                throw new IllegalArgumentException("Invalid observation summary");
        }
    }
    /** Time weighted over the trial's measurement window; missing frames contribute no invented zero. */
    public static final class Accumulator {
        private double seconds,front,rear,steer,yaw;
        private float peak;
        public void add(float dt,VehicleObservation sample) {
            if(!Float.isFinite(dt) || dt<0 || dt>.25f) throw new IllegalArgumentException("Invalid observation interval");
            if(sample==null || dt==0) return;
            seconds+=dt;front+=sample.frontCompression()*dt;rear+=sample.rearCompression()*dt;
            steer+=(double)sample.frontSteer()*sample.frontSteer()*dt;yaw+=(double)sample.yawRate()*sample.yawRate()*dt;
            peak=Math.max(peak,Math.abs(sample.yawRate()));
        }
        public Summary summary() {
            return seconds==0?null:new Summary(seconds,(float)(front/seconds),(float)(rear/seconds),(float)Math.sqrt(steer/seconds),Math.min(peak,(float)Math.sqrt(yaw/seconds)),peak);
        }
    }
}
