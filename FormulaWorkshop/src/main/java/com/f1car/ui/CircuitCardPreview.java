package com.f1car.ui;

import com.f1car.model.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

/** Live, clipped route thumbnails, including unfinished drafts; no external image dependencies. */
public final class CircuitCardPreview {
    private CircuitCardPreview() {}

    static final class Cached {
        // One fixed card slot at up to the native HUD resolution.
        private static final int MAX_PIXELS=500_000;
        private CircuitDocument previousDocument;
        private TrackLayout previousTrack;
        private Rectangle previousArea;
        private AffineTransform previousViewport;
        private Object previousStroke,previousText,previousMetrics;
        private boolean checked;
        private HudRasterLayer raster;

        void clear() {
            previousDocument=null;
            previousTrack=null;
            raster=null;
        }

        HudRasterLayer layer(Graphics2D g,CircuitDocument document,CircuitInspector.Report report,Rectangle area) {
            var viewport=g.getTransform();
            if(!HudRasterLayer.supports(g,viewport)) return null;
            Object stroke=g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
            Object text=g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
            Object metrics=g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
            if(previousDocument!=document || previousTrack!=report.track() || !area.equals(previousArea)
                    || !viewport.equals(previousViewport) || previousStroke!=stroke || previousText!=text || previousMetrics!=metrics) {
                previousDocument=document;
                previousTrack=report.track();
                previousArea=new Rectangle(area);
                previousViewport=viewport;
                previousStroke=stroke;
                previousText=text;
                previousMetrics=metrics;
                checked=false;
                raster=null;
                return null; // Render directly while the HUD scale or position is changing.
            }
            if(!checked) {
                raster=HudRasterLayer.capture(g,viewport,area,MAX_PIXELS,image->CircuitCardPreview.draw(image,document,report,area));
                checked=true;
            }
            return raster;
        }

        void draw(Graphics2D g,CircuitDocument document,CircuitInspector.Report report,Rectangle area) {
            var layer=layer(g,document,report,area);
            if(layer==null) CircuitCardPreview.draw(g,document,report,area);
            else layer.draw(g);
        }
    }

    public static void draw(Graphics2D graphics,CircuitDocument document,CircuitInspector.Report report,Rectangle area) {
        if(area.width<40 || area.height<40) throw new IllegalArgumentException("Preview is too small");
        var g=(Graphics2D)graphics.create();
        try {
            g.clip(area);g.setColor(new Color(12,31,41));g.fill(area);var track=report.track();TrackMapView.Transform map;
            if(track!=null) {
                map=TrackMapView.draw(g,track,area,14,new Color(162,186,189),3);
                var start=track.sample(0).position();g.setColor(new Color(76,235,193));g.fillOval(Math.round(map.x(start.x))-3,Math.round(map.y(start.z))-3,6,6);
            } else {
                float minX=0,maxX=0,minZ=0,maxZ=0;
                for(var point:document.anchors()) { minX=Math.min(minX,point.x());maxX=Math.max(maxX,point.x());minZ=Math.min(minZ,point.z());maxZ=Math.max(maxZ,point.z()); }
                float scale=Math.min((area.width-28f)/Math.max(20,maxX-minX),(area.height-28f)/Math.max(20,maxZ-minZ));
                map=new TrackMapView.Transform(scale,(minX+maxX)*.5f,(minZ+maxZ)*.5f,area.x+area.width*.5f,area.y+area.height*.5f);
                var path=new Path2D.Float();boolean first=true;g.setColor(new Color(245,215,132));g.setStroke(new BasicStroke(2));
                for(var point:document.anchors()) { float x=map.x(point.x()),y=map.y(point.z());if(first) { path.moveTo(x,y);first=false; }else path.lineTo(x,y);g.fillOval(Math.round(x)-2,Math.round(y)-2,4,4); }g.draw(path);
                if(document.anchors().isEmpty()) { g.setFont(new Font("Microsoft YaHei",Font.PLAIN,12));g.drawString("尚无路线点",area.x+8,area.y+area.height/2); }
            }
            for(var item:document.scene().items()) {
                var object=(Graphics2D)g.create();try {
                    object.translate(map.x(item.x()),map.y(item.z()));object.setColor(item.solid()?new Color(255,169,114):new Color(62,167,112));
                    if(item.solid()) { object.rotate(Math.atan2(-item.tangent().z,item.tangent().x));int length=Math.max(2,Math.round(item.length()*map.scale())),width=Math.max(2,Math.round(item.width()*map.scale()));object.fillRect(-length/2,-width/2,length,width); }
                    else object.fillOval(-2,-2,4,4);
                }finally { object.dispose(); }
            }
        }finally { g.dispose(); }
    }
}
