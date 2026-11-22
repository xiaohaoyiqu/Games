package com.f1car.model;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Drafts may have unfinished geometry; malformed data is rejected before the editor sees it. */
public record CircuitDocument(String format,int version,String id,String name,float width,List<Point> anchors,CircuitScene scene,CircuitCard card) {
    private static final Gson JSON = new Gson();
    public static final int MAX_ANCHORS=48;
    public record Point(float x,float z) {
        public Point { if(!Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>240 || Math.abs(z)>240) throw new IllegalArgumentException("Invalid route point"); }
    }
    private record RoadInput(String algorithm,String id,float width,List<Point> anchors,CircuitScene scene) {}
    // Null is deliberate: Gson omits absent metadata, preserving legacy package checksums.
    public CircuitDocument(String format,int version,String id,String name,float width,List<Point> anchors,CircuitScene scene) {
        this(format,version,id,name,width,anchors,scene,null);
    }
    public CircuitDocument {
        if(!"formula-circuit".equals(format) || version!=1 || id==null || !id.matches("user-[a-f0-9]{32}") || name==null || name.isBlank() || name.length()>60
            || name.chars().anyMatch(Character::isISOControl) || !Float.isFinite(width) || width<12 || width>24 || anchors==null || anchors.size()>MAX_ANCHORS || scene==null)
                throw new IllegalArgumentException("Invalid circuit document");
        anchors=List.copyOf(anchors);
    }
    public static String newId() { return "user-"+UUID.randomUUID().toString().replace("-",""); }
    public static CircuitDocument create() {
        return new CircuitDocument("formula-circuit",1,newId(),"我的环线",16,List.of(new Point(0,-130),new Point(80,-130),new Point(130,-90),new Point(130,90),
            new Point(80,130),new Point(-80,130),new Point(-130,90),new Point(-130,-90),new Point(-80,-130)),new CircuitScene(TrackCatalog.Theme.FOREST,8142,List.of()));
    }
    public static CircuitDocument copy(TrackLayout track) {
        var anchors=track.controlPoints().stream().map(p->new Point(p.x,p.z)).toList();
        return new CircuitDocument("formula-circuit",1,newId(),track.name+"副本",track.width,anchors,
            new CircuitScene(TrackCatalog.forLayout(track).theme(),TrackCatalog.forLayout(track).scenerySeed(),track.scene()==null?List.of():track.scene().items()));
    }
    public CircuitCard metadata() { return card==null?CircuitCard.EMPTY:card; }
    public CircuitDocument withCard(CircuitCard value) { Objects.requireNonNull(value);return new CircuitDocument(format,version,id,name,width,anchors,scene,value.empty()?null:value); }
    public CircuitDocument points(List<Point> points) { return new CircuitDocument(format,version,id,name,width,points,scene,card); }
    public CircuitDocument scenery(CircuitScene value) { return new CircuitDocument(format,version,id,name,width,anchors,value,card); }
    public CircuitDocument renamed(String value) { return new CircuitDocument(format,version,id,value.strip(),width,anchors,scene,card); }
    public CircuitDocument roadWidth(float value) { return new CircuitDocument(format,version,id,name,value,anchors,scene,card); }
    public CircuitDocument importedCopy() { return new CircuitDocument(format,version,newId(),name,width,anchors,scene,card); }
    /** Match the course and start anchor when transferring practice settings across map identities. */
    public boolean sameDrivingLayout(CircuitDocument other) {
        return Float.compare(width,other.width)==0 && anchors.equals(other.anchors) && scene.equals(other.scene);
    }
    public String driveKey() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJson(new RoadInput("uniform-catmull-v1",id,width,anchors,scene)).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public TrackLayout track() {
        return TrackLayout.fromAnchors(id,name,width,anchorCoordinates(),scene);
    }
    /** Road drawing only: no scenery/colliders and never a qualification or playable layout. */
    public TrackLayout roadPreview() {
        return roadPreview(null);
    }

    TrackLayout roadPreview(TrackLayout.AnchorSamples samples) {
        return TrackLayout.fromPreviewAnchors(id,name,width,anchorCoordinates(),samples);
    }

    private float[][] anchorCoordinates() {
        if (anchors.size() < 4) throw new IllegalArgumentException("至少需要四个路线点");
        var coordinates = new float[anchors.size()][2];
        for (int i = 0; i < anchors.size(); i++) {
            var point = anchors.get(i);
            coordinates[i][0] = point.x();
            coordinates[i][1] = point.z();
        }
        return coordinates;
    }
}
