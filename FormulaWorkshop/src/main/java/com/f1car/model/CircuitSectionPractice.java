package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One offline run through an editor-selected part of the current road. */
public final class CircuitSectionPractice {
    private static final String LEFT_ROUTE="车辆离开试驾路线，请调整草稿或重跑本区间。";
    public enum State { APPROACH, TIMING, FINISHED, INVALID }
    public enum GateType { START, CHECKPOINT, START_FINISH, FINISH }

    public record ReferenceTimes(double last,double best) {}

    public record GateSpeeds(float entryMps,float exitMps) {
        private static final GateSpeeds UNKNOWN=new GateSpeeds(Float.NaN,Float.NaN);
    }

    public record TimedGate(float distance,double elapsed,float speedMps) {}

    public record GatePosition(int index,GateType type,float distance) {}
    public record PassedGate(GatePosition gate,double elapsed,float speedMps) {}
    /** Latest located observation on the same unwrapped distance axis as the gates. */
    public record RouteObservation(float distance,double sessionSeconds) {}
    /** Missing gate or location data stays absent when the run interrupts before observing it. */
    public record InterruptionProgress(PassedGate lastPassed,GatePosition nextGate,RouteObservation lastLocated) {}

    public record Split(int number,GateType endpoint,double seconds,double bestSeconds,float fromDistance,float toDistance,
                        GateSpeeds speeds,GateSpeeds bestSpeeds) {
        public Split(int number,GateType endpoint,double seconds,double bestSeconds,float fromDistance,float toDistance) {
            this(number,endpoint,seconds,bestSeconds,fromDistance,toDistance,GateSpeeds.UNKNOWN,GateSpeeds.UNKNOWN);
        }
        public double differenceSeconds() {return seconds-bestSeconds;}
    }

    /** Times at one crossed gate, compared with the same gate of the fastest prior complete attempt. */
    public record GateComparison(int splitNumber,GateType endpoint,double elapsed,double referenceElapsed,
                                 double splitSeconds,double referenceSplitSeconds) {
        public double cumulativeDeltaSeconds() {return elapsed-referenceElapsed;}
        public double splitDeltaSeconds() {return splitSeconds-referenceSplitSeconds;}
    }

    public record Guidance(GateType nextGate,float gateDistance,float routeDistance,float finishDistance,int splitNumber) {
        public boolean located() {return Float.isFinite(routeDistance);}
        public float remaining() {return gateDistance-routeDistance;}
        public boolean needsReturn() {return located() && remaining() < -1;}
        public float previewLength() {
            return located() && !needsReturn()?Math.max(0,Math.min(60,finishDistance-routeDistance)):0;
        }
    }

    private record Gate(TrackLayout.Sample sample,float distance) {
        // The map, the result and the crossing plane all use this same unwrapped distance.
        Gate(TrackLayout track,float distance) {this(track.sample(distance),distance);}
    }
    private record TimingRoute(List<Gate> gates,int startLineGate,int focusedSplit,CircuitSectionPractice originalResult) {}
    private record GateReadings(double[] elapsed,float[] speedMps) {}
    /** Frozen timings and gate readings from completed attempts under the same conditions. */
    static final class History {
        private final ReferenceTimes times;
        private final GateReadings best;

        private History(ReferenceTimes times,GateReadings best) {
            this.times=times;
            this.best=best;
        }

        ReferenceTimes times() {return times;}
        private GateReadings best() {return best;}
    }

    public record Range(int from,int to) {
        public Range {
            if(from<0 || from>=100 || to<1 || to>100 || from==to)
                throw new IllegalArgumentException("Invalid circuit section");
        }
        public boolean crossesStartLine() {return to<from;}
        public int endProgressPercent() {return crossesStartLine()?to+100:to;}
        public float start(TrackLayout track) {return track.length*(from/100f);}
        public float finish(TrackLayout track) {return track.length*(to/100f);}
        public float length(TrackLayout track) {return track.length*((endProgressPercent()-from)/100f);}
        public int splitCount() {
            int startUnits=from*ActivitySection.GATES;
            int endUnits=endProgressPercent()*ActivitySection.GATES;
            return (endUnits-1)/100-startUnits/100+1;
        }
    }

