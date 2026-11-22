package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.CircuitSectionPractice;
import com.f1car.model.ControlIdentity;
import com.f1car.model.EquipmentSectionPracticeSession.Car;
import com.f1car.model.EquipmentSectionPracticeSession.SplitReading;
import com.f1car.model.EquipmentSectionPracticeSession.SubsectionReference;
import com.f1car.model.PracticeSession;
import com.jme3.math.Vector3f;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Draft launch conditions for the exact metric gates chosen in the telemetry comparison. */
final class EquipmentSectionPreviewHud {
    static final String DIFFERENT_LAUNCH="发车或控制不同 · 仅列原次读数";
    private static final Rectangle MAP=new Rectangle(60,150,1154,270);
    private static final Color PANEL=new Color(0x13242e),MAP_BACKGROUND=new Color(0x1b3440),ROAD=new Color(0x67828b);
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xb0c6c8),TIMED=new Color(0x63e6bc);
    private static final Color APPROACH=new Color(0xffcc82),BLOCKED=new Color(0xff9298);
    private static final BasicStroke ROAD_LINE=new BasicStroke(2);
    private static final BasicStroke TIMED_LINE=new BasicStroke(4,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke APPROACH_LINE=new BasicStroke(3,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,10,new float[]{7,5},0);

    private record Drawing(TrackMapView.Transform transform,Path2D road,Path2D timed,Path2D approach,
                           Vector3f spawn,Vector3f start,Vector3f end,boolean blocked) {}

    private final BooleanSupplier stillSelected;
    private FormulaGame.EquipmentSectionPreview preview;
    private Drawing drawing;
    private String notice="";

    EquipmentSectionPreviewHud(FormulaGame.EquipmentSectionPreview preview,BooleanSupplier stillSelected) {
        this.stillSelected=stillSelected;
        this.preview=preview;
        drawing=buildDrawing(preview);
    }

    private static Drawing buildDrawing(FormulaGame.EquipmentSectionPreview value) {
        var track=value.track();
        var practice=value.practice();
        float spawn=practice.spawnDistance(),from=practice.startDistance(),to=practice.finishDistance();
        var transform=TrackMapView.fit(TrackMapView.circuitBounds(track,spawn,to),MAP,32);
        return new Drawing(transform,TrackMapView.path(track,transform),TrackMapView.circuitPath(track,transform,from,to),
                TrackMapView.circuitPath(track,transform,spawn,from),track.sample(spawn).position(),
                track.sample(from).position(),track.sample(to).position(),practice.spawnBlocked());
    }

    void editStart(HudRenderer hud,boolean distance) {
        var start=preview.start();
        hud.openNumberDialog(distance?"助跑距离 · 到计时起点":"放行速度 · 0 为静止起步",
                distance?start.runUpMeters():start.speedKph(),
                distance?CircuitSectionPractice.Start.MIN_RUN_UP:0,
                distance?CircuitSectionPractice.Start.maximumRunUp(preview.track()):CircuitSectionPractice.Start.MAX_SPEED_KPH,
                distance?"m":"km/h",value->{
                    var updated=distance?new CircuitSectionPractice.Start(value,start.speedKph())
                            :new CircuitSectionPractice.Start(start.runUpMeters(),value);
                    int previousRunUp=preview.start().runUpMeters();
                    preview=preview.withStart(updated);
                    if(updated.runUpMeters()!=previousRunUp) drawing=buildDrawing(preview);
                    notice="";
                    return true;
                });
    }

    boolean start(FormulaGame app,Runnable reopenComparison,String backLabel) {
        if(drawing.blocked()) return false;
        if(!stillSelected.getAsBoolean() || app.screen!=preview.origin()) {
            notice="所选的两圈或区间已变化，请返回后重新预览。";
            return false;
        }
        if(app.startEquipmentSection(preview,reopenComparison,backLabel)) return true;
        notice=app.toast.isBlank()?"当前无法开始练习，请重新核对发车条件。":app.toast;
        return false;
    }

    void render(HudRenderer hud,Graphics2D graphics,FormulaGame app,String title,String backLabel,Runnable back,Runnable launch) {
        hud.panel(28,28,1224,738,PANEL);
        hud.text(title,60,90,30,INK,true);
        hud.text(String.format(Locale.ROOT,"%s · 计时 %.3f–%.3f m",preview.track().name,preview.from(),preview.to()),60,126,17,SOFT);
        hud.smallButton(1000,56,214,40,backLabel,back);
        drawMap(hud,graphics);
        hud.text("● 发车点 · 金色虚线为助跑 · 绿色实线为计时区间",60,447,16,SOFT);

        carButton(hud,Car.STOCK,60);
        if(app.equipmentSectionCarAvailable(preview.session(),Car.A)) {
            carButton(hud,Car.A,214);
            carButton(hud,Car.B,368);
            hud.text("同条件最快   A "+best(Car.A)+"   /   B "+best(Car.B),530,489,16,SOFT);
            var session=preview.session();
            var controls=preview.controls().identity();
            var start=preview.start();
            var a=session.progress(Car.A,start,controls);
            var b=session.progress(Car.B,start,controls);
            hud.text(HudRenderer.equipmentPracticeSummary(a,b),60,635,16,SOFT);
            if(session.hasEquipmentRuns(start,controls)) {
                var source=preview;
                hud.smallButton(970,609,244,41,"查看 A/B 逐次明细",()->hud.reviewEquipmentSectionRuns(source,stillSelected));
            }
        } else hud.text("记录车型或物理版本与当前游戏不同。",214,489,16,SOFT);

        hud.smallButton(60,522,262,48,"助跑距离  A · "+preview.start().runUpMeters()+" m",()->editStart(hud,true));
        hud.smallButton(338,522,265,48,"放行速度  V · "+preview.start().speedKph()+" km/h",()->editStart(hud,false));
        if(!drawing.blocked()) hud.smallButton(642,522,326,48,"开始独立练习  Enter",launch);
        String status=drawing.blocked()?"发车点被布景占用，请调整助跑距离。":notice;
        if(!status.isEmpty()) hud.text(status,60,609,17,BLOCKED,true);
        hud.text("驾驶控制 · "+(preview.controls().enabled()?"手柄":"键盘"),60,663,16,SOFT);
        var original=preview.subsectionReference();
        if(original==null) hud.text("倒计时结束后按设定速度放行，穿过第一道门才开始计时。",60,697,16,SOFT);
        else {
            hud.text(originalStart(original),440,663,16,SOFT);
            if(!original.matchesLaunch(preview.start(),preview.controls().identity()))
                hud.text(DIFFERENT_LAUNCH,950,663,15,APPROACH);
            hud.text(originalReading("A",original.a()),60,701,16,INK);
            hud.text(originalReading("B",original.b()),60,733,16,INK);
        }
    }

    static String originalStart(SubsectionReference reference) {
        String controls=ControlIdentity.KEYBOARD.equals(reference.controls())?"键盘":"手柄";
        return "原区间 · "+CircuitSectionHud.startLabel(reference.start())+" · "+controls;
    }

    static String originalReading(String car,SplitReading reading) {
        return car+" 原次 #"+reading.attemptNumber()+" · "+PracticeSession.format(reading.seconds())
                +" · "+CircuitSectionHud.speedLabel(reading.speeds());
    }

    private void carButton(HudRenderer hud,Car car,int x) {
        String label=(preview.car()==car?"✓ ":"")+car.label();
        hud.smallButton(x,464,140,39,label,()->{
            preview=preview.withCar(car);
            notice="";
        });
    }

    private String best(Car car) {
        var times=preview.session().referenceTimes(car,preview.start(),preview.controls().identity());
        return times==null?"—":PracticeSession.format(times.best());
    }

    private void drawMap(HudRenderer hud,Graphics2D graphics) {
        hud.panel(MAP.x,MAP.y,MAP.width,MAP.height,MAP_BACKGROUND);
        var g=(Graphics2D)graphics.create();
        try {
            g.clip(MAP);
            g.setStroke(ROAD_LINE);g.setColor(ROAD);g.draw(drawing.road());
            g.setStroke(TIMED_LINE);g.setColor(TIMED);g.draw(drawing.timed());
            g.setStroke(APPROACH_LINE);g.setColor(APPROACH);g.draw(drawing.approach());
            mark(hud,g,drawing.spawn(),"车",drawing.blocked()?BLOCKED:APPROACH,-18,21);
            mark(hud,g,drawing.start(),"起",TIMED,-14,-12);
            mark(hud,g,drawing.end(),"终",INK,10,20);
        } finally {
            g.dispose();
        }
    }

    private void mark(HudRenderer hud,Graphics2D g,Vector3f point,String label,Color color,int dx,int dy) {
        var transform=drawing.transform();
        int x=Math.round(transform.x(point.x)),y=Math.round(transform.y(point.z));
        g.setColor(color);g.fillOval(x-5,y-5,10,10);
        hud.text(label,x+dx,y+dy,14,color,true);
    }
}
