package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.*;

/** Small deterministic overtaking state machine; no position changes, hidden boost or physics authority. */
public final class RaceTrafficPlanner {
    private static final int WINDOW_SIZE=3, COASTING=2;
    public enum Maneuver { FOLLOW, PASS_LEFT, PASS_RIGHT, RETURN }
    public record Vehicle(Vector3f position,Vector3f velocity) {}
    public record ProjectedVehicle(TrackLayout.Projection projection,float speed,float alongSpeed,float lateralSpeed) {}
    public record Decision(float offset,float speedLimit,boolean blocked,boolean emergency,Maneuver maneuver,TrafficCommandGuard.Restriction restriction) {}

    /** Reuses horizontal road geometry across observations. Each observation still supplies its own velocity. */
    public static final class ProjectionHint {
        private TrackLayout track;
        private TrackLayout.Projection projection;
        private int xBits, zBits;

        private TrackLayout.Projection read(TrackLayout road,Vector3f position) {
            int x=Float.floatToRawIntBits(position.x);
            int z=Float.floatToRawIntBits(position.z);
            if(track!=road || xBits!=x || zBits!=z) {
                int segment=track==road ? projection.segment() : -1;
                projection=road.project(position,segment);
                track=road;
                xBits=x;
                zBits=z;
            }
            return projection;
        }
    }

    private final TrackLayout track;
    private final float nominal;
    private final List<TrafficMotionWindow.Neighbor> observed=new ArrayList<>();
    private double[] predictionWindows=new double[4*WINDOW_SIZE];
    private float offset,hold;
    private int passSide;
    private boolean initialized;
    private Maneuver maneuver=Maneuver.FOLLOW;
    public RaceTrafficPlanner(TrackLayout track,float nominal) {this.track=Objects.requireNonNull(track);if(!Float.isFinite(nominal)) throw new IllegalArgumentException("Invalid nominal lane");this.nominal=bound(nominal);offset=this.nominal;}
    public void reset() { offset=nominal;hold=0;passSide=0;initialized=false;maneuver=Maneuver.FOLLOW; }
    public Decision update(float dt,Vector3f position,float speed,float desiredSpeed,List<Vehicle> traffic) {
        finite(position);
        return updateFromMotions(dt,new ProjectedVehicle(track.project(position),speed,speed,0),desiredSpeed,traffic);
    }
    public Decision update(float dt,Vector3f position,Vector3f velocity,float desiredSpeed,List<Vehicle> traffic) {
        return updateFromMotions(dt,project(track,new Vehicle(position,velocity)),desiredSpeed,traffic);
    }

    public static ProjectedVehicle project(TrackLayout track,Vehicle vehicle) {
        return project(track,vehicle,null);
    }

    public static ProjectedVehicle project(TrackLayout track,Vehicle vehicle,ProjectionHint hint) {
        var position=vehicle.position();
        finite(position);
        var velocity=vehicle.velocity();
        finite(velocity);
        var projection=hint==null ? track.project(position) : hint.read(track,position);
        var roadVelocity=track.roadVelocity(projection,velocity);
        return new ProjectedVehicle(projection,velocity.length(),roadVelocity.along(),roadVelocity.lateral());
    }

    private Decision updateFromMotions(float dt,ProjectedVehicle self,float desiredSpeed,List<Vehicle> traffic) {
        checkSpeed(self,desiredSpeed);
        observed.clear();
        for(var other:traffic) addPeer(self.projection(),project(track,other));
        return decide(dt,self,desiredSpeed);
    }

    /** All projections come from the same track and frozen control frame. */
    public Decision updateProjected(float dt,ProjectedVehicle self,float desiredSpeed,List<ProjectedVehicle> traffic) {
        checkSpeed(self,desiredSpeed);
        observed.clear();
        for(var other:traffic) addPeer(self.projection(),other);
        return decide(dt,self,desiredSpeed);
    }

    private void addPeer(TrackLayout.Projection self,ProjectedVehicle other) {
        var projection=other.projection();
        var peer=new TrafficMotionWindow.Neighbor(along(self.distance(),projection.distance()),projection.lateral(),
                other.alongSpeed(),other.lateralSpeed());
        int index=observed.size()*WINDOW_SIZE;
        if(index>=predictionWindows.length) predictionWindows=Arrays.copyOf(predictionWindows,index*2);
        // These results depend on the actual motions of the current frame, independent of the candidate lane.
        predictionWindows[index]=Double.NaN;
        predictionWindows[index+COASTING]=Double.NaN;
        observed.add(peer);
    }