    public record Start(int runUpMeters,int speedKph) {
        public static final int MIN_RUN_UP=4,MAX_RUN_UP=60,MAX_SPEED_KPH=72;

        public Start {
            if(runUpMeters<MIN_RUN_UP || runUpMeters>MAX_RUN_UP || speedKph<0 || speedKph>MAX_SPEED_KPH)
                throw new IllegalArgumentException("Invalid circuit section start");
        }

        public static int maximumRunUp(TrackLayout track) {
            return Math.min(MAX_RUN_UP,(int)Math.ceil(track.length)-1);
        }
        public static Start defaults(TrackLayout track) {return new Start(Math.min(12,maximumRunUp(track)),0);}
        public float speedMps() {return speedKph/3.6f;}
    }

    private final TrackLayout track;
    private final Range range;
    private final Start start;
    private final TimingRoute route;
    private final History history;
    private final double[] gateElapsed;
    private final float[] gateSpeedMps;
    private final float[] gateFractions;
    private State state=State.APPROACH;
    private int next;
    private double lastTime,startedAt,elapsed;
    private String failure="";
    private float routeDistance;
    private int projectionSegment=-1;
    private boolean routeLocated=true;
    private float lastLocatedDistance=Float.NaN;
    private double lastLocatedTime;
    private InterruptionProgress interruptionProgress;
    private GateComparison latestGateComparison;
    private GateComparison largestSplitLoss;

    public CircuitSectionPractice(TrackLayout track,Range range) {
        this(track,range,Start.defaults(track));
    }

    public CircuitSectionPractice(TrackLayout track,Range range,Start start) {
        this(track,range,start,0);
    }

    public CircuitSectionPractice(TrackLayout track,Range range,Start start,int split) {
        this(track,range,start,selectRoute(timingRoute(track,range),split,null),null);
    }

    /** An offline telemetry interval retains its metric bounds for timing and retries. */
    public static CircuitSectionPractice fromTelemetry(TrackLayout track,float from,float to,Start start) {
        return fromTelemetry(track,from,to,start,null);
    }

    static CircuitSectionPractice fromTelemetry(TrackLayout track,float from,float to,Start start,History history) {
        if(!track.closed || !Float.isFinite(from) || !Float.isFinite(to) || from<0 || to>track.length || from>=to)
            throw new IllegalArgumentException("Invalid telemetry practice interval");
        var gates=new ArrayList<Gate>();
        gates.add(new Gate(track,from));
        for(int i=1;i<ActivitySection.GATES;i++) {
            float distance=track.length*(i/(float)ActivitySection.GATES);
            if(distance>from && distance<to) gates.add(new Gate(track,distance));
        }
        gates.add(new Gate(track,to));
        return new CircuitSectionPractice(track,null,start,new TimingRoute(List.copyOf(gates),-1,0,null),history);
    }

    private CircuitSectionPractice(TrackLayout track,Range range,Start start,TimingRoute route,History history) {
        this.track=Objects.requireNonNull(track);
        this.range=range;
        this.start=Objects.requireNonNull(start);
        this.route=route;
        this.history=history;
        if(start.runUpMeters()>Start.maximumRunUp(track)) throw new IllegalArgumentException("Run-up must be shorter than a lap");
        routeDistance=spawnDistance();
        gateElapsed=new double[route.gates().size()];
        gateSpeedMps=new float[gateElapsed.length];
        gateFractions=new float[gateElapsed.length];
    }

    private static TimingRoute timingRoute(TrackLayout track,Range range) {
        if(!track.closed) throw new IllegalArgumentException("Section practice requires a circuit");
        var gates=new ArrayList<Gate>();
        gates.add(new Gate(track,range.start(track)));
        int lapGates=ActivitySection.GATES;
        int startUnits=range.from()*lapGates,endUnits=range.endProgressPercent()*lapGates;
        int line=-1;
        // Both endpoints are exclusive here. Gate 18 is the original line inside a wrapped range.
        for(int gate=startUnits/100+1;gate*100<endUnits;gate++) {
            if(gate==lapGates) {
                line=gates.size();
                gates.add(new Gate(track,track.length));
            } else {
                gates.add(new Gate(track,track.length*gate/lapGates));
            }
        }
        gates.add(new Gate(track,track.length*(range.endProgressPercent()/100f)));
        return new TimingRoute(List.copyOf(gates),line,0,null);
    }

