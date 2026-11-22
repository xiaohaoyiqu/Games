package com.f1car.network;

import com.f1car.model.*;
import com.f1car.scene.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.system.NativeLibraryLoader;
import java.util.*;

/** Host-owned 120 Hz native physics. Inputs, collisions, gates and penalties share one world. */
public final class RaceSession implements AutoCloseable {
    public enum Activity { RACE, PRACTICE, QUALIFYING }
    public enum VehicleRule {
        OPEN("各自配置"),STOCK("统一原厂"),HOST_SETUP("统一房主性能");
        public final String title;VehicleRule(String title) { this.title=title; }
    }
    public record Settings(Activity activity,int laps,VehicleRule vehicles,Tuning fixed) {
        public Settings(Activity activity,int laps) { this(activity,laps,VehicleRule.OPEN,Tuning.STOCK); }
        public Settings { if(activity==null) activity=Activity.RACE;if(laps!=1 && laps!=3 && laps!=5) laps=3;Objects.requireNonNull(vehicles);Objects.requireNonNull(fixed); }
        public Tuning apply(Tuning personal) {
            var performance=vehicles==VehicleRule.OPEN?personal:vehicles==VehicleRule.STOCK?Tuning.STOCK:fixed;
            return performance.livery()==personal.livery()?performance:performance.withLivery(personal.livery());
        }
    }
    public record Entry(int id, String name, Tuning tuning) {}
    public record Input(float throttle, float brake, float steer, boolean reverse) {
        public static final Input STOP = new Input(0, 1, 0, false);
        public Input {
            throttle = bounded(throttle, 0, 1); brake = bounded(brake, 0, 1); steer = bounded(steer, -1, 1);
        }
        private static float bounded(float value, float min, float max) { return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0; }
    }
    public record Pose(float x, float y, float z, float qx, float qy, float qz, float qw, float vx, float vy, float vz) {
        public Vector3f position() { return new Vector3f(x, y, z); }
        public Quaternion rotation() { return new Quaternion(qx, qy, qz, qw); }
        public Vector3f velocity() { return new Vector3f(vx, vy, vz); }
        public boolean usable() {
            float orientation=qx*qx+qy*qy+qz*qz+qw*qw;
            return Float.isFinite(x*x+y*y+z*z) && Float.isFinite(vx*vx+vy*vy+vz*vz)
                && Float.isFinite(orientation) && Math.abs(orientation-1)<=.01f;
        }
        float distanceSquared(Pose other) {
            float dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
        public Pose between(Pose before, float t) {
            float s = 1 - t;
            var q = new Quaternion().slerp(before.rotation(), rotation(), t).normalizeLocal();
            return new Pose(s * before.x + t * x, s * before.y + t * y, s * before.z + t * z,
                    q.getX(), q.getY(), q.getZ(), q.getW(),
                    s * before.vx + t * vx, s * before.vy + t * vy, s * before.vz + t * vz);
        }
    }
    public record CarState(int id, String name, Tuning tuning, boolean connected, boolean retired, int recovery,
                           Pose pose, LapProgress.State progress, float penalty, float throttle, float braking, float steering, float distance) {
        private CarState disconnected() {
            return new CarState(id,name,tuning,false,!progress.finished(),recovery,pose,progress,penalty,throttle,braking,steering,distance);
        }
        public double result() { return progress.finished() ? progress.finishTime() + penalty : Double.POSITIVE_INFINITY; }
        public double qualifyingTime() {
            return QualifyingSession.validTime(progress.completedLaps(),progress.finished(),progress.lastLapValid(),
                    progress.lastLap(),progress.finishTime())?progress.lastLap():-1;
        }
    }
    public record Frame(long session,long sequence,Settings settings,double elapsed,float countdown,boolean complete,List<CarState> cars,int trackIndex,String trackKey) {
        public Frame(long session,long sequence,Settings settings,double elapsed,float countdown,boolean complete,List<CarState> cars) {
            this(session,sequence,settings,elapsed,countdown,complete,cars,0,RoomTrack.builtin(TrackCatalog.DEFAULT_ID).key());
        }
        public Frame { cars = List.copyOf(cars); }
        public boolean acceptsInputFrom(int id) {
            if (complete) return false;
            for (var car : cars) {
                if (car.id() == id) return car.connected() && !car.retired() && !car.progress().finished();
            }
            return false;
        }
        public List<CarState> standings() {
            if(settings.activity()==Activity.QUALIFYING) return cars.stream().sorted((a,b)->QualifyingSession.compareTimes(
                    a.qualifyingTime(),a.progress().finishTime(),a.id(),b.qualifyingTime(),b.progress().finishTime(),b.id())).toList();
            boolean classified=complete && settings.activity()==Activity.RACE;
            return cars.stream().sorted(Comparator.comparingInt((CarState car) -> car.progress.finished() ? 0 : car.retired && !classified ? 2 : 1)
                .thenComparingDouble(car -> car.progress.finished() ? Math.round(car.result()*1000)/1000d
                        : -(classified?Math.max(0,car.distance):car.distance)).thenComparingInt(CarState::id)).toList();
        }
    }
    public static final int TICKS_PER_SECOND = 120;
    private static final double STEP_SECONDS = 1.0 / TICKS_PER_SECOND;
    private static final float PHYSICS_STEP = (float) STEP_SECONDS;
    static final long INPUT_TIMEOUT_NANOS = 600_000_000L;
    private static final double INPUT_TIMEOUT_SECONDS = INPUT_TIMEOUT_NANOS / 1_000_000_000.0;
    private static final class Driver {
        final Entry entry;
        final FormulaCar car;
        final LapProgress progress;
        final Vector3f before=new Vector3f(),after=new Vector3f();
        Vector3f cutoffPosition;
        Input input = Input.STOP;
        double receivedAt, resetAt = -10;
        long remoteReceiptNanos;
        boolean remoteInput;
        boolean connected = true, retired;
        CarState detachedState;
        int recovery;
        float penalty;
        Driver(Entry entry, FormulaCar car, LapProgress progress) { this.entry = entry; this.car = car; this.progress = progress; }
    }
    private final TrackLayout track;
    private final RecoveryPlanner.LocalSearch recoverySearch;
    private final Vector3f recoveryGravity=new Vector3f();
    private final int trackIndex;
    private final String trackKey;
    private final PhysicsSpace space;
    private final WorldScene world;
    private final List<Driver> drivers = new ArrayList<>();
    private final Settings settings;
    private final long id;
    private double elapsed, clock, accumulator, firstFinish = Double.POSITIVE_INFINITY;
    private int countdownTicks;
    private long sequence;
    private List<CarState> resultCars;
    private boolean complete, closed;

