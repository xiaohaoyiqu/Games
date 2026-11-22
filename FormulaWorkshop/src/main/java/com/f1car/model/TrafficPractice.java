package com.f1car.model;

import com.jme3.math.Vector3f;

/** Course rules use continuous route distance; crossing the finish seam cannot invent an overtake. */
public final class TrafficPractice {
    public enum Kind { FOLLOW, OVERTAKE, REJOIN }
    public enum State { RUNNING, SUCCESS, FAILED }
    public record Observation(Vector3f position,Vector3f opponent,float speed,float opponentSpeed,float alignment,boolean contact) {}
    private final Kind kind;
    private final TrackLayout track;
    private float previousSelf,previousOpponent;
    private final Vector3f previousPosition=new Vector3f(),previousOpponentPosition=new Vector3f();
    private float previousSpeed,previousOtherSpeed,previousAlignment,previousSeparation;
    private int selfSegment=-1,opponentSegment=-1;
    private final Interval eligible=new Interval();
    private static final double LIMIT=150;
    private double selfTravel,opponentTravel,gap,held,clock;
    private boolean initialized,merged;
    private State state=State.RUNNING;
    private String feedback="准备好后开始练习";
    public TrafficPractice(Kind kind,TrackLayout track) { this.kind=kind;this.track=track; }
    public Kind kind() { return kind; }
    public State state() { return state; }
    public boolean complete() { return state!=State.RUNNING; }
    public String feedback() { return feedback; }
    public double gap() { return gap; }
    public double held() { return held; }
    public double elapsed() { return clock; }

    /** Intersection of the rule thresholds between two successive observations. */
    private static final class Interval {
        double start,end;
        boolean endQualifies;
        void atLeast(double before,double after,double threshold,boolean strict) {
            boolean from=strict?before>threshold:before>=threshold;
            boolean to=strict?after>threshold:after>=threshold;
            if(!to) endQualifies=false;
            if(from && to) return;
            if(!from && !to) {end=-1;return;}
            double crossing=(threshold-before)/(after-before);
            if(to) start=Math.max(start,crossing);
            else end=Math.min(end,crossing);
        }
    }