    public TrackLayout track() {return track;}
    /** The integral-percent editor range, when this attempt has one. */
    public Range range() {return range;}
    public Start start() {return start;}
    public State state() {return state;}
    public double elapsed() {return elapsed;}
    /** Valid results recorded before this attempt in the same practice session. */
    public ReferenceTimes referenceTimes() {return history==null?null:history.times();}
    /** The latest timed gate passed during this run. The starting gate has no comparison. */
    public GateComparison lastGateComparison() {return state==State.TIMING?latestGateComparison:null;}
    /** Greatest positive split difference visible at millisecond precision, in driving order on ties. */
    public GateComparison largestSplitLoss() {return largestSplitLoss;}
    public String failure() {return failure;}
    public InterruptionProgress interruptionProgress() {return interruptionProgress;}
    public int focusedSplit() {return route.focusedSplit();}
    public boolean hasOriginalResult() {return route.originalResult()!=null;}
    public float startDistance() {return route.gates().get(0).distance();}
    public float finishDistance() {return route.gates().get(splitCount()).distance();}

    boolean matchesSplitGates(int number,float from,float to) {
        var gates=route.gates();
        return number>0 && number<gates.size() && gates.get(number-1).distance()==from && gates.get(number).distance()==to;
    }
    public float spawnDistance() {return startDistance()-start.runUpMeters();}
    public boolean spawnBlocked() {
        var position=track.sample(spawnDistance()).position();
        return TrackScenery.solids(track).stream().anyMatch(solid->TrackScenery.distanceTo(position,solid)<2.5f);
    }
    public boolean hasInteriorStartLine() {return route.startLineGate()>=0;}
    public boolean passedStartLine() {return hasInteriorStartLine() && next>route.startLineGate();}
    public boolean complete() {return state==State.FINISHED || state==State.INVALID;}
    public CircuitSectionPractice restart() {return restart(true);}

    public CircuitSectionPractice restart(boolean keepReference) {
        return freshAttempt(start,keepReference?historyAfterFinish(history):null);
    }

    CircuitSectionPractice freshAttempt(Start launch,History reference) {
        return new CircuitSectionPractice(track,range,launch,route,reference);
    }

    History historyAfterFinish(History previous) {
        if(state!=State.FINISHED) return previous;
        var best=previous==null || elapsed<previous.times().best()
                ?new GateReadings(gateElapsed.clone(),gateSpeedMps.clone()):previous.best();
        return new History(new ReferenceTimes(elapsed,best.elapsed()[splitCount()]),best);
    }

    public int splitCount() {return route.gates().size()-1;}

    /** Reuses the two original timing gates and the configured start for a fresh practice session. */
    public CircuitSectionPractice practiceSplit(int number) {
        return practiceSplit(number,start);
    }

    public CircuitSectionPractice practiceSplit(int number,Start launch) {
        if(state!=State.FINISHED || number<1)
            throw new IllegalArgumentException("A completed section and a valid split are required");
        return new CircuitSectionPractice(track,range,launch,selectRoute(route,number,this),null);
    }

    private static TimingRoute selectRoute(TimingRoute route,int split,CircuitSectionPractice original) {
        if(split==0) return route;
        var gates=route.gates();
        if(gates.size()<3 || split<1 || split>=gates.size()) throw new IllegalArgumentException("Invalid practice split");
        return new TimingRoute(List.of(gates.get(split-1),gates.get(split)),-1,split,original);
    }

    boolean focusedOn(CircuitSectionPractice original) {return original!=null && route.originalResult()==original;}

    public GateSpeeds completedGateSpeeds() {
        return state==State.FINISHED?new GateSpeeds(gateSpeedMps[0],gateSpeedMps[splitCount()]):GateSpeeds.UNKNOWN;
    }

