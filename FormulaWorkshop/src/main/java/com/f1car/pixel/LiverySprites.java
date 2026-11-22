package com.f1car.pixel;

import com.f1car.model.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/** One immutable design/look/atlas key, lazily populated for at most 32 directions. */
public final class LiverySprites implements AutoCloseable {
    private BufferedImage[] source,frames;
    private List<CarLivery.Layer> layers=List.of();
    private DriverJournal.Look look;
    private PaintRegions regions;
    private PaintRegionStencil stencil;
    private int rendered;

    public BufferedImage image(BufferedImage[] atlas,CarLivery next,DriverJournal.Look emblem,int direction) {
        return image(atlas,next,emblem,direction,null);
    }

    public BufferedImage image(BufferedImage[] atlas,CarLivery next,DriverJournal.Look emblem,int direction,PaintRegions mask) {
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(emblem);
        if(atlas.length<8 || atlas.length>32 || direction<0 || direction>=atlas.length)
            throw new IllegalArgumentException("Invalid livery directions");
        if(next==null || next.version()==1 || mask!=null && !mask.vehicle().equals(next.vehicle())) mask=null;
        var nextLayers=next==null?List.<CarLivery.Layer>of():next.layers();
        boolean maskChanged=!Objects.equals(regions,mask);
        if(source!=atlas || !layers.equals(nextLayers) || !emblem.equals(look) || maskChanged) {
            clearFrames();
            if(frames==null || frames.length!=atlas.length) frames=new BufferedImage[atlas.length];
            if(maskChanged) stencil=mask==null?null:new PaintRegionStencil(mask);
            source=atlas;
            layers=nextLayers;
            look=emblem;
            regions=mask;
        }
        if(frames[direction]==null) {
            double angle=direction*Math.PI*2/atlas.length;
            var base=Objects.requireNonNull(atlas[direction]);
            frames[direction]=LiveryPainter.compose(base,layers,look,angle,stencil);
            rendered++;
        }
        return frames[direction];
    }

    public int cachedDirections() {
        if(frames==null) return 0;
        int count=0;
        for(var frame:frames) if(frame!=null) count++;
        return count;
    }

    public int renderedFrames() {return rendered;}

    private void clearFrames() {
        if(frames==null) return;
        for(int direction=0;direction<frames.length;direction++) {
            if(frames[direction]!=null) frames[direction].flush();
            frames[direction]=null;
        }
    }

    @Override public void close() {
        clearFrames();
        source=frames=null;
        layers=List.of();
        look=null;
        regions=null;
        stencil=null;
    }
}
