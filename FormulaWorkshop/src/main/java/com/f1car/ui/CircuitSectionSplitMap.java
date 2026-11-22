package com.f1car.ui;

import com.f1car.model.CircuitSectionPractice;
import com.f1car.model.CircuitSplitPracticeHistory;
import com.jme3.math.Vector3f;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.function.BiFunction;

/** A frozen section result with selectable rows and their matching circuit geometry. */
final class CircuitSectionSplitMap {
    private static final Rectangle MAP=new Rectangle(635,214,461,290);
    private static final Color PANEL=new Color(0x142d39),MAP_PANEL=new Color(0x102430);
    private static final Color INK=new Color(0xeaf4ef),MUTED=new Color(0xa8c2c7);
    private static final Color ROAD=new Color(0x486472),SECTION=new Color(0x8ba5ad);
    private static final Color HIGHLIGHT=new Color(0x63e6bc),FINISH=new Color(0xffbd78),APPROACH=new Color(0x8dc5fd);
    private static final BasicStroke BASE=new BasicStroke(4,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke SELECTED=new BasicStroke(6,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke RUN_UP=new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{5,5},0);
    private static final BasicStroke OUTLINE=new BasicStroke(2);

    private record TimingDrawing(Path2D.Float highlight,Ellipse2D.Float startMarker,Path2D.Float finishMarker,
                                 List<String> speedRows,CircuitSectionPractice.GateComparison comparison,String cumulative) {}
    private record LaunchDrawing(int runUpMeters,Path2D.Float runUpPath,Ellipse2D.Float launchMarker,boolean spawnBlocked) {}

    private final CircuitSectionPractice result;
    private final List<CircuitSectionPractice.Split> splits;
    private final BiFunction<Integer,CircuitSectionPractice.Start,CircuitSplitPracticeHistory.Progress> progressLookup;
    private final TrackMapView.Transform transform;
    private final Path2D.Float road,timedRoute;
    private final TimingDrawing[] timingDrawings;
    private final LaunchDrawing[] launchDrawings;
    private CircuitSectionPractice.Start start;
    private int selected=-1;
    private CircuitSplitPracticeHistory.Progress progress;

    CircuitSectionSplitMap(CircuitSectionPractice result,List<CircuitSectionPractice.Split> splits,
                           BiFunction<Integer,CircuitSectionPractice.Start,CircuitSplitPracticeHistory.Progress> progressLookup) {
        this.result=result;
        this.splits=splits;
        this.progressLookup=progressLookup;
        start=result.start();
        var track=result.track();
        transform=TrackMapView.fit(track,MAP,26);
        road=TrackMapView.path(track,transform);
        timedRoute=TrackMapView.circuitPath(track,transform,splits.get(0).fromDistance(),splits.get(splits.size()-1).toDistance());
        timingDrawings=new TimingDrawing[splits.size()];
        launchDrawings=new LaunchDrawing[splits.size()];
        select(0);
    }

    CircuitSectionPractice result() {return result;}
    int selectedNumber() {return splits.get(selected).number();}
    CircuitSectionPractice.Start start() {return start;}

    void setStart(CircuitSectionPractice.Start value) {
        if(start.equals(value)) return;
        updateLaunchPreview(value);
        start=value;
        refreshProgress();
    }

    void refreshProgress() {progress=progressLookup.apply(selectedNumber(),start);}

    void moveSelection(ReviewPages pages,int step) {
        int target=Math.max(0,Math.min(splits.size()-1,selected+step));
        pages.showRow(target);
        select(target);
    }

    void focusSplit(ReviewPages pages,int number) {
        for(int index=0;index<splits.size();index++) {
            if(splits.get(index).number()!=number) continue;
            pages.showRow(index);
            select(index);
            return;
        }
    }

    void render(HudRenderer hud,Graphics2D graphics,String title,ReviewPages pages,Runnable close,Runnable practice,Runnable showProgress) {
        int first=pages.firstIndex();
        var rows=pages.visible();
        if(selected<first || selected>=first+rows.size()) select(first);
        var timing=timingDrawings[selected];
        var launch=launchDrawings[selected];
        var loss=result.largestSplitLoss();
        boolean selectedLoss=loss!=null && selectedNumber()==loss.splitNumber();

        hud.panel(148,132,984,535,PANEL);
        hud.text(title,176,181,24,INK,true);
        if(timing.comparison()!=null)
            hud.text(timing.cumulative(),635,201,15,CircuitSectionPaceHud.color(timing.comparison().cumulativeDeltaSeconds()));
        if(selectedLoss) hud.text("本段失时最多",990,201,15,FINISH,true);
        for(int row=0;row<rows.size();row++) {
            int index=first+row,y=216+row*45;
            hud.smallButton(176,y,427,39,rows.get(row),()->select(index));
            if(index==selected) {
                graphics.setColor(HIGHLIGHT);
                graphics.fillRect(181,y+7,4,25);
            }
        }
        if(pages.pages()>1) {
            hud.smallButton(176,454,118,36,"上一页",()->pages.move(-1));
            hud.centered((pages.page()+1)+" / "+pages.pages(),389,477,15,MUTED);
            hud.smallButton(485,454,118,36,"下一页",()->pages.move(1));
        }
        hud.text(pages.pages()>1?"↑↓ 选择 · PgUp / PgDn 翻页":"↑↓ 选择",176,519,14,MUTED);
        if(loss!=null && !selectedLoss)
            hud.smallButton(392,502,211,32,"定位失时最多 · 第 "+loss.splitNumber()+" 段",()->focusSplit(pages,loss.splitNumber()));
        hud.smallButton(176,550,427,43,"返回区间成绩",close);
        for(int row=0;row<timing.speedRows().size();row++) hud.text(timing.speedRows().get(row),176,612+row*20,15,MUTED);
        if(launch.spawnBlocked()) hud.text("分段发车点被占用，请调整助跑距离。",176,653,16,FINISH);

        hud.panel(MAP.x,MAP.y,MAP.width,MAP.height,MAP_PANEL);
        drawMap(graphics,timing,launch);
        var split=splits.get(selected);
        int length=Math.round(split.toDistance()-split.fromDistance());
        hud.text("第 "+split.number()+" 段 · 约 "+length+" m",635,532,16,INK,true);
        hud.text("虚线 助跑   ○ 起点   ◆ 终点",866,532,15,MUTED);
        int practiceWidth=progress==null?461:294;
        hud.smallButton(635,550,practiceWidth,43,"练习第 "+split.number()+" 段  P",practice);
        if(progress!=null) hud.smallButton(945,550,151,43,"同条件记录",showProgress);
        hud.smallButton(635,606,223,41,"助跑 "+start.runUpMeters()+" m  A",()->hud.editCircuitSplitStart(true));
        hud.smallButton(873,606,223,41,"放行 "+start.speedKph()+" km/h  V",()->hud.editCircuitSplitStart(false));
    }

    private void select(int index) {
        if(selected==index) return;
        selected=index;
        if(timingDrawings[index]==null) timingDrawings[index]=timingDrawing(splits.get(index));
        updateLaunchPreview(start);
        refreshProgress();
    }

    private TimingDrawing timingDrawing(CircuitSectionPractice.Split split) {
        var comparison=result.completedGateComparison(split.number());
        var cumulative=comparison==null?null:CircuitSectionPaceHud.cumulativeLabel(comparison);
        String current="本次 · "+CircuitSectionHud.speedLabel(split.speeds());
        var speedRows=Double.isFinite(split.bestSeconds())
                ?List.of(current,"此前最快 · "+CircuitSectionHud.speedLabel(split.bestSpeeds())):List.of(current);
        var track=result.track();
        var highlight=TrackMapView.circuitPath(track,transform,split.fromDistance(),split.toDistance());
        var startPoint=mapPoint(track.sample(split.fromDistance()).position());
        var startMarker=new Ellipse2D.Float(startPoint.x-8,startPoint.y-8,16,16);
        var finish=mapPoint(track.sample(split.toDistance()).position());
        var finishMarker=new Path2D.Float();
        finishMarker.moveTo(finish.x,finish.y-5);
        finishMarker.lineTo(finish.x+5,finish.y);
        finishMarker.lineTo(finish.x,finish.y+5);
        finishMarker.lineTo(finish.x-5,finish.y);
        finishMarker.closePath();
        return new TimingDrawing(highlight,startMarker,finishMarker,speedRows,comparison,cumulative);
    }

    private void updateLaunchPreview(CircuitSectionPractice.Start settings) {
        var cached=launchDrawings[selected];
        if(cached!=null && cached.runUpMeters()==settings.runUpMeters()) return;
        var trial=result.practiceSplit(selectedNumber(),settings);
        var track=result.track();
        float spawn=trial.spawnDistance();
        var runUpPath=TrackMapView.circuitPath(track,transform,spawn,trial.startDistance());
        var point=mapPoint(track.sample(spawn).position());
        var launchMarker=new Ellipse2D.Float(point.x-4,point.y-4,8,8);
        launchDrawings[selected]=new LaunchDrawing(settings.runUpMeters(),runUpPath,launchMarker,trial.spawnBlocked());
    }

    private Point2D.Float mapPoint(Vector3f point) {
        return new Point2D.Float(transform.x(point.x),transform.y(point.z));
    }

    private void drawMap(Graphics2D graphics,TimingDrawing timing,LaunchDrawing launch) {
        var map=(Graphics2D)graphics.create();
        try {
            map.clip(MAP);
            map.setStroke(BASE);
            map.setColor(ROAD);
            map.draw(road);
            map.setColor(SECTION);
            map.draw(timedRoute);
            map.setStroke(RUN_UP);
            map.setColor(APPROACH);
            map.draw(launch.runUpPath());
            map.setStroke(SELECTED);
            map.setColor(HIGHLIGHT);
            map.draw(timing.highlight());
            map.setStroke(OUTLINE);
            map.draw(timing.startMarker());
            map.setColor(FINISH);
            map.fill(timing.finishMarker());
            map.setColor(launch.spawnBlocked()?FINISH:APPROACH);
            map.fill(launch.launchMarker());
        } finally {
            map.dispose();
        }
    }
}