    /** Original elapsed times and speeds at the gates of one completed attempt. */
    public List<TimedGate> completedGates() {
        if(state!=State.FINISHED) return List.of();
        var gates=route.gates();
        var result=new ArrayList<TimedGate>(gates.size());
        for(int index=0;index<gates.size();index++)
            result.add(new TimedGate(gates.get(index).distance(),gateElapsed[index],gateSpeedMps[index]));
        return List.copyOf(result);
    }

    public GateComparison completedGateComparison(int number) {
        if(state!=State.FINISHED || history==null) return null;
        int gate=number;
        if(focusedSplit()!=0) {
            if(number!=focusedSplit()) return null;
            gate=1;
        }
        return gate>=1 && gate<=splitCount()?compareGate(gate):null;
    }

    /** Adjacent gate readings from the completed attempt and the same gates of the fastest prior attempt. */
    public List<Split> completedSplits() {
        if(state!=State.FINISHED) return List.of();
        var splits=new ArrayList<Split>(splitCount());
        var gates=route.gates();
        var reference=history==null?null:history.best();
        for(int gate=1;gate<gates.size();gate++) {
            double seconds=gateElapsed[gate]-gateElapsed[gate-1];
            double best=reference==null?Double.NaN:reference.elapsed()[gate]-reference.elapsed()[gate-1];
            var speeds=new GateSpeeds(gateSpeedMps[gate-1],gateSpeedMps[gate]);
            var bestSpeeds=reference==null?GateSpeeds.UNKNOWN:new GateSpeeds(reference.speedMps()[gate-1],reference.speedMps()[gate]);
            splits.add(new Split(splitNumber(gate),gateType(gate),seconds,best,gates.get(gate-1).distance(),gates.get(gate).distance(),speeds,bestSpeeds));
        }
        return List.copyOf(splits);
    }

    public Guidance guidance() {
        if(complete()) return null;
        int number=next==0 || focusedSplit()==0?next:focusedSplit();
        return new Guidance(gateType(next),route.gates().get(next).distance(),routeLocated?routeDistance:Float.NaN,
                finishDistance(),number);
    }

    private GateType gateType(int index) {
        return index==0?GateType.START:index==splitCount()?GateType.FINISH
                :index==route.startLineGate()?GateType.START_FINISH:GateType.CHECKPOINT;
    }

    private int splitNumber(int gate) {return focusedSplit()==0?gate:focusedSplit();}

    private GateComparison compareGate(int gate) {
        var reference=history.best().elapsed();
        return new GateComparison(splitNumber(gate),gateType(gate),gateElapsed[gate],reference[gate],
                gateElapsed[gate]-gateElapsed[gate-1],reference[gate]-reference[gate-1]);
    }

    private GateComparison findLargestSplitLoss() {
        if(history==null || splitCount()<2) return null;
        var reference=history.best().elapsed();
        long largestMillis=0;
        int selectedGate=0;
        for(int gate=1;gate<=splitCount();gate++) {
            double current=gateElapsed[gate]-gateElapsed[gate-1];
            double best=reference[gate]-reference[gate-1];
            long millis=Math.round((current-best)*1000);
            if(millis>largestMillis) {
                largestMillis=millis;
                selectedGate=gate;
            }
        }
        return selectedGate==0?null:compareGate(selectedGate);
    }

    public void update(Vector3f previous,Vector3f current,double time) {
        update(previous,current,time,Float.NaN,Float.NaN);
    }

