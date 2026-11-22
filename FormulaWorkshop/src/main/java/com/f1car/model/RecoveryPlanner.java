package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Search behind the last legal gate; an occupied recovery zone waits instead of spawning into traffic. */
public final class RecoveryPlanner {
    public static final int UNKNOWN_DRIVER=-1;
    public record Spawn(Vector3f position,Vector3f tangent) {}
    public record Traffic(Vector3f position,Vector3f velocity,int driverId) {
        public Traffic(Vector3f position,Vector3f velocity) {this(position,velocity,UNKNOWN_DRIVER);}
        public Traffic {
            if(driverId<UNKNOWN_DRIVER) throw new IllegalArgumentException("Invalid recovery traffic driver");
        }
        public boolean complete() {
            return position!=null && velocity!=null && Vector3f.isValidVector(position) && Vector3f.isValidVector(velocity);
        }
    }
    /** Captures the axes usable by the local sweep before checking its candidate sites. */
    public static final class LocalTraffic {
        private final float x,y,z,vx,vy,vz;
        private final double horizontalSpeedSquared;
        private final double minX,maxX,minZ,maxZ;
        private final boolean knownX,knownY,knownZ;
        private final int driverId;

        public LocalTraffic(Vector3f position,Vector3f velocity,int driverId) {
            if(driverId<UNKNOWN_DRIVER) throw new IllegalArgumentException("Invalid recovery traffic driver");
            this.driverId=driverId;
            boolean present=position!=null && velocity!=null;
            knownX=present && Float.isFinite(position.x) && Float.isFinite(velocity.x);
            knownY=present && Float.isFinite(position.y) && Float.isFinite(velocity.y);
            knownZ=present && Float.isFinite(position.z) && Float.isFinite(velocity.z);
            x=knownX?position.x:0;
            y=knownY?position.y:0;
            z=knownZ?position.z:0;
            vx=knownX?velocity.x:0;
            vy=knownY?velocity.y:0;
            vz=knownZ?velocity.z:0;
            horizontalSpeedSquared=(double)vx*vx+(double)vz*vz;
            double endX=x+(double)vx*SWEEP_SECONDS,endZ=z+(double)vz*SWEEP_SECONDS;
            // An unknown axis cannot exclude a site. Pad known bounds outwards for rounding.
            minX=knownX?Math.nextDown(Math.min(x,endX)-PLANAR_CLEARANCE):Double.NEGATIVE_INFINITY;
            maxX=knownX?Math.nextUp(Math.max(x,endX)+PLANAR_CLEARANCE):Double.POSITIVE_INFINITY;
            minZ=knownZ?Math.nextDown(Math.min(z,endZ)-PLANAR_CLEARANCE):Double.NEGATIVE_INFINITY;
            maxZ=knownZ?Math.nextUp(Math.max(z,endZ)+PLANAR_CLEARANCE):Double.POSITIVE_INFINITY;
        }

