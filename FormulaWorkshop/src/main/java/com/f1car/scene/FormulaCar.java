package com.f1car.scene;

import com.f1car.model.Tuning;
import com.f1car.model.VehicleSpec;
import com.f1car.model.SpeedLimiter;
import com.f1car.model.VehicleObservation;
import com.f1car.model.AxleAerodynamics;
import com.f1car.model.AxleDamping;
import com.f1car.model.AxleFriction;
import com.f1car.model.WheelObservationWindow;
import com.f1car.model.AiSteering;
import com.f1car.model.RecoveryPlanner;
import com.f1car.model.TrafficBodyMotion;
import com.f1car.model.TrackLayout;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.PhysicsTickListener;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.CompoundCollisionShape;
import com.jme3.bullet.control.VehicleControl;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Cylinder;

public final class FormulaCar implements AutoCloseable,PhysicsTickListener {
    public static final int[] LIVERIES = {0x17cfb1, 0xff654f, 0x789bff, 0xffce5e, 0xd7e6ec};
    public final String name;
    public final Node node = new Node("formula car");
    public final VehicleControl body;
    public final VehicleSpec spec;
    public final Tuning tuning;
    private final PhysicsSpace space;
    private final AeroStep aeroStep;
    private volatile boolean closed;
    private static final class AeroStep {
        private final AxleAerodynamics profile;private final AxleAerodynamics.Frame frame=new AxleAerodynamics.Frame();
        private final Quaternion rotation=new Quaternion(),inverse=new Quaternion();
        private final Vector3f velocity=new Vector3f(),localVelocity=new Vector3f(),localForce=new Vector3f(),force=new Vector3f(),localOffset=new Vector3f(),offset=new Vector3f();
        private boolean valid;
        AeroStep(AxleAerodynamics profile) {this.profile=profile;}
        synchronized AxleAerodynamics.Reading reading() {return valid?frame.reading():null;}
        synchronized void clear() {valid=false;}
        synchronized void apply(FormulaCar car) {
            valid=false;
            car.body.getPhysicsRotation(rotation);
            car.body.getLinearVelocity(velocity);
            if(!usableRotation(rotation) || !Float.isFinite(velocity.lengthSquared())) return;
            inverse.set(rotation).inverseLocal().mult(velocity,localVelocity);
            try {profile.calculate(localVelocity,car.tuning.wing(),frame);}catch(IllegalArgumentException invalid) {return;}
            frame.frontForce(localForce);rotation.mult(localForce,force);profile.frontOffset(localOffset);rotation.mult(localOffset,offset);car.body.applyForce(force,offset);
            frame.rearForce(localForce);rotation.mult(localForce,force);profile.rearOffset(localOffset);rotation.mult(localOffset,offset);car.body.applyForce(force,offset);
            frame.centralForce(localForce);rotation.mult(localForce,force);car.body.applyCentralForce(force);valid=true;
        }
    }
    /** Applied study forces from the latest valid step, not measured wheel loads. */
    public AxleAerodynamics.Reading aeroReading() {return aeroStep==null?null:aeroStep.reading();}
    private final Vector3f limitVelocity=new Vector3f(),limitForward=new Vector3f();
    private final Vector3f roadDamping=new Vector3f();
    private int roadDampingSegment=-1;
    private final Quaternion limitRotation=new Quaternion();
    private final Vector3f observedAngular=new Vector3f();
    private volatile VehicleObservation observation;
    public VehicleObservation observation() { return observation; }
    private volatile NativeWheelState wheelState;
    private long wheelSequence;
    private volatile WheelObservationWindow wheelWindow;
    private WheelObservationWindow armedWheelWindow;
    private float armedWheelStep;
    /** Explicit programmatic instrumentation. The ordinary native dependency reports UNAVAILABLE. */
    public synchronized boolean enableNativeWheelObservation() {
        if(closed) return false;interruptWheelWindow(WheelObservationWindow.Status.READER_RESTARTED);if(wheelState!=null) wheelSequence=wheelState.closeAndSequence();wheelState=NativeWheelState.open(wheelSequence);return wheelState.status()==NativeWheelState.Status.READY;
    }
    public synchronized void disableNativeWheelObservation() {interruptWheelWindow(WheelObservationWindow.Status.READER_DISABLED);if(wheelState!=null) wheelSequence=wheelState.closeAndSequence();wheelState=null;}
    public com.f1car.model.NativeWheelObservation nativeWheelObservation() {var current=wheelState;return current==null?null:current.reading();}
    public String nativeWheelObservationStatus() {var current=wheelState;return closed?"CLOSED":current==null?"DISABLED":current.status().name();}
    /** Programmatic research only. Offsets start at the next full owning pre/post physics step. */
    public synchronized boolean beginNativeWheelWindow(double startSeconds,double endSeconds) {
        if(closed || wheelWindow!=null && wheelWindow.status()==WheelObservationWindow.Status.OPEN) return false;
        var context=WheelObservationWindow.Context.capture(spec,tuning);long preceding=wheelState==null?wheelSequence:wheelState.sequence();
        wheelWindow=new WheelObservationWindow(context,startSeconds,endSeconds,preceding);armedWheelWindow=null;return true;
    }
    public synchronized void stopNativeWheelWindow() {interruptWheelWindow(WheelObservationWindow.Status.STOPPED);}
    public WheelObservationWindow.Summary nativeWheelWindowSummary() {var current=wheelWindow;return current==null?null:current.summary();}
    public String nativeWheelWindowStatus() {var current=wheelWindow;return current==null?"NONE":current.status().name();}
    private void interruptWheelWindow(WheelObservationWindow.Status reason) {armedWheelWindow=null;var current=wheelWindow;if(current!=null) current.interrupt(reason);}
    public float throttle, braking, steering;
    private float steeringInput;
    private float steeringDemand;
    public float steeringDemand() { return steeringDemand; }
    private Node cupAssembly;
    private Geometry cupWater;

