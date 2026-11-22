package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Directional ordered gates: a teleport, reverse crossing or skipped gate cannot award a lap. */
public final class LapProgress {
    public static final int RACE_GATES=18;
    private static final float MAX_OBSERVED_MOVE=55;
    public record State(int nextGate, int completedLaps, boolean started, boolean finished, boolean validLap,
                        double lapStart, double lastLap, double bestLap, double finishTime, boolean lastLapValid) {}
    public record Crossing(long sequence,int gate,int completedLaps,boolean firstStart,double time,boolean valid) {}
    private final TrackLayout track;
    private final int targetLaps, gateCount;
    private final TrackLayout.Sample[] gates;
    private int nextGate, completedLaps;
    private boolean started, finished, validLap = true;
    private double lapStart, lastLap = -1, bestLap = -1, finishTime = -1;
    private boolean lastLapValid;
    private TrackLayout.Projection raceProjection;
    private int raceProjectionX, raceProjectionZ;
    private int roadSegment=-1;
    private long crossingSequence;
    private List<Crossing> latestCrossings=List.of();
    private double[] additionalGateTimes=new double[0];

    public LapProgress(TrackLayout track, int targetLaps) { this(track, targetLaps, RACE_GATES); }
    public LapProgress(TrackLayout track, int targetLaps, int gateCount) {
        this.track = Objects.requireNonNull(track);
        if (targetLaps < 1 || gateCount < 1) throw new IllegalArgumentException("Lap and gate counts must be positive");
        this.targetLaps = targetLaps;
        this.gateCount = gateCount;
        gates = gateCount <= RACE_GATES ? new TrackLayout.Sample[gateCount] : null;
        if (gates != null) for (int i = 0; i < gates.length; i++) gates[i] = track.sample(i * track.length / gateCount);
    }

    public boolean update(Vector3f previous, Vector3f current, double time) {
        if (!Double.isFinite(time) || time < 0) return false;
        return updateObserved(previous,current,current,time,time,Double.POSITIVE_INFINITY);
    }

    /** Estimate the crossing time between observations, accepting gates up to and including the deadline. */
    public boolean updateTimed(Vector3f previous,Vector3f current,double from,double to,double deadline) {
        return updateTimed(previous,current,null,from,to,deadline);
    }

    /** Reuse a cutoff position already sampled from these same observations. */
    public boolean updateTimed(Vector3f previous,Vector3f current,Vector3f observedEnd,double from,double to,double deadline) {
        if(finished || !Double.isFinite(from) || !Double.isFinite(to) || from<0 || Double.isNaN(deadline) || from>=deadline || to<=from) return false;
        Vector3f end=observedEnd!=null?observedEnd:deadline<to?positionAtDeadline(previous,current,from,to,deadline):current;
        return updateObserved(previous,current,end,from,to,deadline);
    }

    public static Vector3f positionAtDeadline(Vector3f previous,Vector3f current,double from,double to,double deadline) {
        if(!Vector3f.isValidVector(previous)) return current;
        if(!Vector3f.isValidVector(current) || previous.distance(current)>MAX_OBSERVED_MOVE || deadline<=from
                || !Double.isFinite(from) || !Double.isFinite(to) || to<=from || Double.isNaN(deadline)) return previous;
        if(deadline>=to) return current;
        float portion=(float)((deadline-from)/(to-from));
        return previous.clone().interpolateLocal(current,portion);
    }

