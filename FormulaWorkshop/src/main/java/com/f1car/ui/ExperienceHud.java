package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Per-map practice board and read-only playback of measured personal attempts. */
final class ExperienceHud {
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(255,205,122),PANEL=new Color(10,29,39,245);
    private static final Rectangle TIMELINE=new Rectangle(70,548,600,20);
    private static final Rectangle MAP = new Rectangle(70, 179, 600, 309);
    private static final BasicStroke ROUTE_STROKE = new BasicStroke(4), TRACE_STROKE = new BasicStroke(2);
    private record Gate(int x, int y, Color color) {}
    private record CourseLayer(Path2D.Float section, List<Gate> gates) {}
    private TrackLayout mapTrack;
    private TrackMapView.Outline outline;
    private final CourseLayer[] layers = new CourseLayer[4];
    private ExperienceRun traceRun;
    private Path2D.Float tracePath;
    private TelemetryLap.Sample formattedSample;
    private String playbackReading;
    private ExperiencePlayer scrubPlayer;

    void clearMap() {
        if (mapTrack != null) Arrays.fill(layers, null);
        mapTrack = null;
        outline = null;
        clearTrace();
        endScrub();
    }

    private void clearTrace() {
        traceRun = null;
        tracePath = null;
        formattedSample = null;
        playbackReading = null;
    }

    private CourseLayer courseLayer(CircuitExperience.Course course) {
        var track = course.track();
        if (mapTrack != track) {
            clearMap();
            outline = new TrackMapView.Outline(track, MAP, 20, MUTED, 4);
            mapTrack = track;
        }
        var layer = layers[course.part()];
        if (layer != null) return layer;
        var map = outline.transform();
        var section = TrackMapView.circuitPath(track, map, course.start(), course.start() + course.length());
        var gates = new ArrayList<Gate>(course.gates() + 1);
        for (int i = 0; i <= course.gates(); i++) {
            var point = course.gate(i).position();
            gates.add(new Gate(Math.round(map.x(point.x)), Math.round(map.y(point.z)), i == 0 ? MINT : i == course.gates() ? GOLD : INK));
        }
        layer = new CourseLayer(section, List.copyOf(gates));
        layers[course.part()] = layer;
        return layer;
    }

