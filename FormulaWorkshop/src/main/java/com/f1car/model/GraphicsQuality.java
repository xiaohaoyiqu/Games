package com.f1car.model;

/** Presentation-only budgets. Bullet timing, vehicle rules and record identities do not depend on them. */
public enum GraphicsQuality {
    BALANCED("标准",1280,800,4,60),LOW("省资源",960,600,2,30),NATIVE("原生",4096,4096,4,60);
    public record Surface(int width,int height) {}
    public final String title;
    public final int maxWidth,maxHeight,mapScale,frameRate;
    GraphicsQuality(String title,int maxWidth,int maxHeight,int mapScale,int frameRate) { this.title=title;this.maxWidth=maxWidth;this.maxHeight=maxHeight;this.mapScale=mapScale;this.frameRate=frameRate; }
    public Surface surface(int width,int height) {
        if(width<1 || height<1) throw new IllegalArgumentException("Invalid window surface");
        double ratio=Math.min(1,Math.min(maxWidth/(double)width,maxHeight/(double)height));return new Surface(Math.max(1,(int)Math.floor(width*ratio)),Math.max(1,(int)Math.floor(height*ratio)));
    }
}