    private boolean updateObserved(Vector3f previous,Vector3f current,Vector3f end,double from,double to,double deadline) {
        if (finished) return false;
        if (!Vector3f.isValidVector(previous) || !Vector3f.isValidVector(current)) {invalidate();return false;}
        if (previous.distance(current) > MAX_OBSERVED_MOVE) { invalidate(); return false; }

        int accepted=0,lapLine=-1,gateIndex=nextGate;
        double firstTime=0;
        float lastFraction=-1;
        // A straight observation can cross a gate plane at most once, even if the gate order wraps.
        while(accepted<gateCount) {
            var gate=gates!=null && gateIndex>=0 && gateIndex<gates.length
                    ?gates[gateIndex]:track.sample(gateIndex*track.length/gateCount);
            float fraction=crossingFraction(previous,current,gate,track.width);
            if(!Float.isFinite(fraction) || fraction<=lastFraction) break;
            double time=from==to?to:Math.fma(to-from,fraction,from);
            if(time>deadline) break;
            if(accepted==0) firstTime=time;
            else {
                int index=accepted-1;
                if(index==additionalGateTimes.length)
                    additionalGateTimes=Arrays.copyOf(additionalGateTimes,Math.max(4,index*2));
                additionalGateTimes[index]=time;
            }
            accepted++;
            if(gateIndex==0) {
                lapLine=accepted-1;
                if(started && completedLaps>=targetLaps-1) break;
            }
            gateIndex=(gateIndex+1)%gateCount;
            lastFraction=fraction;
        }

        // An accepted lap line is on the road by the gate-width check. Attribute the endpoint to the lap still open there.
        checkRoad(lapLine<0?end:previous);
        if(accepted==0) return false;
        int previousLaps=completedLaps;
        var first=creditGate(firstTime);
        if(lapLine==0 && !finished) checkRoad(end);
        if(accepted==1 || finished) latestCrossings=List.of(first);
        else {
            var batch=new ArrayList<Crossing>(accepted);
            batch.add(first);
            for(int index=1;index<accepted && !finished;index++) {
                batch.add(creditGate(additionalGateTimes[index-1]));
                if(index==lapLine && !finished) checkRoad(end);
            }
            latestCrossings=List.copyOf(batch);
        }
        return completedLaps!=previousLaps;
    }

    private void checkRoad(Vector3f end) {
        if(validLap) {
            int segment=track.findSegmentWithinDistance(end,track.width*.5f+.6f,roadSegment>=0?roadSegment:projectionHint());
            if(segment<0) validLap=false;
            else roadSegment=segment;
        }
    }

    private Crossing creditGate(double time) {
        boolean firstStart=!started,crossingValid=validLap;
        int crossedGate=nextGate;
        if (nextGate == 0) {
            if (started) {
                completedLaps++;
                lastLap = time - lapStart;
                lastLapValid = validLap;
                if (validLap && (bestLap < 0 || lastLap < bestLap)) bestLap = lastLap;
                if (completedLaps >= targetLaps) { finished = true; finishTime = time; }
            }
            started = true;
            lapStart = time;
            validLap = true;
        }
        nextGate = (nextGate + 1) % gateCount;
        return new Crossing(++crossingSequence,crossedGate,completedLaps,firstStart,time,crossingValid);
    }

    static float crossingFraction(Vector3f previous,Vector3f current,TrackLayout.Sample gate,float width) {
        return crossingFraction(previous,current,gate,width*.5f+.5f,true);
    }

    static float crossingFraction(Vector3f previous,Vector3f current,TrackLayout.Sample gate,float halfWidth,boolean includeEdge) {
        var origin = gate.position();
        var tangent = gate.tangent();
        float before = (previous.x-origin.x)*tangent.x + (previous.y-origin.y)*tangent.y + (previous.z-origin.z)*tangent.z;
        float after = (current.x-origin.x)*tangent.x + (current.y-origin.y)*tangent.y + (current.z-origin.z)*tangent.z;
        if(before>=0 || after<0 || after==before) return Float.NaN;
        float fraction=-before/(after-before);
        float beforeWeight = 1 - fraction;
        float x = previous.x*beforeWeight + current.x*fraction - origin.x;
        float y = previous.y*beforeWeight + current.y*fraction - origin.y;
        float z = previous.z*beforeWeight + current.z*fraction - origin.z;
        var right = gate.right();
        float lateral = Math.abs(x*right.x + y*right.y + z*right.z);
        if(lateral<halfWidth || includeEdge && lateral==halfWidth) return fraction;
        return Float.NaN;
    }