    public FormulaCar(String name, SceneFactory factory, PhysicsSpace space, VehicleSpec spec, Tuning tuning) {
        if(!spec.studyIdentityValid()) throw new IllegalArgumentException("Vehicle study physics identity differs from its configuration");
        if(spec.frictionStudy()!=null) spec=spec.withAxleFriction(spec.frictionStudy());
        else if(spec.dampingStudy()!=null) spec=spec.withAxleDamping(spec.dampingStudy());
        else if(spec.aeroStudy()!=null) spec=spec.withAxleAerodynamics(spec.aeroStudy());
        AxleDamping.Coefficients splitDamping=spec.dampingStudy()==null?null:spec.dampingStudy().coefficients(spec,tuning);
        AxleFriction.Coefficients splitFriction=spec.frictionStudy()==null?null:spec.frictionStudy().coefficients();
        this.name = name; this.space = space; this.spec = spec; this.tuning = tuning;
        this.aeroStep=spec.aeroStudy()==null?null:new AeroStep(spec.aeroStudy());
        node.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
        CompoundCollisionShape chassis = new CompoundCollisionShape();
        chassis.addChildShape(new BoxCollisionShape(new Vector3f(TrafficBodyMotion.HALF_WIDTH,TrafficBodyMotion.HALF_HEIGHT,TrafficBodyMotion.HALF_LENGTH)),new Vector3f(0,TrafficBodyMotion.CENTER_HEIGHT,0));
        body = new VehicleControl(chassis, spec.massKg);
        node.addControl(body);
        float stiffness = spec.suspensionStiffness * tuning.suspension();
        body.setSuspensionStiffness(stiffness);
        body.setSuspensionCompression(.32f * 2 * (float) Math.sqrt(stiffness));
        body.setSuspensionDamping(.45f * 2 * (float) Math.sqrt(stiffness));
        body.setMaxSuspensionForce(12000);
        body.setMaxSuspensionTravelCm(25);
        body.setFrictionSlip(2.5f);
        body.setDamping(.025f, .35f);
        body.setCcdMotionThreshold(.5f);
        body.setCcdSweptSphereRadius(.2f);
        body.setSleepingThresholds(0, 0);
        int paint = LIVERIES[tuning.livery()];
        factory.box(node, "carbon floor", 0, .15f, -.15f, .77f, .07f, 1.85f, 0x17252e);
        factory.box(node, "monocoque", 0, .37f, -.1f, .36f, .22f, 1.1f, paint);
        factory.box(node, "nose", 0, .31f, 1.3f, .22f, .13f, .9f, paint);
        factory.box(node, "nose stripe", 0, .45f, 1.3f, .07f, .014f, .85f, 0xeef6ed);
        factory.box(node, "left sidepod", -.62f, .34f, -.35f, .24f, .19f, .76f, paint);
        factory.box(node, "right sidepod", .62f, .34f, -.35f, .24f, .19f, .76f, paint);
        factory.box(node, "cockpit", 0, .59f, .08f, .28f, .03f, .38f, 0x0d1723);
        factory.cylinder(node, "driver helmet", 0, .76f, -.03f, .20f, .28f, 0xffdf81);
        factory.box(node, "helmet visor", 0, .79f, .13f, .17f, .065f, .06f, 0x122a3e);
        factory.box(node, "halo beam", 0, .89f, .28f, .04f, .045f, .52f, 0x182934);
        factory.box(node, "halo crossbar", 0, .89f, -.18f, .38f, .045f, .045f, 0x182934);
        factory.box(node, "roll hoop", 0, .85f, -.55f, .19f, .24f, .11f, paint);
        factory.box(node, "rear wing", 0, .96f, -1.79f, .98f, .055f, .29f * tuning.wing()*(aeroStep==null?1:spec.aeroStudy().rear()), paint);
        factory.box(node, "rear wing stripe", 0, 1.02f, -1.79f, .75f, .01f, .12f, 0xf0f3ec);
        for (float x : new float[]{-.93f, .93f}) {
            factory.box(node, "rear wing endplate", x, .81f, -1.79f, .04f, .24f, .32f, 0x17252e);
            factory.box(node, "front wing endplate", x, .28f, 2.01f, .04f, .15f, .28f, paint);
        }
        factory.box(node, "front wing", 0, .16f, 2.02f, 1.0f, .045f, .27f*(aeroStep==null?1:spec.aeroStudy().front()), 0x17252e);
        factory.box(node, "front flap", 0, .22f, 1.91f, .86f, .035f, .11f, paint);
        for (int i = 0; i < 4; i++) {
            boolean front = i < 2;
            float x = (i % 2 == 0 ? -1 : 1) * 1.04f, z = front ? 1.38f : -1.30f;
            Node wheel = new Node("wheel-" + i);
            Geometry tire = new Geometry("slick tire", new Cylinder(2, 20, spec.wheelRadius, front ? .34f : .42f, true));
            tire.rotate(0, (float) Math.PI / 2, 0);
            tire.setMaterial(factory.material(0x141e25));
            wheel.attachChild(tire);
            Geometry hub = new Geometry("wheel rim", new Cylinder(2, 16, spec.wheelRadius * .58f, front ? .36f : .44f, true));
            hub.rotate(0, (float) Math.PI / 2, 0);
            hub.setMaterial(factory.material(0x647985));
            wheel.attachChild(hub);
            node.attachChild(wheel);
            body.addWheel(wheel, new Vector3f(x, .20f, z), new Vector3f(0, -1, 0), new Vector3f(-1, 0, 0), spec.suspensionRestLength, spec.wheelRadius, front);
            body.setRollInfluence(i, .09f);
            var physicalWheel=body.getWheel(i);
            if(splitFriction!=null) physicalWheel.setFrictionSlip(splitFriction.wheel(i));
            physicalWheel.setSuspensionStiffness(stiffness*(front?tuning.frontSpring():tuning.rearSpring()));
            // Axis damping is independent of axis spring changes. The common package keeps its original coupling.
            float damping=front?tuning.frontDamping():tuning.rearDamping();
            physicalWheel.setWheelsDampingCompression(splitDamping==null?.32f*2*(float)Math.sqrt(stiffness)*damping:splitDamping.wheel(i).compression());
            physicalWheel.setWheelsDampingRelaxation(splitDamping==null?.45f*2*(float)Math.sqrt(stiffness)*damping:splitDamping.wheel(i).rebound());
        }
        space.add(body);
        space.addTickListener(this);
    }

