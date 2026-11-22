package com.f1car.ui;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/** An offscreen layer that preserves the HUD's device-pixel positions. */
record HudRasterLayer(BufferedImage pixels,AffineTransform placement) {
    static boolean supports(Graphics2D g,AffineTransform viewport) {
        return viewport.getScaleX()>0 && viewport.getScaleY()>0
                && viewport.getShearX()==0 && viewport.getShearY()==0
                && AlphaComposite.SrcOver.equals(g.getComposite())
                && RenderingHints.VALUE_ANTIALIAS_OFF.equals(g.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
    }

    static HudRasterLayer capture(Graphics2D source,AffineTransform viewport,Rectangle area,int maxPixels,
                                  Consumer<Graphics2D> paint) {
        var bounds=viewport.createTransformedShape(area).getBounds();
        if(bounds.isEmpty() || (long)bounds.width*bounds.height>maxPixels) return null;
        var pixels=new BufferedImage(bounds.width,bounds.height,BufferedImage.TYPE_INT_ARGB_PRE);
        var g=pixels.createGraphics();
        try {
            g.setRenderingHints(source.getRenderingHints());
            g.translate(-bounds.getX(),-bounds.getY());
            g.transform(viewport);
            g.clip(area);
            paint.accept(g);
        } finally {
            g.dispose();
        }
        double scaleX=viewport.getScaleX(),scaleY=viewport.getScaleY();
        var placement=new AffineTransform(1/scaleX,0,0,1/scaleY,
                (bounds.x-viewport.getTranslateX())/scaleX,(bounds.y-viewport.getTranslateY())/scaleY);
        return new HudRasterLayer(pixels,placement);
    }

    void draw(Graphics2D g) {g.drawImage(pixels,placement,null);}
}