        private LocalTraffic(Traffic traffic) {this(traffic.position(),traffic.velocity(),traffic.driverId());}
        public boolean complete() {return knownX && knownY && knownZ;}
    }
    public enum Failure { NO_STATIC_CANDIDATE, TRAFFIC_PREDICTION, INCOMPLETE_TRAFFIC }
    public static final int MAX_SITES=18;
    public record Site(float x,float z) {
        public Site {
            if(!Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>10000 || Math.abs(z)>10000)
                throw new IllegalArgumentException("Invalid recovery site");
            if(x==0) x=0;
            if(z==0) z=0;
        }
    }
    public record FailedSites(List<Site> points,int geometryMask,int predictedMask,List<Integer> blockingDrivers) {
        public FailedSites(List<Site> points,int geometryMask,int predictedMask) {
            this(points,geometryMask,predictedMask,List.of());
        }
        public FailedSites {
            points=List.copyOf(points);
            blockingDrivers=List.copyOf(blockingDrivers);
            if(points.isEmpty() || points.size()>MAX_SITES || geometryMask<0 || predictedMask<0
                    || (geometryMask&predictedMask)!=0 || (geometryMask|predictedMask)>=(1<<points.size()))
                throw new IllegalArgumentException("Invalid recovery site decisions");
            if(!blockingDrivers.isEmpty() && blockingDrivers.size()!=points.size())
                throw new IllegalArgumentException("Recovery blocker count does not match candidate count");
            for(int index=0;index<blockingDrivers.size();index++) {
                int driver=blockingDrivers.get(index);
                if(driver<UNKNOWN_DRIVER || driver!=UNKNOWN_DRIVER && (geometryMask&(1<<index))!=0)
                    throw new IllegalArgumentException("Invalid recovery site blocker");
            }
        }
        public Failure reasonAt(int index) {
            Objects.checkIndex(index,points.size());
            int bit=1<<index;
            if((geometryMask&bit)!=0) return Failure.NO_STATIC_CANDIDATE;
            return (predictedMask&bit)!=0?Failure.TRAFFIC_PREDICTION:Failure.INCOMPLETE_TRAFFIC;
        }
        public int blockingDriverAt(int index) {
            Objects.checkIndex(index,points.size());
            return blockingDrivers.isEmpty()?UNKNOWN_DRIVER:blockingDrivers.get(index);
        }
    }
    public record Placement(int index,Site site) {
        public Placement {
            if(index<0 || index>=MAX_SITES || site==null) throw new IllegalArgumentException("Invalid selected recovery site");
        }
    }

    public record LocalLookup(Optional<Spawn> spawn,boolean reusedCandidates,Failure failure,FailedSites failedSites,
                              Placement placement) {
        public LocalLookup(Optional<Spawn> spawn,boolean reusedCandidates,Failure failure) {
            this(spawn,reusedCandidates,failure,null,null);
        }
        public LocalLookup(Optional<Spawn> spawn,boolean reusedCandidates,Failure failure,FailedSites failedSites) {
            this(spawn,reusedCandidates,failure,failedSites,null);
        }
        public LocalLookup {
            if(spawn.isPresent()==(failure!=null) || spawn.isPresent() && failedSites!=null)
                throw new IllegalArgumentException("Inconsistent local recovery result");
            if(placement!=null) {
                var selected=spawn.orElseThrow(()->new IllegalArgumentException("Failed search has no selected site")).position();
                if(placement.site().x()!=selected.x || placement.site().z()!=selected.z)
                    throw new IllegalArgumentException("Selected site does not match the recovery spawn");
            }
        }
    }

    public static final float SPAWN_LIFT=.75f;
    private static final float[] LANES={0,-2.5f,2.5f};
    private static final float SWEEP_SECONDS=.75f, PLANAR_CLEARANCE=6;
    private static final double VERTICAL_CLEARANCE=
            Math.hypot(Math.hypot(TrafficBodyMotion.HALF_WIDTH,TrafficBodyMotion.HALF_LENGTH),
                TrafficBodyMotion.CENTER_HEIGHT+TrafficBodyMotion.HALF_HEIGHT)
            + TrafficBodyMotion.CENTER_HEIGHT+TrafficBodyMotion.HALF_HEIGHT+TrafficBodyMotion.HEIGHT_PADDING;

    /** Static road and obstacle geometry for one local driving session. */
    public static final class LocalSearch {
        private static final int MAX_ANCHORS=32;
        private final TrackLayout track;
        private final List<TrackScenery.Wall> solids;
        private record Candidates(List<Spawn> sites,List<Site> points,int geometryMask) {}
        private final LinkedHashMap<Float,Candidates> prepared=new LinkedHashMap<>(8,.75f,true);

        public LocalSearch(TrackLayout track) {
            this.track=Objects.requireNonNull(track);
            solids=TrackScenery.solids(track);
        }

        public boolean matches(TrackLayout current) {return current==track;}

        public LocalLookup find(float distance,List<Traffic> occupied,float gravityY) {
            return find(distance,occupied,gravityY,false);
        }

        public LocalLookup find(float distance,List<Traffic> occupied,float gravityY,boolean captureSites) {
            return findWithSweeps(distance,occupied.stream().map(LocalTraffic::new).toList(),gravityY,captureSites);
        }

