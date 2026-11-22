package com.f1car.pixel;

import com.f1car.model.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;

/** Compile vector clipping once, in the same canonical coordinates as livery layers. */
public final class PaintRegionStencil {
    private final PaintRegions definition;private final int hash;
    private final EnumMap<CarLivery.Target,Area> shapes=new EnumMap<>(CarLivery.Target.class);
    public PaintRegionStencil(PaintRegions definition) {this.definition=Objects.requireNonNull(definition);hash=definition.hashCode();double scale=1000d/definition.frameSize();for(var region:definition.regions()) {var area=new Area();for(var polygon:region.polygons()) {var path=new Path2D.Double();for(int i=0;i<polygon.points().size();i++) {var point=polygon.points().get(i);double x=point.x()*scale-500,y=point.y()*scale-500;if(i==0) path.moveTo(x,y);else path.lineTo(x,y);}path.closePath();area.add(new Area(path));}shapes.put(region.target(),area);}}
    public PaintRegions definition() {return definition;}
    public boolean clip(Graphics2D graphics,CarLivery.Target target) {if(target==CarLivery.Target.ALL) return true;var shape=shapes.get(target);if(shape==null) return false;graphics.clip(shape);return true;}
    public void outline(Graphics2D graphics,CarLivery.Target target,int x,int y,int size) {var shape=shapes.get(target);if(shape==null) return;var g=(Graphics2D)graphics.create();try {g.translate(x+size*.5,y+size*.5);g.scale(size/1000d,size/1000d);g.setColor(new Color(0xf5d784));g.setStroke(new BasicStroke(1000f/size));g.draw(shape);}finally {g.dispose();}}
    @Override public int hashCode() {return hash;}
    @Override public boolean equals(Object value) {return this==value || value instanceof PaintRegionStencil other && definition.equals(other.definition);}
}
