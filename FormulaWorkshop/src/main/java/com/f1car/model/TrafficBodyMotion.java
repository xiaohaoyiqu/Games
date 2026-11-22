package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.Objects;

/** Constant-velocity chassis windows, with bounded constant-yaw-rate envelopes; not Bullet contacts. */
public final class TrafficBodyMotion {
    // Same chassis geometry used by FormulaCar; padding belongs to AI observation only.
    public static final float HALF_WIDTH=.64f,HALF_HEIGHT=.18f,HALF_LENGTH=1.65f,CENTER_HEIGHT=.22f;
    public static final float PLANAR_PADDING=.15f,HEIGHT_PADDING=.10f;
    public static final int MAX_ROTATION_SLICES=12;
    public static final float ROTATION_SLICE_SECONDS=.1f;
    private static final double UPRIGHT_PLANAR_RADIUS=Math.hypot((double)HALF_LENGTH+PLANAR_PADDING,(double)HALF_WIDTH+PLANAR_PADDING);
    private static final double UNKNOWN_POSE_RADIUS=Math.hypot(UPRIGHT_PLANAR_RADIUS,(double)HALF_HEIGHT+HEIGHT_PADDING)+CENTER_HEIGHT;
    private TrafficBodyMotion() {}

    /** Immutable scalar snapshot at the chassis center, with a normalized horizontal forward axis. */
    public record Body(float x,float y,float z,float vx,float vy,float vz,float forwardX,float forwardZ,float yawRate) {
        public Body(float x,float y,float z,float vx,float vy,float vz,float forwardX,float forwardZ) {this(x,y,z,vx,vy,vz,forwardX,forwardZ,0);}
        public Body {
            if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(vz) || !Float.isFinite(forwardX) || !Float.isFinite(forwardZ) || !Float.isFinite(yawRate)) throw new IllegalArgumentException("Nonfinite body observation");
            double length=Math.hypot(forwardX,forwardZ);
            if(length<.0001) throw new IllegalArgumentException("Missing horizontal body direction");
            forwardX=(float)(forwardX/length);forwardZ=(float)(forwardZ/length);
        }
    }
    /** Rotating results enclose candidate slices; entry/exit can span gaps and are not actual impact times. */
    public record Window(float entry,float exit,boolean rotationEnvelope) {
        public Window(float entry,float exit) {this(entry,exit,false);}
        public static final Window NONE=new Window(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY);
        private static final Window ROTATION_NONE=new Window(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,true);
        public boolean intersects() {return Float.isFinite(entry);}
    }

    public static Window window(Body self,Body other) {return window(self,other,TrafficMotionWindow.HORIZON);}
    public static Window window(Body self,Body other,float horizon) {
        return window(self,other,horizon,true);
    }
    /** Geometric overlap of the unpadded upright model only; not a Bullet contact query. */
    public static boolean unpaddedOverlap(Body self,Body other) {return window(self,other,0,false).intersects();}
    public static Window unpaddedWindow(Body self,Body other) {return window(self,other,TrafficMotionWindow.HORIZON,false);}

    /** Screen an unavailable forward pose using the reported origin and a bound that encloses any chassis orientation. */
    static boolean mayOverlapUnoriented(Body self,Vector3f origin,Vector3f velocity) {
        if(!Float.isFinite(origin.x) || !Float.isFinite(origin.y) || !Float.isFinite(origin.z)
                || !Float.isFinite(velocity.x) || !Float.isFinite(velocity.y) || !Float.isFinite(velocity.z)) return false;
        double x=(double)origin.x-self.x(),z=(double)origin.z-self.z();
        if(x*self.forwardX()+z*self.forwardZ()<=0) return false;
        var interval=new Interval(TrafficMotionWindow.HORIZON);
        double planar=UPRIGHT_PLANAR_RADIUS+UNKNOWN_POSE_RADIUS;
        return interval.clip(x,(double)velocity.x-self.vx(),planar)
                && interval.clip(z,(double)velocity.z-self.vz(),planar)
                && interval.clip((double)origin.y-self.y(),(double)velocity.y-self.vy(),
                    UNKNOWN_POSE_RADIUS+(double)HALF_HEIGHT+HEIGHT_PADDING);
    }

    private static Window window(Body self,Body other,float horizon,boolean padded) {
        Objects.requireNonNull(self);Objects.requireNonNull(other);
        if(!Float.isFinite(horizon) || horizon<0) throw new IllegalArgumentException("Invalid body horizon");
        boolean rotating=horizon>0 && (self.yawRate()!=0 || other.yawRate()!=0);
        var interval=new Interval(horizon);
        if(!interval.clip((double)other.y()-self.y(),(double)other.vy()-self.vy(),2*((double)HALF_HEIGHT+(padded?HEIGHT_PADDING:0))))
            return rotating?Window.ROTATION_NONE:Window.NONE;
        var motion=new PlanarMotion((double)other.x()-self.x(),(double)other.z()-self.z(),
                (double)other.vx()-self.vx(),(double)other.vz()-self.vz());
        double padding=padded?PLANAR_PADDING:0;
        if(rotating) return rotatingWindow(interval,motion,self,other,horizon,padding);
        if(!clipPlanar(interval,motion,self.forwardX(),self.forwardZ(),other.forwardX(),other.forwardZ(),padding,0)) return Window.NONE;
        return new Window((float)interval.start,(float)interval.end);
    }
    private record PlanarMotion(double x,double z,double vx,double vz) {}
    private static Window rotatingWindow(Interval interval,PlanarMotion motion,Body self,Body other,float horizon,double padding) {
        double corner=Math.hypot((double)HALF_LENGTH+padding,(double)HALF_WIDTH+padding);
        double selfRadius=corner*Math.hypot(self.forwardX(),self.forwardZ()),otherRadius=corner*Math.hypot(other.forwardX(),other.forwardZ());
        // A circle around each complete yaw sweep is a cheap rejection before any trigonometry.
        if(!interval.clip(motion.x(),motion.vx(),selfRadius+otherRadius)
            || !interval.clip(motion.z(),motion.vz(),selfRadius+otherRadius)) return Window.ROTATION_NONE;
        double broadStart=interval.start,broadEnd=interval.end;
        int slices=Math.min(MAX_ROTATION_SLICES,Math.max(1,(int)Math.ceil((double)horizon/ROTATION_SLICE_SECONDS)));
        double first=Double.POSITIVE_INFINITY,last=Double.NEGATIVE_INFINITY;
        for(int i=0;i<slices;i++) {
            double start=Math.max(broadStart,(double)horizon*i/slices),end=Math.min(broadEnd,(double)horizon*(i+1)/slices);
            if(start>end) continue;
            double mid=(start+end)*.5,halfDuration=(end-start)*.5;
            double ax=self.forwardX(),az=self.forwardZ(),bx=other.forwardX(),bz=other.forwardZ();
            if(self.yawRate()!=0) {
                double angle=self.yawRate()*mid,cos=Math.cos(angle),sin=Math.sin(angle);
                ax=self.forwardX()*cos+self.forwardZ()*sin;
                az=self.forwardZ()*cos-self.forwardX()*sin;
            }
            if(other.yawRate()!=0) {
                double angle=other.yawRate()*mid,cos=Math.cos(angle),sin=Math.sin(angle);
                bx=other.forwardX()*cos+other.forwardZ()*sin;
                bz=other.forwardZ()*cos-other.forwardX()*sin;
            }
            // Every corner over this entire slice lies within this displacement of its midpoint pose.
            double expansion=rotationDisplacement(selfRadius,self.yawRate(),halfDuration)+rotationDisplacement(otherRadius,other.yawRate(),halfDuration);
            interval.start=start;interval.end=end;
            if(clipPlanar(interval,motion,ax,az,bx,bz,padding,expansion)) {first=Math.min(first,interval.start);last=Math.max(last,interval.end);}
        }
        return Double.isFinite(first)?new Window((float)first,(float)last,true):Window.ROTATION_NONE;
    }
    private static double rotationDisplacement(double radius,float rate,double halfDuration) {
        if(rate==0 || halfDuration==0) return 0;
        return 2*radius*Math.sin(Math.min(Math.PI*.5,Math.abs((double)rate)*halfDuration*.5));
    }
    private static boolean clipPlanar(Interval interval,PlanarMotion motion,double ax,double az,double bx,double bz,double padding,double expansion) {
        if(!axis(interval,motion,1,0,ax,az,bx,bz,padding,expansion)
                || !axis(interval,motion,0,1,ax,az,bx,bz,padding,expansion)) return false;
        if(!worldAligned(ax,az) && (!axis(interval,motion,ax,az,ax,az,bx,bz,padding,expansion)
                || !axis(interval,motion,az,-ax,ax,az,bx,bz,padding,expansion))) return false;
        // Match axis components exactly so near-parallel chassis still test both rectangles' normals.
        if(worldAligned(bx,bz) || ax==bx && az==bz || ax==-bx && az==-bz) return true;
        return axis(interval,motion,bx,bz,ax,az,bx,bz,padding,expansion)
            && axis(interval,motion,bz,-bx,ax,az,bx,bz,padding,expansion);
    }

    private static boolean worldAligned(double x,double z) {
        return x==0 && Math.abs(z)==1 || z==0 && Math.abs(x)==1;
    }
    private static boolean axis(Interval interval,PlanarMotion motion,double x,double z,double ax,double az,double bx,double bz,double padding,double expansion) {
        double gap=motion.x()*x+motion.z()*z;
        double velocity=motion.vx()*x+motion.vz()*z;
        double extent=radius(ax,az,x,z,padding)+radius(bx,bz,x,z,padding);
        if(expansion!=0) extent+=expansion*Math.hypot(x,z);
        return interval.clip(gap,velocity,extent);
    }
    private static double radius(double forwardX,double forwardZ,double x,double z,double padding) {
        return ((double)HALF_LENGTH+padding)*Math.abs(x*forwardX+z*forwardZ)
            +((double)HALF_WIDTH+padding)*Math.abs(x*forwardZ-z*forwardX);
    }
    private static final class Interval {
        double start,end;
        Interval(double horizon) {end=horizon;}
        boolean clip(double gap,double velocity,double radius) {
            if(velocity==0) return Math.abs(gap)<=radius;
            double a=(-radius-gap)/velocity,b=(radius-gap)/velocity;
            start=Math.max(start,Math.min(a,b));end=Math.min(end,Math.max(a,b));
            return start<=end;
        }
    }
}
