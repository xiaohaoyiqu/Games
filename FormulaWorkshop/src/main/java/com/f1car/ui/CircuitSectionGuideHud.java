package com.f1car.ui;

import com.f1car.model.CircuitSectionPractice.Guidance;
import com.f1car.model.TrackLayout;
import com.jme3.math.Vector3f;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/** Local road sketch aligned to the centreline tangent, and the authoritative next timing gate. */
final class CircuitSectionGuideHud {
    private static final Color PANEL=new Color(0x142d39),INK=new Color(0xeaf4ef);
    private static final Color ROAD=new Color(0x63e6bc),GATE=new Color(0xffbd78);
    private static final int X=500,Y=100,ORIGIN_X=750,ORIGIN_Y=200;
    private static final float SCALE=.9f,STEP=4;
    private static final BasicStroke ROAD_STROKE=new BasicStroke(5,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke GATE_STROKE=new BasicStroke(2);

    private CircuitSectionGuideHud() {}

    static void draw(HudRenderer h,Graphics2D g,TrackLayout track,Guidance hint) {
        h.panel(X,Y,500,157,PANEL);
        var label=switch(hint.nextGate()) {
            case START -> "区间起点";
            case CHECKPOINT -> "中间路门";
            case START_FINISH -> "赛道起终线";
            case FINISH -> "区间终点";
        };
        String split=hint.splitNumber()==0?"":" · 第 "+hint.splitNumber()+" 段";
        h.text("下一道门 · "+label+split,X+18,Y+29,18,INK,true);
        if(!hint.located()) {
            h.text("暂无法定位车辆所在路段",X+18,Y+58,15,GATE);
            return;
        }
        if(hint.needsReturn()) {
            h.text("请回到门前，沿赛道正向通过",X+18,Y+58,15,GATE);
            return;
        }
        h.text("沿中心线约 "+Math.max(0,Math.round(hint.remaining()))+" m",X+18,Y+58,15,INK);
        float length=hint.previewLength();
        if(length<=0) return;
        h.text("前方中心线 ↑",X+18,Y+130,13,ROAD);
        var origin=track.sample(hint.routeDistance());
        var path=new Path2D.Float();
        path.moveTo(ORIGIN_X,ORIGIN_Y);
        for(float offset=STEP;offset<length;offset+=STEP)
            append(path,track.sample(hint.routeDistance()+offset),origin);
        append(path,track.sample(hint.routeDistance()+length),origin);
        var view=(Graphics2D)g.create();
        try {
            view.clipRect(X+149,Y+39,336,116);
            view.setColor(ROAD);
            view.setStroke(ROAD_STROKE);
            view.draw(path);
            view.setColor(INK);
            view.fillOval(ORIGIN_X-3,ORIGIN_Y-3,6,6);
            if(hint.remaining()>=0 && hint.remaining()<=length) {
                var gate=track.sample(hint.gateDistance()).position().subtract(origin.position());
                gateMarker(view,Math.round(localX(gate,origin)),Math.round(localY(gate,origin)));
            }
        } finally {
            view.dispose();
        }
    }

    static void drawGate(Graphics2D g,TrackLayout track,TrackMapView.Transform map,Guidance hint) {
        var position=track.sample(hint.gateDistance()).position();
        gateMarker(g,Math.round(map.x(position.x)),Math.round(map.y(position.z)));
    }

    private static void gateMarker(Graphics2D g,int x,int y) {
        var oldStroke=g.getStroke();
        g.setStroke(GATE_STROKE);
        g.setColor(GATE);
        g.drawOval(x-5,y-5,10,10);
        g.setStroke(oldStroke);
    }

    private static void append(Path2D path,TrackLayout.Sample point,TrackLayout.Sample origin) {
        var delta=point.position().subtract(origin.position());
        path.lineTo(localX(delta,origin),localY(delta,origin));
    }

    private static float localX(Vector3f delta,TrackLayout.Sample origin) {
        return ORIGIN_X+delta.dot(origin.right())*SCALE;
    }

    private static float localY(Vector3f delta,TrackLayout.Sample origin) {
        return ORIGIN_Y-delta.dot(origin.tangent())*SCALE;
    }
}