    public void update(Vector3f previous,Vector3f current,double time,float previousSpeedMps,float currentSpeedMps) {
        if(complete()) return;
        float moved=Vector3f.isValidVector(previous) && Vector3f.isValidVector(current)?previous.distance(current):Float.NaN;
        if(!Double.isFinite(time) || time<lastTime || !Float.isFinite(moved) || moved>55) {
            invalidate("试驾观测中断，请重跑本区间。");
            return;
        }
        if(time==lastTime) return;
        double span=time-lastTime;
        int passed=next;
        float lastFraction=-1;
        var gates=route.gates();
        while(passed<gates.size()) {
            float fraction=LapProgress.crossingFraction(previous,current,gates.get(passed).sample(),track.width);
            if(!Float.isFinite(fraction) || fraction<=lastFraction || lastTime+span*fraction>CircuitTrial.LIMIT) break;
            gateFractions[passed++]=fraction;
            lastFraction=fraction;
        }

        float roadLimit=track.width*.5f+.6f;
        if(passed==gates.size()) {
            // The gate's lateral check places the finish on the road. The observed approach and the height at the line still matter.
            float finishHeight=previous.y*(1-lastFraction)+current.y*lastFraction;
            if(!validHeight(previous.y) || !validHeight(finishHeight)
                    || !track.withinDistance(previous,roadLimit,projectionSegment)) {
                invalidate(LEFT_ROUTE);
                return;
            }
            recordGates(passed,span,previousSpeedMps,currentSpeedMps);
            elapsed=gateElapsed[next-1];
            largestSplitLoss=findLargestSplitLoss();
            state=State.FINISHED;
            return;
        }

        double observedAt=Math.min(time,CircuitTrial.LIMIT);
        var observed=observedAt<time?LapProgress.positionAtDeadline(previous,current,lastTime,time,observedAt):current;
        var projection=track.project(observed,projectionSegment);
        if(!validHeight(observed.y) || projection.separation()>roadLimit) {
            invalidate(LEFT_ROUTE);
            return;
        }
        projectionSegment=projection.segment();
        int firstExpected=next;
        recordGates(passed,span,previousSpeedMps,currentSpeedMps);
        if(history!=null && next>1 && next!=firstExpected) latestGateComparison=compareGate(next-1);
        updateRouteLocation(projection.distance(),observed==current?moved:previous.distance(observed),firstExpected);
        if(routeLocated) {
            lastLocatedDistance=routeDistance;
            lastLocatedTime=observedAt;
        }
        lastTime=observedAt;
        if(state==State.TIMING) elapsed=observedAt-startedAt;
        if(observedAt>=CircuitTrial.LIMIT) invalidate("五分钟内尚未完成本区间，请重跑。");
    }

    private void recordGates(int passed,double span,float previousSpeedMps,float currentSpeedMps) {
        while(next<passed) {
            float fraction=gateFractions[next];
            double crossedAt=lastTime+span*fraction;
            if(next==0) {
                startedAt=crossedAt;
                state=State.TIMING;
            }
            gateElapsed[next]=crossedAt-startedAt;
            gateSpeedMps[next]=crossingSpeed(previousSpeedMps,currentSpeedMps,fraction);
            next++;
        }
    }

    private static boolean validHeight(float y) {return y>=-3 && y<=8;}

    private static float crossingSpeed(float before,float after,float fraction) {
        if(!Float.isFinite(after) || after<0) return Float.NaN;
        if(fraction==1) return after;
        if(!Float.isFinite(before) || before<0) return Float.NaN;
        return (float)(before+(after-(double)before)*fraction);
    }

    private void updateRouteLocation(float distance,float moved,int firstExpected) {
        if(next!=firstExpected) routeDistance=route.gates().get(next-1).distance();
        float unwrapped=distance+Math.round((routeDistance-distance)/track.length)*track.length;
        // A close parallel branch can win the global projection without a reachable change in arc length.
        routeLocated=Math.abs(unwrapped-routeDistance)<=2+2*moved;
        if(routeLocated) routeDistance=unwrapped;
    }

    private void invalidate(String reason) {
        int last=next-1;
        var passed=last<0?null:new PassedGate(gatePosition(last),gateElapsed[last],gateSpeedMps[last]);
        var location=Float.isFinite(lastLocatedDistance)?new RouteObservation(lastLocatedDistance,lastLocatedTime):null;
        interruptionProgress=new InterruptionProgress(passed,gatePosition(next),location);
        state=State.INVALID;
        failure=reason;
    }

    private GatePosition gatePosition(int index) {
        return new GatePosition(index,gateType(index),route.gates().get(index).distance());
    }
}
