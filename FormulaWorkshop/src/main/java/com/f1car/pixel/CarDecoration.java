package com.f1car.pixel;

import com.f1car.model.DriverJournal;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Objects;

/** Paints onto a copy of the opaque sprite pixels; source atlases and physics stay independent. */
public final class CarDecoration {
    private CarDecoration() {}
    public static BufferedImage apply(BufferedImage sprite,DriverJournal.Look look,double angle) {
        Objects.requireNonNull(sprite);Objects.requireNonNull(look);
        if(!Double.isFinite(angle)) throw new IllegalArgumentException("Invalid sprite direction");
        int w=sprite.getWidth(),h=sprite.getHeight();
        var result=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        sprite.getRGB(0,0,w,h,((java.awt.image.DataBufferInt)result.getRaster().getDataBuffer()).getData(),0,w);
        paintOwned(result,look,angle);
        return result;
    }

    /** Draw over a sprite created for this composition. Shared source images must go through apply. */
    static void paintOwned(BufferedImage sprite,DriverJournal.Look look,double angle) {
        int w=sprite.getWidth(),h=sprite.getHeight();
        var g=sprite.createGraphics();
        try {
            PixelScene.crisp(g);
            g.setComposite(AlphaComposite.SrcAtop);
            g.translate(w*.5,h*.5);g.rotate(angle);g.setColor(new Color(0xf5d784));
            switch(look.pattern()) {
                case ORIGINAL -> { }
                case STRIPE -> g.fillRect(-1,-h/3,2,h*2/3);
                case CHEVRON -> { g.setStroke(new BasicStroke(2));g.drawPolyline(new int[]{-w/6,0,w/6},new int[]{-h/5,-h/10,-h/5},3); }
                case LAUREL -> { g.setStroke(new BasicStroke(2));g.drawOval(-w/6,-h/5,w/3,h/3); }
            }
            int fontSize=Math.max(5,h/6);g.setFont(new Font(Font.MONOSPACED,Font.BOLD,fontSize));String number=Integer.toString(look.number());
            int width=g.getFontMetrics().stringWidth(number);g.setColor(new Color(0x142633));g.fillRect(-width/2-1,-1,width+2,fontSize+1);
            g.setColor(Color.WHITE);g.drawString(number,-width/2,fontSize-2);
        } finally {
            g.dispose();
        }
    }
}
