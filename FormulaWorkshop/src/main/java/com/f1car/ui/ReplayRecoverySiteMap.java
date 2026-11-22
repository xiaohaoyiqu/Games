package com.f1car.ui;

import com.f1car.model.PracticeSession;
import com.f1car.model.RecoveryPlanner;
import com.f1car.model.ReplayReviewTimeline;
import com.f1car.model.TrackLayout;
import com.jme3.input.KeyInput;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Renders the captured decisions in a shared, north-up world viewport. */
final class ReplayRecoverySiteMap {
    private static final Rectangle LEFT=new Rectangle(60,192,556,356),RIGHT=new Rectangle(658,192,556,356);
    private static final Color PANEL=new Color(0x162b38),MAP=new Color(0x1b3940),ROAD=new Color(0x6d8889);
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xb5c9cc);
    private static final Color FOCUS=new Color(0x63e6bc);
    private static final Color PLACED=new Color(0xd0f88a);
    private static final Color GEOMETRY=new Color(0xe0e9e8),PREDICTED=new Color(0xffbc75),INCOMPLETE=new Color(0xffa8c0);
    private static final BasicStroke ROAD_LINE=new BasicStroke(3),MARK=new BasicStroke(2);
    enum Phase {
        FIRST_FAILURE("首次失败"), LAST_FAILURE("末次失败"), RECOVERED("完成复位");
        final String label;
        Phase(String label) {this.label=label;}
    }
    record Scene(double observationTime,double frameTime,int driver,Phase phase) {}
    private final RecoveryPlanner.FailedSites first,last;
    private final TrackMapView.Transform transform;
    private final Path2D road;
    private final double firstTime,lastTime,recoveredTime;
    private final OptionalDouble firstFrame,lastFrame;
    private final RecoveryPlanner.Placement placement;
    private final int waitingDriver;
    private final String driver;
    private final IntFunction<String> driverLabel;
    private boolean lastSelected;
    private int selected;

    ReplayRecoverySiteMap(TrackLayout track,ReplayReviewTimeline timeline,ReplayReviewTimeline.Mark entry,IntFunction<String> driverLabel) {
        var locations=entry.waitLocations();
        this.driverLabel=driverLabel;
        waitingDriver=entry.driver();
        driver=driverLabel.apply(waitingDriver);
        firstTime=entry.time();
        lastTime=locations.lastTime();
        recoveredTime=entry.through();
        placement=locations.placement();
        firstFrame=timeline.recoverySceneTime(entry,false);
        lastFrame=firstTime==lastTime?firstFrame:timeline.recoverySceneTime(entry,true);
        first=locations.first();
        last=locations.last();
        transform=TrackMapView.fit(bounds(first,last,placement),LEFT,26);
        road=TrackMapView.clippedOutline(track,transform,LEFT,ROAD_LINE);
    }

    void render(HudRenderer hud,Graphics2D graphics,Runnable back,Consumer<Scene> showScene) {
        hud.panel(28,28,1224,738,PANEL);
        hud.text("复位搜索落点 · "+driver,60,84,29,INK,true);
        hud.text("首次与末次失败搜索 · 同尺度 · 记录的候选位置",60,120,16,SOFT);
        hud.smallButton(1004,56,210,40,"返回回看标记",back);
        if(placement!=null) {
            var site=placement.site();
            hud.text("选中的落点 · 第 "+(placement.index()+1)+" 个候选 · X "+site.x()+" m · Z "+site.z()+" m",658,120,16,PLACED);
        }
        drawPane(hud,graphics,LEFT,first,"首次失败 · "+PracticeSession.format(firstTime),lastSelected?-1:selected);
        drawPane(hud,graphics,RIGHT,last,"末次失败 · "+PracticeSession.format(lastTime),lastSelected?selected:-1);
        hud.text("× 固定条件排除",60,622,17,GEOMETRY);
        hud.text("■ 交通预测排除",293,622,17,PREDICTED);
        hud.text("○ 读数不全时排除",533,622,17,INCOMPLETE);
        if(placement!=null) hud.text("◇ 最终复位落点",784,622,17,PLACED);
        if(firstTime==lastTime) hud.text("两侧显示同一次失败搜索",998,622,16,SOFT);
        if(firstTime!=lastTime) hud.smallButton(660,651,230,42,lastSelected?"切到首次  Tab":"切到末次  Tab",this::switchPane);
        hud.smallButton(902,651,144,42,"← 上一候选",()->move(-1));
        hud.smallButton(1058,651,156,42,"下一候选 →",()->move(1));
        var sites=activeSites();
        var point=sites.points().get(selected);
        hud.text("候选 "+(selected+1)+" / "+sites.points().size()+" · X "+point.x()+" m · Z "+point.z()+" m",60,666,17,INK,true);
        hud.text(selectedDecision(),60,697,16,SOFT);
        var recorded=activeFrame();
        hud.text(frameLabel(recorded),60,734,16,SOFT);
        if(recorded.isPresent()) {
            hud.smallButton(660,709,178,42,"查看等待车辆  P",()->openScene(false,showScene));
            if(sites.blockingDriverAt(selected)!=RecoveryPlanner.UNKNOWN_DRIVER)
                hud.smallButton(850,709,174,42,"查看排除车辆  B",()->openScene(true,showScene));
        }
        if(placement!=null) hud.smallButton(1036,709,178,42,"查看复位完成  R",()->openRecovery(showScene));
    }

    private String selectedDecision() {
        var sites=activeSites();
        int blocker=sites.blockingDriverAt(selected);
        return switch(sites.reasonAt(selected)) {
            case NO_STATIC_CANDIDATE -> "固定条件排除";
            case TRAFFIC_PREDICTION -> "交通预测排除 · "+blockerLabel(blocker);
            case INCOMPLETE_TRAFFIC -> "读数不全时排除 · "+blockerLabel(blocker);
        };
    }

    private String blockerLabel(int blocker) {
        return blocker==RecoveryPlanner.UNKNOWN_DRIVER?"未保存排除车辆":"排除依据之一 · "+driverLabel.apply(blocker);
    }

    void key(int key,Runnable back,Consumer<Scene> showScene) {
        switch(key) {
            case KeyInput.KEY_ESCAPE,KeyInput.KEY_M,KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> back.run();
            case KeyInput.KEY_TAB -> switchPane();
            case KeyInput.KEY_LEFT -> move(-1);
            case KeyInput.KEY_RIGHT -> move(1);
            case KeyInput.KEY_P -> openScene(false,showScene);
            case KeyInput.KEY_B -> openScene(true,showScene);
            case KeyInput.KEY_R -> openRecovery(showScene);
            default -> {}
        }
    }

    private RecoveryPlanner.FailedSites activeSites() {return lastSelected?last:first;}
    private OptionalDouble activeFrame() {return lastSelected?lastFrame:firstFrame;}
    private double activeSearchTime() {return lastSelected?lastTime:firstTime;}

    private String frameLabel(OptionalDouble recorded) {
        if(recorded.isEmpty()) return "该搜索附近无可定位的已录画面";
        double time=recorded.getAsDouble();
        if(time==activeSearchTime()) return "搜索时的录制画面 · "+PracticeSession.format(time);
        return "同段较早画面 · "+PracticeSession.format(time);
    }

    private void openScene(boolean blocker,Consumer<Scene> showScene) {
        var recorded=activeFrame();
        int target=blocker?activeSites().blockingDriverAt(selected):waitingDriver;
        if(recorded.isPresent() && target!=RecoveryPlanner.UNKNOWN_DRIVER)
            showScene.accept(new Scene(activeSearchTime(),recorded.getAsDouble(),target,lastSelected?Phase.LAST_FAILURE:Phase.FIRST_FAILURE));
    }

    private void openRecovery(Consumer<Scene> showScene) {
        if(placement!=null) showScene.accept(new Scene(recoveredTime,recoveredTime,waitingDriver,Phase.RECOVERED));
    }

    private void switchPane() {
        if(firstTime==lastTime) return;
        lastSelected=!lastSelected;
        selected=Math.min(selected,activeSites().points().size()-1);
    }

    private void move(int step) {
        selected=Math.floorMod(selected+step,activeSites().points().size());
    }

    private static TrackLayout.Bounds bounds(RecoveryPlanner.FailedSites first,RecoveryPlanner.FailedSites last,
                                             RecoveryPlanner.Placement placement) {
        float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY;
        float minZ=Float.POSITIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        for(var sites:new RecoveryPlanner.FailedSites[]{first,last}) {
            for(var point:sites.points()) {
                minX=Math.min(minX,point.x());
                maxX=Math.max(maxX,point.x());
                minZ=Math.min(minZ,point.z());
                maxZ=Math.max(maxZ,point.z());
            }
        }
        if(placement!=null) {
            var site=placement.site();
            minX=Math.min(minX,site.x());
            maxX=Math.max(maxX,site.x());
            minZ=Math.min(minZ,site.z());
            maxZ=Math.max(maxZ,site.z());
        }
        return new TrackLayout.Bounds(minX-8,maxX+8,minZ-8,maxZ+8);
    }

    private void drawPane(HudRenderer hud,Graphics2D graphics,Rectangle area,RecoveryPlanner.FailedSites sites,String title,int selected) {
        hud.text(title,area.x,172,21,selected<0?INK:FOCUS,true);
        hud.panel(area.x,area.y,area.width,area.height,MAP);
        var g=(Graphics2D)graphics.create();
        try {
            g.clip(area);
            g.translate(area.x-LEFT.x,area.y-LEFT.y);
            g.setStroke(ROAD_LINE);
            g.setColor(ROAD);
            g.draw(road);
            g.setStroke(MARK);
            for(int index=0;index<sites.points().size();index++) {
                var point=sites.points().get(index);
                int x=Math.round(transform.x(point.x())),y=Math.round(transform.y(point.z()));
                switch(sites.reasonAt(index)) {
                    case NO_STATIC_CANDIDATE -> {
                        g.setColor(GEOMETRY);
                        g.drawLine(x-5,y-5,x+5,y+5);
                        g.drawLine(x-5,y+5,x+5,y-5);
                    }
                    case TRAFFIC_PREDICTION -> {
                        g.setColor(PREDICTED);
                        g.fillRect(x-4,y-4,9,9);
                    }
                    case INCOMPLETE_TRAFFIC -> {
                        g.setColor(INCOMPLETE);
                        g.drawOval(x-5,y-5,10,10);
                    }
                }
            }
            if(placement!=null) {
                var site=placement.site();
                int x=Math.round(transform.x(site.x())),y=Math.round(transform.y(site.z()));
                g.setColor(PLACED);
                g.drawPolygon(new int[]{x,x+14,x,x-14},new int[]{y-14,y,y+14,y},4);
            }
            if(selected>=0) {
                var point=sites.points().get(selected);
                int x=Math.round(transform.x(point.x())),y=Math.round(transform.y(point.z()));
                g.setColor(FOCUS);
                g.drawOval(x-10,y-10,20,20);
            }
        } finally {
            g.dispose();
        }
        int geometry=Integer.bitCount(sites.geometryMask());
        int predicted=Integer.bitCount(sites.predictedMask());
        int incomplete=sites.points().size()-geometry-predicted;
        hud.text("几何 "+geometry+" 点 · 交通预测 "+predicted+" 点 · 读数不全 "+incomplete+" 点",area.x,581,16,SOFT);
    }
}
