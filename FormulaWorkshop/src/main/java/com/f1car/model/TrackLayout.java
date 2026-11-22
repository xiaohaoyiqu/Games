package com.f1car.model;

import com.google.gson.Gson;
import com.jme3.math.Vector3f;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.IntPredicate;

/** Single source of geometry for rendering, AI, progress, practice and recovery. */
public final class TrackLayout {
    static final float MIN_TARGET_SPEED = 12;
    static final float MAX_TARGET_SPEED = 48;
    private static final int TARGET_SPEED_CACHE_BITS=8;
    private static final int SEGMENTS_PER_BLOCK = 16;
    private static final int BLOCKS_PER_GROUP = 8;
    private static final int MAX_CURVE_SAMPLES = 4095;
    public record Sample(Vector3f position, Vector3f tangent, Vector3f right, float distance) {}
    public record Projection(float distance, float lateral, float separation, int segment) {}
    record RoadVelocity(float along, float lateral) {}
    public record Bounds(float minX, float maxX, float minZ, float maxZ) {
        public float width() { return maxX-minX; }
        public float height() { return maxZ-minZ; }
        public float centerX() { return (minX+maxX)*.5f; }
        public float centerZ() { return (minZ+maxZ)*.5f; }
        public float fitScale(float width,float height,float margin) {
            return Math.min(width/Math.max(1,width()+2*margin),height/Math.max(1,height()+2*margin));
        }
    }
    private static final class Definition {
        String id, name;
        float width;
        boolean closed;
        float[][] anchors;
        CircuitScene scene;
    }
    private static final class PointView extends AbstractList<Vector3f> implements RandomAccess {
        private final List<Vector3f> centreline;
        PointView(List<Vector3f> centreline) {this.centreline = centreline;}
        @Override public Vector3f get(int index) {return centreline.get(index).clone();}
        @Override public int size() {return centreline.size();}
    }

    private record Segment(Vector3f start, Vector3f edge, Vector3f tangent, float lengthSquared, float length) {}
    private record ProjectionNode(int from, int end, int after, float minX, float maxX, float minZ, float maxZ) {
        boolean leaf() {return from<end;}

        boolean outside(Vector3f position, float margin) {
            return position.x < minX - margin || position.x > maxX + margin
                    || position.z < minZ - margin || position.z > maxZ + margin;
        }
    }

    public final String id, name;
    public final float width, length;
    public final boolean closed;
    /** Route vertices are exposed as copies so previews cannot alter the geometry or its key. */
    public final List<Vector3f> points;
    private final transient List<Vector3f> centreline;
    private final float[] cumulative;
    private final transient Segment[] segments;
    private transient volatile ProjectionNode[] projectionIndex;
    private transient volatile AtomicLongArray targetSpeedCache;
    private transient volatile TrackScenery.Footprints sceneryFootprints;
    private transient volatile TrackGuide guide;
    private final Bounds bounds;
    private volatile String geometryKey;
    private volatile String exchangeGeometryKey;
    private final List<Vector3f> controlPoints;
    private final CircuitScene scene;

