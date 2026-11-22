package com.f1car.ui;

import com.f1car.model.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.function.IntConsumer;

/** Cached route diagram only. Picking a gate edits an activity draft, never live lap progress. */
final class ActivitySectionPreview {
    private static final Color ROAD=new Color(0x54717c),SELECTED=new Color(0x4cebc1);
    private static final BasicStroke ROAD_STROKE=new BasicStroke(10,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND),SECTION_STROKE=new BasicStroke(5,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private TrackLayout cached;private int start=-1,end=-1;private final double[] x=new double[181],y=new double[181];private Path2D road,selected;
    void render(Graphics2D g,HudRenderer h,TrackLayout track,ActivitySection section,IntConsumer pick) {
        if(cached!=track) {cached=track;start=-1;var bounds=track.bounds();float scale=bounds.fitScale(1000,210,track.width);for(int i=0;i<=180;i++) {var point=track.sample(track.length*i/180).position();x[i]=638+(point.x-bounds.centerX())*scale;y[i]=455+(point.z-bounds.centerZ())*scale;}road=path(0,180);}
        if(start!=section.startGate() || end!=section.endGate()) {start=section.startGate();end=section.endGate();selected=path(start*10,end*10);}
        var original=g.getStroke();g.setStroke(ROAD_STROKE);g.setColor(ROAD);g.draw(road);g.setStroke(SECTION_STROKE);g.setColor(SELECTED);g.draw(selected);g.setStroke(original);
        for(int gate=0;gate<18;gate++) {final int chosen=gate;h.smallButton((int)Math.round(x[gate*10])-15,(int)Math.round(y[gate*10])-13,gate==0?42:30,26,gate==0?"0/18":Integer.toString(gate),()->pick.accept(chosen));}
        h.text("起",(int)Math.round(x[start*10])-21,(int)Math.round(y[start*10])-22,17,SELECTED,true);h.text("终",(int)Math.round(x[end*10])+22,(int)Math.round(y[end*10])+23,17,SELECTED,true);
    }
    private Path2D path(int first,int last) {var result=new Path2D.Double();result.moveTo(x[first],y[first]);for(int i=first+1;i<=last;i++) result.lineTo(x[i],y[i]);return result;}
}
