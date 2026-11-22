package com.f1car.model;

import java.util.*;

/** Compare first arrivals at the same centerline distance, including time lost reversing/stopping. */
public final class TelemetryComparison {
    public static final int ANALYSIS_SEGMENTS=18;
    public static final double MIN_SEGMENT_DELTA_SECONDS=.02;
    private static final int PLOT_STEPS=300;
    private static final int TRAVERSAL_BLOCK_SIZE=64;
    public record Point(float distance,TelemetryLap.Sample a,TelemetryLap.Sample b) { public double delta() { return a.time()-b.time(); } }
    public record RoadCursor(float centerX,float centerZ,float rightX,float rightZ,double offsetA,double offsetB) {}
    public record Segment(float from,float to,double delta) {}
    public record SegmentExtremes(Segment mostTimeLost,Segment mostTimeGained) {}
    public record Brake(float distance,double time) {}
    public record BrakeAction(boolean pressed,TelemetryLap.Sample sample) {}
    public record BrakeReadout(int windows,double seconds,Brake firstPress,Brake lastRelease,boolean activeAtEntry,boolean activeAtExit) {}
    /** Speeds are in m/s. The signed change sums only the qualifying windows. */
    public record Coasting(int windows,double seconds,double forwardMetres,double minimumSpeed,double maximumSpeed,double speedChange) {}
    public record Traversal(double elapsed,float entrySpeed,float minimumSpeed,float exitSpeed,float peakBrake,Coasting coasting,
                            BrakeReadout braking) {}
    public record Interval(float from,float to,Traversal a,Traversal b) {
        public double delta() {return a.elapsed()-b.elapsed();}
    }
    public record IntervalTrajectory(float from,float to,List<TelemetryLap.Sample> a,List<TelemetryLap.Sample> b,
                                     List<BrakeAction> brakesA,List<BrakeAction> brakesB) {
        public IntervalTrajectory {a=List.copyOf(a);b=List.copyOf(b);brakesA=List.copyOf(brakesA);brakesB=List.copyOf(brakesB);}
    }
    public record DeltaPoint(float distance,double seconds) {}
    public record ObservationPoint(float distance,VehicleObservation observation,boolean connected) {}
    public record DeltaTrace(double baseline,double extent,List<DeltaPoint> points) {
        public DeltaTrace {points=List.copyOf(points);}
        public double at(Point point) {return point.delta()-baseline;}
    }
    private record BrakeWindow(TelemetryLap.Sample press,TelemetryLap.Sample release,double end) {
        double overlap(double entry,double exit) {return Math.min(exit,end)-Math.max(entry,press.time());}
        boolean pressIn(double entry) {return press.time()>0 && press.time()>=entry;}
        boolean releaseIn(double exit) {return release!=null && end<=exit;}
    }
    private record SegmentSummary(List<Segment> losses,SegmentExtremes extremes) {}
    private record ArrivalJump(float distance,double before,double after) {}
    private record TraversalBlocks(float[] minimumSpeed,float[] peakBrake,BitSet coastingPairs) {}

    private static final class CoastReadings {
        private static final float PEDAL_LIMIT=.02f,STEER_LIMIT=.10f,MIN_SPEED_MPS=5/3.6f;
        private int windows;
        private double lastEnd=-1,seconds,distance,speedChange,minSpeed=Double.POSITIVE_INFINITY,maxSpeed;

        void include(TelemetryLap.Sample before,TelemetryLap.Sample after,double entry,double exit) {
            double start=Math.max(entry,before.time()),end=Math.min(exit,after.time());
            double advance=(double)after.distance()-before.distance();
            if(start>=end) return;

            if(start!=lastEnd) windows++;
            lastEnd=end;
            double duration=after.time()-before.time();
            double from=(start-before.time())/duration,to=(end-before.time())/duration;
            double dv=(double)after.driving().speed()-before.driving().speed();
            double startSpeed=before.driving().speed()+dv*from,endSpeed=before.driving().speed()+dv*to;
            seconds+=end-start;
            distance+=advance*(to-from);
            speedChange+=endSpeed-startSpeed;
            minSpeed=Math.min(minSpeed,Math.min(startSpeed,endSpeed));
            maxSpeed=Math.max(maxSpeed,Math.max(startSpeed,endSpeed));
        }

