package com.f1car.model;

import com.jme3.math.Vector3f;
import java.awt.geom.Line2D;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Conservative flat-circuit checks against the sampled road, not just its anchor polygon. */
public final class CircuitInspector {
    public enum Code {
        GEOMETRY("路线结构"),LENGTH("路线长度"),BOUNDS("工作区域"),CROSSING("道路自交"),CLEARANCE("路段间距"),CURVATURE("弯道半径"),START("发车区域"),OBJECT("场景占地"),REJOIN("汇入空间");
        public final String title;Code(String title) { this.title=title; }
    }
    public record Location(float x,float z) {
        public Location { if(!Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>1000 || Math.abs(z)>1000) throw new IllegalArgumentException("Invalid diagnostic location"); }
        static Location of(Vector3f point) { return new Location(point.x,point.z); }
    }
    public record Issue(Code code,String message,List<Location> locations,int anchor,int item) {
        public Issue { if(code==null || message==null || message.isBlank() || locations==null || locations.size()>3 || anchor< -1 || anchor>47 || item< -1 || item>63 || anchor>=0 && item>=0) throw new IllegalArgumentException("Invalid circuit diagnostic");locations=List.copyOf(locations); }
    }
    public record Report(TrackLayout track,List<String> errors,List<Issue> issues) {
        public Report { errors=List.copyOf(errors);issues=List.copyOf(issues);if(!errors.equals(issues.stream().map(Issue::message).toList())) throw new IllegalArgumentException("Diagnostic messages differ"); }
        public Report(TrackLayout track,List<String> errors) { this(track,errors,errors.stream().map(message->new Issue(Code.GEOMETRY,message,List.of(),-1,-1)).toList()); }
        public boolean ready() { return errors.isEmpty() && track!=null; }
    }
    private CircuitInspector() {}

    private record RejoinCheck(List<CircuitScene.Item> solids,boolean available) {}

    /** Inspection of the current draft, reusing geometry while its inputs still match. */
    public static final class DraftInspection {
        private final TrackLayout.AnchorSamples anchorSamples=new TrackLayout.AnchorSamples();
        private final Map<CircuitScene.Item,Boolean> roadIntrusions=new HashMap<>();
        private CircuitDocument previewDocument,reportDocument;
        private TrackLayout preview;
        private List<Issue> roadIssues,sceneryIssues;
        private List<CircuitScene.Item> inspectedItems;
        private RejoinCheck rejoinCheck;
        private Report report;

        TrackLayout preview(CircuitDocument document) {
            if(previewDocument!=document) {
                if(previewDocument==null || !previewDocument.id().equals(document.id())
                        || previewDocument.width()!=document.width() || !previewDocument.anchors().equals(document.anchors())) {
                    try {preview=document.roadPreview(anchorSamples);}
                    catch(IllegalArgumentException error) {preview=null;}
                    roadIssues=null;
                    sceneryIssues=null;
                    inspectedItems=null;
                    rejoinCheck=null;
                    roadIntrusions.clear();
                }
                previewDocument=document;
            }
            return preview;
        }

        public Report inspect(CircuitDocument document) {
            Objects.requireNonNull(document);
            if(reportDocument==document) return report;
            var road=preview(document);
            if(roadIssues==null) roadIssues=inspectRoad(document,road);
            if(road==null) {
                report=report(null,roadIssues);
            } else {
                var previous=report==null?null:report.track();
                var base=previous!=null && previous.points==road.points?previous:road;
                var track=base.withNameAndScene(document.name(),document.scene());
                var items=document.scene().items();
                if(sceneryIssues==null || !items.equals(inspectedItems)) {
                    // Keep road results for surviving footprints, then resolve issue indices in the new order.
                    roadIntrusions.keySet().retainAll(new HashSet<>(items));
                    Function<CircuitScene.Item,Boolean> check=item->TrackScenery.intrudesOnRoad(track,item);
                    sceneryIssues=inspectScenery(track,items,item->roadIntrusions.computeIfAbsent(item,check),canRejoin(track,items));
                    inspectedItems=items;
                }
                report=combine(track,roadIssues,sceneryIssues);
            }
            reportDocument=document;
            return report;
        }

        private boolean canRejoin(TrackLayout track,List<CircuitScene.Item> items) {
            var solids=items.stream().filter(CircuitScene.Item::solid).toList();
            if(rejoinCheck==null || !rejoinCheck.solids().equals(solids)) {
                rejoinCheck=new RejoinCheck(solids,TrackScenery.rejoinSpawn(track).isPresent());
            }
            return rejoinCheck.available();
        }
    }

