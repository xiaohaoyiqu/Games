package com.f1car.scene;

import com.f1car.model.TrackLayout;
import com.f1car.model.AiRecovery;
import com.f1car.model.RaceTrafficPlanner;
import com.f1car.model.BrakingPreview;
import com.f1car.model.AiSteering;
import com.f1car.model.TrafficBodyMotion;
import com.f1car.model.WorldTrafficBraking;
import com.f1car.model.WorldTrafficRestart;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DriverAI {
    record Observation(FormulaCar car, RaceTrafficPlanner.Vehicle motion, Quaternion rotation,
            Vector3f forward, float upY, TrafficBodyMotion.Body chassis, AiRecovery.Reason fault) {}

    /** Vehicle observations for one control update, sampled before any driver writes to the bodies. */
    public static final class TrafficFrame {
        private final List<Observation> observations;
        private final WorldTrafficBraking.SharedWindows chassisWindows;
        private final TrafficSampler sampler;
        private final long generation;
        private TrackLayout projectedTrack;
        private RaceTrafficPlanner.ProjectedVehicle[] projected;

        TrafficFrame(List<Observation> observations) {
            this(observations,null,0);
        }

        private TrafficFrame(List<Observation> observations,TrafficSampler sampler,long generation) {
            this.observations = List.copyOf(observations);
            this.sampler=sampler;
            this.generation=generation;
            chassisWindows=observations.size()>1?new WorldTrafficBraking.SharedWindows():null;
        }

        /** Copies the position sampled in this frame when the roster slot and observation match. */
        public boolean copyPosition(int slot,FormulaCar car,Vector3f destination) {
            if(slot<0 || slot>=observations.size()) return false;
            var observation=observations.get(slot);
            if(observation.car()!=car || observation.motion()==null) return false;
            destination.set(observation.motion().position());
            return true;
        }

        /** Returns a projection already calculated for this car's frozen position on the given track. */
        public TrackLayout.Projection roadProjection(TrackLayout track,int slot,FormulaCar car) {
            if(projectedTrack!=track || projected==null || slot<0 || slot>=projected.length
                    || observations.get(slot).car()!=car) return null;
            var motion=projected[slot];
            return motion==null?null:motion.projection();
        }

        RaceTrafficPlanner.ProjectedVehicle[] project(TrackLayout track) {
            if(projectedTrack!=track) {
                var next=new RaceTrafficPlanner.ProjectedVehicle[observations.size()];
                // An older frame can still be projected using its frozen observations.
                var hints=sampler!=null && sampler.generation==generation ? sampler.hints(next.length) : null;
                for(int i=0;i<next.length;i++) {
                    var motion=observations.get(i).motion();
                    if(motion!=null) next[i]=RaceTrafficPlanner.project(track,motion,hints==null ? null : hints[i]);
                }
                projected=next;
                projectedTrack=track;
            }
            return projected;
        }
    }

    /** Scratch storage is separate from the values retained by each captured frame. */
    public static final class TrafficSampler {
        private static final RaceTrafficPlanner.ProjectionHint[] NO_HINTS=new RaceTrafficPlanner.ProjectionHint[0];
        private final Vector3f axisReading=new Vector3f();
        private final ArrayList<Observation> observations=new ArrayList<>();
        private RaceTrafficPlanner.ProjectionHint[] projectionHints=NO_HINTS;
        private long generation;

        private RaceTrafficPlanner.ProjectionHint[] hints(int count) {
            // Slots hold only geometry. A different car in a slot still uses its current position and velocity.
            int size=projectionHints.length;
            if(count>size) {
                var next=Arrays.copyOf(projectionHints,count);
                for(int i=size;i<count;i++) next[i]=new RaceTrafficPlanner.ProjectionHint();
                projectionHints=next;
            }
            return projectionHints;
        }

        public void clear() {
            projectionHints=NO_HINTS;
            generation++;
        }

        public TrafficFrame capture(List<FormulaCar> traffic) {
            return capture(traffic,null);
        }

        private TrafficFrame capture(List<FormulaCar> traffic,FormulaCar requiredCar) {
            boolean found=requiredCar==null;
            boolean observeChassis=traffic.size()>1
                    || requiredCar!=null && traffic.size()==1 && traffic.get(0)!=requiredCar;
            try {
                for(var car:traffic) {
                    observations.add(observe(car,observeChassis,axisReading));
                    found|=car==requiredCar;
                }
                if(!found) observations.add(observe(requiredCar,observeChassis,axisReading));
                return new TrafficFrame(observations,this,++generation);
            } finally {
                observations.clear();
            }
        }
    }

    private final TrackLayout track;
    private final float pace;
    private final BrakingPreview brakingPreview;
    private final AiRecovery recovery=new AiRecovery();
    private final RaceTrafficPlanner trafficPlanner;
    private final List<RaceTrafficPlanner.ProjectedVehicle> peerRoad=new ArrayList<>();
    private final List<RaceTrafficPlanner.Vehicle> peerMotion=new ArrayList<>();
    private final List<TrafficBodyMotion.Body> peerBodies=new ArrayList<>();
    private final TrafficSampler directTraffic=new TrafficSampler();
    private final Quaternion inverseRotation=new Quaternion();
    private final Vector3f steeringTarget=new Vector3f();
    private final Vector3f steeringRight=new Vector3f();
    private final Vector3f steeringChord=new Vector3f();
    private float plannedSpeed=-1;
    private RaceTrafficPlanner.Decision trafficDecision;
    private WorldTrafficBraking.Decision proximityDecision;
    private final WorldTrafficRestart restart=new WorldTrafficRestart();
    private WorldTrafficRestart.Decision restartDecision;
    private WorldTrafficBraking.Decision restartProbeDecision;
    public RaceTrafficPlanner.Decision trafficDecision() {return trafficDecision;}
    public WorldTrafficBraking.Decision proximityDecision() {return proximityDecision;}
    public WorldTrafficRestart.Decision restartDecision() {return restartDecision;}
    public WorldTrafficBraking.Decision restartProbeDecision() {return restartProbeDecision;}
    public boolean needsRecovery;
    public AiRecovery.Reason recoveryReason() { return recovery.reason(); }
    public void recovered() {
        recovery.reset();
        trafficPlanner.reset();
        restart.reset();
        needsRecovery=false;
        plannedSpeed=-1;
        clearTrafficReadings();
    }

    private void clearTrafficReadings() {
        trafficDecision=null;
        proximityDecision=null;
        restartDecision=null;
        restartProbeDecision=null;
    }

    public DriverAI(TrackLayout track, float pace, float lane) {
        this.track=track;
        this.pace=pace;
        trafficPlanner=new RaceTrafficPlanner(track,lane);
        brakingPreview=new BrakingPreview(track);
    }

    public static TrafficFrame captureTraffic(List<FormulaCar> traffic) {
        return new TrafficSampler().capture(traffic);
    }

    public void update(float dt, FormulaCar car, List<FormulaCar> traffic) {
        updateFromFrame(dt, car, directTraffic.capture(traffic,car));
    }

    public void updateFromFrame(float dt, FormulaCar car, TrafficFrame frame) {
        if(needsRecovery) {
            clearTrafficReadings();
            car.holdForRecovery();
            return;
        }
        float step = Float.isFinite(dt) && dt > 0 ? dt : 0;
        int selfIndex = -1;
        for(int i=0;i<frame.observations.size();i++) {
            if(frame.observations.get(i).car()==car) selfIndex=i;
        }
        if(selfIndex<0) throw new IllegalArgumentException("Driving car is missing from the traffic frame");
        var self=frame.observations.get(selfIndex);
        if(self.fault()!=AiRecovery.Reason.NONE) {
            clearTrafficReadings();
            recovery.requestInvalidObservation(self.fault());
            needsRecovery=true;
            car.holdForRecovery();
            return;
        }
        var projected=frame.project(track);
        var roadMotion=projected[selfIndex];
        Vector3f position=self.motion().position();
        float speed=roadMotion.speed();
        var projection=roadMotion.projection();
        peerRoad.clear();
        peerMotion.clear();
        peerBodies.clear();
        int missingMotions=0;
        for(int i=0;i<frame.observations.size();i++) {
            if(frame.observations.get(i).car()==car) continue;
            var observation=frame.observations.get(i);
            if(projected[i]==null) {
                missingMotions++;
            } else {
                peerRoad.add(projected[i]);
                peerMotion.add(observation.motion());
                peerBodies.add(observation.chassis());
            }
        }
        if(missingMotions>0) {
            holdForMissingTraffic(car,step,speed,self.upY(),position.y,missingMotions);
            return;
        }
        float lookAhead = Math.max(8,Math.min(26,7 + speed * .53f));
        float targetSpeed=brakingPreview.speedLimit(projection.distance(),speed,pace,car.tuning.brakes());
        var decision=trafficPlanner.updateProjected(step,roadMotion,targetSpeed,peerRoad);trafficDecision=decision;
        targetSpeed=Math.min(targetSpeed,decision.speedLimit());
        var ownBody=self.chassis();
        proximityDecision=peerBodies.isEmpty()?WorldTrafficBraking.CLEAR:frame.chassisWindows.assess(ownBody,peerBodies,peerMotion);
        restartProbeDecision=null;
        if(!peerBodies.isEmpty() && !proximityDecision.blocked()) {
            var probe=WorldTrafficRestart.probe(ownBody,speed,targetSpeed);
            if(probe!=null) restartProbeDecision=WorldTrafficBraking.assess(probe,peerBodies,peerMotion);
        }
        peerRoad.clear();
        peerMotion.clear();
        peerBodies.clear();
        restartDecision=restart.update(step,proximityDecision,restartProbeDecision);
        recovery.update(step,speed,track.tangentAlignment(projection,self.forward()),self.upY(),position.y,
                decision.blocked() || restartDecision.blocked());
        needsRecovery=recovery.requested();
        if(needsRecovery) {
            car.holdForRecovery();
            return;
        }
        targetSpeed=Math.min(targetSpeed,proximityDecision.speedLimit());
        targetSpeed=Math.min(targetSpeed,restartDecision.speedLimit(speed));
        track.samplePositionAndRight(projection.distance()+lookAhead,steeringTarget,steeringRight);
        var inverse=inverseRotation.set(self.rotation()).inverseLocal();
        // Pure pursuit uses the actual chord to the target, including when off the reference line.
        float steer=steeringDemand(steeringTarget,steeringRight,position,inverse,decision.offset(),speed,steeringChord);
        AiSteering.Bounds steeringBounds=null;
        if(decision.restriction().left() || decision.restriction().right()) {
            float following=steeringDemand(steeringTarget,steeringRight,position,inverse,projection.lateral(),speed,steeringChord);
            steeringBounds=AiSteering.bounds(following,decision.restriction());
        }
        if (projection.separation() > track.width * .43f) targetSpeed = Math.min(targetSpeed, 12);
        if(plannedSpeed<0) plannedSpeed=targetSpeed;
        float delta=targetSpeed-plannedSpeed;
        plannedSpeed+=Math.max(-12*step,Math.min(WorldTrafficRestart.PLAN_ACCELERATION*step,delta));
        if(proximityDecision.blocked()) plannedSpeed=proximityDecision.limitPlan(plannedSpeed,speed,step);
        plannedSpeed=restartDecision.limitPlan(plannedSpeed,speed);
        if(decision.emergency() || proximityDecision.emergency()) plannedSpeed=Math.min(plannedSpeed,targetSpeed);
        float difference = plannedSpeed - speed;
        float throttle=Math.max(0,Math.min(1,difference*.45f)),brake=Math.max(0,Math.min(1,-difference*.22f));
        if(steeringBounds==null) car.drive(step,throttle,brake,steer);
        else car.driveWithSteeringBounds(step,throttle,brake,steer,steeringBounds);
    }

    static float steeringDemand(Vector3f target,Vector3f right,Vector3f position,Quaternion inverse,
                                float offset,float speed,Vector3f chord) {
        chord.set(right).multLocal(offset).addLocal(target).subtractLocal(position);
        inverse.mult(chord,chord);
        return AiSteering.demand(chord.x,chord.z,speed);
    }

    private void holdForMissingTraffic(FormulaCar car,float step,float speed,float upY,float height,int count) {
        peerRoad.clear();
        peerMotion.clear();
        peerBodies.clear();
        trafficDecision=null;
        proximityDecision=new WorldTrafficBraking.Decision(false,Float.POSITIVE_INFINITY,0,count);
        restartProbeDecision=null;
        restartDecision=restart.observationLost();
        plannedSpeed=0;
        recovery.update(step,speed,0,upY,height,true);
        needsRecovery=recovery.requested();
        if(needsRecovery) car.holdForRecovery();
        else car.drive(step,0,1,0);
    }

    private static Observation observe(FormulaCar car, boolean observeChassis,Vector3f axisReading) {
        return observe(car,car.position(),car.velocity(),car.body.getPhysicsRotation(null),
                observeChassis?car.body.getAngularVelocity(axisReading).y:Float.NaN,axisReading);
    }

    static Observation observe(FormulaCar car,Vector3f position,Vector3f velocity,Quaternion rotation,float yawRate) {
        return observe(car,position,velocity,rotation,yawRate,new Vector3f());
    }

    private static Observation observe(FormulaCar car,Vector3f position,Vector3f velocity,Quaternion rotation,float yawRate,Vector3f axisReading) {
        if(!usableVector(position) || !usableVector(velocity))
            return new Observation(car,null,null,null,Float.NaN,null,AiRecovery.Reason.OUT_OF_WORLD);
        var motion=new RaceTrafficPlanner.Vehicle(position,velocity);
        if(!FormulaCar.usableRotation(rotation))
            return new Observation(car,motion,null,null,Float.NaN,null,AiRecovery.Reason.OVERTURNED);
        Vector3f forward = rotation.mult(Vector3f.UNIT_Z);
        Vector3f up = rotation.mult(Vector3f.UNIT_Y,axisReading);
        var chassis=chassis(position,velocity,forward,up,yawRate);
        return new Observation(car,motion,rotation,forward,up.y,chassis,AiRecovery.Reason.NONE);
    }

    private static boolean usableVector(Vector3f value) {
        return value!=null && Float.isFinite(value.lengthSquared());
    }

    private static TrafficBodyMotion.Body chassis(Vector3f origin,Vector3f velocity,Vector3f forward,Vector3f up,float yawRate) {
        // Only upright horizontal yaw is predicted; pitch/roll evolution remains outside this model.
        if(!Float.isFinite(up.y) || !Float.isFinite(yawRate) || up.y<.9f || (double)forward.x*forward.x+(double)forward.z*forward.z<.00000001) return null;
        float height=TrafficBodyMotion.CENTER_HEIGHT;
        return new TrafficBodyMotion.Body(origin.x+up.x*height,origin.y+up.y*height,origin.z+up.z*height,
                velocity.x,velocity.y,velocity.z,forward.x,forward.z,yawRate);
    }
}