        static boolean qualifies(TelemetryLap.Sample before,TelemetryLap.Sample after) {
            return after.distance()>before.distance() && lowCommands(before.driving()) && lowCommands(after.driving());
        }

        private static boolean lowCommands(TelemetryLap.Driving driving) {
            return driving.throttle()>=0 && driving.throttle()<=PEDAL_LIMIT && driving.brake()<=PEDAL_LIMIT
                    && Math.abs(driving.steer())<=STEER_LIMIT && driving.speed()>=MIN_SPEED_MPS;
        }

        Coasting result() {return windows==0?null:new Coasting(windows,seconds,distance,minSpeed,maxSpeed,speedChange);}
    }

    private static final class Index {
        final TelemetryLap lap;
        final int[] arrivals;
        final int[] missing;
        final double observationEnd;
        private List<BrakeWindow> brakeWindows;
        private List<Brake> brakeMarkers;
        private List<ObservationPoint> observationTrace;
        private TraversalBlocks traversalBlocks;
        Index(TelemetryLap lap) {
            this.lap=lap;
            var samples=lap.samples();
            // Accepted recordings can end a few microseconds after their declared duration.
            observationEnd=Math.max(lap.duration(),samples.get(samples.size()-1).time());
            var crossings=new int[samples.size()-1];
            int count=0;
            float reached=0;
            missing=new int[samples.size()+1];
            for(int i=0;i<samples.size();i++) {
                var sample=samples.get(i);
                missing[i+1]=missing[i]+(sample.observation()==null?1:0);
                if(i>0 && sample.distance()>reached) {
                    crossings[count++]=i;
                    reached=sample.distance();
                }
            }
            if(reached<lap.conditions().length()-.001) throw new IllegalArgumentException("Missing distance coverage");
            arrivals=count==crossings.length?crossings:Arrays.copyOf(crossings,count);
        }
        TelemetryLap.Sample at(float distance) {
            var endpoint=endpoint(distance);
            return endpoint!=null?endpoint:betweenArrivals(distance,arrivalAt(distance,0));
        }
        double timeAt(float distance) {
            var endpoint=endpoint(distance);
            if(endpoint!=null) return endpoint.time();
            var samples=lap.samples();
            int crossing=arrivals[arrivalAt(distance,0)];
            return crossingTime(distance,samples.get(crossing-1),samples.get(crossing));
        }
        private TelemetryLap.Sample endpoint(float distance) {
            if(distance<=0) return lap.samples().get(0);
            if(distance>=lap.conditions().length()) return lap.samples().get(lap.samples().size()-1);
            return null;
        }
        int arrivalAt(float distance,int low) {
            var samples=lap.samples();
            int high=arrivals.length-1;
            if(samples.get(arrivals[low]).distance()>=distance) return low;
            while(low<high) {
                int middle=(low+high)/2;
                if(samples.get(arrivals[middle]).distance()<distance) low=middle+1;
                else high=middle;
            }
            return low;
        }
        TelemetryLap.Sample betweenArrivals(float distance,int arrival) {
            var samples=lap.samples();
            int crossing=arrivals[arrival];
            var before=samples.get(crossing-1);
            var after=samples.get(crossing);
            if(distance>=after.distance()) return after;
            return before.interpolate(after,crossingTime(distance,before,after));
        }
        private static double crossingTime(float distance,TelemetryLap.Sample before,TelemetryLap.Sample after) {
            if(distance<=before.distance()) return before.time();
            if(distance>=after.distance()) return after.time();
            double fraction=(distance-before.distance())/(after.distance()-before.distance());
            return before.time()+(after.time()-before.time())*fraction;
        }
        List<ArrivalJump> arrivalJumps() {
            var samples=lap.samples();
            float length=lap.conditions().length();
            var jumps=new ArrayList<ArrivalJump>();
            int previous=0;
            for(int crossing:arrivals) {
                var first=samples.get(previous);
                float distance=previous==0?0:first.distance();
                if(distance>=length) break;
                if(crossing>previous+1) {
                    double before=previous==0?first.time():crossingTime(distance,samples.get(previous-1),first);
                    double after=crossingTime(distance,samples.get(crossing-1),samples.get(crossing));
                    jumps.add(new ArrivalJump(distance,before,after));
                }
                previous=crossing;
            }
            // Projection can reach lap length before the actual finish gate closes the recording.
            int finish=arrivals[arrivalAt(length,0)];
            var projectedFinish=samples.get(finish);
            if(projectedFinish.distance()>=length) {
                double first=projectedFinish.distance()==length?projectedFinish.time()
                        :crossingTime(length,samples.get(finish-1),projectedFinish);
                double last=samples.get(samples.size()-1).time();
                if(first<last) jumps.add(new ArrivalJump(length,first,last));
            }
            return jumps;
        }
        boolean observationsContinuous(double from,double to) {
            if(!Double.isFinite(from) || !Double.isFinite(to) || from<0 || to<from || to>observationEnd) throw new IllegalArgumentException("Invalid observation span");
            var samples=lap.samples();
            int end=firstSampleAtOrAfter(to);
            if(end==samples.size()) return false;
            int begin=firstSampleAtOrAfter(from);
            if(samples.get(begin).time()>from) begin--;
            return missing[end+1]==missing[begin];
        }
        List<ObservationPoint> observationTrace() {
            if(observationTrace!=null) return observationTrace;
            var samples=lap.samples();
            var points=new ArrayList<ObservationPoint>();
            var start=samples.get(0).observation();
            if(start!=null) points.add(new ObservationPoint(0,start,false));
            float length=lap.conditions().length();
            int previous=0,last=samples.size()-1;
            for(int crossing:arrivals) {
                var before=samples.get(crossing-1);
                var after=samples.get(crossing);
                float distance=crossing==last?length:Math.min(length,after.distance());
                var observation=distance<after.distance()
                        ?before.interpolate(after,crossingTime(distance,before,after)).observation():after.observation();
                if(observation!=null) {
                    boolean connected=before.observation()!=null;
                    // After a pause or reversal, start a new line at the right-hand limit of the old distance.
                    if(connected && crossing>previous+1) {
                        float priorDistance=samples.get(previous).distance();
                        var resumed=before.interpolate(after,crossingTime(priorDistance,before,after));
                        points.add(new ObservationPoint(priorDistance,resumed.observation(),false));
                    }
                    points.add(new ObservationPoint(distance,observation,connected));
                }
                previous=crossing;
                if(distance==length) break;
            }
            var finish=samples.get(last).observation();
            if((previous!=last || samples.get(last).distance()>length) && finish!=null)
                points.add(new ObservationPoint(length,finish,false));
            observationTrace=List.copyOf(points);
            return observationTrace;
        }
        Traversal traversal(TelemetryLap.Sample entry,TelemetryLap.Sample exit) {
            float minimumSpeed=Math.min(entry.driving().speed(),exit.driving().speed());
            float peakBrake=Math.max(entry.driving().brake(),exit.driving().brake());
            var samples=lap.samples();
            var blocks=traversalBlocks();
            int firstRawSample=firstSampleAtOrAfter(entry.time());
            int firstPair=Math.max(1,firstRawSample);
            int lastPair=firstSampleAtOrAfter(exit.time());
            int endOfRawSamples=lastPair;
            if(lastPair<samples.size() && samples.get(lastPair).time()==exit.time()) endOfRawSamples++;
            for(int i=firstRawSample;i<endOfRawSamples;) {
                if(i%TRAVERSAL_BLOCK_SIZE==0 && endOfRawSamples-i>=TRAVERSAL_BLOCK_SIZE) {
                    int block=i/TRAVERSAL_BLOCK_SIZE;
                    minimumSpeed=Math.min(minimumSpeed,blocks.minimumSpeed()[block]);
                    peakBrake=Math.max(peakBrake,blocks.peakBrake()[block]);
                    i+=TRAVERSAL_BLOCK_SIZE;
                } else {
                    var sample=samples.get(i++);
                    minimumSpeed=Math.min(minimumSpeed,sample.driving().speed());
                    peakBrake=Math.max(peakBrake,sample.driving().brake());
                }
            }
            Coasting coasting=null;
            var pairs=blocks.coastingPairs();
            int firstCoast=pairs.nextSetBit(firstPair);
            if(firstCoast>=0 && firstCoast<=lastPair) {
                var readings=new CoastReadings();
                // Keep the raw pair order for interpolation, run boundaries and summation.
                for(int i=firstCoast;i>=0 && i<=lastPair;i=pairs.nextSetBit(i+1))
                    readings.include(samples.get(i-1),samples.get(i),entry.time(),exit.time());
                coasting=readings.result();
            }
            return new Traversal(exit.time()-entry.time(),entry.driving().speed(),minimumSpeed,exit.driving().speed(),peakBrake,
                    coasting,braking(entry.time(),exit.time()));
        }