        public LocalLookup findWithSweeps(float distance,List<LocalTraffic> occupied,float gravityY,boolean captureSites) {
            var candidates=prepared.get(distance);
            boolean reused=candidates!=null;
            if(!reused) {
                candidates=prepareCandidates(distance);
                prepared.put(distance,candidates);
                if(prepared.size()>MAX_ANCHORS) prepared.remove(prepared.keySet().iterator().next());
            }

            int geometry=candidates.geometryMask(),predicted=0;
            var vertical=new VerticalSweep(gravityY);
            ArrayList<Integer> blockingDrivers=null;
            for(int index=0;index<candidates.sites().size();index++) {
                int bit=1<<index;
                if((geometry&bit)!=0) continue;
                var candidate=candidates.sites().get(index);
                var blocker=blockingTraffic(candidate.position(),occupied,vertical);
                if(blocker==null) {
                    var spawn=new Spawn(candidate.position().clone(),candidate.tangent().clone());
                    var placement=captureSites?new Placement(index,candidates.points().get(index)):null;
                    return new LocalLookup(Optional.of(spawn),reused,null,null,placement);
                }
                if(vertical.known() && blocker.complete()) predicted|=bit;
                if(captureSites && blocker.driverId!=UNKNOWN_DRIVER) {
                    if(blockingDrivers==null)
                        blockingDrivers=new ArrayList<>(Collections.nCopies(candidates.sites().size(),UNKNOWN_DRIVER));
                    blockingDrivers.set(index,blocker.driverId);
                }
            }
            int all=(1<<candidates.sites().size())-1;
            Failure failure;
            if(geometry==all) failure=Failure.NO_STATIC_CANDIDATE;
            else failure=(geometry|predicted)==all?Failure.TRAFFIC_PREDICTION:Failure.INCOMPLETE_TRAFFIC;
            var sites=captureSites?new FailedSites(candidates.points(),geometry,predicted,
                    blockingDrivers==null?List.of():blockingDrivers):null;
            return new LocalLookup(Optional.empty(),reused,failure,sites);
        }

        private Candidates prepareCandidates(float distance) {
            var candidates=new ArrayList<Spawn>(MAX_SITES);
            var points=new ArrayList<Site>(MAX_SITES);
            int geometry=0;
            for(int back=0;back<=40;back+=8) {
                var sample=track.sample(distance-back);
                for(float lane:LANES) {
                    var position=sample.position().add(sample.right().mult(lane));
                    if(!allowedByGeometry(track,lane,position,solids)) geometry|=1<<candidates.size();
                    candidates.add(new Spawn(position,sample.tangent()));
                    points.add(new Site(position.x,position.z));
                }
            }
            return new Candidates(List.copyOf(candidates),List.copyOf(points),geometry);
        }
    }

    private RecoveryPlanner() {}
    public static Optional<Spawn> find(TrackLayout track,float distance,List<Vector3f> occupied) {
        return findWithTraffic(track,distance,occupied.stream().map(position->new Traffic(position,Vector3f.ZERO)).toList());
    }
    public static Optional<Spawn> findWithTraffic(TrackLayout track,float distance,List<Traffic> occupied) {
        return findPlacement(track,distance,occupied,VerticalSweep.PLANAR);
    }

    /** Test the observed axes on a common time interval, allowing for any position on an unobserved axis. */
    public static Optional<Spawn> findWithLocalTraffic(TrackLayout track,float distance,List<Traffic> occupied,float gravityY) {
        return findPlacement(track,distance,occupied,new VerticalSweep(gravityY));
    }

    private static Optional<Spawn> findPlacement(TrackLayout track,float distance,List<Traffic> occupied,VerticalSweep vertical) {
        var readings=occupied.stream().map(LocalTraffic::new).toList();
        var solids=TrackScenery.solids(track);
        for(int back=0;back<=40;back+=8) {
            var sample=track.sample(distance-back);
            for(float lane:LANES) {
                var position=candidatePosition(track,sample,lane,solids);
                if(position!=null && clearOfTraffic(position,readings,vertical))
                    return Optional.of(new Spawn(position,sample.tangent()));
            }
        }
        return Optional.empty();
    }