    /** Conservative ranges only prune pairs; original loop order and exact predicates remain authoritative. */
    private static final class SegmentRanges {
        private static final int SEGMENTS_PER_BLOCK=16;
        private final int count;
        private final float[] values;
        private final float[] blocks;
        private final boolean[] degenerateBlocks;
        SegmentRanges(List<Vector3f> points,boolean closed) {
            count=points.size()-(closed?0:1);
            values=new float[count*4];
            int blockCount=(count+SEGMENTS_PER_BLOCK-1)/SEGMENTS_PER_BLOCK;
            blocks=new float[blockCount*4];
            degenerateBlocks=new boolean[blockCount];
            for(int i=0;i<count;i++) {
                var a=points.get(i);var b=points.get((i+1)%points.size());int at=i*4;
                values[at]=Math.min(a.x,b.x);values[at+1]=Math.max(a.x,b.x);
                values[at+2]=Math.min(a.z,b.z);values[at+3]=Math.max(a.z,b.z);
                int index=i/SEGMENTS_PER_BLOCK,base=index*4;
                if(i%SEGMENTS_PER_BLOCK==0) System.arraycopy(values,at,blocks,base,4);
                else {
                    blocks[base]=Math.min(blocks[base],values[at]);
                    blocks[base+1]=Math.max(blocks[base+1],values[at+1]);
                    blocks[base+2]=Math.min(blocks[base+2],values[at+2]);
                    blocks[base+3]=Math.max(blocks[base+3],values[at+3]);
                }
                degenerateBlocks[index]|=degenerate(at);
            }
        }
        int nextCandidate(int first,int from,double margin) {
            int a=first*4;
            boolean point=degenerate(a);
            double expanded=margin+1e-4;
            for(int b=from;b<count;) {
                int block=b/SEGMENTS_PER_BLOCK,end=Math.min(count,(block+1)*SEGMENTS_PER_BLOCK);
                if(overlaps(a,blocks,block*4,expanded) || point && degenerateBlocks[block]) {
                    for(;b<end;b++) {
                        int other=b*4;
                        // Line2D treats two zero-length segments as intersecting.
                        if(overlaps(a,values,other,expanded) || point && degenerate(other)) return b;
                    }
                } else b=end;
            }
            return count;
        }
        private boolean overlaps(int a,float[] bounds,int b,double margin) {
            return values[a]<=bounds[b+1]+margin && bounds[b]<=values[a+1]+margin
                && values[a+2]<=bounds[b+3]+margin && bounds[b+2]<=values[a+3]+margin;
        }
        private boolean degenerate(int offset) {return values[offset]==values[offset+1] && values[offset+2]==values[offset+3];}
    }
    public static Report inspect(CircuitDocument document) {
        TrackLayout track;
        try {track=document.track();}
        catch(IllegalArgumentException error) {return report(null,inspectRoad(document,null));}
        var roadIssues=inspectRoad(document,track);
        return combine(track,roadIssues,
                inspectScenery(track,document.scene().items(),item->TrackScenery.intrudesOnRoad(track,item),
                        TrackScenery.rejoinSpawn(track).isPresent()));
    }

    private static List<Issue> inspectRoad(CircuitDocument document,TrackLayout track) {
        if(track==null) {
            int index=document.anchors().isEmpty()?-1:0;
            for(int i=0;i<document.anchors().size();i++) { var a=document.anchors().get(i);var b=document.anchors().get((i+1)%document.anchors().size());if(Math.hypot(a.x()-b.x(),a.z()-b.z())<1) { index=(i+1)%document.anchors().size();break; } }
            var locations=index<0?List.<Location>of():List.of(new Location(document.anchors().get(index).x(),document.anchors().get(index).z()));
            return List.of(new Issue(Code.GEOMETRY,"路线尚未闭合成可用环线：检查点数、重复点和过近的点。",locations,index,-1));
        }
        var issues=new ArrayList<Issue>();var bounds=track.bounds();
        var points = List.copyOf(track.points);
        if(track.length<400 || track.length>2200) issues.add(new Issue(Code.LENGTH,"路线长度需要在 400–2200 米之间。",List.of(),-1,-1));
        if(Math.max(Math.max(Math.abs(bounds.minX()),Math.abs(bounds.maxX())),Math.max(Math.abs(bounds.minZ()),Math.abs(bounds.maxZ())))>280) {
            var point=points.stream().max(Comparator.comparingDouble(p->Math.max(Math.abs(p.x),Math.abs(p.z)))).orElseThrow();issues.add(located(document,Code.BOUNDS,"曲线超出工作区域，请收回边缘的路线点。",point));
        }
        int segments=points.size()-1;
        var roadRanges=new SegmentRanges(points,false);
        outer:for(int a=0;a<segments;a++) for(int b=roadRanges.nextCandidate(a,a+2,0);b<segments;b=roadRanges.nextCandidate(a,b+1,0)) {
            if(a==0 && b==segments-1) continue;
            if(crosses(points.get(a),points.get(a+1),points.get(b),points.get(b+1))) { issues.add(located(document,Code.CROSSING,"道路中心线自交，请拆开相交路段。",intersection(points.get(a),points.get(a+1),points.get(b),points.get(b+1))));break outer; }
        }
        if(track.length>=400 && track.length<=2200) {
            int count=(int)Math.ceil(track.length/4);float step=track.length/count;
            var samples=new ArrayList<Vector3f>();for(int i=0;i<count;i++) samples.add(track.pointAlong(i*step));
            var sampleRanges=new SegmentRanges(samples,true);
            double margin=track.width+4;
            float minArc=Math.max(40,track.width*3);
            outer:for(int a=0;a<count;a++) for(int b=sampleRanges.nextCandidate(a,a+1,margin);b<count;b=sampleRanges.nextCandidate(a,b+1,margin)) {
                float arc=Math.min(b-a,count-(b-a))*step;if(arc<minArc) continue;
                if(separation(samples.get(a),samples.get((a+1)%count),samples.get(b),samples.get((b+1)%count))<track.width+4) {
                    var first=samples.get(a).add(samples.get((a+1)%count)).mult(.5f);var second=samples.get(b).add(samples.get((b+1)%count)).mult(.5f);
                    issues.add(new Issue(Code.CLEARANCE,"相隔较远的路段过近，请留出道路宽度和缓冲区。",List.of(Location.of(first),Location.of(second)),nearest(document,first),-1));break outer;
                }
            }
            for(int i=0;i<count;i++) {
                float angle=track.turnAngle(i*step-6,i*step+6);
                if(angle>.0001 && 12/angle<Math.max(20,track.width*.5f+10)) { issues.add(located(document,Code.CURVATURE,"存在过紧弯道，请放宽相邻路线点。",track.sample(i*step).position()));break; }
            }
        }
        var heading=track.sample(0).tangent();
        for(float distance=-40;distance<=20;distance+=4) if(heading.dot(track.sample(distance).tangent())<.97f) {
            issues.add(new Issue(Code.START,"起点前 40 米、后 20 米需要较平顺的发车区，可调整起点或路线。",List.of(Location.of(track.sample(0).position()),Location.of(track.sample(distance).position())),0,-1));break;
        }
        return issues;
    }

