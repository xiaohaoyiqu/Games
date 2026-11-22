package com.f1car.ui;

import java.awt.Rectangle;
import java.util.*;

/** Spatial navigation over the currently visible button layer, in logical HUD coordinates. */
public final class MenuFocus {
    private List<Rectangle> bounds=List.of();
    private Rectangle selected;
    private String context="";
    private boolean visible;
    private final BitSet heldNavigationKeys=new BitSet();
    public void update(List<Rectangle> rectangles,String nextContext) {
        if(!context.equals(nextContext)) { selected=null;visible=false;context=nextContext;heldNavigationKeys.clear(); }
        if(rectangles.isEmpty()) heldNavigationKeys.clear();
        if(!bounds.equals(rectangles)) bounds=rectangles.stream().map(Rectangle::new).toList();
        if(selected==null || !bounds.contains(selected)) {
            selected=bounds.isEmpty()?null:new Rectangle(bounds.get(0));
            visible=false;
        }
    }
    public int index() { return selected==null?-1:bounds.indexOf(selected); }
    public Optional<Rectangle> outline() { return visible && selected!=null?Optional.of(new Rectangle(selected)):Optional.empty(); }
    public void showOutline() { visible=selected!=null; }
    public boolean active() {return visible && selected!=null;}
    boolean acceptsNavigationPress(int key,boolean repeat) {
        if(selected==null) return false;
        if(repeat) return heldNavigationKeys.get(key);
        heldNavigationKeys.set(key);
        return true;
    }
    void releaseNavigationKey(int key) {if(key>=0) heldNavigationKeys.clear(key);}
    void clearNavigationKeys() {heldNavigationKeys.clear();}
    public int confirm() { showOutline();return index(); }
    public void mouse() { visible=false; }
    public void cycle(boolean backward) {
        if(bounds.isEmpty()) return;
        int next=active()?Math.floorMod(index()+(backward?-1:1),bounds.size()):backward?bounds.size()-1:0;
        selected=new Rectangle(bounds.get(next));
        visible=true;
    }
    public void move(int direction) {
        if(direction<1 || direction>4 || selected==null) return;visible=true;
        int sx=direction==1?1:direction==3?-1:0,sy=direction==2?1:direction==4?-1:0;
        Rectangle best=null;double score=Double.POSITIVE_INFINITY;
        for(var candidate:bounds) {
            if(candidate.equals(selected)) continue;
            double dx=candidate.getCenterX()-selected.getCenterX(),dy=candidate.getCenterY()-selected.getCenterY();
            double forward=dx*sx+dy*sy,cross=Math.abs(dx*sy-dy*sx);
            if(forward<=.01) continue;
            double cost=forward+cross*3;if(cost<score) { score=cost;best=candidate; }
        }
        if(best==null) {
            for(var candidate:bounds) {
                double forward=candidate.getCenterX()*sx+candidate.getCenterY()*sy;
                double cross=Math.abs((candidate.getCenterX()-selected.getCenterX())*sy-(candidate.getCenterY()-selected.getCenterY())*sx);
                double cost=forward+cross*3;if(cost<score) { score=cost;best=candidate; }
            }
        }
        if(best!=null) selected=new Rectangle(best);
    }
}