        private synchronized TraversalBlocks traversalBlocks() {
            if(traversalBlocks!=null) return traversalBlocks;
            var samples=lap.samples();
            int blocks=(samples.size()+TRAVERSAL_BLOCK_SIZE-1)/TRAVERSAL_BLOCK_SIZE;
            float[] minimumSpeed=new float[blocks],peakBrake=new float[blocks];
            Arrays.fill(minimumSpeed,Float.POSITIVE_INFINITY);
            Arrays.fill(peakBrake,Float.NEGATIVE_INFINITY);
            var coastingPairs=new BitSet(samples.size());
            var previous=samples.get(0);
            for(int i=0;i<samples.size();i++) {
                var sample=samples.get(i);
                int block=i/TRAVERSAL_BLOCK_SIZE;
                minimumSpeed[block]=Math.min(minimumSpeed[block],sample.driving().speed());
                peakBrake[block]=Math.max(peakBrake[block],sample.driving().brake());
                if(CoastReadings.qualifies(previous,sample)) coastingPairs.set(i);
                previous=sample;
            }
            traversalBlocks=new TraversalBlocks(minimumSpeed,peakBrake,coastingPairs);
            return traversalBlocks;
        }
        List<TelemetryLap.Sample> trajectory(TelemetryLap.Sample entry,TelemetryLap.Sample exit) {
            var samples=lap.samples();
            if(entry==samples.get(0) && exit==samples.get(samples.size()-1)) return samples;
            int first=firstSampleAtOrAfter(entry.time());
            int end=firstSampleAtOrAfter(exit.time());
            if(first<end && samples.get(first).time()==entry.time()) first++;
            var points=new ArrayList<TelemetryLap.Sample>(Math.max(0,end-first)+2);
            points.add(entry);
            for(int index=first;index<end;index++) points.add(samples.get(index));
            points.add(exit);
            return points;
        }
        private BrakeReadout braking(double entry,double exit) {
            int windows=0;
            double seconds=0;
            Brake firstPress=null,lastRelease=null;
            boolean activeAtEntry=false,activeAtExit=false;
            var timed=brakeWindows();
            for(int i=firstOverlappingWindow(timed,entry);i<timed.size();i++) {
                var window=timed.get(i);
                if(window.press().time()>=exit) break;
                double overlap=window.overlap(entry,exit);
                if(overlap<=0) continue;

                windows++;
                seconds+=overlap;
                if(!window.pressIn(entry)) activeAtEntry=true;
                else if(firstPress==null) firstPress=brakePoint(window.press());
                if(window.releaseIn(exit)) lastRelease=brakePoint(window.release());
                else activeAtExit=true;
            }
            return windows==0?null:new BrakeReadout(windows,seconds,firstPress,lastRelease,activeAtEntry,activeAtExit);
        }
        private List<BrakeAction> brakeActions(double entry,double exit) {
            var actions=new ArrayList<BrakeAction>();
            var timed=brakeWindows();
            for(int i=firstOverlappingWindow(timed,entry);i<timed.size();i++) {
                var window=timed.get(i);
                if(window.press().time()>=exit) break;
                if(window.overlap(entry,exit)<=0) continue;
                if(window.pressIn(entry)) actions.add(new BrakeAction(true,window.press()));
                if(window.releaseIn(exit)) actions.add(new BrakeAction(false,window.release()));
            }
            return actions;
        }
        private static int firstOverlappingWindow(List<BrakeWindow> windows,double entry) {
            int low=0,high=windows.size();
            while(low<high) {
                int middle=(low+high)/2;
                if(windows.get(middle).end()<=entry) low=middle+1;
                else high=middle;
            }
            return low;
        }
        private List<BrakeWindow> brakeWindows() {
            if(brakeWindows==null) brakeWindows=readBrakeWindows(lap);
            return brakeWindows;
        }
        private List<Brake> brakeMarkers() {
            if(brakeMarkers==null) brakeMarkers=brakeWindows().stream().map(window->brakePoint(window.press())).toList();
            return brakeMarkers;
        }
        private int firstSampleAtOrAfter(double time) {
            int low=0,high=lap.samples().size();
            while(low<high) {
                int middle=(low+high)/2;
                if(lap.samples().get(middle).time()<time) low=middle+1;
                else high=middle;
            }
            return low;
        }
    }
    private final TelemetryLap a,b;
    private final Index indexA,indexB;
    private List<TelemetryLap.Sample> brakePressesByDistance;
    private List<Point> plot;
    private DeltaTrace fullTimeDelta;
    private SegmentSummary segmentSummary;
    public TelemetryComparison(TelemetryLap a,TelemetryLap b) {
        this(a,b,false);
    }
    /** Explicit cross-setup analysis requires each complete lap's own frozen vehicle source. */
    public static TelemetryComparison equipment(EquipmentLap a,EquipmentLap b) {
        if(!EquipmentLap.differences(a,b).isEmpty()) throw new IllegalArgumentException("Equipment lap route, vehicle, physics or controls differ");
        return new TelemetryComparison(a.lap(),b.lap(),true);
    }
    private TelemetryComparison(TelemetryLap a,TelemetryLap b,boolean equipment) {
        this.a=Objects.requireNonNull(a);this.b=Objects.requireNonNull(b);
        if(!equipment && !a.conditions().equals(b.conditions())) throw new IllegalArgumentException("Choose laps under the same conditions");
        indexA=new Index(a);indexB=a==b?indexA:new Index(b);
    }
    public TelemetryLap a() { return a; }
    public TelemetryLap b() { return b; }
    public synchronized List<Point> plot() {
        if(plot==null) plot=buildPlot();
        return plot;
    }

