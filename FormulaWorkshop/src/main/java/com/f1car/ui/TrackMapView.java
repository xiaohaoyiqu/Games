package com.f1car.ui;

import com.f1car.model.TrackLayout;
import com.jme3.math.Vector3f;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

/** Aspect-preserving, north-up map framing used by selection previews and the driving minimap. */
public final class TrackMapView {
    static final class Outline {
        private final Transform transform;
        private final Path2D.Float path;
        private final Color color;
        private final BasicStroke stroke;

        Outline(TrackLayout track, Rectangle area, int padding, Color color, float width) {
            transform = fit(track, area, padding);
            path = path(track, transform);
            this.color = color;
            stroke = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }

        Transform draw(Graphics2D graphics) {
            applyStyle(graphics);
            graphics.draw(path);
            return transform;
        }

        private void applyStyle(Graphics2D graphics) {
            graphics.setColor(color);
            graphics.setStroke(stroke);
        }

        Transform transform() {return transform;}
    }

    static final class CachedOutline {
        private static final int MIN_RASTER_POINTS = 128;
        private static final int MAX_RASTER_PIXELS = 400_000;
        private final Outline outline;
        private final Rectangle area;
        private final boolean dense;
        private AffineTransform previousViewport;
        private Object previousStroke;
        private boolean attempted;
        private HudRasterLayer raster;

        CachedOutline(TrackLayout track, Rectangle area, int padding, Color color, float width) {
            outline = new Outline(track, area, padding, color, width);
            this.area = new Rectangle(area);
            dense = track.points.size() >= MIN_RASTER_POINTS;
        }

        Transform draw(Graphics2D graphics) {
            var layer = layer(graphics);
            if (layer == null) return outline.draw(graphics);
            outline.applyStyle(graphics);
            layer.draw(graphics);
            return outline.transform();
        }

        HudRasterLayer layer(Graphics2D graphics) {
            if (!dense) return null;
            var viewport = graphics.getTransform();
            if (!HudRasterLayer.supports(graphics, viewport)) {
                releaseRaster();
                return null;
            }
            var stroke = graphics.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
            if (!viewport.equals(previousViewport) || stroke != previousStroke) {
                releaseRaster();
                previousViewport = viewport;
                previousStroke = stroke;
                return null; // Draw directly until the HUD stops moving or resizing.
            }
            if (!attempted) {
                raster = HudRasterLayer.capture(graphics, viewport, area, MAX_RASTER_PIXELS, outline::draw);
                attempted = true;
            }
            return raster;
        }

        void releaseRaster() {
            if (raster != null) raster.pixels().flush();
            raster = null;
            previousViewport = null;
            attempted = false;
        }
    }

    public record Transform(float scale,float centerX,float centerZ,float screenX,float screenY) {
        public float x(float worldX) { return screenX+(worldX-centerX)*scale; }
        public float y(float worldZ) { return screenY-(worldZ-centerZ)*scale; }
    }

    @FunctionalInterface
    private interface CircuitVertex {
        void accept(float x,float z,boolean first);
    }

    private static final class CircuitExtents {
        private float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY;
        private float minZ=Float.POSITIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;

        void include(float x,float z,boolean first) {
            minX=Math.min(minX,x);
            maxX=Math.max(maxX,x);
            minZ=Math.min(minZ,z);
            maxZ=Math.max(maxZ,z);
        }

        TrackLayout.Bounds bounds() {return new TrackLayout.Bounds(minX,maxX,minZ,maxZ);}
    }

    private TrackMapView() {}
    public static Transform fit(TrackLayout track,Rectangle area,int padding) {
        return fit(track.bounds(),area,padding);
    }
    static Transform fit(TrackLayout.Bounds bounds,Rectangle area,int padding) {
        if(area.width<=2*padding || area.height<=2*padding || padding<0) throw new IllegalArgumentException("Invalid map viewport");
        float scale=Math.min((area.width-2f*padding)/Math.max(1,bounds.width()),(area.height-2f*padding)/Math.max(1,bounds.height()));
        return new Transform(scale,bounds.centerX(),bounds.centerZ(),area.x+area.width*.5f,area.y+area.height*.5f);
    }
    public static Transform draw(Graphics2D graphics,TrackLayout track,Rectangle area,int padding,Color color,float stroke) {
        return new Outline(track, area, padding, color, stroke).draw(graphics);
    }