    private static void checkSpeed(ProjectedVehicle self,float desiredSpeed) {
        if(!Float.isFinite(self.speed()) || self.speed()<0 || !Float.isFinite(self.alongSpeed())
                || !Float.isFinite(self.lateralSpeed()) || !Float.isFinite(desiredSpeed) || desiredSpeed<0)
            throw new IllegalArgumentException("Invalid traffic speed");
    }
    private Decision decide(float dt,ProjectedVehicle state,float desiredSpeed) {
        var self=state.projection();
        float speed=state.speed(),alongSpeed=state.alongSpeed(),lateralSpeed=state.lateralSpeed();
        dt=Float.isFinite(dt)?Math.max(0,Math.min(.1f,dt)):0;
        float commandStep=TrafficMotionWindow.LATERAL_RATE*dt;
        TrafficMotionWindow.Neighbor lead=null;float leadGap=Float.POSITIVE_INFINITY,leadSpeed=0,conflict=Float.POSITIVE_INFINITY,predictedLimit=Float.POSITIVE_INFINITY;
        if(!initialized) {offset=bound(self.lateral());initialized=true;}
        for(int i=0;i<observed.size();i++) {
            var other=observed.get(i);
            if(other.gap()>0 && (Math.abs(other.lateral()-self.lateral())<2.7f || Float.isFinite(TrafficMotionWindow.laneEntry(self.lateral(),lateralSpeed,other)))) {
                int window=longitudinalWindow(i,other,alongSpeed);
                float predicted=predictedOverlap(window,other,self.lateral(),offset,lateralSpeed);
                conflict=Math.min(conflict,predicted);if(Float.isFinite(predicted)) predictedLimit=Math.min(predictedLimit,Math.max(0,other.alongSpeed()-2));
                if(other.gap()<leadGap) {lead=other;leadGap=other.gap();leadSpeed=Math.max(0,other.alongSpeed());}
            }
        }
        hold=Math.max(0,hold-dt);
        float passingOffset=Math.min(3.5f,Math.max(0,track.width*.5f-2));
        boolean targetChecked=false;
        if(passSide==0 && lead!=null && leadGap<30+speed*.4f && desiredSpeed>leadSpeed+2 && passingOffset>2.8f
                && track.closed && track.targetSpeed(self.distance()+15)>=22 && track.targetSpeed(self.distance()+35)>=22) {
            int preferred=nominal<0?-1:1;
            if(canMoveTo(self.lateral(),preferred*passingOffset,speed,alongSpeed,lateralSpeed,commandStep)) passSide=preferred;
            else if(canMoveTo(self.lateral(),-preferred*passingOffset,speed,alongSpeed,lateralSpeed,commandStep)) passSide=-preferred;
            if(passSide!=0) {hold=2;targetChecked=true;}
        }
        float target=nominal;
        if(passSide!=0) {
            target=passSide*passingOffset;
            maneuver=passSide<0?Maneuver.PASS_LEFT:Maneuver.PASS_RIGHT;
            boolean carStillAhead=false;
            for(var other:observed) {
                if(other.gap()>-8 && other.gap()<35 && Math.abs(other.lateral()-nominal)<2.7f) {carStillAhead=true;break;}
            }
            if(hold==0 && !carStillAhead && canMoveTo(self.lateral(),nominal,speed,alongSpeed,lateralSpeed,commandStep)) {
                passSide=0;target=nominal;maneuver=Maneuver.RETURN;
                targetChecked=true;
            }
        } else maneuver=Math.abs(offset-nominal)>.1f?Maneuver.RETURN:Maneuver.FOLLOW;
        // Check the physical starting corridor even when the old command already reached its target.
        boolean lateralBlocked=!targetChecked && (Math.abs(target-offset)>.05f || Math.abs(target-self.lateral())>.05f)
                && !canMoveTo(self.lateral(),target,speed,alongSpeed,lateralSpeed,commandStep);
        if(lateralBlocked) {
            target=bound(self.lateral());
            if(passSide!=0 && hold==0 && Math.abs(offset-nominal)<.4f) {passSide=0;maneuver=Math.abs(offset-nominal)>.1f?Maneuver.RETURN:Maneuver.FOLLOW;}
        }
        offset+=Math.max(-commandStep,Math.min(commandStep,target-offset));
        float edge=Math.max(0,track.width*.5f-2);
        var restriction=TrafficCommandGuard.restriction(self.lateral(),edge,observed);
        float guarded=TrafficCommandGuard.clampWithRestriction(self.lateral(),offset,edge,restriction);
        lateralBlocked|=guarded!=offset;
        offset=guarded;
        boolean blocked=lead!=null && leadGap<8+speed*.65f || Float.isFinite(predictedLimit);
        float limit=Math.min(lead!=null && leadGap<8+speed*.65f?Math.max(0,leadSpeed-2+(leadGap-6)*.5f):Float.POSITIVE_INFINITY,predictedLimit);
        return new Decision(offset,limit,blocked || lateralBlocked,lead!=null && leadGap<6 || conflict<=.75f,maneuver,restriction);
    }
    private boolean canMoveTo(float from,float lane,float speed,float alongSpeed,float lateralSpeed,float commandStep) {
        float frontClearance=Math.max(18,speed*.9f);
        for(var other:observed) {
            float gap=other.gap(),closingSpeed=other.alongSpeed()-alongSpeed;
            float rearClearance=Math.max(8,6+closingSpeed*1.2f);
            if(gap>-rearClearance && gap<frontClearance && Math.abs(other.lateral()-lane)<2.8f
                    && !separatingMove(from,lane,alongSpeed,lateralSpeed,commandStep,other)) return false;
        }
        for(int i=0;i<observed.size();i++) {
            var other=observed.get(i);
            int window=longitudinalWindow(i,other,alongSpeed);
            if(predictionWindows[window]>predictionWindows[window+1]) continue;
            if(separatingMove(from,lane,alongSpeed,lateralSpeed,commandStep,other)) continue;
            if(Float.isFinite(predictedOverlap(window,other,from,lane,lateralSpeed))) return false;
        }
        return true;
    }
    private int longitudinalWindow(int index,TrafficMotionWindow.Neighbor peer,float alongSpeed) {
        int window=index*WINDOW_SIZE;
        if(Double.isNaN(predictionWindows[window])) {
            double relativeSpeed=(double)peer.alongSpeed()-alongSpeed;
            predictionWindows[window]=TrafficMotionWindow.longitudinalStart(peer.gap(),relativeSpeed);
            predictionWindows[window+1]=TrafficMotionWindow.longitudinalEnd(peer.gap(),relativeSpeed);
        }
        return window;
    }
    private float predictedOverlap(int window,TrafficMotionWindow.Neighbor peer,float from,float target,float lateralSpeed) {
        double start=predictionWindows[window],end=predictionWindows[window+1];
        if(start>end) return Float.POSITIVE_INFINITY;
        double planned=TrafficMotionWindow.plannedLateral(from,target,peer,start,end);
        if(planned==start) return (float)planned;
        if(Double.isNaN(predictionWindows[window+COASTING]))
            predictionWindows[window+COASTING]=TrafficMotionWindow.coastingLateral(from,lateralSpeed,peer,start,end);
        return (float)Math.min(planned,predictionWindows[window+COASTING]);
    }
    private boolean separatingMove(float from,float target,float alongSpeed,float lateralSpeed,float step,TrafficMotionWindow.Neighbor other) {if(!TrafficMotionWindow.separating(alongSpeed,from,target,lateralSpeed,other)) return false;double next=(double)offset+Math.max(-step,Math.min(step,target-offset));return ((double)other.lateral()-from)*(next-from)<=0;}
    private float bound(float value) {float edge=Math.max(0,track.width*.5f-2);return Math.max(-edge,Math.min(edge,value));}
    private static void finite(Vector3f value) {if(value==null || !Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) throw new IllegalArgumentException("Invalid traffic vector");}
    private float along(float from,float to) {
        float result=to-from;
        if(track.closed) { if(result>track.length*.5f) result-=track.length;else if(result< -track.length*.5f) result+=track.length; }
        return result;
    }
}