    public void update(float dt,Observation observation) {
        if(complete() || dt==0) return;
        if(!Float.isFinite(dt) || dt<0 || observation==null || !Vector3f.isValidVector(observation.position())
                || !Vector3f.isValidVector(observation.opponent()) || !Float.isFinite(observation.speed()) || observation.speed()<0
                || !Float.isFinite(observation.opponentSpeed()) || observation.opponentSpeed()<0 || !Float.isFinite(observation.alignment())) {
            fail("练习车辆观测异常，按 R 重试。");return;
        }
        double from=clock;
        clock=Math.min(LIMIT,from+dt);
        if(observation.contact()) { fail("与练习车辆接触，按 R 重试并预留空间。");return; }
        if(initialized && (previousPosition.distanceSquared(observation.position())>55*55
                || previousOpponentPosition.distanceSquared(observation.opponent())>55*55)) {
            fail("车辆位置发生跳变，本次练习结束。");return;
        }
        var self=track.project(observation.position(),selfSegment);
        var other=track.project(observation.opponent(),opponentSegment);
        if(!Float.isFinite(self.separation()) || !Float.isFinite(other.separation())) {
            fail("练习车辆观测异常，按 R 重试。");return;
        }
        selfSegment=self.segment();opponentSegment=other.segment();
        double priorGap=gap;
        if(initialized) {
            selfTravel+=along(previousSelf,self.distance());opponentTravel+=along(previousOpponent,other.distance());
        } else {
            opponentTravel=along(self.distance(),other.distance());
            priorGap=opponentTravel;
            previousSeparation=self.separation();
            previousSpeed=observation.speed();previousOtherSpeed=observation.opponentSpeed();previousAlignment=observation.alignment();
        }
        gap=opponentTravel-selfTravel;
        double through=Math.min(1,(LIMIT-from)/dt);
        // The first observation establishes a pose. It cannot establish an earlier continuous hold.
        eligible.start=initialized?0:1;
        eligible.end=through;
        eligible.endQualifies=true;
        eligible.atLeast(previousSpeed,observation.speed(),6,false);
        double required;
        if(kind==Kind.FOLLOW) {
            required=20;
            eligible.atLeast(priorGap,gap,12,false);
            eligible.atLeast(-priorGap,-gap,-28,false);
            eligible.atLeast(previousOtherSpeed,observation.opponentSpeed(),6,false);
        } else if(kind==Kind.OVERTAKE) {
            required=3;
            eligible.atLeast(-priorGap,-gap,8,false);
            eligible.atLeast(previousAlignment,observation.alignment(),.5f,true);
        } else {
            required=4;
            double inside=track.width*.5f-1;
            if(!merged && self.separation()<=inside) {
                double entry=1;
                if(initialized) entry=previousSeparation<=inside?0:(previousSeparation-inside)/(previousSeparation-self.separation());
                if(entry<=through && !admitRejoin(entry,from,dt,priorGap,observation)) return;
            }
            if(!merged) eligible.end=-1;
            eligible.atLeast(-previousSeparation,-self.separation(),-inside,false);
            eligible.atLeast(previousAlignment,observation.alignment(),.7f,true);
        }
        double roadExit=Double.POSITIVE_INFINITY;
        double roadLimit=track.width*.5f+.6f;
        if((kind!=Kind.REJOIN || merged) && self.separation()>roadLimit) {
            roadExit=previousSeparation>=roadLimit?0:(roadLimit-previousSeparation)/(self.separation()-previousSeparation);
            if(!initialized) roadExit=1;
            eligible.end=Math.min(eligible.end,roadExit);
        }
        if(advanceHold(required,from,dt,priorGap,through)) return;
        if(roadExit<=through) {
            clock=Math.fma(roadExit,dt,from);gap=Math.fma(roadExit,gap-priorGap,priorGap);
            fail("驶出道路，按 R 重试并降低入弯速度。");return;
        }
        if(clock>=LIMIT) {
            gap=Math.fma(through,gap-priorGap,priorGap);
            fail("练习时间已到，按 R 回到同一场景。");return;
        }
        feedback=switch(kind) {
            case FOLLOW -> String.format(java.util.Locale.ROOT,"与领车相距 %.1f m · 保持 12–28 m：%.1f / 20 s",gap,held);
            case OVERTAKE -> String.format(java.util.Locale.ROOT,"领先 %.1f m · 超过对手 8 m 后保持：%.1f / 3 s",-gap,held);
            case REJOIN -> merged?String.format(java.util.Locale.ROOT,"已汇入 · 沿正确方向稳定行驶 %.1f / 4 s",held)
                    :String.format(java.util.Locale.ROOT,"先观察来车：相距 %.1f m，留足空间再汇入",Math.abs(gap));
        };
        initialized=true;
        previousSelf=self.distance();previousOpponent=other.distance();
        previousPosition.set(observation.position());previousOpponentPosition.set(observation.opponent());
        previousSpeed=observation.speed();previousOtherSpeed=observation.opponentSpeed();
        previousSeparation=self.separation();previousAlignment=observation.alignment();
    }

    private boolean admitRejoin(double entry,double from,float dt,double priorGap,Observation observation) {
        double closingBefore=(double)previousOtherSpeed-previousSpeed;
        double closingAfter=(double)observation.opponentSpeed()-observation.speed();
        double clearance=Math.max(8,6+Math.fma(entry,closingAfter-closingBefore,closingBefore));
        double entryGap=Math.fma(entry,gap-priorGap,priorGap);
        if(Math.abs(entryGap)<clearance) {
            clock=Math.fma(entry,dt,from);
            gap=entryGap;
            fail("汇入时距练习车辆太近，先让来车通过再汇入。");
            return false;
        }
        merged=true;
        return true;
    }

    private boolean advanceHold(double required,double from,float dt,double priorGap,double through) {
        if(eligible.start>0) held=0;
        if(eligible.end>eligible.start) {
            double duration=(eligible.end-eligible.start)*dt;
            if(held+duration>=required) {
                double finish=eligible.start+(required-held)/dt;
                clock=Math.min(LIMIT,Math.fma(finish,dt,from));
                gap=Math.fma(finish,gap-priorGap,priorGap);
                held=required;
                success(switch(kind) {
                    case FOLLOW -> "完成跟车：连续保持合适距离 20 秒，未接触或越界。";
                    case OVERTAKE -> "完成一次干净超车，并在前方稳定保持位置。";
                    case REJOIN -> "完成安全汇入，并沿正确方向稳定行驶。";
                });
                return true;
            }
            held+=duration;
        }
        if(eligible.end<through || through==1 && !eligible.endQualifies) held=0;
        return false;
    }
    private float along(float from,float to) {
        float difference=to-from;
        if(track.closed) { if(difference>track.length*.5f) difference-=track.length;else if(difference< -track.length*.5f) difference+=track.length; }
        return difference;
    }
    private void fail(String message) { state=State.FAILED;feedback=message; }
    public void abort(String message) { if(!complete()) fail(message); }
    private void success(String message) { state=State.SUCCESS;feedback=message; }
}