    private Path2D.Float trace(ExperienceRun run, TrackMapView.Transform map) {
        if (traceRun == run) return tracePath;
        var path = new Path2D.Float(Path2D.WIND_NON_ZERO,run.samples().size());
        boolean first = true;
        for (var sample : run.samples()) {
            if (first) {path.moveTo(map.x(sample.x()), map.y(sample.z()));first = false;}
            else path.lineTo(map.x(sample.x()), map.y(sample.z()));
        }
        tracePath = path;
        traceRun = run;
        formattedSample = null;
        return path;
    }
    void board(HudRenderer h,Graphics2D g,FormulaGame app) {
        var course=app.experienceCourse();var player=app.experiencePlayer();
        h.panel(38,32,1204,732,PANEL);h.text("赛道分段攻关",70,92,32,INK,true);h.text(course.track().name,70,128,23,MINT,true);
        h.text("固定原厂性能 · 初速 36 km/h · "+(ControlIdentity.KEYBOARD.equals(app.experienceInputIdentity())?"键盘":"手柄")+" · 各项目可直接进入",70,155,15,MUTED);
        h.smallButton(850,72,160,39,"上一张地图",()->app.cycleExperienceTrack(-1));h.smallButton(1030,72,176,39,"下一张地图",()->app.cycleExperienceTrack(1));
        var playbackSample=player==null?null:player.sample();
        h.panel(MAP.x, MAP.y, MAP.width, MAP.height, new Color(15,39,47));
        var layer = courseLayer(course);
        var map = outline.draw(g);
        g.setStroke(ROUTE_STROKE);g.setColor(MINT);g.draw(layer.section());
        for (var gate : layer.gates()) {g.setColor(gate.color());g.fillOval(gate.x()-3,gate.y()-3,6,6);}
        if(player!=null) {
            g.setStroke(TRACE_STROKE);g.setColor(GOLD);g.draw(trace(player.run(), map));int x=Math.round(map.x(playbackSample.x())),y=Math.round(map.y(playbackSample.z()));g.fillOval(x-6,y-6,12,12);g.setColor(INK);g.drawOval(x-8,y-8,16,16);
        } else if(traceRun!=null) clearTrace();
        for(var entry:app.experienceCourses()) {
            int part=entry.part(),y=178+part*99;var best=app.experienceBest(part);boolean selected=part==course.part();
            h.panel(700,y,506,87,selected?new Color(29,67,70):new Color(22,45,56));h.text(entry.title(),720,y+29,22,selected?MINT:INK,true);
            h.text(best==null?"尚无当前条件的成绩":"个人最佳 "+seconds(best.duration())+" · "+best.medal().title,720,y+61,16,best==null?MUTED:GOLD);
            h.smallButton(1104,y+24,82,35,selected?"已选择":"选择",()->app.selectExperiencePart(part));
        }
        var targets=course.targets();h.text("金 "+seconds(targets.gold())+"    银 "+seconds(targets.silver())+"    铜 "+seconds(targets.bronze()),70,522,18,GOLD);
        if(player!=null) {
            int offset=Math.round(TIMELINE.width*(float)(player.time()/player.run().duration()));
            h.panel(TIMELINE.x,TIMELINE.y,TIMELINE.width,TIMELINE.height,new Color(29,62,73));
            g.setColor(MINT);
            g.fillRect(TIMELINE.x,TIMELINE.y,offset,TIMELINE.height);
            g.setColor(INK);
            g.fillRect(TIMELINE.x+offset-1,TIMELINE.y-2,3,TIMELINE.height+4);
            h.text(playbackReading(player,playbackSample),70,596,15,INK);
            h.smallButton(70,618,180,36,player.playing()?"暂停回看":"播放回看",player::toggle);h.smallButton(268,618,180,36,"速度 "+player.speed()+"×",player::speedCycle);h.smallButton(466,618,204,36,"回到开头",()->player.seek(0));
            h.text("个人有效记录 · "+("keyboard-v1".equals(player.run().conditions().controls())?"键盘":"手柄"),70,680,14,MUTED);
        } else {
            h.wrapped("完成一次有效尝试后，可回看个人最佳的线路、速度和操作，并在下一次尝试中选择显示幽灵。",70,574,595,18,MUTED,29);
        }
        h.wrapped(course.focus(),720,605,463,17,INK,26);
        h.smallButton(720,643,224,35,"查看个人最佳回看",app::viewExperienceBest);
        h.smallButton(958,643,228,35,"对照近期有效成绩",()->compareRecent(h,app));
        h.smallButton(70,701,420,42,"开始 · "+course.title(),app::startExperience);h.smallButton(510,701,270,42,app.store.profile.ghostEnabled?"个人最佳幽灵：显示":"个人最佳幽灵：隐藏",app::toggleGhost);
        if(app.experienceStore.dirty()) h.smallButton(800,701,200,42,
                app.experienceStore.conflictReviewAvailable()?"核对两份分段成绩":"重试保存记录",app::retryExperienceSave);
        h.smallButton(1020,701,186,42,"返回练习中心",app::backFromExperience);
    }
    private void compareRecent(HudRenderer hud, FormulaGame app) {
        var comparison = app.compareRecentExperience();
        if (comparison == null) return;
        var summary = new ArrayList<String>();
        var course=app.experienceCourse();
        summary.add(course.track().name + " · " + course.title());
        summary.add(String.format(Locale.ROOT, "个人最佳 %.3f s · 近次 %.3f s · 近次相差 %+.3f s",
                comparison.reference().duration(), comparison.recent().duration(), comparison.difference()));
        summary.add("记录编号 " + comparison.reference().id().substring(0, 8) + " / " + comparison.recent().id().substring(0, 8));
        summary.add("两次均为同条件已存记录。用时差按近次减个人最佳计算。");
        for (var gate : comparison.gates()) {
            summary.add(String.format(Locale.ROOT,
                    "门 %d–%d  最佳 %.3f s / 近次 %.3f s · 本段 %+.3f s · 累计 %+.3f s",
                    gate.number() - 1, gate.number(), gate.referenceSplit(), gate.recentSplit(), gate.splitDifference(), gate.elapsedDifference()));
        }
        hud.review("同条件分段成绩对照", summary, "回看这次近期成绩", () -> app.viewComparedExperience(comparison));
    }
    private String playbackReading(ExperiencePlayer player,TelemetryLap.Sample sample) {
        if(formattedSample!=sample) {
            var controls=sample.driving();
            playbackReading=String.format(Locale.ROOT,"%.2f / %.2f s · %.0f km/h · 油 %.0f%% 刹 %.0f%% 转 %+.0f%%",
                    player.time(),player.run().duration(),controls.speed()*3.6,controls.throttle()*100,controls.brake()*100,controls.steer()*100);
            formattedSample=sample;
        }
        return playbackReading;
    }