    public void invalidate() { validLap = false; }
    public Crossing latestCrossing() {return latestCrossings.isEmpty()?null:latestCrossings.get(latestCrossings.size()-1);}
    /** Immutable batch from the most recent observation that crossed any gates. */
    public List<Crossing> latestCrossings() {return latestCrossings;}
    public int gateCount() {return gateCount;}
    public State snapshot() { return new State(nextGate, completedLaps, started, finished, validLap, lapStart, lastLap, bestLap, finishTime, lastLapValid); }
    /** Display replicas only; hosts always calculate progress through update(). */
    public void applySnapshot(State state) {
        nextGate = state.nextGate; completedLaps = state.completedLaps; started = state.started;
        finished = state.finished; validLap = state.validLap; lapStart = state.lapStart;
        lastLap = state.lastLap; bestLap = state.bestLap; finishTime = state.finishTime; lastLapValid = state.lastLapValid;
    }
    public int completedLaps() { return completedLaps; }
    public int nextGate() { return nextGate; }
    /** Copy the current gate into two caller-owned vectors for the driving display. */
    public void nextGatePositionAndRight(Vector3f position, Vector3f right) {
        if (gates != null && nextGate >= 0 && nextGate < gates.length) {
            var gate = gates[nextGate];
            position.set(gate.position());
            right.set(gate.right());
        } else {
            track.samplePositionAndRight(nextGate * track.length / gateCount, position, right);
        }
    }
    public boolean started() { return started; }
    public boolean finished() { return finished; }
    public boolean validLap() { return validLap; }
    public boolean lastLapValid() { return lastLapValid; }
    public double lapStartTime() {return lapStart;}
    public double currentLap(double time) { return started ? Math.max(0, time - lapStart) : 0; }
    public double lastLap() { return lastLap; }
    public double bestLap() { return bestLap; }
    public double finishTime() { return finishTime; }
    public float recoveryDistance() {
        if (!started) return -12;
        return Math.floorMod(nextGate - 1, gateCount) * track.length / gateCount - 4;
    }
    public float raceDistance(Vector3f position) {
        if (finished) return targetLaps * track.length;
        var projection = projectRacePosition(position);
        if (!started) return projection == null ? -track.length : Math.min(0, projection.distance() - track.length);
        float gateSpacing = track.length / gateCount;
        float last = Math.floorMod(nextGate - 1, gateCount) * gateSpacing;
        if (projection == null || !Float.isFinite(projection.separation())) return completedLaps * track.length + last;
        float distance = projection.distance();
        if (track.closed) {
            // Unwrap around the current gate interval so backing up past a gate stays behind it.
            float offset = distance - (last + gateSpacing * .5f);
            float halfLap = track.length * .5f;
            if (offset > halfLap) distance -= track.length;
            else if (offset < -halfLap) distance += track.length;
        }
        return completedLaps * track.length + Math.min(last + gateSpacing, Math.max(last, distance));
    }

    /** Shares the race projection for the same observed position and track. */
    public void rememberRaceProjection(TrackLayout road,Vector3f position,TrackLayout.Projection projection) {
        if(road==track && projection!=null && Vector3f.isValidVector(position))
            cacheRaceProjection(Float.floatToRawIntBits(position.x),Float.floatToRawIntBits(position.z),projection);
    }

    /** Projects the current observation, reusing the same geometry used by standings and replay. */
    public TrackLayout.Projection trackProjection(TrackLayout road,Vector3f position) {
        return road==track?projectRacePosition(position):null;
    }

    private TrackLayout.Projection projectRacePosition(Vector3f position) {
        if (!Vector3f.isValidVector(position)) return null;
        int x = Float.floatToRawIntBits(position.x);
        int z = Float.floatToRawIntBits(position.z);
        // Standings and replay can request the same pose. Gate and lap state are evaluated on every call.
        if (raceProjection == null || raceProjectionX != x || raceProjectionZ != z)
            cacheRaceProjection(x,z,track.project(position,projectionHint()));
        return raceProjection;
    }

    private void cacheRaceProjection(int x,int z,TrackLayout.Projection projection) {
        raceProjection=projection;
        raceProjectionX=x;
        raceProjectionZ=z;
    }

    private int projectionHint() {return raceProjection==null?-1:raceProjection.segment();}
}
