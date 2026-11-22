package com.f1car.model;

import java.awt.Rectangle;

/** A presentation-only camera. It never changes the selected map or spawns vehicles. */
public final class TrackPreview {
    public static final Rectangle AREA=new Rectangle(480,128,752,530);
    private TrackLayout track;
    private final boolean training;
    private float centerX,centerZ,scale,fit;
    public TrackPreview(TrackLayout track,boolean training) { this.training=training;select(track); }
    public TrackLayout track() { return track; }
    public float centerX() { return centerX; }
    public float centerZ() { return centerZ; }
    public float scale() { return scale; }
    public void select(TrackLayout track) { this.track=java.util.Objects.requireNonNull(track);reset(); }
    public void reset() {
        var b=track.bounds();centerX=training?0:b.centerX();centerZ=training?-10:b.centerZ();
        fit=training?Math.min((AREA.width-60)/300f,(AREA.height-60)/340f):b.fitScale(AREA.width,AREA.height,32);scale=fit;
    }
    public void nearStart() { var p=track.sample(0).position();centerX=training?-65:p.x;centerZ=training?-100:p.z;scale=Math.min(fit*6,Math.max(fit*2,4)); }
    public float worldX(float x) { return centerX+(x-(float)AREA.getCenterX())/scale; }
    public float worldZ(float y) { return centerZ-(y-(float)AREA.getCenterY())/scale; }
    public void pan(float dx,float dy) { if(!Float.isFinite(dx) || !Float.isFinite(dy)) return;centerX=Math.max(-1000,Math.min(1000,centerX-dx/scale));centerZ=Math.max(-1000,Math.min(1000,centerZ+dy/scale)); }
    public void zoom(boolean inward,float x,float y) {
        float a=worldX(x),b=worldZ(y);scale=Math.max(fit*.5f,Math.min(fit*8,scale*(inward?1.15f:1/1.15f)));centerX+=a-worldX(x);centerZ+=b-worldZ(y);
    }
}