    private List<Point> buildPlot() {
        var points=new ArrayList<Point>(PLOT_STEPS+1);
        float length=a.conditions().length();
        points.add(at(0));
        int arrivalA=0,arrivalB=0;
        for(int step=1;step<PLOT_STEPS;step++) {
            float distance=length*(step/(float)PLOT_STEPS);
            arrivalA=indexA.arrivalAt(distance,arrivalA);
            var sampleA=indexA.betweenArrivals(distance,arrivalA);
            var sampleB=sampleA;
            if(indexA!=indexB) {
                arrivalB=indexB.arrivalAt(distance,arrivalB);
                sampleB=indexB.betweenArrivals(distance,arrivalB);
            }
            points.add(new Point(distance,sampleA,sampleB));
        }
        points.add(at(length));
        return List.copyOf(points);
    }
    public List<Brake> brakeMarkers(boolean reference) {return (reference?indexB:indexA).brakeMarkers();}
    /** A's original brake presses in cursor order. Equal screen distances retain recording order. */
    public List<TelemetryLap.Sample> brakePressesByDistance() {
        if(brakePressesByDistance==null) {
            float length=a.conditions().length();
            brakePressesByDistance=indexA.brakeWindows().stream().map(BrakeWindow::press)
                    .sorted(Comparator.comparingDouble(sample->Math.min(length,sample.distance()))).toList();
        }
        return brakePressesByDistance;
    }
    public boolean observationsContinuous(double from,double to,boolean reference) { return (reference?indexB:indexA).observationsContinuous(from,to); }
    /** Observations in nondecreasing distance order, including both sides of a break at the same distance. */
    public synchronized List<ObservationPoint> observationTrace(boolean reference) {return (reference?indexB:indexA).observationTrace();}
    public Point at(float distance) {
        validateDistance(distance);
        var start=indexA.at(distance);
        return new Point(distance,start,indexA==indexB?start:indexB.at(distance));
    }
    private double deltaAt(float distance) {
        validateDistance(distance);
        return indexA==indexB?0:indexA.timeAt(distance)-indexB.timeAt(distance);
    }
    private void validateDistance(float distance) {
        if(!Float.isFinite(distance) || distance<0 || distance>a.conditions().length()) throw new IllegalArgumentException("Invalid analysis position");
    }
    /** Compare lateral offsets in the local frame of the recorded centreline distance. */
    public RoadCursor roadCursor(TrackLayout track,Point point) {
        if(!a.conditions().matchesTrack(track) || !b.conditions().matchesTrack(track))
            throw new IllegalArgumentException("Road geometry does not match both laps");
        float distance=point.distance();
        if(!Float.isFinite(distance) || distance<0 || distance>track.length)
            throw new IllegalArgumentException("Invalid analysis position");
        float centerX,centerZ,rightX,rightZ;
        if(distance==track.length) {
            // A closed road normally wraps to the outgoing heading at zero. A lap end uses the arrival heading.
            int last=track.points.size()-1;
            var end=track.points.get(last);
            var tangent=end.subtract(track.points.get(last-1)).normalizeLocal();
            centerX=end.x;centerZ=end.z;rightX=tangent.z;rightZ=-tangent.x;
        } else {
            var frame=track.sample(distance);
            centerX=frame.position().x;centerZ=frame.position().z;rightX=frame.right().x;rightZ=frame.right().z;
        }
        double offsetA=lateralOffset(point.a(),centerX,centerZ,rightX,rightZ);
        double offsetB=lateralOffset(point.b(),centerX,centerZ,rightX,rightZ);
        return new RoadCursor(centerX,centerZ,rightX,rightZ,offsetA,offsetB);
    }

