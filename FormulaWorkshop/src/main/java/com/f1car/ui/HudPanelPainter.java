package com.f1car.ui;

import java.awt.*;

/** Preserve the original panel pixels while skipping shadows completely covered by opaque fills. */
final class HudPanelPainter {
    private static final Color SHADOW=new Color(6,16,25,185),BORDER=new Color(100,146,146,100);
    private static final BasicStroke STROKE=new BasicStroke(2);
    private HudPanelPainter() {}
    static void draw(Graphics2D g,int x,int y,int width,int height,Color color) {
        boolean covered=color.getAlpha()==255 && width>4 && height>4 && AlphaComposite.SrcOver.equals(g.getComposite()) && RenderingHints.VALUE_ANTIALIAS_OFF.equals(g.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
        if(covered) {var transform=g.getTransform();covered=transform.getShearX()==0 && transform.getShearY()==0 && transform.getScaleX()>0 && transform.getScaleY()>0;}
        g.setColor(SHADOW);
        if(covered) {g.fillRect(x+width,y+4,4,height-4);g.fillRect(x+4,y+height,width,4);}else g.fillRect(x+4,y+4,width,height);
        g.setColor(color);g.fillRect(x,y,width,height);g.setStroke(STROKE);g.setColor(BORDER);g.drawRect(x,y,width,height);
    }
}
