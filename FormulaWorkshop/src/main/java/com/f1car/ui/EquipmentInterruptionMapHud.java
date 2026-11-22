package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.EquipmentSectionPracticeSession.Car;
import com.f1car.model.EquipmentSectionPracticeSession.Interruption;
import com.f1car.model.EquipmentSectionPracticeSession.Interruptions;
import com.f1car.model.PracticeSession;
import com.f1car.model.TrackLayout;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Positions recorded during one interrupted equipment practice attempt. */
final class EquipmentInterruptionMapHud {
    private static final Rectangle MAP=new Rectangle(174,212,600,365);
    private static final Color PANEL=new Color(0x13242e),BACKGROUND=new Color(0x1b3440),ROAD=new Color(0x67828b);
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xb0c6c8),TIMED=new Color(0x63e6bc);
    private static final Color PASSED=new Color(0x87c6ff),NEXT=new Color(0xffce82),LOCATED=new Color(0xfbd2f7);
    private static final BasicStroke ROAD_STROKE=new BasicStroke(2);
    private static final BasicStroke TIMED_STROKE=new BasicStroke(4,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke APPROACH_STROKE=new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,10,new float[]{5,5},0);
    private static final BasicStroke MARK_STROKE=new BasicStroke(2);

    private record Entry(Car car,Interruption run) {}
    private record MapPoint(int x,int y) {}
    private record Markers(MapPoint lastPassed,MapPoint nextGate,MapPoint lastLocated) {}

    private final TrackLayout track;
    private final EquipmentSectionRunsHud runs;
    private final List<Entry> entries;
    private final Markers[] entryMarkers;
    private final TrackMapView.Transform transform;
    private final Path2D road,timed,approach;
    private int selected;
    private Markers markers;

    EquipmentInterruptionMapHud(EquipmentSectionRunsHud runs,FormulaGame.EquipmentSectionPreview source,
                                Interruptions stoppedA,Interruptions stoppedB) {
        this.runs=runs;
        track=source.track();
        var rows=new ArrayList<Entry>();
        append(rows,Car.A,stoppedA);
        append(rows,Car.B,stoppedB);
        if(rows.isEmpty()) throw new IllegalArgumentException("Interruption map needs a recorded attempt");
        entries=List.copyOf(rows);
        entryMarkers=new Markers[entries.size()];

        float spawn=source.from()-source.start().runUpMeters();
        float lower=spawn,upper=source.to();
        for(var entry:entries) {
            var location=entry.run().progress().lastLocated();
            if(location!=null) {
                lower=Math.min(lower,location.distance());
                upper=Math.max(upper,location.distance());
            }
        }
        var bounds=upper-lower>=track.length?track.bounds():TrackMapView.circuitBounds(track,lower,upper);
        transform=TrackMapView.fit(bounds,MAP,32);
        road=TrackMapView.path(track,transform);
        timed=TrackMapView.circuitPath(track,transform,source.from(),source.to());
        approach=TrackMapView.circuitPath(track,transform,spawn,source.from());
        select(0);
    }

    private static void append(List<Entry> rows,Car car,Interruptions history) {
        if(history==null) return;
        var recent=history.recent();
        for(int index=recent.size()-1;index>=0;index--) rows.add(new Entry(car,recent.get(index)));
    }

    void move(int step) {select(Math.floorMod(selected+step,entries.size()));}

    boolean canPracticeSelected() {
        var entry=entries.get(selected);
        return runs.canPracticeInterruption(entry.car(),entry.run());
    }

    FormulaGame.EquipmentSectionPreview subsection() {
        var entry=entries.get(selected);
        return runs.interruptionSubsection(entry.car(),entry.run());
    }

    BooleanSupplier practiceGuard() {
        var entry=entries.get(selected);
        return ()->entries.get(selected)==entry && runs.canPracticeInterruption(entry.car(),entry.run());
    }

    private void select(int index) {
        selected=index;
        var cached=entryMarkers[index];
        if(cached==null) {
            var progress=entries.get(index).run().progress();
            var passed=progress.lastPassed();
            var location=progress.lastLocated();
            cached=new Markers(passed==null?null:point(passed.gate().distance()),point(progress.nextGate().distance()),
                    location==null?null:point(location.distance()));
            entryMarkers[index]=cached;
        }
        markers=cached;
    }

    private MapPoint point(float distance) {
        var position=track.sample(distance).position();
        return new MapPoint(Math.round(transform.x(position.x)),Math.round(transform.y(position.z)));
    }

    void render(HudRenderer h,Graphics2D graphics,Runnable back,Runnable practice) {
        h.panel(140,98,1000,598,PANEL);
        h.text("A/B 中止位置",174,145,27,INK,true);
        for(int index=0;index<entries.size();index++) {
            int row=index;
            var entry=entries.get(index);
            String label=(index==selected?"✓ ":"")+entry.car().name()+" 中止 #"+entry.run().number();
            h.smallButton(174+index*155,162,146,36,label,()->select(row));
        }
        h.panel(MAP.x,MAP.y,MAP.width,MAP.height,BACKGROUND);
        drawMap(graphics);
        h.text("虚线 · 助跑   实线 · 计时区间",174,601,16,SOFT);
        if(entries.size()>1) h.text("← → 切换记录",610,601,16,SOFT);

        var entry=entries.get(selected);
        var run=entry.run();
        var progress=run.progress();
        var passed=progress.lastPassed();
        var location=progress.lastLocated();
        h.text(entry.car().label()+" · "+(passed==null?"助跑阶段":"计时阶段"),796,246,19,INK,true);
        h.wrapped(run.reason(),796,280,310,17,SOFT,24);
        h.text("● 最后通过",796,349,17,PASSED,true);
        h.text(passed==null?"尚未通过区间起点":EquipmentSectionRunsHud.gateLabel(passed.gate()),796,376,16,INK);
        h.text("□ 待过",796,416,17,NEXT,true);
        h.text(EquipmentSectionRunsHud.gateLabel(progress.nextGate()),796,443,16,INK);
        h.text("◎ 最近可定位观测",796,483,17,LOCATED,true);
        if(location==null) h.text("暂无可定位的路线观测",796,511,16,SOFT);
        else {
            h.text(String.format(Locale.ROOT,"中心线 %.3f m",location.distance()),796,511,16,INK);
            h.text("试车时钟 "+PracticeSession.format(location.sessionSeconds()),796,538,16,SOFT);
        }
        if(canPracticeSelected()) {
            h.smallButton(174,624,426,41,"返回中止原因  Esc",back);
            h.smallButton(628,624,478,41,"预览相邻计时门的重练  Enter",practice);
        } else h.smallButton(174,624,932,41,"返回中止原因  Esc",back);
    }

    private void drawMap(Graphics2D graphics) {
        var g=(Graphics2D)graphics.create();
        try {
            g.clip(MAP);
            g.setColor(ROAD);g.setStroke(ROAD_STROKE);g.draw(road);
            g.setColor(TIMED);g.setStroke(TIMED_STROKE);g.draw(timed);
            g.setColor(NEXT);g.setStroke(APPROACH_STROKE);g.draw(approach);
            g.setStroke(MARK_STROKE);
            var passed=markers.lastPassed();
            var gate=markers.nextGate();
            var location=markers.lastLocated();
            if(passed!=null) {g.setColor(PASSED);g.fillOval(passed.x()-5,passed.y()-5,10,10);}
            g.setColor(NEXT);g.drawRect(gate.x()-7,gate.y()-7,14,14);
            if(location!=null) {g.setColor(LOCATED);g.drawOval(location.x()-10,location.y()-10,20,20);}
        } finally {
            g.dispose();
        }
    }
}