    private static List<Issue> inspectScenery(TrackLayout track,List<CircuitScene.Item> items,
            Predicate<CircuitScene.Item> intrudesOnRoad,boolean rejoinAvailable) {
        var issues=new ArrayList<Issue>();
        for(int index=0;index<items.size();index++) {
            var item=items.get(index);
            if(intrudesOnRoad.test(item)) {
                issues.add(new Issue(Code.OBJECT,"场景物件离道路过近，请移到缓冲区外。",List.of(new Location(item.x(),item.z())),-1,index));
                break;
            }
        }
        if(!rejoinAvailable) issues.add(new Issue(Code.REJOIN,"起点旁缺少安全汇入空间，请移开护栏或拉开相邻路段。",List.of(Location.of(track.sample(0).position())),0,-1));
        return issues;
    }

    private static Report combine(TrackLayout track,List<Issue> road,List<Issue> scenery) {
        var issues=new ArrayList<Issue>(road.size()+scenery.size());
        issues.addAll(road);
        issues.addAll(scenery);
        return report(track,issues);
    }
    private static Report report(TrackLayout track,List<Issue> issues) { return new Report(track,issues.stream().map(Issue::message).toList(),issues); }
    private static Issue located(CircuitDocument document,Code code,String text,Vector3f point) { return new Issue(code,text,List.of(Location.of(point)),nearest(document,point),-1); }
    private static int nearest(CircuitDocument document,Vector3f point) {
        int index=-1;double best=Double.POSITIVE_INFINITY;for(int i=0;i<document.anchors().size();i++) { var anchor=document.anchors().get(i);double distance=Math.hypot(anchor.x()-point.x,anchor.z()-point.z);if(distance<best) { best=distance;index=i; } }return index;
    }
    private static Vector3f intersection(Vector3f a,Vector3f b,Vector3f c,Vector3f d) {
        double rx=b.x-a.x,rz=b.z-a.z,sx=d.x-c.x,sz=d.z-c.z,denominator=rx*sz-rz*sx;
        if(Math.abs(denominator)>1e-9) { double t=((c.x-a.x)*sz-(c.z-a.z)*sx)/denominator;return new Vector3f((float)(a.x+t*rx),0,(float)(a.z+t*rz)); }
        if(Line2D.ptSegDistSq(c.x,c.z,d.x,d.z,a.x,a.z)<1e-6) return a.clone();if(Line2D.ptSegDistSq(c.x,c.z,d.x,d.z,b.x,b.z)<1e-6) return b.clone();return c.clone();
    }
    private static boolean crosses(Vector3f a,Vector3f b,Vector3f c,Vector3f d) { return Line2D.linesIntersect(a.x,a.z,b.x,b.z,c.x,c.z,d.x,d.z); }
    private static double separation(Vector3f a,Vector3f b,Vector3f c,Vector3f d) {
        if(crosses(a,b,c,d)) return 0;
        return Math.sqrt(Math.min(Math.min(Line2D.ptSegDistSq(a.x,a.z,b.x,b.z,c.x,c.z),Line2D.ptSegDistSq(a.x,a.z,b.x,b.z,d.x,d.z)),
            Math.min(Line2D.ptSegDistSq(c.x,c.z,d.x,d.z,a.x,a.z),Line2D.ptSegDistSq(c.x,c.z,d.x,d.z,b.x,b.z))));
    }
}
