package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.*;

/** Read-only visual observations. No playback API can restore physics or award results. */
public record RaceReplay(int version,String id,long recordedAt,String title,String physics,Source source,Track track,int laps,
                         boolean qualifying,List<Driver> drivers,End end,List<Frame> frames,List<Event> events,List<RecoveryWait> recoveryWaits) {
    public static final int MAX_FRAMES=30002,MAX_EVENTS=8192;
    public static final int MAX_DRIVERS=4,MAX_ANCHORS=128;
    public static final int MAX_RECOVERY_WAITS=MAX_FRAMES*4;
    public static final int MAX_RECOVERY_WAIT_MAPS=256;
    static final int RECOVERY_WAIT_VERSION=4;
    public static final double MAX_DURATION=1200;
    public static final double MAX_CONTIGUOUS_INTERVAL=.25;
    public enum Source { LOCAL,HOST,RECEIVED }
    public enum End { COMPLETED,STOPPED,LIMIT,SNAPSHOT }
    public enum Kind {
        START,LAP,RECOVERY,FINISH,RETIRE,DISCONNECT,GAP;

        public boolean interruption() {
            return this==RECOVERY || this==RETIRE || this==DISCONNECT || this==GAP;
        }
    }
    public enum RecoveryWaitEnd { RECOVERED, REQUEST_CLEARED, LAST_OBSERVATION }
    public record RecoverySearchEvidence(long staticBlocked,long predictedTraffic,long incompleteTraffic) {
        public RecoverySearchEvidence {
            if(staticBlocked<0 || predictedTraffic<0 || incompleteTraffic<0
                    || (staticBlocked|predictedTraffic|incompleteTraffic)==0)
                throw new IllegalArgumentException("Invalid replay recovery search counts");
        }
    }
    public record RecoveryWaitLocations(RecoveryPlanner.FailedSites first,Double lastTime,RecoveryPlanner.FailedSites last,
                                        RecoveryPlanner.Placement placement) {
        public RecoveryWaitLocations(RecoveryPlanner.FailedSites first,Double lastTime,RecoveryPlanner.FailedSites last) {
            this(first,lastTime,last,null);
        }
        public RecoveryWaitLocations {
            if(first==null || last==null || lastTime==null || !Double.isFinite(lastTime) || lastTime<0 || lastTime>MAX_DURATION)
                throw new IllegalArgumentException("Invalid replay recovery locations");
            if(lastTime==0) lastTime=0d;
        }
    }
    public record RecoveryWait(Integer driver,Double from,Double through,RecoveryWaitEnd end,RecoverySearchEvidence evidence,
                               RecoveryWaitLocations locations) {
        public RecoveryWait(Integer driver,Double from,Double through,RecoveryWaitEnd end) {
            this(driver,from,through,end,null,null);
        }
        public RecoveryWait(Integer driver,Double from,Double through,RecoveryWaitEnd end,RecoverySearchEvidence evidence) {
            this(driver,from,through,end,evidence,null);
        }
        public RecoveryWait {
            if(driver==null || driver<0 || from==null || through==null || end==null
                    || !Double.isFinite(from) || !Double.isFinite(through) || from<0 || through<from || through>MAX_DURATION
                    || through.doubleValue()==from.doubleValue() && end!=RecoveryWaitEnd.LAST_OBSERVATION)
                throw new IllegalArgumentException("Invalid replay recovery wait");
            if(from==0) from=0d;
            if(through==0) through=0d;
            if(locations!=null && (locations.lastTime()<from || locations.lastTime()>through
                    || end==RecoveryWaitEnd.LAST_OBSERVATION && !locations.lastTime().equals(through)
                    || locations.lastTime().equals(from) && !locations.first().equals(locations.last())
                    || locations.placement()!=null && (end!=RecoveryWaitEnd.RECOVERED || locations.lastTime()>=through)))
                throw new IllegalArgumentException("Recovery locations do not match the failed-search interval");
        }
    }
    public record Anchor(float x,float z) {
        public Anchor { if(!Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>900 || Math.abs(z)>900) throw new IllegalArgumentException("Invalid replay anchor"); }
    }
    /** Frozen road and custom scene remain viewable after a draft is changed or deleted. */
    public record Track(String id,String name,float width,List<Anchor> anchors,CircuitScene scene,TrackCatalog.Theme theme,long seed,String geometry) {
        public Track {
            if(id==null || !id.matches("[a-z0-9][a-z0-9-]{0,63}") || name==null || name.isBlank() || name.length()>80 || !Float.isFinite(width) || width<=0 || width>50 || anchors==null || anchors.size()<4 || anchors.size()>MAX_ANCHORS || theme==null || geometry==null || !geometry.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid replay map");
            anchors=List.copyOf(anchors);
        }
        public static Track freeze(TrackLayout track) { var entry=TrackCatalog.forLayout(track);return new Track(track.id,track.name,track.width,track.controlPoints().stream().map(p->new Anchor(p.x,p.z)).toList(),track.scene(),entry.theme(),entry.scenerySeed(),track.geometryKey()); }
        public TrackLayout resolve() {
            var coordinates = new float[anchors.size()][2];
            for (int i = 0; i < anchors.size(); i++) {
                var point = anchors.get(i);
                coordinates[i][0] = point.x();
                coordinates[i][1] = point.z();
            }
            var result = TrackLayout.fromAnchors(id, name, width, coordinates, scene);
            if(!result.geometryKey().equals(geometry)) throw new IllegalArgumentException("Replay road geometry mismatch");return result;
        }
    }
    public record Appearance(String vehicle,String atlas,CarLivery design,PaintRegions regions) {
        public Appearance(String vehicle,String atlas,CarLivery design) {this(vehicle,atlas,design,null);}
        public Appearance {if(vehicle==null || !vehicle.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || atlas==null || !atlas.isEmpty() && !atlas.matches("car-png-sha256-[a-f0-9]{64}") || design==null || !vehicle.equals(design.vehicle()) || regions!=null && (design.version()!=2 || !regions.vehicle().equals(vehicle) || !regions.atlas().equals(atlas))) throw new IllegalArgumentException("Invalid replay appearance");}
        public PaintRegions usableRegions(String model,String identity,int size) {return regions!=null && regions.matches(model,identity,size)?regions:null;}
    }
    public record Driver(int id,String name,Tuning tuning,DriverJournal.Look look,Appearance appearance) {
        public Driver(int id,String name,Tuning tuning,DriverJournal.Look look) {this(id,name,tuning,look,null);}
        public Driver { if(id<0 || name==null || name.isBlank() || name.length()>80 || name.chars().anyMatch(Character::isISOControl) || tuning==null) throw new IllegalArgumentException("Invalid replay driver"); }
    }
    public record Car(int id,float x,float z,float yaw,float speed,float distance,int laps,boolean valid,boolean lastValid,boolean finished,boolean retired,boolean connected,int recovery,float penalty,double finishTime,double lapTime) {
        public Car {
            if(id<0 || !Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>10000 || Math.abs(z)>10000 || !Float.isFinite(yaw) || !Float.isFinite(speed) || speed<0 || speed>300 || !Float.isFinite(distance) || Math.abs(distance)>1_000_000
                || laps<0 || laps>10000 || recovery<0 || !Float.isFinite(penalty) || penalty<0 || penalty>86400 || !Double.isFinite(finishTime) || finishTime< -1 || finishTime>86400 || !Double.isFinite(lapTime) || lapTime< -1 || lapTime>86400 || finished && finishTime<=0) throw new IllegalArgumentException("Invalid replay car");
        }
        public Vector3f position() { return new Vector3f(x,0,z); }
        public double qualifyingTime() {
            return QualifyingSession.validTime(laps,finished,lastValid,lapTime,finishTime)?lapTime:-1;
        }
        boolean jumpedFrom(Car previous) {
            // Match Vector3f.distance's float subtraction and rounded distance at the cutoff.
            double dx=x-previous.x, dz=z-previous.z;
            double squared=dx*dx+dz*dz;
            return squared>55.0*55.0 && (float)Math.sqrt(squared)>55;
        }
        boolean cutsCameraFrom(Car previous) {return recovery!=previous.recovery || jumpedFrom(previous);}
        public boolean continuous(Car after) {return finished==after.finished && poseContinuous(after);}
        boolean poseContinuous(Car after) {
            return sameParticipation(after) && !after.cutsCameraFrom(this);
        }
        boolean sameParticipation(Car after) {return id==after.id && retired==after.retired && connected==after.connected;}
        public Car interpolate(Car after,float fraction) {
            return !equals(after) && continuous(after)?interpolateContinuous(after,fraction,yawChangeTo(after)):this;
        }
        float yawChangeTo(Car after) {
            return (float)YawAngles.shortestChange(yaw,after.yaw);
        }
        Car interpolateContinuous(Car after,float fraction,float turn) {
            return interpolateContinuous(after,fraction,turn,false);
        }
        Car interpolateContinuous(Car after,float fraction,float turn,boolean passedLapMarker) {
            var timing=passedLapMarker?after:this;
            float progress=passedLapMarker && after.finished?after.distance:distance+(after.distance-distance)*fraction;
            return new Car(id,x+(after.x-x)*fraction,z+(after.z-z)*fraction,(float)(YawAngles.principal(yaw)+turn*fraction),
                    speed+(after.speed-speed)*fraction,progress,
                    timing.laps, timing.valid, timing.lastValid, timing.finished, retired, connected, recovery,
                    timing.penalty, timing.finishTime, timing.lapTime);
        }
    }
    public record Frame(double time,double elapsed,float countdown,List<Car> cars) {
        public Frame {
            if(!Double.isFinite(time) || time<0 || time>86400 || !Double.isFinite(elapsed) || elapsed<0 || elapsed>86400
                    || !Float.isFinite(countdown) || countdown<0 || countdown>5 || cars==null || cars.isEmpty() || cars.size()>MAX_DRIVERS)
                throw new IllegalArgumentException("Invalid replay frame");
            cars=List.copyOf(cars);
            for(int index=1;index<cars.size();index++) {
                int id=cars.get(index).id();
                for(int previous=0;previous<index;previous++) {
                    if(cars.get(previous).id()==id) throw new IllegalArgumentException("Duplicate replay car");
                }
            }
        }
        boolean sharesRaceClockWith(Frame after) {
            double interval=after.time-time;
            return countdown==0 && after.countdown==0 && interval>0 && interval<=MAX_CONTIGUOUS_INTERVAL
                    && Math.abs(interval-(after.elapsed-elapsed))<=1e-7;
        }
        /** Room ticks account for each interval either in the countdown or in the race clock. */
        double roomStartBetween(Frame after) {
            if(countdown<=0 || elapsed!=0 || after.countdown!=0 || after.elapsed<=0
                    || after.time-time>MAX_CONTIGUOUS_INTERVAL) return Double.NaN;
            double start=after.time-after.elapsed;
            if(start<=time || start>=after.time || Math.abs(start-time-countdown)>1e-7) return Double.NaN;
            return start;
        }
        int recoveryCount(int driver) {
            for(var car:cars) if(car.id()==driver) return car.recovery();
            throw new IllegalArgumentException("Replay roster changed");
        }
    }
    public record Event(double time,Kind kind,int driver) {
        public Event { if(!Double.isFinite(time) || time<0 || time>MAX_DURATION || kind==null || driver< -1) throw new IllegalArgumentException("Invalid replay event"); }
    }
    public RaceReplay(int version,String id,long recordedAt,String title,String physics,Source source,Track track,int laps,
                      boolean qualifying,List<Driver> drivers,End end,List<Frame> frames,List<Event> events) {
        this(version,id,recordedAt,title,physics,source,track,laps,qualifying,drivers,end,frames,events,null);
    }
    public RaceReplay {
        if(version<1 || version>RECOVERY_WAIT_VERSION || id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || recordedAt<0 || title==null || title.isBlank() || title.length()>80 || physics==null || physics.isBlank() || physics.length()>120 || source==null || track==null || laps<1 || laps>5 || drivers==null || drivers.isEmpty() || drivers.size()>MAX_DRIVERS || end==null || frames==null || frames.size()<2 || frames.size()>MAX_FRAMES || events==null || events.size()>MAX_EVENTS) throw new IllegalArgumentException("Invalid race replay");
        if(version==RECOVERY_WAIT_VERSION && (source!=Source.LOCAL || qualifying || recoveryWaits==null || recoveryWaits.size()>MAX_RECOVERY_WAITS)
                || version<RECOVERY_WAIT_VERSION && recoveryWaits!=null) throw new IllegalArgumentException("Invalid replay recovery wait format");
        drivers=List.copyOf(drivers);frames=List.copyOf(frames);events=List.copyOf(events);var ids=new HashSet<>(drivers.stream().map(Driver::id).toList());
        boolean appearance=drivers.stream().anyMatch(driver->driver.appearance()!=null),regional=drivers.stream().anyMatch(driver->driver.appearance()!=null && driver.appearance().design().version()==2);if(version==1 && appearance || version==2 && (!appearance || regional || source!=Source.LOCAL) || version==3 && (!regional || source!=Source.LOCAL)) throw new IllegalArgumentException("Replay appearance requires the matching explicit local content version");
        if(ids.size()!=drivers.size() || frames.get(0).time()!=0 || frames.get(frames.size()-1).time()<=0 || frames.get(frames.size()-1).time()>MAX_DURATION) throw new IllegalArgumentException("Invalid replay bounds");
        double previous=-1;
        for(var frame:frames) {
            if(frame.time()<=previous || frame.cars().size()!=ids.size())
                throw new IllegalArgumentException("Replay roster or times changed");
            for(var car:frame.cars()) {
                if(!ids.contains(car.id())) throw new IllegalArgumentException("Replay roster or times changed");
            }
            previous=frame.time();
        }
        previous=-1;for(var event:events) { if(event.time()<previous || event.time()>frames.get(frames.size()-1).time()+1e-8 || event.driver()!=-1 && !ids.contains(event.driver())) throw new IllegalArgumentException("Invalid replay event order");previous=event.time(); }
        if(recoveryWaits!=null) {
            recoveryWaits=List.copyOf(recoveryWaits);
            validateRecoveryWaits(recoveryWaits,ids,frames);
        }
    }
    private static void validateRecoveryWaits(List<RecoveryWait> waits,Set<Integer> drivers,List<Frame> frames) {
        double duration=frames.get(frames.size()-1).time();
        var previousByDriver=new HashMap<Integer,RecoveryWait>();
        double previousStart=-1;
        int maps=0;
        for(var wait:waits) {
            var earlier=previousByDriver.put(wait.driver(),wait);
            if(!drivers.contains(wait.driver()) || wait.from()<previousStart || wait.through()>duration
                    || earlier!=null && (earlier.through()>=wait.from() || earlier.end()==RecoveryWaitEnd.LAST_OBSERVATION))
                throw new IllegalArgumentException("Replay recovery waits are out of order or outside the recording");
            previousStart=wait.from();
            var locations=wait.locations();
            if(locations!=null) {
                if(++maps>MAX_RECOVERY_WAIT_MAPS) throw new IllegalArgumentException("Too many replay recovery maps");
                validateRecoveryBlockers(locations.first(),wait.driver(),drivers);
                validateRecoveryBlockers(locations.last(),wait.driver(),drivers);
                if(locations.placement()!=null) validateRecoveryPlacement(wait,frames);
            }
        }
    }
    private static void validateRecoveryBlockers(RecoveryPlanner.FailedSites sites,int waiting,Set<Integer> drivers) {
        for(int blocker:sites.blockingDrivers()) {
            if(blocker!=RecoveryPlanner.UNKNOWN_DRIVER && (blocker==waiting || !drivers.contains(blocker)))
                throw new IllegalArgumentException("Recovery blocker is not another replay driver");
        }
    }
    private static void validateRecoveryPlacement(RecoveryWait wait,List<Frame> frames) {
        int first=1,last=frames.size()-1;
        while(first<last) {
            int middle=(first+last)/2;
            if(frames.get(middle).time()<wait.through()) first=middle+1;
            else last=middle;
        }
        var resolved=frames.get(first);
        if(resolved.time()!=wait.through()
                || resolved.recoveryCount(wait.driver())<=frames.get(first-1).recoveryCount(wait.driver()))
            throw new IllegalArgumentException("Selected recovery site has no matching recovery frame");
    }
    public double duration() { return frames.get(frames.size()-1).time(); }
}
