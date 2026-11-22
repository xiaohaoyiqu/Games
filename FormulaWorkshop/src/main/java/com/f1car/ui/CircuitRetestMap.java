package com.f1car.ui;

import com.f1car.model.CircuitDocument;
import com.f1car.model.CircuitDriveReview;
import com.f1car.model.CircuitObservationRetest;
import com.f1car.model.CircuitSectionPractice;
import com.f1car.model.TrackLayout;
import com.f1car.persistence.CircuitStore;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/** Read-only maps fitted to the same world bounds for both frozen retest records. */
final class CircuitRetestMap {
    private static final Rectangle LEFT=new Rectangle(104,224,520,284);
    private static final Rectangle RIGHT=new Rectangle(656,224,520,284);
    private static final Color PANEL=new Color(0x172d38),MAP=new Color(0x1b3940),ROAD=new Color(0x72898b);
    private static final Color INK=new Color(0xebf5f0),MUTED=new Color(0xb2c6c9),MINT=new Color(0x4cebc1);
    private static final Color GOLD=new Color(0xffca75),OBSERVATION=new Color(0xf4a3b5);
    private static final BasicStroke ROAD_LINE=new BasicStroke(2);
    private static final BasicStroke TIMING_LINE=new BasicStroke(4,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke RUN_UP_LINE=new BasicStroke(3,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,10,new float[]{6,5},0);

    record Route(TrackLayout track,float launchDistance,float startDistance,float finishDistance,boolean interiorStartLine) {
        TrackLayout.Bounds localBounds() {
            return TrackMapView.circuitBounds(track,launchDistance,finishDistance);
        }
        TrackLayout.Bounds bounds(boolean wholeCircuit) {return wholeCircuit?track.bounds():localBounds();}
    }
    record Pane(TrackMapView.Transform transform,Path2D road,Path2D timed,Path2D approach,Point2D.Float launch,
                Point2D.Float start,Point2D.Float finish,Point2D.Float seam,Point2D.Float observation) {}
    record Frame(Pane saved,Pane proposed) {}
    private record Routes(Route saved,Route proposed,CircuitDriveReview.Marker marker) {}

    private final CircuitObservationRetest.Result saved;
    private final CircuitObservationRetest.Result proposed;
    private final Frame local;
    private Routes pendingWhole;
    private Frame whole;
    private boolean fullCircuit;

    CircuitRetestMap(CircuitObservationRetest proposal,CircuitObservationRetest.Result saved,
                     CircuitDocument working,CircuitStore.Saved archive) {
        this.saved=saved;
        proposed=proposal.result();
        var source=proposal.source();
        var savedVersion=resolveVersion(source.mapId(),saved.driveKey(),working,archive);
        var proposedVersion=saved.driveKey().equals(proposed.driveKey())?savedVersion
                :resolveVersion(source.mapId(),proposed.driveKey(),working,archive);
        var savedTrack=preview(savedVersion);
        var proposedTrack=savedVersion==proposedVersion?savedTrack:preview(proposedVersion);
        var savedRoute=resolveRoute(savedTrack,saved);
        var proposedRoute=savedTrack==proposedTrack && sameRouteSettings()?savedRoute:resolveRoute(proposedTrack,proposed);
        var marker=source.marker();
        local=frame(savedRoute,proposedRoute,marker,false);
        if(savedRoute!=null || proposedRoute!=null) pendingWhole=new Routes(savedRoute,proposedRoute,marker);
    }

    static Route resolveRoute(String mapId,CircuitObservationRetest.Result result,CircuitDocument working,CircuitStore.Saved archive) {
        return resolveRoute(preview(resolveVersion(mapId,result.driveKey(),working,archive)),result);
    }

    private static CircuitDocument resolveVersion(String mapId,String driveKey,CircuitDocument working,CircuitStore.Saved archive) {
        if(working!=null && working.id().equals(mapId) && working.driveKey().equals(driveKey)) return working;
        if(archive!=null && archive.document().id().equals(mapId)) return archive.drivingVersion(driveKey);
        return null;
    }

    private static TrackLayout preview(CircuitDocument version) {
        if(version==null) return null;
        try {return version.roadPreview();}
        catch(IllegalArgumentException unavailable) {return null;}
    }

    private static Route resolveRoute(TrackLayout track,CircuitObservationRetest.Result result) {
        if(track==null) return null;
        try {
            var practice=new CircuitSectionPractice(track,result.range(),result.start(),result.split());
            return new Route(track,practice.spawnDistance(),practice.startDistance(),practice.finishDistance(),practice.hasInteriorStartLine());
        } catch(IllegalArgumentException unavailable) {
            return null;
        }
    }

    private boolean sameRouteSettings() {
        return saved.range().equals(proposed.range()) && saved.split()==proposed.split()
                && saved.start().runUpMeters()==proposed.start().runUpMeters();
    }

    static Frame frame(Route saved,Route proposed,CircuitDriveReview.Marker marker,boolean fullCircuit) {
        if(saved==null && proposed==null) return new Frame(null,null);
        var bounds=saved==null?null:saved.bounds(fullCircuit);
        if(proposed!=null && proposed!=saved) bounds=union(bounds,proposed.bounds(fullCircuit));
        if(fullCircuit) bounds=union(bounds,new TrackLayout.Bounds(marker.x(),marker.x(),marker.z(),marker.z()));
        return new Frame(pane(saved,bounds,LEFT,marker),pane(proposed,bounds,RIGHT,marker));
    }

    private static TrackLayout.Bounds union(TrackLayout.Bounds left,TrackLayout.Bounds right) {
        if(left==null) return right;
        return new TrackLayout.Bounds(Math.min(left.minX(),right.minX()),Math.max(left.maxX(),right.maxX()),
                Math.min(left.minZ(),right.minZ()),Math.max(left.maxZ(),right.maxZ()));
    }

    private static Pane pane(Route route,TrackLayout.Bounds bounds,Rectangle area,CircuitDriveReview.Marker marker) {
        if(route==null) return null;
        var map=TrackMapView.fit(bounds,area,26);
        var track=route.track();
        var observation=new Point2D.Float(map.x(marker.x()),map.y(marker.z()));
        if(!new Rectangle(area.x+8,area.y+8,area.width-16,area.height-16).contains(observation)) observation=null;
        return new Pane(map,TrackMapView.path(track,map),
                TrackMapView.circuitPath(track,map,route.startDistance(),route.finishDistance()),
                TrackMapView.circuitPath(track,map,route.launchDistance(),route.startDistance()),
                location(track,map,route.launchDistance()),location(track,map,route.startDistance()),
                location(track,map,route.finishDistance()),route.interiorStartLine()?location(track,map,0):null,observation);
    }

    private static Point2D.Float location(TrackLayout track,TrackMapView.Transform map,float distance) {
        var point=track.sample(distance).position();
        return new Point2D.Float(map.x(point.x),map.y(point.z));
    }

    void toggleExtent() {
        if(whole==null) {
            if(pendingWhole==null) return;
            whole=frame(pendingWhole.saved(),pendingWhole.proposed(),pendingWhole.marker(),true);
            pendingWhole=null;
        }
        fullCircuit=!fullCircuit;
    }

    void render(HudRenderer hud,Graphics2D graphics,Runnable back) {
        var frame=fullCircuit?whole:local;
        var reference=frame.saved()!=null?frame.saved():frame.proposed();
        hud.panel(72,108,1136,590,PANEL);
        hud.text("两次复测的计时区间",104,151,28,INK,true);
        hud.text("同尺度 · 同一坐标方向 · 道路中心线与计时布置",104,182,17,MUTED);
        renderSide(hud,graphics,LEFT,"已存复测",saved,frame.saved());
        renderSide(hud,graphics,RIGHT,"将存复测",proposed,frame.proposed());
        if(reference!=null) {
            hud.text("每侧视野宽约 "+Math.round(LEFT.width/reference.transform().scale())+" m",904,182,16,MUTED);
            hud.text("粗实线 计时区间    虚线 助跑",104,588,15,MUTED);
            hud.text("○ 计时起点    ■ 计时终点    △ 放行点    × 原观测    ◇ 区间内的赛道起终线",104,610,15,INK);
            if(!fullCircuit && (frame.saved()==null || frame.saved().observation()==null)
                    && (frame.proposed()==null || frame.proposed().observation()==null))
                hud.text("原观测在局部图外，可切换至整圈。",488,652,15,MUTED);
        }
        hud.smallButton(900,624,276,43,"返回条件对照",back);
        if(reference!=null) hud.smallButton(104,624,296,43,fullCircuit?"查看局部区间  M":"查看整圈  M",this::toggleExtent);
    }

    private static void renderSide(HudRenderer hud,Graphics2D graphics,Rectangle area,String title,
                                   CircuitObservationRetest.Result result,Pane pane) {
        hud.text(title,area.x,213,20,INK,true);
        hud.panel(area.x,area.y,area.width,area.height,MAP);
        hud.text(CircuitSectionHud.rangeLabel(result.range())+(result.split()==0?" · 完整区间":" · 第 "+result.split()+" 段"),
                area.x,539,17,INK);
        hud.text(CircuitSectionHud.startLabel(result.start()),area.x,563,16,MUTED);
        if(pane==null) {
            hud.text("对应版本的道路图不可用",area.x+28,area.y+142,18,MUTED);
            return;
        }
        var g=(Graphics2D)graphics.create();
        try {
            g.clip(area);
            g.setStroke(ROAD_LINE);
            g.setColor(ROAD);
            g.draw(pane.road());
            g.setStroke(TIMING_LINE);
            g.setColor(MINT);
            g.draw(pane.timed());
            g.setStroke(RUN_UP_LINE);
            g.setColor(GOLD);
            g.draw(pane.approach());
            g.setStroke(ROAD_LINE);
            drawMarks(g,pane);
        } finally {
            g.dispose();
        }
    }

    private static void drawMarks(Graphics2D g,Pane pane) {
        if(pane.observation()!=null) {
            int x=Math.round(pane.observation().x),y=Math.round(pane.observation().y);
            g.setColor(OBSERVATION);
            g.drawLine(x-6,y-6,x+6,y+6);
            g.drawLine(x-6,y+6,x+6,y-6);
        }
        if(pane.seam()!=null) {
            int x=Math.round(pane.seam().x),y=Math.round(pane.seam().y);
            g.setColor(INK);
            g.drawPolygon(new int[]{x,x+5,x,x-5},new int[]{y-5,y,y+5,y},4);
        }
        int x=Math.round(pane.launch().x),y=Math.round(pane.launch().y);
        g.setColor(GOLD);
        g.drawPolygon(new int[]{x,x+6,x-6},new int[]{y-6,y+5,y+5},3);
        g.setColor(INK);
        g.drawOval(Math.round(pane.start().x)-5,Math.round(pane.start().y)-5,10,10);
        g.fillRect(Math.round(pane.finish().x)-4,Math.round(pane.finish().y)-4,9,9);
    }
}