    public RaceSession(long id, Settings settings, List<Entry> entries) {
        this(id,settings,entries,TrackLayout.circuit(),0,RoomTrack.builtin(TrackCatalog.DEFAULT_ID).key(),entries.stream().map(Entry::id).toList());
    }
    public RaceSession(long id,Settings settings,List<Entry> entries,TrackLayout track,int trackIndex,String trackKey,List<Integer> grid) {
        if(entries==null || entries.size()<2 || entries.size()>4 || entries.stream().anyMatch(e->e==null || e.id()<0 || e.name()==null || e.tuning()==null)
            || entries.stream().map(Entry::id).distinct().count()!=entries.size() || grid==null || grid.size()!=entries.size() || new HashSet<>(grid).size()!=grid.size()
            || !new HashSet<>(grid).equals(new HashSet<>(entries.stream().map(Entry::id).toList()))) throw new IllegalArgumentException("Two to four unique racers and grid required");
        this.track=Objects.requireNonNull(track);this.trackIndex=trackIndex;this.trackKey=Objects.requireNonNull(trackKey);
        recoverySearch=new RecoveryPlanner.LocalSearch(track);
        if(trackIndex<0 || trackIndex>2 || !trackKey.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid session map");
        this.id = id; this.settings = Objects.requireNonNull(settings);
        NativeLibraryLoader.loadNativeLibrary("bulletjme", true);
        space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT); space.setAccuracy(PHYSICS_STEP);
        var factory = new SceneFactory(new DesktopAssetManager(true));
        world = new WorldScene(factory, space, track, false);
        var spec = VehicleSpec.load();
        try {
            for (int i = 0; i < entries.size(); i++) {
                var source=entries.get(i);Entry entry=new Entry(source.id(),source.name(),settings.apply(source.tuning()));
                var car = new FormulaCar(entry.name, factory, space, spec, entry.tuning);
                int position=grid.indexOf(entry.id());var spawn=track.sample(settings.activity()==Activity.QUALIFYING?-18-position*80:-8-position*7);
                car.recover(spawn.position().add(spawn.right().mult(settings.activity()==Activity.QUALIFYING?0:position%2==0?-2:2)),spawn.tangent());
                drivers.add(new Driver(entry,car,new LapProgress(track,settings.activity==Activity.QUALIFYING?1:settings.activity==Activity.RACE?settings.laps:99999)));
            }
        } catch (RuntimeException error) { close(); throw error; }
        countdownTicks=settings.activity==Activity.PRACTICE?0:4*TICKS_PER_SECOND;
    }
    public List<FormulaCar> cars() { return drivers.stream().map(d -> d.car).toList(); }
    public void input(int id, Input input) {
        acceptInput(id, input);
    }
    void receiveRemoteInput(int id, Input input, long receiptNanos, long now) {
        var driver = acceptInput(id, input);
        if (driver == null) return;
        driver.remoteInput = true;
        driver.remoteReceiptNanos = receiptNanos;
        driver.receivedAt -= Math.max(0, now - receiptNanos) / 1_000_000_000.0;
    }
    private Driver acceptInput(int id, Input input) {
        for (var d : drivers) if (d.entry.id == id && d.connected && !d.retired && !d.progress.finished()) {
            d.input = Objects.requireNonNull(input);
            d.receivedAt = clock;
            d.remoteInput = false;
            return d;
        }
        return null;
    }
    public boolean recover(int id) {
        for (var d : drivers) {
            if (d.entry.id != id || d.retired || d.progress.finished()) continue;
            // RESET releases the previous controls even when the car cannot be moved.
            d.input = Input.STOP;
            d.remoteInput = false;
            if (clock - d.resetAt < 1) return false;
            if(settings.activity()==Activity.QUALIFYING) { d.progress.invalidate();d.retired=true;d.recovery++;detach(d);return true; }
            var occupied=new ArrayList<RecoveryPlanner.LocalTraffic>(drivers.size()-1);
            for(var other:drivers) {
                if(other!=d && other.detachedState==null)
                    occupied.add(new RecoveryPlanner.LocalTraffic(other.car.position(),other.car.velocity(),other.entry.id()));
            }
            var recovery=recoverySearch.findWithSweeps(d.progress.recoveryDistance(),occupied,space.getGravity(recoveryGravity).y,false);
            if(recovery.spawn().isEmpty()) return false;
            var spawn=recovery.spawn().get();
            d.car.recover(spawn.position(),spawn.tangent());d.progress.invalidate();
            d.resetAt = clock; d.recovery++;
            if (settings.activity == Activity.RACE) d.penalty += 5;
            return true;
        }
        return false;
    }
    public void disconnect(int id) {
        for (var d : drivers) if (d.entry.id == id && d.connected) {
            d.connected = false; d.retired = !d.progress.finished(); d.input = Input.STOP;
            if(d.detachedState!=null) d.detachedState=d.detachedState.disconnected();
            else detach(d);
            resultCars=null;
        }
    }
    public void retire(int id) {
        for (var d : drivers) if (d.entry.id == id && !d.progress.finished()) { d.retired = true; d.input = Input.STOP; detach(d); }
    }
    private void detach(Driver driver) {
        if (driver.detachedState!=null) return;
        driver.car.body.setLinearVelocity(Vector3f.ZERO); driver.car.body.setAngularVelocity(Vector3f.ZERO);
        driver.car.throttle = driver.car.braking = driver.car.steering = 0;
        var finalState=observe(driver);
        space.remove(driver.car.body);
        driver.detachedState=finalState;
    }
    public void update(double dt) {
        update(dt, System.nanoTime());
    }
    void update(double dt, long now) {
        if (closed || !Double.isFinite(dt) || dt <= 0) return;
        // Simulation catch-up is capped. A stalled host must still release expired guest controls before its first step.
        for (var driver : drivers) {
            if (driver.remoteInput && now - driver.remoteReceiptNanos >= INPUT_TIMEOUT_NANOS) {
                driver.input = Input.STOP;
                driver.remoteInput = false;
            }
        }
        accumulator += Math.min(.25, dt);
        while (accumulator + 1e-12 >= STEP_SECONDS) { tick(); accumulator -= STEP_SECONDS; }
    }
    private void tick() {
        sequence++;
        clock = sequence / (double) TICKS_PER_SECOND;
        if (complete) return;
        boolean starting = countdownTicks > 0;
        double deadline = switch (settings.activity()) {
            case QUALIFYING -> QualifyingSession.LIMIT;
            case RACE -> EventRaceClock.deadlineSeconds(settings.laps(), firstFinish);
            case PRACTICE -> Double.POSITIVE_INFINITY;
        };
        double frameStart=elapsed,observedAt=frameStart+STEP_SECONDS;
        if (starting) countdownTicks--;
        else elapsed=Math.min(observedAt,deadline);
        for (var d : drivers) {
            if (!d.connected || d.detachedState!=null) continue;
            if(!starting) d.car.body.getPhysicsLocation(d.before);
            Input input = starting || d.retired || d.progress.finished() || clock - d.receivedAt > INPUT_TIMEOUT_SECONDS ? Input.STOP : d.input;
            float throttle = input.throttle, brake = input.brake;
            if (input.reverse) { if (d.car.velocity().dot(d.car.forward()) > 1) brake = 1; else throttle = -.55f; }
            d.car.drive(PHYSICS_STEP, throttle, brake, input.steer);
        }
        space.update(PHYSICS_STEP, 1);
        for(var d:drivers) {
            if(!d.connected || d.detachedState!=null || d.retired || starting) continue;
            d.car.body.getPhysicsLocation(d.after);
            if(observedAt>deadline)
                d.cutoffPosition=LapProgress.positionAtDeadline(d.before,d.after,frameStart,observedAt,deadline);
            d.progress.updateTimed(d.before,d.after,d.cutoffPosition,frameStart,observedAt,deadline);
            d.car.applyOffRoadDamping(track,d.after);
            if (d.progress.finished()) {
                firstFinish=Math.min(firstFinish,d.progress.finishTime());
                detach(d);
            }
        }
        if(settings.activity()!=Activity.PRACTICE) {
            if(elapsed>=deadline) for(var d:drivers) if(!d.progress.finished()) { d.retired=true;detach(d); }
            complete=drivers.stream().allMatch(d->d.retired || d.progress.finished());
        }
    }
    public Frame frame() {
        var cars=resultCars;
        if(cars==null) {
            cars=new ArrayList<>(drivers.size());
            for(var d:drivers) cars.add(d.detachedState!=null?d.detachedState:observe(d));
        }
        var frame=new Frame(id,sequence,settings,elapsed,countdownTicks/(float)TICKS_PER_SECOND,complete,cars,trackIndex,trackKey);
        if(complete) resultCars=frame.cars();
        return frame;
    }
    private static CarState observe(Driver d) {
        var p = d.cutoffPosition==null?d.car.position():d.cutoffPosition;
        var v = d.car.velocity(); var q = d.car.body.getPhysicsRotation(null);
        return new CarState(d.entry.id, d.entry.name, d.entry.tuning, d.connected, d.retired, d.recovery,
                new Pose(p.x,p.y,p.z,q.getX(),q.getY(),q.getZ(),q.getW(),v.x,v.y,v.z), d.progress.snapshot(),
                d.penalty,d.car.throttle,d.car.braking,d.car.steering,d.progress.raceDistance(p));
    }
    @Override public void close() {
        if (closed) return; closed = true;
        for (var d : drivers) { if (d.detachedState!=null) { d.car.node.removeControl(d.car.body); d.car.node.removeFromParent(); } else d.car.close(); }
        world.close(); space.destroy();
    }
}