    private static double lateralOffset(TelemetryLap.Sample point,float centerX,float centerZ,float rightX,float rightZ) {
        return ((double)point.x()-centerX)*rightX+((double)point.z()-centerZ)*rightZ;
    }
    /** Use each lap's own clock and all recorded samples between its two distance crossings. */
    public Interval interval(float from,float to) {
        validateIntervalOrder(from,to);
        var entry=at(from);
        var exit=at(to);
        var a=indexA.traversal(entry.a(),exit.a());
        return new Interval(from,to,a,indexA==indexB?a:indexB.traversal(entry.b(),exit.b()));
    }

    /** The recorded positions in time order between each lap's two distance crossings. */
    public IntervalTrajectory intervalTrajectory(float from,float to) {
        validateIntervalOrder(from,to);
        var entry=at(from);
        var exit=at(to);
        var pathA=indexA.trajectory(entry.a(),exit.a());
        var brakesA=indexA.brakeActions(entry.a().time(),exit.a().time());
        return new IntervalTrajectory(from,to,pathA,indexA==indexB?pathA:indexB.trajectory(entry.b(),exit.b()),
            brakesA,indexA==indexB?brakesA:indexB.brakeActions(entry.b().time(),exit.b().time()));
    }

    private static void validateIntervalOrder(float from,float to) {
        if(!(from<to)) throw new IllegalArgumentException("Interval must move forward along the lap");
    }