    boolean click(FormulaGame app,float x,float y,boolean pointer) {
        var player=app.experiencePlayer();
        if(app.screen!=FormulaGame.Screen.EXPERIENCE || app.helpVisible || player==null
                || !Float.isFinite(x) || !Float.isFinite(y) || x<TIMELINE.x || x>TIMELINE.x+TIMELINE.width
                || y<TIMELINE.y || y>TIMELINE.y+TIMELINE.height) return false;
        player.pauseAt(timelineTime(player,x));
        if(pointer) scrubPlayer=player;
        return true;
    }

    boolean scrubbing() {return scrubPlayer!=null;}
    void endScrub() {scrubPlayer=null;}

    void drag(FormulaGame app,float x) {
        if(scrubPlayer==null) return;
        if(app.screen!=FormulaGame.Screen.EXPERIENCE || app.helpVisible || app.experiencePlayer()!=scrubPlayer || scrubPlayer.playing()) {
            endScrub();
            return;
        }
        if(!Float.isFinite(x)) return;
        double target=timelineTime(scrubPlayer,x);
        if(target!=scrubPlayer.time()) scrubPlayer.pauseAt(target);
    }

    private static double timelineTime(ExperiencePlayer player,float x) {
        double offset=Math.max(0,Math.min(TIMELINE.width,x-TIMELINE.x));
        return offset/TIMELINE.width*player.run().duration();
    }
    void driving(HudRenderer h,FormulaGame app) {
        var trial=app.experience();h.panel(25,101,465,191,PANEL);h.text(trial.course().title(),46,135,23,MINT,true);
        h.text(app.drivingHint("原厂性能 · 起始 36 km/h · R 固定起点重试"),46,164,15,MUTED);
        h.wrapped(trial.state()==ExperienceTrial.State.READY?"正在准备固定起始状态。":trial.feedback(),46,194,420,16,INK,24);
        if(trial.completedGates()>0 && app.experienceReference()!=null) { int index=trial.completedGates()-1;double delta=trial.gateTime(index)-app.experienceReference().gateTimes().get(index);h.text(String.format(Locale.ROOT,"最近路门相对个人最佳 %+.3f s",delta),46,269,16,delta<=0?MINT:GOLD); }
        else h.text("通过全部有序路门；离开道路需重新开始。",46,269,15,MUTED);
    }
    void result(HudRenderer h,FormulaGame app) {
        var trial=app.experience();boolean success=trial.state()==ExperienceTrial.State.SUCCESS;
        h.text(trial.course().title()+" · "+app.circuit.name,316,343,19,INK,true);h.text("用时 "+seconds(trial.elapsed()),316,385,28,MINT,true);
        if(success) {
            var run=trial.result();
            String status=app.experienceStore.saved(run)?(app.newBest?"当前条件的个人最佳":"本次有效，个人最佳保留")
                    :(app.experienceStore.dirty()?"本次有效，成绩等待保存":"本次有效，成绩未进入档案");
            h.text(status,316,423,18,GOLD);
            var loss=run.largestLoss(app.experienceReference());h.wrapped(loss.isPresent()?String.format(Locale.ROOT,"路门 %d–%d 区间比该参考多用 %.3f s；可在回看中观察线路与操作。",loss.get().gate()-1,loss.get().gate(),loss.get().delta()):"当前尝试的线路与操作已记录；幽灵只使用同条件下完整保存的一次尝试。",316,459,640,16,MUTED,25);
            h.smallButton(316,521,642,33,"查看本次线路与操作回看",app::viewExperienceResult);
        } else {
            var position=trial.observedPosition();h.text("已通过路门 "+trial.completedGates()+" / "+trial.course().gates(),316,427,18,GOLD);
            h.wrapped(String.format(Locale.ROOT,"观测位置 X %.1f m / Z %.1f m。下一路门 %d；本次失败不写入最佳或奖牌。",position.x,position.z,trial.nextGate()),316,467,640,17,MUTED,27);
        }
        h.smallButton(316,570,198,45,"固定起点重试",app::retry);h.smallButton(538,570,198,45,"返回攻关列表",app::openExperience);h.smallButton(760,570,198,45,"练习中心",app::backFromExperience);
        if(app.experienceStore.dirty()) h.smallButton(316,627,642,28,
                app.experienceStore.conflictReviewAvailable()?"核对两份分段成绩":"记录尚未保存 · 点击重试",app::retryExperienceSave);
    }
    private String seconds(double value) { return String.format(Locale.ROOT,"%.3f s",value); }
}