    static Path2D.Float path(TrackLayout track,Transform transform) {
        int pointCount=track.points.size();
        var path=new Path2D.Float(Path2D.WIND_NON_ZERO,pointCount);
        var point=track.copyPoint(0,new Vector3f());
        path.moveTo(transform.x(point.x),transform.y(point.z));
        for(int i=1;i<pointCount;i++) {
            track.copyPoint(i,point);
            path.lineTo(transform.x(point.x),transform.y(point.z));
        }
        return path;
    }

    static Path2D.Float clippedOutline(TrackLayout track,Transform transform,Rectangle viewport,BasicStroke stroke) {
        if(stroke.getDashArray()!=null) return path(track,transform);
        // Leave room for stroke caps, miters and antialiasing at the visible boundary.
        double padding=stroke.getLineWidth()*Math.max(1,stroke.getMiterLimit())+2;
        double left=viewport.getMinX()-padding,right=viewport.getMaxX()+padding;
        double top=viewport.getMinY()-padding,bottom=viewport.getMaxY()+padding;
        var path=new Path2D.Float();
        var point=track.copyPoint(0,new Vector3f());
        float previousX=transform.x(point.x),previousY=transform.y(point.z);
        boolean joined=false;
        for(int i=1;i<track.points.size();i++) {
            track.copyPoint(i,point);
            float x=transform.x(point.x),y=transform.y(point.z);
            boolean visible=Math.max(previousX,x)>=left && Math.min(previousX,x)<=right
                    && Math.max(previousY,y)>=top && Math.min(previousY,y)<=bottom;
            if(visible) {
                if(!joined) path.moveTo(previousX,previousY);
                path.lineTo(x,y);
            }
            joined=visible;
            previousX=x;
            previousY=y;
        }
        return path;
    }

    /** Paths between timing gates use unwrapped distances and retain the intervening road vertices. */
    static Path2D.Float circuitPath(TrackLayout track,Transform transform,float from,float to) {
        var path=new Path2D.Float();
        visitCircuitVertices(track,from,to,(x,z,first)->{
            if(first) path.moveTo(transform.x(x),transform.y(z));
            else path.lineTo(transform.x(x),transform.y(z));
        });
        return path;
    }

    static TrackLayout.Bounds circuitBounds(TrackLayout track,float from,float to) {
        var extents=new CircuitExtents();
        visitCircuitVertices(track,from,to,extents::include);
        return extents.bounds();
    }

    private static void visitCircuitVertices(TrackLayout track,float from,float to,CircuitVertex vertex) {
        if(!Float.isFinite(from) || !Float.isFinite(to) || to<from || (double)to-from>2.0*track.length)
            throw new IllegalArgumentException("Invalid circuit map distances");
        double first=Math.floor(from/(double)track.length),last=Math.floor(to/(double)track.length);
        if(first<Integer.MIN_VALUE || last>=Integer.MAX_VALUE)
            throw new IllegalArgumentException("Circuit map distances exceed the supported lap index");
        var point=track.sample(from).position();
        vertex.accept(point.x,point.z,true);
        int firstLap=(int)first,lastLap=(int)last;
        int lastVertex=track.points.size()-1;
        for(int lap=firstLap;lap<=lastLap;lap++) {
            float offset=lap*track.length;
            int low=0,high=lastVertex;
            while(low<high) {
                int middle=low+(high-low)/2;
                if(offset+track.pointDistance(middle)<=from) low=middle+1;
                else high=middle;
            }
            // Vertex zero of each lap represents the seam once.
            for(int i=low;i<lastVertex;i++) {
                float distance=offset+track.pointDistance(i);
                if(distance>=to) break;
                track.copyPoint(i,point);
                vertex.accept(point.x,point.z,false);
            }
        }
        var end=track.sample(to).position();
        vertex.accept(end.x,end.z,false);
    }
}
