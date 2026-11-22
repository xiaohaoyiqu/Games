package com.f1car.pixel;

import com.f1car.model.CarLivery;
import com.f1car.model.DriverJournal;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.Objects;

/** Original geometric layers clipped to existing opaque pixels, including wheels in ALL mode; optional pinned vector part masks. */
public final class LiveryPainter {
    private LiveryPainter() {}

    static BufferedImage compose(BufferedImage source,java.util.List<CarLivery.Layer> layers,
                                 DriverJournal.Look look,double angle,PaintRegionStencil stencil) {
        if(layers.isEmpty()) return look==null?source:CarDecoration.apply(source,look,angle);
        var painted=applyLayers(source,layers,angle,stencil);
        if(look!=null) CarDecoration.paintOwned(painted,look,angle);
        return painted;
    }

    public static BufferedImage apply(BufferedImage source,CarLivery design,double angle) {
        return applyLayers(source,Objects.requireNonNull(design).layers(),angle);
    }
    public static BufferedImage apply(BufferedImage source,CarLivery design,double angle,PaintRegionStencil stencil) {return applyLayers(source,Objects.requireNonNull(design).layers(),angle,stencil);}
    public static BufferedImage applyLayers(BufferedImage source,java.util.List<CarLivery.Layer> layers,double angle) {return applyLayers(source,layers,angle,null);}
    public static BufferedImage applyLayers(BufferedImage source,java.util.List<CarLivery.Layer> layers,double angle,PaintRegionStencil stencil) {Objects.requireNonNull(source);Objects.requireNonNull(layers);int w=source.getWidth(),h=source.getHeight();if(w<16 || w>256 || h!=w || !Double.isFinite(angle) || layers.size()>CarLivery.CAPACITY) throw new IllegalArgumentException("Invalid livery sprite");var result=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);var g=result.createGraphics();try {PixelScene.crisp(g);source.getRGB(0,0,w,h,((java.awt.image.DataBufferInt)result.getRaster().getDataBuffer()).getData(),0,w);g.setComposite(AlphaComposite.SrcAtop);g.translate(w*.5,h*.5);g.rotate(angle);g.scale(w/1000d,h/1000d);for(var layer:layers) {if(layer.target()==CarLivery.Target.ALL) {paint(g,layer,false);if(layer.mirror()==1) paint(g,layer,true);}else if(stencil!=null) {var clipped=(Graphics2D)g.create();try {if(stencil.clip(clipped,layer.target())) {paint(clipped,layer,false);if(layer.mirror()==1) paint(clipped,layer,true);}}finally {clipped.dispose();}}}}finally {g.dispose();}return result;}
    private static void paint(Graphics2D parent,CarLivery.Layer layer,boolean mirrored) {var g=(Graphics2D)parent.create();try {if(mirrored) g.scale(-1,1);g.translate(layer.x(),layer.y());g.rotate(Math.toRadians(layer.rotation()));g.setColor(new Color(layer.rgb() | ((layer.opacity()*255/100)<<24),true));double w=layer.width(),h=layer.height();Shape shape=switch(layer.shape()) {case RECTANGLE->new Rectangle2D.Double(-w/2,-h/2,w,h);case ELLIPSE->new Ellipse2D.Double(-w/2,-h/2,w,h);case CHEVRON->{var p=new Path2D.Double();p.moveTo(-w/2,-h/2);p.lineTo(0,0);p.lineTo(w/2,-h/2);p.lineTo(w/2,0);p.lineTo(0,h/2);p.lineTo(-w/2,0);p.closePath();yield p;}};g.fill(shape);}finally {g.dispose();}}
}