    public void drive(float dt, float desiredThrottle, float brake, float desiredSteer) {
        drive(dt,desiredThrottle,brake,desiredSteer,null);
    }
    /** AI-only command envelope. Normal player/replay drive calls keep their existing smoothing. */
    public void driveWithSteeringBounds(float dt,float desiredThrottle,float brake,float desiredSteer,AiSteering.Bounds bounds) {
        drive(dt,desiredThrottle,brake,desiredSteer,java.util.Objects.requireNonNull(bounds));
    }
    private void drive(float dt,float desiredThrottle,float brake,float desiredSteer,AiSteering.Bounds bounds) {
        if(!readLimitedMotion()) {
            holdForRecovery();
            return;
        }
        float step = Float.isFinite(dt) && dt > 0 ? dt : 0;
        boolean valid = Float.isFinite(desiredThrottle) && Float.isFinite(brake) && Float.isFinite(desiredSteer);
        throttle = valid ? clamp(desiredThrottle, -1, 1) : 0;
        braking = valid ? clamp(brake, 0, 1) : 1;
        steeringDemand = valid ? clamp(desiredSteer, -1, 1) : 0;
        if (bounds != null) steeringDemand = bounds.clamp(steeringDemand);
        if (!valid) {
            steeringInput = steeringDemand;
        } else if (bounds == null) {
            steeringInput += (steeringDemand - steeringInput) * Math.min(1, step * 7.5f);
        } else {
            steeringInput = bounds.smooth(steeringInput, steeringDemand, step);
        }
        float speed = limitVelocity.length();
        float maxSteer = Math.max(.11f, .44f / (1 + speed * .044f));
        steering = steeringInput * maxSteer;
        body.steer(steering);
        float signedSpeed = limitVelocity.dot(limitForward);
        float factor = Math.max(.12f, 1 - speed / (spec.maxSpeedMps * 1.18f));
        float force = spec.engineForce * tuning.power() * factor * throttle;
        if (speed >= spec.maxSpeedMps * tuning.power() || (throttle < 0 && signedSpeed <= -SpeedLimiter.REVERSE_MPS)) force = 0;
        body.accelerate(0, 0);
        body.accelerate(1, 0);
        body.accelerate(2, force);
        body.accelerate(3, force);
        float impulse=spec.brakeForce * tuning.brakes() * braking;
        // Wheels 0/1 are front, 2/3 rear. Keep the four-wheel total constant.
        for(int wheel=0;wheel<4;wheel++) body.brake(wheel,2*impulse*(wheel<2?tuning.brakeBias():1-tuning.brakeBias()));
        if (aeroStep==null && speed > .4f) {
            float drag = spec.dragCoefficient * (.55f + .45f * tuning.wing()) * speed;
            body.applyCentralForce(limitVelocity.mult(-drag));
            body.applyCentralForce(new Vector3f(0, -Math.min(5500, spec.downforceCoefficient * tuning.wing() * speed * speed), 0));
        }
    }
    public synchronized void recover(Vector3f location, Vector3f forward) {
        interruptWheelWindow(WheelObservationWindow.Status.RECOVERED);
        body.setPhysicsLocation(location.add(0, RecoveryPlanner.SPAWN_LIFT, 0));
        body.setPhysicsRotation(new Quaternion().fromAngleAxis((float) Math.atan2(forward.x, forward.z), Vector3f.UNIT_Y));
        body.setLinearVelocity(Vector3f.ZERO);
        body.setAngularVelocity(Vector3f.ZERO);
        body.clearForces();
        body.resetSuspension();
        body.steer(0); body.accelerate(0); body.brake(0);
        steeringDemand = steeringInput = steering = throttle = braking = 0;
        observation=null;
        var wheelReader=wheelState;if(wheelReader!=null) wheelReader.clear();
        if(aeroStep!=null) aeroStep.clear();
    }
    public void installCup(SceneFactory factory) {
        if (cupAssembly != null) return;
        cupAssembly = new Node("X05 cup stability component");
        cupAssembly.setLocalTranslation(.49f, .69f, .46f);
        node.attachChild(cupAssembly);
        factory.cylinder(cupAssembly, "cup holder", 0, -.04f, 0, .16f, .06f, 0x243943);
        factory.cylinder(cupAssembly, "cup", 0, .12f, 0, .12f, .28f, 0xf3ecda);
        cupWater = factory.cylinder(cupAssembly, "virtual water surface", 0, .269f, 0, .105f, .015f, 0x40bdde);
        factory.box(cupAssembly, "cup handle", .15f, .13f, 0, .05f, .07f, .025f, 0xf3ecda);
    }
    public boolean hasCup() { return cupAssembly != null; }
    public void setCupSpill(float spill) {
        if (cupWater != null) cupWater.setLocalTranslation(0, .269f - Math.min(100, spill) / 100 * .08f, 0);
    }
    public float speed() { return velocity().length(); }
    public Vector3f position() { return position(null); }
    public Vector3f position(Vector3f target) { return body.getPhysicsLocation(target); }
    public Vector3f velocity() { return velocity(null); }
    public Vector3f velocity(Vector3f target) { return body.getLinearVelocity(target); }
    public Vector3f forward() { return body.getPhysicsRotation(null).mult(Vector3f.UNIT_Z); }
    public Vector3f forward(Quaternion rotationTarget,Vector3f target) {
        return body.getPhysicsRotation(rotationTarget).mult(Vector3f.UNIT_Z,target);
    }
    public Vector3f up() { return body.getPhysicsRotation(null).mult(Vector3f.UNIT_Y); }
    public void applyOffRoadDamping(TrackLayout track,Vector3f position) {
        if(closed || !Vector3f.isValidVector(position)) return;
        int segment=track.findSegmentWithinDistance(position,track.width*.5f+2,roadDampingSegment);
        if(segment>=0) {
            roadDampingSegment=segment;
            return;
        }
        body.getLinearVelocity(roadDamping);
        roadDamping.multLocal(-spec.massKg*.6f);
        if(Float.isFinite(roadDamping.lengthSquared())) body.applyCentralForce(roadDamping);
    }
    public void halt() { body.accelerate(0); body.brake(spec.brakeForce * 2); }
    void holdForRecovery() {
        halt();
        body.steer(0);
        steeringDemand = steeringInput = steering = throttle = 0;
        braking = 1;
    }
    static boolean usableRotation(Quaternion rotation) {
        // A native rigid body uses a unit quaternion. Reject values that make its inverse or chord unusable.
        float norm=rotation==null?Float.NaN:rotation.norm();
        return Float.isFinite(norm) && Math.abs(norm-1)<=.01f;
    }
    private boolean readLimitedMotion() {
        body.getLinearVelocity(limitVelocity);
        body.getPhysicsRotation(limitRotation);
        if(!usableRotation(limitRotation)) return false;
        limitRotation.mult(Vector3f.UNIT_Z,limitForward);
        boolean valid=Float.isFinite(limitVelocity.lengthSquared());
        if(SpeedLimiter.apply(limitVelocity,limitForward,spec.maxSpeedMps*tuning.power())) body.setLinearVelocity(limitVelocity);
        return valid;
    }
    @Override public void prePhysicsTick(PhysicsSpace space,float dt) {if(wheelWindow!=null) armWheelWindow(space,dt);if(!closed && aeroStep!=null && space==this.space && Float.isFinite(dt) && dt>0) aeroStep.apply(this);}
    private synchronized void armWheelWindow(PhysicsSpace owner,float dt) {
        if(!closed && owner==space && Float.isFinite(dt) && dt>0 && dt<=.25f && wheelWindow!=null && wheelWindow.status()==WheelObservationWindow.Status.OPEN) {armedWheelWindow=wheelWindow;armedWheelStep=dt;}
    }
    private synchronized void observeNativeWheels(PhysicsSpace owner,float dt,boolean motionAvailable) {
        if(closed || owner!=space || !Float.isFinite(dt) || dt<=0 || dt>.25f) return;
        var wheelReader=wheelState;var window=wheelWindow;
        if(wheelReader!=null) {
            if(motionAvailable) {
                var controller=body.getController();
                wheelReader.capture(controller==null?0:controller.nativeId(),dt);
            } else {
                wheelReader.clear();
            }
        }
        if(window!=null && armedWheelWindow==window && Float.compare(armedWheelStep,dt)==0) window.advance(dt,wheelReader==null?null:wheelReader.reading(),spec,tuning);
        armedWheelWindow=null;
    }
    @Override public void physicsTick(PhysicsSpace space,float dt) {
        if(!readLimitedMotion()) {
            observation=null;
            if(aeroStep!=null) aeroStep.clear();
            holdForRecovery();
            if(wheelState!=null || wheelWindow!=null) observeNativeWheels(space,dt,false);
            return;
        }
        // Read only after the native vehicle step; no extra ray casts, forces or wheel updates.
        float front=0,rear=0;
        for(int i=0;i<4;i++) {var wheel=body.getWheel(i);float compression=wheel.getRestLength()-wheel.getSuspensionLength();if(i<2) front+=compression;else rear+=compression;}
        body.getAngularVelocity(observedAngular);
        try { observation=new VehicleObservation(front*.5f,rear*.5f,(body.getWheel(0).getSteerAngle()+body.getWheel(1).getSteerAngle())*.5f,observedAngular.y); }
        catch(IllegalArgumentException unavailable) { observation=null; }
        if(wheelState!=null || wheelWindow!=null) observeNativeWheels(space,dt,true);
    }
    @Override public synchronized void close() {if(closed) return;closed=true;interruptWheelWindow(WheelObservationWindow.Status.CLOSED);if(wheelState!=null) wheelState.close();if(aeroStep!=null) aeroStep.clear();space.removeTickListener(this);space.remove(body); node.removeControl(body); node.removeFromParent();}
    private static float clamp(float x, float min, float max) { return Math.max(min, Math.min(max, x)); }
}
