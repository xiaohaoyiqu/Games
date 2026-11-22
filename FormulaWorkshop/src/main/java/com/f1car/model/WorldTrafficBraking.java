package com.f1car.model;

import java.util.List;
import java.util.Objects;

/** Additional forward-risk braking; it never selects a passing lane or takes collision authority. */
public final class WorldTrafficBraking {
    public static final float EMERGENCY_TIME=.75f;
    public static final float PLAN_DECELERATION=12;
    private WorldTrafficBraking() {}
    public record Decision(boolean available,float firstEntry,int threats,int skippedBodies,int rotationEnvelopeThreats,
            int unresolvedForwardBodies) {
        public Decision(boolean available,float firstEntry,int threats,int skippedBodies) {this(available,firstEntry,threats,skippedBodies,0,0);}
        public Decision(boolean available,float firstEntry,int threats,int skippedBodies,int rotationEnvelopeThreats) {
            this(available,firstEntry,threats,skippedBodies,rotationEnvelopeThreats,0);
        }
        public boolean blocked() {return threats>0;}
        public boolean emergency() {return blocked() && firstEntry<=EMERGENCY_TIME;}
        public float speedLimit() {return blocked()?0:Float.POSITIVE_INFINITY;}
        public boolean releaseObservationComplete() {return available && unresolvedForwardBodies==0;}
        /** Do not let an older, faster speed plan keep accelerating into a newly observed world risk. */
        public float limitPlan(float planned,float speed,float dt) {
            if(!Float.isFinite(planned) || planned<0 || !Float.isFinite(speed) || speed<0 || !Float.isFinite(dt) || dt<0) throw new IllegalArgumentException("Invalid world speed plan");
            return blocked()?Math.min(planned,Math.max(0,speed-PLAN_DECELERATION*dt)):planned;
        }
    }
    /** No peers need no extra chassis observation. */
    public static final Decision CLEAR=new Decision(true,Float.POSITIVE_INFINITY,0,0);

    /** Reuses symmetric chassis windows for the actual body observations of one control frame. */
    public static final class SharedWindows {
        // Six unordered pairs cover a four-car grid; further pairs can still be evaluated directly.
        private static final int MAX_PAIRS=6;
        private BodyPair[] pairs;
        private int size;

        public Decision assess(TrafficBodyMotion.Body self,List<TrafficBodyMotion.Body> traffic,
                List<RaceTrafficPlanner.Vehicle> peerMotion) {
            return WorldTrafficBraking.assess(self,traffic,peerMotion,this);
        }

        BodyPair pair(TrafficBodyMotion.Body self,TrafficBodyMotion.Body other) {
            for(int i=0;i<size;i++) {
                var pair=pairs[i];
                if((pair.first==self && pair.second==other) || (pair.first==other && pair.second==self)) return pair;
            }
            if(size==MAX_PAIRS) return null;
            if(pairs==null) pairs=new BodyPair[MAX_PAIRS];
            var pair=new BodyPair(self,other);
            pairs[size++]=pair;
            return pair;
        }
    }

    static final class BodyPair {
        private final TrafficBodyMotion.Body first,second;
        private final TrafficBodyMotion.Window padded;
        private boolean checkedUnpadded,unpaddedIntersects;

        BodyPair(TrafficBodyMotion.Body first,TrafficBodyMotion.Body second) {
            this.first=first;
            this.second=second;
            padded=TrafficBodyMotion.window(first,second);
        }

        boolean unpaddedIntersects() {
            if(!checkedUnpadded) {
                unpaddedIntersects=checkUnpadded(first,second,padded.rotationEnvelope());
                checkedUnpadded=true;
            }
            return unpaddedIntersects;
        }
    }
    /** Null bodies explicitly mean a non-upright pose unavailable to this fixed-yaw observation. */
    public static Decision assess(TrafficBodyMotion.Body self,List<TrafficBodyMotion.Body> traffic) {
        return assess(self,traffic,null);
    }
    /** Peer motion is in chassis-list order and supplies the origin and velocity for any missing pose. */
    public static Decision assess(TrafficBodyMotion.Body self,List<TrafficBodyMotion.Body> traffic,
            List<RaceTrafficPlanner.Vehicle> peerMotion) {
        return assess(self,traffic,peerMotion,null);
    }

    private static Decision assess(TrafficBodyMotion.Body self,List<TrafficBodyMotion.Body> traffic,
            List<RaceTrafficPlanner.Vehicle> peerMotion,SharedWindows shared) {
        Objects.requireNonNull(traffic);
        if(peerMotion!=null && peerMotion.size()!=traffic.size()) throw new IllegalArgumentException("Peer motion must match chassis order");
        float first=Float.POSITIVE_INFINITY;
        int threats=0,rotationThreats=0,skipped=0,unresolved=0;
        int index=0;
        for(var other:traffic) {
            int peer=index++;
            if(other==null) {
                skipped++;
                if(self!=null && peerMotion!=null) {
                    var motion=peerMotion.get(peer);
                    if(TrafficBodyMotion.mayOverlapUnoriented(self,motion.position(),motion.velocity())) unresolved++;
                }
                continue;
            }
            if(self==null) continue;
            double x=(double)other.x()-self.x(),z=(double)other.z()-self.z();
            // Rear threats do not cause an unrelated brake check by the leading car.
            if(x*self.forwardX()+z*self.forwardZ()<=0) continue;
            // The reciprocal check can reuse this window only when it also sees the first car ahead.
            var pair=shared!=null && x*other.forwardX()+z*other.forwardZ()<0?shared.pair(self,other):null;
            var window=pair==null?TrafficBodyMotion.window(self,other):pair.padded;
            if(!window.intersects()) continue;
            double closing=x*((double)other.vx()-self.vx())+z*((double)other.vz()-self.vz());
            if(window.entry()==0 && closing>=0) {
                // Center separation cannot exclude a corner rotating in. Check the whole unpadded future.
                if(!window.rotationEnvelope() && closing>0) continue;
                boolean unpadded=pair==null?checkUnpadded(self,other,window.rotationEnvelope()):pair.unpaddedIntersects();
                if(!unpadded) continue;
            }
            if(window.rotationEnvelope()) rotationThreats++;
            threats++;first=Math.min(first,window.entry());
        }
        if(self!=null && threats==0 && skipped==0) return CLEAR;
        return new Decision(self!=null,first,threats,skipped,rotationThreats,unresolved);
    }

    private static boolean checkUnpadded(TrafficBodyMotion.Body self,TrafficBodyMotion.Body other,boolean rotating) {
        return rotating?TrafficBodyMotion.unpaddedWindow(self,other).intersects():TrafficBodyMotion.unpaddedOverlap(self,other);
    }
}