    public TrackLayout(String id, String name, float width, boolean closed, List<Vector3f> points) {
        this(id,name,width,closed,points,List.of(),null,true,null,false);
    }
    private TrackLayout(String id,String name,float width,boolean closed,List<Vector3f> points,List<Vector3f> controls,
            CircuitScene scene,boolean copyPoints,Segment[] reusableSegments,boolean deferProjectionIndex) {
        if(id==null || !id.matches("[a-z0-9][a-z0-9-]{0,63}") || name==null || name.isBlank()
            || name.length()>80 || !Float.isFinite(width) || width<=0 || width>50 || points==null
            || points.size()<2 || points.size()>4096) throw new IllegalArgumentException("Invalid track definition");
        for(var point:points) if(point==null || !Float.isFinite(point.x) || !Float.isFinite(point.y)
            || !Float.isFinite(point.z) || Math.abs(point.x)>1000 || Math.abs(point.z)>1000 || Math.abs(point.y)>.001f)
            throw new IllegalArgumentException("Track points must be finite and planar");
        if(closed && points.get(0).distance(points.get(points.size()-1))>.001f) throw new IllegalArgumentException("Track must close explicitly");
        this.id = id;
        this.name = name;
        this.width = width;
        this.closed = closed;
        // Internally sampled points are owned here and are never exposed without copying.
        centreline = copyPoints ? points.stream().map(Vector3f::clone).toList() : List.copyOf(points);
        this.points = new PointView(centreline);
        this.controlPoints=List.copyOf(controls);this.scene=scene;
        cumulative = new float[centreline.size()];
        segments = reusableSegments==null?new Segment[centreline.size()-1]:reusableSegments;
        float minX=Float.POSITIVE_INFINITY,maxX=-Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxZ=-Float.POSITIVE_INFINITY;
        for(var point:centreline) { minX=Math.min(minX,point.x);maxX=Math.max(maxX,point.x);minZ=Math.min(minZ,point.z);maxZ=Math.max(maxZ,point.z); }
        bounds=new Bounds(minX,maxX,minZ,maxZ);
        for (int i = 0; i < segments.length; i++) {
            var start = centreline.get(i);
            var finish = centreline.get(i + 1);
            float distance = finish.distance(start);
            if (distance < .0001f) throw new IllegalArgumentException("Duplicate track points");
            cumulative[i + 1] = cumulative[i] + distance;
            if(segments[i]==null) {
                var edge = finish.subtract(start);
                segments[i] = new Segment(start, edge, edge.clone().normalizeLocal(), edge.lengthSquared(), edge.length());
            }
        }
        length = cumulative[cumulative.length - 1];
        if (length < 10 || width <= 0) throw new IllegalArgumentException("Degenerate track");
        if(!deferProjectionIndex) projectionIndex=buildProjectionIndex();
    }

    private TrackLayout(TrackLayout route, String name, CircuitScene scene) {
        id = route.id;
        this.name = name;
        width = route.width;
        length = route.length;
        closed = route.closed;
        centreline = route.centreline;
        points = route.points;
        cumulative = route.cumulative;
        segments = route.segments;
        projectionIndex = route.projectionNodes();
        bounds = route.bounds;
        controlPoints = route.controlPoints;
        this.scene = scene;
        if(Objects.equals(route.scene,scene)) {
            geometryKey=route.geometryKey;
            exchangeGeometryKey=route.exchangeGeometryKey;
            sceneryFootprints=route.sceneryFootprints;
        }
    }

    public static TrackLayout circuit() {
        return loadResource("/data/circuit.json");
    }
    public static TrackLayout loadResource(String resource) {
        try (var stream = Objects.requireNonNull(TrackLayout.class.getResourceAsStream(resource));
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return fromDefinition(new Gson().fromJson(reader,Definition.class));
        } catch(Exception error) { throw new IllegalStateException("Cannot load circuit: "+resource,error); }
    }
    public static TrackLayout fromJson(String json) {
        if(json==null || json.length()>200000) throw new IllegalArgumentException("Invalid track JSON size");
        return fromDefinition(new Gson().fromJson(json,Definition.class));
    }
    static TrackLayout fromAnchors(String id, String name, float width, float[][] anchors, CircuitScene scene) {
        return fromAnchors(id,name,width,anchors,scene,null,false);
    }
    static TrackLayout fromPreviewAnchors(String id,String name,float width,float[][] anchors,AnchorSamples cache) {
        return fromAnchors(id,name,width,anchors,null,cache,true);
    }
    private static TrackLayout fromDefinition(Definition d) {
        if(d==null || !d.closed) throw new IllegalArgumentException("Circuit needs 4 to 128 anchors and a closed route");
        return fromAnchors(d.id,d.name,d.width,d.anchors,d.scene);
    }

    private static TrackLayout fromAnchors(String id,String name,float width,float[][] anchors,CircuitScene scene,
            AnchorSamples cache,boolean deferProjectionIndex) {
        if(anchors==null || anchors.length<4 || anchors.length>128)
            throw new IllegalArgumentException("Circuit needs 4 to 128 anchors and a closed route");
        var controls=new ArrayList<Vector3f>(anchors.length);
        for(var point:anchors) {
            if(point==null || point.length!=2 || !Float.isFinite(point[0]) || !Float.isFinite(point[1])
                    || Math.abs(point[0])>900 || Math.abs(point[1])>900) throw new IllegalArgumentException("Invalid circuit anchor");
            controls.add(new Vector3f(point[0],0,point[1]));
        }
        var result=new ArrayList<Vector3f>();
        int[] offsets=cache==null?null:new int[anchors.length+1];
        for(int index=0;index<controls.size();index++) {
            if(cache==null || !cache.appendUnchanged(controls,index,result)) appendCurve(controls,index,result);
            if(offsets!=null) offsets[index+1]=result.size();
        }
        result.add(result.get(0).clone());
        var reusableSegments=cache==null?null:cache.reusableSegments(result,offsets);
        var layout=new TrackLayout(id,name,width,true,result,controls,scene,false,reusableSegments,deferProjectionIndex);
        if(cache!=null) cache.remember(layout,offsets);
        return layout;
    }