    /** Full-distance view, rebased to the selected start when an interval exists. */
    public DeltaTrace timeDeltaTrace(Interval selected) {
        var full=fullTimeDelta();
        if(selected==null) return full;
        double baseline=deltaAt(selected.from());
        var first=new DeltaPoint(selected.from(),0);
        var last=new DeltaPoint(selected.to(),deltaAt(selected.to())-baseline);
        var boundaries=Float.compare(first.distance(),last.distance())<=0?List.of(first,last):List.of(last,first);
        var points=new ArrayList<DeltaPoint>(full.points().size()+2);
        int boundary=0;
        for(var point:full.points()) {
            boolean replaced=false;
            while(boundary<boundaries.size() && Float.compare(boundaries.get(boundary).distance(),point.distance())<=0) {
                var exact=boundaries.get(boundary++);
                if(exact.distance()==point.distance()) {
                    // Preserve both values at a jump. Signed zero still belongs to the selected boundary.
                    replaced=Float.compare(exact.distance(),point.distance())!=0;
                    if(replaced) points.add(new DeltaPoint(exact.distance(),point.seconds()-baseline));
                } else points.add(exact);
            }
            if(!replaced) points.add(new DeltaPoint(point.distance(),point.seconds()-baseline));
        }
        return deltaTrace(baseline,points);
    }

