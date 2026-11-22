package com.f1car.ui;

import com.f1car.model.CircuitDocument;
import com.f1car.model.CircuitInspector;
import java.awt.Rectangle;
import java.util.List;

/** Pixel/world transforms preserve the point under the cursor when zooming. */
public final class CircuitEditorView {
    public static final Rectangle BOARD=new Rectangle(44,168,846,510);
    private float centerX,centerZ,scale=1.2f;
    private TrackMapView.Transform projection;
    public float x(float worldX) { return BOARD.x+BOARD.width*.5f+(worldX-centerX)*scale; }
    public float y(float worldZ) { return BOARD.y+BOARD.height*.5f-(worldZ-centerZ)*scale; }
    public float worldX(float x) { return centerX+(x-BOARD.x-BOARD.width*.5f)/scale; }
    public float worldZ(float y) { return centerZ-(y-BOARD.y-BOARD.height*.5f)/scale; }
    public float scale() { return scale; }

    TrackMapView.Transform projection() {
        float screenX=BOARD.x+BOARD.width*.5f,screenY=BOARD.y+BOARD.height*.5f;
        if(projection==null || projection.scale()!=scale || projection.centerX()!=centerX || projection.centerZ()!=centerZ
                || projection.screenX()!=screenX || projection.screenY()!=screenY)
            projection=new TrackMapView.Transform(scale,centerX,centerZ,screenX,screenY);
        return projection;
    }
    public void focus(List<CircuitInspector.Location> locations) {
        if(locations==null || locations.isEmpty()) return;
        float minX=locations.get(0).x(),maxX=minX,minZ=locations.get(0).z(),maxZ=minZ;
        for(var point:locations) { minX=Math.min(minX,point.x());maxX=Math.max(maxX,point.x());minZ=Math.min(minZ,point.z());maxZ=Math.max(maxZ,point.z()); }
        centerX=(minX+maxX)*.5f;centerZ=(minZ+maxZ)*.5f;scale=Math.max(.5f,Math.min(3.5f,Math.min((BOARD.width-80)/(maxX-minX+60),(BOARD.height-80)/(maxZ-minZ+60))));
    }
    public void fit(CircuitDocument document) {
        float minX=-100,maxX=100,minZ=-100,maxZ=100;
        for(var point:document.anchors()) { minX=Math.min(minX,point.x());maxX=Math.max(maxX,point.x());minZ=Math.min(minZ,point.z());maxZ=Math.max(maxZ,point.z()); }
        for(var item:document.scene().items()) { minX=Math.min(minX,item.x());maxX=Math.max(maxX,item.x());minZ=Math.min(minZ,item.z());maxZ=Math.max(maxZ,item.z()); }
        centerX=(minX+maxX)*.5f;centerZ=(minZ+maxZ)*.5f;scale=Math.max(.5f,Math.min(3.5f,Math.min((BOARD.width-60)/(maxX-minX+80),(BOARD.height-60)/(maxZ-minZ+80))));
    }
    public void pan(float dx,float dy) { centerX-=dx/scale;centerZ+=dy/scale; }
    public void zoom(boolean inward,float x,float y) {
        float beforeX=worldX(x),beforeZ=worldZ(y);scale=Math.max(.5f,Math.min(3.5f,scale*(inward?1.12f:1/1.12f)));
        centerX+=beforeX-worldX(x);centerZ+=beforeZ-worldZ(y);
    }
}