    private static void appendCurve(List<Vector3f> controls,int index,List<Vector3f> result) {
        Vector3f p0=anchor(controls,index-1),p1=anchor(controls,index);
        Vector3f p2=anchor(controls,index+1),p3=anchor(controls,index+2);
        float distance=p1.distance(p2);
        if(distance<1) throw new IllegalArgumentException("Circuit anchors are too close");
        int steps=Math.max(12,(int)(distance/3));
        checkSampleLimit(result.size()+steps);
        var constant=p1.mult(2);
        var linear=p2.subtract(p0);
        var quadratic=p0.mult(2).subtract(p1.mult(5)).add(p2.mult(4)).subtract(p3);
        var cubic=p0.negate().add(p1.mult(3)).subtract(p2.mult(3)).add(p3);
        for(int j=0;j<steps;j++) {
            float t=j/(float)steps,t2=t*t,t3=t2*t;
            // Keep the original float operation order because route signatures include the sample bits.
            result.add(new Vector3f((constant.x+linear.x*t+quadratic.x*t2+cubic.x*t3)*.5f,
                    (constant.y+linear.y*t+quadratic.y*t2+cubic.y*t3)*.5f,
                    (constant.z+linear.z*t+quadratic.z*t2+cubic.z*t3)*.5f));
        }
    }

    private static void checkSampleLimit(int count) {
        if(count>MAX_CURVE_SAMPLES) throw new IllegalArgumentException("Circuit has too many samples");
    }

    /** One successful draft's sampling and segments, divided by anchor span. */
    static final class AnchorSamples {
        private List<Vector3f> controls=List.of(),samples=List.of();
        private Segment[] segments;
        private int[] offsets;

        private boolean appendUnchanged(List<Vector3f> next,int index,List<Vector3f> destination) {
            if(next.size()!=controls.size()) return false;
            for(int control=index-1;control<=index+2;control++) {
                if(!anchor(next,control).equals(anchor(controls,control))) return false;
            }
            int start=offsets[index],end=offsets[index+1];
            checkSampleLimit(destination.size()+end-start);
            for(int sample=start;sample<end;sample++) destination.add(samples.get(sample));
            return true;
        }

        private Segment[] reusableSegments(List<Vector3f> next,int[] nextOffsets) {
            if(offsets==null || offsets.length!=nextOffsets.length) return null;
            Segment[] reused=null;
            for(int span=0;span<offsets.length-1;span++) {
                int oldStart=offsets[span],oldEnd=offsets[span+1];
                int nextStart=nextOffsets[span],nextEnd=nextOffsets[span+1];
                // Matching the first sample by identity means appendUnchanged reused the entire span.
                if(next.get(nextStart)!=samples.get(oldStart)) continue;
                int count=oldEnd-oldStart-1;
                // The last segment also needs the first sample of the following span.
                if(next.get(nextEnd)==samples.get(oldEnd)) count++;
                if(reused==null) reused=new Segment[next.size()-1];
                System.arraycopy(segments,oldStart,reused,nextStart,count);
            }
            return reused;
        }