    private synchronized DeltaTrace fullTimeDelta() {
        if(fullTimeDelta!=null) return fullTimeDelta;
        var grid=plot();
        var jumpsA=indexA==indexB?List.<ArrivalJump>of():indexA.arrivalJumps();
        var jumpsB=indexA==indexB?jumpsA:indexB.arrivalJumps();
        var points=new ArrayList<DeltaPoint>(grid.size());
        int step=0,nextA=0,nextB=0;
        while(nextA<jumpsA.size() || nextB<jumpsB.size()) {
            var a=nextA<jumpsA.size()?jumpsA.get(nextA):null;
            var b=nextB<jumpsB.size()?jumpsB.get(nextB):null;
            float distance=a==null?b.distance():b==null?a.distance():Math.min(a.distance(),b.distance());
            while(step<grid.size() && grid.get(step).distance()<distance) {
                var point=grid.get(step++);
                points.add(new DeltaPoint(point.distance(),point.delta()));
            }
            var point=step<grid.size() && grid.get(step).distance()==distance?grid.get(step++):null;
            boolean jumpsHereA=a!=null && a.distance()==distance,jumpsHereB=b!=null && b.distance()==distance;
            double beforeA=jumpsHereA?a.before():point!=null?point.a().time():indexA.timeAt(distance);
            double beforeB=jumpsHereB?b.before():point!=null?point.b().time():indexB.timeAt(distance);
            double before=beforeA-beforeB;
            double after=(jumpsHereA?a.after():beforeA)-(jumpsHereB?b.after():beforeB);
            points.add(new DeltaPoint(distance,before));
            if(after!=before) points.add(new DeltaPoint(distance,after));
            if(jumpsHereA) nextA++;
            if(jumpsHereB) nextB++;
        }
        while(step<grid.size()) {
            var point=grid.get(step++);
            points.add(new DeltaPoint(point.distance(),point.delta()));
        }
        fullTimeDelta=deltaTrace(0,points);
        return fullTimeDelta;
    }

    private static DeltaTrace deltaTrace(double baseline,List<DeltaPoint> points) {
        double peak=0;
        for(var point:points) peak=Math.max(peak,Math.abs(point.seconds()));
        double extent=Math.max(.05,Math.ceil(peak*20)/20);
        return new DeltaTrace(baseline,extent,points);
    }

    public List<Segment> losses() {
        return segmentSummary().losses();
    }

    public SegmentExtremes segmentExtremes() {
        return segmentSummary().extremes();
    }

    private SegmentSummary segmentSummary() {
        if(segmentSummary!=null) return segmentSummary;
        var losses=new ArrayList<Segment>();
        Segment mostTimeGained=null;
        float length=a.conditions().length();
        float from=0;
        double entryDelta=deltaAt(from);
        for(int i=1;i<=ANALYSIS_SEGMENTS;i++) {
            float to=i==ANALYSIS_SEGMENTS?length:length*(i/(float)ANALYSIS_SEGMENTS);
            double exitDelta=deltaAt(to);
            double delta=exitDelta-entryDelta;
            if(delta>MIN_SEGMENT_DELTA_SECONDS) losses.add(new Segment(from,to,delta));
            else if(delta < -MIN_SEGMENT_DELTA_SECONDS && (mostTimeGained==null || delta<mostTimeGained.delta()))
                mostTimeGained=new Segment(from,to,delta);
            from=to;
            entryDelta=exitDelta;
        }
        losses.sort(Comparator.comparingDouble(Segment::delta).reversed().thenComparingDouble(Segment::from));
        var topLosses=List.copyOf(losses.subList(0,Math.min(3,losses.size())));
        var extremes=new SegmentExtremes(topLosses.isEmpty()?null:topLosses.get(0),mostTimeGained);
        segmentSummary=new SegmentSummary(topLosses,extremes);
        return segmentSummary;
    }
    public static List<Brake> braking(TelemetryLap lap) {
        return readBrakeWindows(lap).stream().map(window->brakePoint(window.press())).toList();
    }
    private static Brake brakePoint(TelemetryLap.Sample sample) {return new Brake(sample.distance(),sample.time());}
    private static boolean sustainedBrake(double start,double end) {
        // Decimal 20 Hz timestamps can fall one representable double below the 100 ms boundary.
        return end>=Math.nextDown(start+.1);
    }
    private static List<BrakeWindow> readBrakeWindows(TelemetryLap lap) {
        var windows=new ArrayList<BrakeWindow>();
        TelemetryLap.Sample press=null;
        for(var sample:lap.samples()) {
            if(press==null && sample.driving().brake()>=.1f) {
                press=sample;
            } else if(press!=null && sample.driving().brake()<=.05f) {
                if(sustainedBrake(press.time(),sample.time()))
                    windows.add(new BrakeWindow(press,sample,sample.time()));
                press=null;
            }
        }
        double end=lap.samples().get(lap.samples().size()-1).time();
        if(press!=null && sustainedBrake(press.time(),end)) {
            windows.add(new BrakeWindow(press,null,end));
        }
        return List.copyOf(windows);
    }
}