    private static Vector3f candidatePosition(TrackLayout track,TrackLayout.Sample sample,float lane,List<TrackScenery.Wall> solids) {
        var position=sample.position().add(sample.right().mult(lane));
        return allowedByGeometry(track,lane,position,solids)?position:null;
    }

    private static boolean allowedByGeometry(TrackLayout track,float lane,Vector3f position,List<TrackScenery.Wall> solids) {
        if(Math.abs(lane)+1.4f>=track.width*.5f) return false;
        for(var solid:solids) {
            if(TrackScenery.distanceTo(position,solid)<2.5f) return false;
        }
        return true;
    }

    private static boolean clearOfTraffic(Vector3f position,List<LocalTraffic> occupied,VerticalSweep vertical) {
        for(var other:occupied) {
            if(occupiesLocal(position,other,vertical)) return false;
        }
        return true;
    }

    /** A complete prediction can reject a candidate independently of any missing readings. */
    private static LocalTraffic blockingTraffic(Vector3f position,List<LocalTraffic> occupied,VerticalSweep vertical) {
        LocalTraffic incomplete=null;
        for(var other:occupied) {
            if(!occupiesLocal(position,other,vertical)) continue;
            if(vertical.known() && other.complete()) return other;
            if(incomplete==null) incomplete=other;
        }
        return incomplete;
    }

    private record VerticalSweep(boolean known,double downward,double reversedUpward) {
        private static final VerticalSweep PLANAR=new VerticalSweep(false,0,0);
        VerticalSweep(float gravityY) {this(Float.isFinite(gravityY),Math.min(0,gravityY),-Math.max(0,gravityY));}
    }

    private static boolean occupiesLocal(Vector3f site,LocalTraffic other,VerticalSweep vertical) {
        if(site.x<other.minX || site.x>other.maxX || site.z<other.minZ || site.z>other.maxZ) return false;
        // Omitting an axis gives the smallest possible horizontal distance on each instant of the sweep.
        double x=other.knownX?(double)other.x-site.x:0;
        double z=other.knownZ?(double)other.z-site.z:0;
        double horizontalSpeedSquared=other.horizontalSpeedSquared;
        double start=0,end=SWEEP_SECONDS;
        double radiusSquared=PLANAR_CLEARANCE*PLANAR_CLEARANCE;
        if(horizontalSpeedSquared==0) {
            if(x*x+z*z>=radiusSquared) return false;
        } else {
            // Intersect the projected motion with the clearance circle and restrict it to the sweep.
            double cross=x*other.vz-z*other.vx;
            double available=radiusSquared-cross*cross/horizontalSpeedSquared;
            if(available<=0) return false;
            double closest=-(x*other.vx+z*other.vz)/horizontalSpeedSquared;
            if(!vertical.known() || !other.knownY) {
                // Within the sweep the perpendicular distance already proved occupancy.
                if(closest>0 && closest<SWEEP_SECONDS) return true;
                double at=closest<=0?0:SWEEP_SECONDS;
                double dx=x+at*other.vx,dz=z+at*other.vz;
                return dx*dx+dz*dz<radiusSquared;
            }
            double halfTime=Math.sqrt(available/horizontalSpeedSquared);
            start=Math.max(start,closest-halfTime);
            end=Math.min(end,closest+halfTime);
            if(start>=end) {
                // A positive chord can be shorter than one representable time step at its midpoint.
                if(start!=end || start!=closest) return false;
                start=Math.max(0,Math.nextDown(closest));
                end=Math.min(SWEEP_SECONDS,Math.nextUp(closest));
            }
        }

        if(!vertical.known() || !other.knownY) return true;
        // Enclose the vertical paths with and without gravity over the same horizontal interval.
        double lower=minHeight(other.y,other.vy,vertical.downward(),start,end);
        double upper=-minHeight(-other.y,-other.vy,vertical.reversedUpward(),start,end);
        return lower<=site.y+SPAWN_LIFT+VERTICAL_CLEARANCE && upper>=site.y-VERTICAL_CLEARANCE;
    }

    private static double minHeight(double y,double speed,double acceleration,double start,double end) {
        return y+Math.min(start*(speed+.5*acceleration*start),end*(speed+.5*acceleration*end));
    }
}