        private void remember(TrackLayout layout,int[] offsets) {
            controls=layout.controlPoints;
            samples=layout.centreline;
            segments=layout.segments;
            this.offsets=offsets;
        }
    }
    public Bounds bounds() { return bounds; }
    /** Cumulative distance along the centreline at a vertex in points. */
    public float pointDistance(int index) { return cumulative[index]; }
    /** Copy a centreline vertex into the supplied vector and return that vector. */
    public Vector3f copyPoint(int index, Vector3f destination) {return destination.set(centreline.get(index));}
    public String geometryKey() {
        String key=geometryKey;
        if(key==null) geometryKey=key=makeGeometryKey(true);
        return key;
    }
    /** Identity-free route/collider signature for untrusted visual ghost exchange only. */
    public String exchangeGeometryKey() {
        String key=exchangeGeometryKey;
        if(key==null) exchangeGeometryKey=key=makeGeometryKey(false);
        return key;
    }
    public List<Vector3f> controlPoints() { return controlPoints.stream().map(Vector3f::clone).toList(); }
    boolean matchesAnchors(List<CircuitDocument.Point> anchors) {
        if(controlPoints.size()!=anchors.size()) return false;
        for(int i=0;i<anchors.size();i++) {
            var anchor=anchors.get(i);
            var point=controlPoints.get(i);
            if(anchor.x()!=point.x || anchor.z()!=point.z) return false;
        }
        return true;
    }
    public CircuitScene scene() { return scene; }
    TrackGuide guide() {
        var current=guide;
        if(current==null) {
            synchronized(this) {
                current=guide;
                if(current==null) guide=current=TrackGuide.build(this);
            }
        }
        return current;
    }

    TrackScenery.Footprints sceneryFootprints() {
        var footprints=sceneryFootprints;
        if(footprints==null) {
            synchronized(this) {
                footprints=sceneryFootprints;
                if(footprints==null) sceneryFootprints=footprints=TrackScenery.buildFootprints(this);
            }
        }
        return footprints;
    }

    public TrackLayout withScene(CircuitScene value) {
        return withNameAndScene(name,value);
    }
    TrackLayout withNameAndScene(String name,CircuitScene value) {
        Objects.requireNonNull(value);
        return name.equals(this.name) && value.equals(scene) ? this : new TrackLayout(this,name,value);
    }
    private String makeGeometryKey(boolean includeIdentity) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            hash.update((includeIdentity?id:"shared-ghost-route-v1").getBytes(StandardCharsets.UTF_8));
            var bytes=ByteBuffer.allocate(20);
            hash.update(bytes.putFloat(width).put((byte)(closed?1:0)).flip());
            for(var point:centreline) {
                bytes.clear();
                hash.update(bytes.putFloat(point.x).putFloat(point.y).putFloat(point.z).flip());
            }
            if(scene!=null) {
                hashObstacles(hash,bytes,CircuitScene.Kind.BARRIER,"custom-walls-v1");
                hashObstacles(hash,bytes,CircuitScene.Kind.BUILDING,"solid-buildings-v1");
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private void hashObstacles(MessageDigest hash,ByteBuffer bytes,CircuitScene.Kind kind,String marker) {
        boolean started=false;
        for(var item:scene.items()) {
            if(item.kind()!=kind) continue;
            if(!started) {
                hash.update(marker.getBytes(StandardCharsets.UTF_8));
                started=true;
            }
            bytes.clear();
            hash.update(bytes.putFloat(item.x()).putFloat(item.z()).putFloat(item.yaw()).putFloat(item.length()).putFloat(item.width()).flip());
        }
    }

    private static final class TrainingCircles {
        static final TrackLayout LEFT=buildCircle(false);
        static final TrackLayout RIGHT=buildCircle(true);
    }

    public static TrackLayout circle(boolean clockwise) {
        return clockwise?TrainingCircles.RIGHT:TrainingCircles.LEFT;
    }

    private static TrackLayout buildCircle(boolean clockwise) {
        List<Vector3f> p = new ArrayList<>();
        float sign = clockwise ? -1 : 1;
        for (int i = 0; i <= 96; i++) {
            double a = sign * 2 * Math.PI * i / 96 - Math.PI / 2;
            p.add(new Vector3f(55 + 28 * (float) Math.cos(a), 0, 20 + 28 * (float) Math.sin(a)));
        }
        return new TrackLayout(clockwise ? "circle-right" : "circle-left", "定圆训练", 10, true, p);
    }

    public Sample sample(float distance) {
        float d = sampleDistance(distance);
        int segment = segmentAt(d);
        Vector3f tangent = segments[segment].tangent().clone();
        Vector3f position=positionAt(segment,d,new Vector3f());
        Vector3f right = new Vector3f(tangent.z, 0, -tangent.x);
        return new Sample(position, tangent, right, d);
    }

    /** Samples the same position and right direction into two distinct caller-owned vectors. */
    public void samplePositionAndRight(float distance,Vector3f position,Vector3f right) {
        float d=sampleDistance(distance);
        int segment=segmentAt(d);
        positionAt(segment,d,position);
        var tangent=segments[segment].tangent();
        right.set(tangent.z,0,-tangent.x);
    }

    /** Dot product with the sampled tangent at a projection on this route. */
    public float tangentAlignment(Projection projection, Vector3f direction) {
        return direction.dot(tangentAt(projection));
    }

    RoadVelocity roadVelocity(Projection projection, Vector3f velocity) {
        var tangent = tangentAt(projection);
        return new RoadVelocity(velocity.dot(tangent), velocity.x*tangent.z + velocity.y*0f + velocity.z*(-tangent.x));
    }

    private Vector3f tangentAt(Projection projection) {
        int hint = projection.segment();
        float d = sampleDistance(projection.distance());
        // Use the sample's segment at a vertex or the finish line, where the projection may have tied on the previous segment.
        return segments[segmentAt(d, hint < segments.length ? hint : -1)].tangent();
    }

    Vector3f pointAlong(float distance) {
        float d=sampleDistance(distance);
        return positionAt(segmentAt(d),d,new Vector3f());
    }

    private Vector3f positionAt(int segment,float d,Vector3f destination) {
        Vector3f a=centreline.get(segment),b=centreline.get(segment+1);
        float fraction = (d - cumulative[segment]) / (cumulative[segment + 1] - cumulative[segment]);
        return destination.set(a).multLocal(1-fraction).addLocal(b.x*fraction,b.y*fraction,b.z*fraction);
    }

    private float sampleDistance(float distance) {
        return closed ? wrap(distance, length) : Math.max(0, Math.min(length - .001f, distance));
    }

    private int segmentAt(float sampledDistance) {
        int low = 0, high = cumulative.length - 1;
        while (low + 1 < high) {
            int mid = (low + high) / 2;
            if (cumulative[mid] <= sampledDistance) low = mid; else high = mid;
        }
        return low;
    }

    private int segmentAt(float sampledDistance, int previous) {
        if (previous >= 0 && sampledDistance >= cumulative[previous]
                && (sampledDistance < cumulative[previous + 1] || previous == segments.length - 1)) {
            return previous;
        }
        return segmentAt(sampledDistance);
    }

    public Projection project(Vector3f position) {
        return project(position,-1);
    }

    Projection project(Vector3f position,int hintSegment) {
        float best = Float.POSITIVE_INFINITY, fraction = 0;
        int nearest = -1;
        // Preserve the float projection margin and the original segment order on ties.
        float rounding = projectionRounding(position);
        float margin = Float.POSITIVE_INFINITY;
        if(hintSegment>=0 && hintSegment<segments.length) {
            var segment=segments[hintSegment];
            float dx=position.x-segment.start().x,dz=position.z-segment.start().z;
            float t=nearestFraction(segment,dx,dz);
            float sq=separationSquared(segment,dx,dz,t);
            if(sq<best) {
                best=sq;
                fraction=t;
                nearest=hintSegment;
                margin=(float)Math.sqrt(best)+rounding;
            }
        }
        var nodes=projectionNodes();
        for (int index=0;index<nodes.length;) {
            var node=nodes[index++];
            if(node.outside(position,margin)) {index=node.after();continue;}
            if(!node.leaf()) continue;
            float previous = best;
            for (int i = node.from(); i < node.end(); i++) {
                var segment = segments[i];
                var a = segment.start();
                float dx=position.x-a.x,dz=position.z-a.z;
                float t=nearestFraction(segment,dx,dz);
                float sq=separationSquared(segment,dx,dz,t);
                if (sq < best || sq==best && i<nearest) {
                    best = sq;
                    fraction = t;
                    nearest = i;
                }
            }
            if (best < previous) margin = (float)Math.sqrt(best) + rounding;
        }
        if (nearest < 0) return new Projection(0,0,Float.POSITIVE_INFINITY,0);
        var segment = segments[nearest];
        var start = segment.start();
        var edge=segment.edge();
        float dx=(position.x-start.x)-edge.x*fraction;
        float dy=0f-edge.y*fraction;
        float dz=(position.z-start.z)-edge.z*fraction;
        float distance = cumulative[nearest] + fraction * segment.length();
        var tangent = segment.tangent();
        float lateral=dx*tangent.z+dy*0f+dz*(-tangent.x);
        return new Projection(distance,lateral,(float)Math.sqrt(best),nearest);
    }

    /** Checks the same separation threshold as project(), without finding the nearest segment. */
    public boolean withinDistance(Vector3f position,float limit) {
        return withinDistance(position,limit,-1);
    }

    boolean withinDistance(Vector3f position,float limit,int hintSegment) {
        return findSegmentWithinDistance(position,limit,hintSegment)>=0;
    }

    /** Returns any segment within the limit, or -1. A hint is checked against the current position and limit. */
    public int findSegmentWithinDistance(Vector3f position,float limit,int hintSegment) {
        if(position==null || !Float.isFinite(position.x) || !Float.isFinite(position.z)
                || !Float.isFinite(limit) || limit<0) return -1;
        double outer=Math.nextUp(limit);
        double upperSquared=outer*outer;
        if(hintSegment>=0 && hintSegment<segments.length && segmentWithinDistance(segments[hintSegment],position,limit,upperSquared))
            return hintSegment;
        float margin=limit+projectionRounding(position);
        var nodes=projectionNodes();
        for(int index=0;index<nodes.length;) {
            var node=nodes[index++];
            if(node.outside(position,margin)) {index=node.after();continue;}
            for(int i=node.from();i<node.end();i++) {
                if(segmentWithinDistance(segments[i],position,limit,upperSquared)) return i;
            }
        }
        return -1;
    }

    private static boolean segmentWithinDistance(Segment segment,Vector3f position,float limit,double upperSquared) {
        var start=segment.start();
        float dx=position.x-start.x,dz=position.z-start.z;
        float squared=separationSquared(segment,dx,dz,nearestFraction(segment,dx,dz));
        return Float.isFinite(squared) && squared<=upperSquared && (float)Math.sqrt(squared)<=limit;
    }

    /** Visits segments in blocks whose padded bounds can reach the query square. */
    boolean anySegmentNear(Vector3f centre,float radius,IntPredicate test) {
        float margin=radius+projectionRounding(centre);
        var nodes=projectionNodes();
        for(int index=0;index<nodes.length;) {
            var node=nodes[index++];
            if(node.outside(centre,margin)) {index=node.after();continue;}
            for(int segment=node.from();segment<node.end();segment++) {
                if(test.test(segment)) return true;
            }
        }
        return false;
    }

    private static float projectionRounding(Vector3f position) {
        return 8*Math.ulp(Math.max(4096,Math.max(Math.abs(position.x),Math.abs(position.z))));
    }

    private static float nearestFraction(Segment segment,float dx,float dz) {
        var edge=segment.edge();
        return Math.max(0,Math.min(1,(dx*edge.x+0f*edge.y+dz*edge.z)/segment.lengthSquared()));
    }

    private static float separationSquared(Segment segment,float dx,float dz,float fraction) {
        var edge=segment.edge();
        float x=dx-edge.x*fraction,y=0f-edge.y*fraction,z=dz-edge.z*fraction;
        return x*x+y*y+z*z;
    }

    private ProjectionNode[] projectionNodes() {
        var nodes=projectionIndex;
        return nodes!=null?nodes:initializeProjectionIndex();
    }

    private synchronized ProjectionNode[] initializeProjectionIndex() {
        if(projectionIndex==null) projectionIndex=buildProjectionIndex();
        return projectionIndex;
    }

    private ProjectionNode[] buildProjectionIndex() {
        int blocks=(segments.length+SEGMENTS_PER_BLOCK-1)/SEGMENTS_PER_BLOCK;
        var nodes=new ArrayList<ProjectionNode>(blocks+(blocks+BLOCKS_PER_GROUP-1)/BLOCKS_PER_GROUP);
        for(int first=0;first<blocks;first+=BLOCKS_PER_GROUP) {
            int end=Math.min(first+BLOCKS_PER_GROUP,blocks);
            int group=nodes.size();
            boolean grouped=blocks>BLOCKS_PER_GROUP && end-first>1;
            if(grouped) nodes.add(null);
            for(int block=first;block<end;block++) {
                int fromSegment=block*SEGMENTS_PER_BLOCK;
                int endSegment=Math.min(fromSegment+SEGMENTS_PER_BLOCK,segments.length);
                nodes.add(projectionLeaf(fromSegment,endSegment,nodes.size()+1));
            }
            if(grouped) nodes.set(group,projectionGroup(nodes,group+1,nodes.size()));
        }
        return nodes.toArray(ProjectionNode[]::new);
    }

    private ProjectionNode projectionLeaf(int from,int end,int after) {
        var first=centreline.get(from);
        float minX=first.x,maxX=first.x,minZ=first.z,maxZ=first.z;
        for(int index=from+1;index<=end;index++) {
            var point=centreline.get(index);
            minX=Math.min(minX,point.x);
            maxX=Math.max(maxX,point.x);
            minZ=Math.min(minZ,point.z);
            maxZ=Math.max(maxZ,point.z);
        }
        return new ProjectionNode(from,end,after,minX,maxX,minZ,maxZ);
    }

    private static ProjectionNode projectionGroup(List<ProjectionNode> nodes,int first,int after) {
        var head=nodes.get(first);
        float minX=head.minX(),maxX=head.maxX(),minZ=head.minZ(),maxZ=head.maxZ();
        for(int i=first+1;i<after;i++) {
            var node=nodes.get(i);
            minX=Math.min(minX,node.minX());
            maxX=Math.max(maxX,node.maxX());
            minZ=Math.min(minZ,node.minZ());
            maxZ=Math.max(maxZ,node.maxZ());
        }
        // A group only supplies bounds. Its skip target follows all of its ordered leaves.
        return new ProjectionNode(0,0,after,minX,maxX,minZ,maxZ);
    }

    public float targetSpeed(float distance) {
        return targetSpeed(segmentAt(sampleDistance(distance-8)),segmentAt(sampleDistance(distance+12)));
    }

    private float targetSpeed(int before,int after) {
        if(before==after) return MAX_TARGET_SPEED;
        var cache=targetSpeedCache;
        if(cache==null) cache=initializeTargetSpeedCache();
        int key=before*segments.length+after+1;
        int slot=(key*0x9e3779b9) >>> (Integer.SIZE-TARGET_SPEED_CACHE_BITS);
        long entry=cache.get(slot);
        if((int)(entry>>>Integer.SIZE)==key) return Float.intBitsToFloat((int)entry);

        float curvature=turnAngle(segments[before].tangent(),segments[after].tangent())/20;
        float speed=Math.max(MIN_TARGET_SPEED, Math.min(MAX_TARGET_SPEED, (float) Math.sqrt(7.5f / Math.max(.003f, curvature))));
        // Publish the segment pair and its speed together so a concurrent replacement cannot mix the two.
        cache.set(slot,((long)key<<Integer.SIZE)|Integer.toUnsignedLong(Float.floatToRawIntBits(speed)));
        return speed;
    }

    private synchronized AtomicLongArray initializeTargetSpeedCache() {
        if(targetSpeedCache==null) targetSpeedCache=new AtomicLongArray(1<<TARGET_SPEED_CACHE_BITS);
        return targetSpeedCache;
    }

    /** Per-driver storage for recurring look-ahead distances on this immutable route. */
    final class TargetSpeedSamples {
        private final int[] before,after;
        private final float[] speed;

        TargetSpeedSamples(int count) {
            before=new int[count];
            after=new int[count];
            speed=new float[count];
            Arrays.fill(before,-1);
            Arrays.fill(after,-1);
        }

        float at(int index,float distance) {
            int start=segmentAt(sampleDistance(distance-8),before[index]);
            int end=segmentAt(sampleDistance(distance+12),after[index]);
            if(start!=before[index] || end!=after[index]) {
                speed[index]=targetSpeed(start,end);
                before[index]=start;
                after[index]=end;
            }
            return speed[index];
        }
    }

    float turnAngle(float from,float to) {
        var a=segments[segmentAt(sampleDistance(from))].tangent();
        var b=segments[segmentAt(sampleDistance(to))].tangent();
        return turnAngle(a,b);
    }

    private static float turnAngle(Vector3f a,Vector3f b) {
        return Math.abs((float)Math.atan2(a.x*b.z-a.z*b.x,a.dot(b)));
    }

    public static float wrap(float value, float length) {
        if (!(length > 0) || !Float.isFinite(length)) return (value % length + length) % length;
        if (value > 0 && value < length) return value;
        float remainder = value % length;
        if (remainder < 0) {
            float wrapped = length + remainder;
            // A point immediately before the line still belongs to the final road segment.
            return wrapped == length ? Math.nextDown(length) : wrapped;
        }
        return remainder == 0 ? 0f : remainder;
    }
    private static Vector3f anchor(List<Vector3f> points,int index) {
        return points.get(Math.floorMod(index,points.size()));
    }
}
