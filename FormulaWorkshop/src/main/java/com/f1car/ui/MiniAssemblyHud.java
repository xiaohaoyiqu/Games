package com.f1car.ui;

import com.f1car.mini4wd.Mini4wdGame;
import com.f1car.mini4wd.MiniAssembly;
import com.f1car.mini4wd.MiniAssembly.Slot;
import com.f1car.mini4wd.MiniAssemblyComparison;
import com.f1car.mini4wd.MiniSetup;
import com.f1car.model.PracticeSession;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class MiniAssemblyHud {
    private static final Color LIGHT = new Color(0xeaf4ef), SOFT = new Color(0xb0c6c8), TEAL = new Color(0x63e6bc), AMBER = new Color(0xffb267);
    private static final Color PANEL = new Color(0x1c343f), DARK = new Color(0x13242e);
    private static final Color GRID_DOT = new Color(0x29434d);
    private static final BasicStroke GUIDE_STROKE = new BasicStroke(1);
    private static final int[][] POSITIONS = {{343,198},{76,270},{76,343},{76,416},{611,270},{611,343},{76,198},{611,416},{343,479},{611,198}};
    private boolean blueprints;
    private int blueprintSlot;
    private boolean choosingComparison;
    private int comparisonSlot=-1;
    private MiniSetup displayedSetup;
    private String massText, accelerationText, limitsText, readinessText;

    String navigationContext(Mini4wdGame game) {
        if(game.pendingAssembly()!=null) return "pending";
        if(game.assemblyComparison()!=null) return "comparison";
        if(choosingComparison) return "comparison-choice";
        return blueprints?"blueprints":"parts";
    }

    boolean closeChooser(Mini4wdGame game) {
        if(game.pendingAssembly()!=null || game.assemblyComparison()!=null || !choosingComparison && !blueprints) return false;
        choosingComparison=false;
        blueprints=false;
        return true;
    }

    void render(HudRenderer ui, Graphics2D g, Mini4wdGame game) {
        ui.panel(48, 178, 832, 520, PANEL);
        ui.panel(912, 178, 320, 520, PANEL);
        var pending=game.pendingAssembly();
        if(pending!=null) {renderPending(ui,game,pending);return;}
        var comparison=game.assemblyComparison();
        if(comparison!=null) {renderComparison(ui,game,comparison);return;}
        if(choosingComparison) {chooseComparison(ui,game);return;}
        if(blueprints) { renderBlueprints(ui,game);return; }
        var setup = game.setup();
        updateSetupReadings(setup);
        g.setColor(GRID_DOT);
        for (int x = 60; x < 880; x += 24) for (int y = 180; y < 539; y += 24) {
            g.fillRect(x, y, 1, 1);
        }
        g.setColor(SOFT);
        g.setStroke(GUIDE_STROKE);
        g.drawLine(300, 368, 599, 368); g.drawLine(455, 252, 455, 466);
        MiniCarPainter.paint(g, 455, 366, -Math.PI / 2, 5, setup, TEAL, true);
        if (setup.installedParts() == 0) ui.centered("空套件 · 从底盘开始", 455, 367, 18, SOFT);
        for (Slot slot : Slot.values()) {
            int[] p = POSITIONS[slot.ordinal()];
            String state = setup.has(slot) ? "已装" : slot == Slot.DAMPER ? "选装" : "空位";
            ui.smallButton(p[0], p[1], 193, 44, slot.label + "  ·  " + state, () -> {
                if (game.assemblySlot() == slot) game.installAssemblyPart();
                else game.selectAssemblySlot(slot);
            });
            if (game.assemblySlot() == slot) {
                g.setColor(TEAL); g.drawRect(p[0] - 2, p[1] - 2, 197, 48);
            }
        }
        ui.text("点安装位选择零件，再点一次装入；车壳以透视框显示。", 76, 549, 14, SOFT);
        ui.text(massText, 76, 589, 20, LIGHT, true);
        ui.text(accelerationText, 343, 585, 16, LIGHT);
        ui.text(limitsText, 343, 613, 15, SOFT);
        ui.text(readinessText, 76, 624, 16, setup.ready() ? TEAL : AMBER);
        ui.smallButton(76, 646, 232, 33, "组装检查", game::checkAssembly);
        if(!game.roomActive()) ui.smallButton(330,646,248,33,"装配蓝图 · 六套整车方案",()->{game.blueprints().reload();blueprints=true;});
        ui.smallButton(611, 646, 193, 33, "检查并试跑", () -> {
            if (!game.checkAssembly()) return;
            if (!game.inspection().ready()) game.message("车辆已组装，请返回地图选择可试跑路线。");
            else if (game.roomActive()) game.message("车辆已组装，请返回房间准备，由房主统一发车。");
            else game.startTrial();
        });
        Slot selected = game.assemblySlot();
        ui.text(selected.label + "零件盒", 936, 217, 21, TEAL, true);
        ui.smallButton(936, 236, 36, 37, "−", () -> game.cycleAssemblyPart(-1));
        ui.smallButton(1174, 236, 36, 37, "+", () -> game.cycleAssemblyPart(1));
        ui.centered(MiniAssembly.label(game.assemblyCandidate(), selected), 1090, 300, 16, LIGHT);
        ui.wrapped(selected.description, 936, 337, 274, 15, SOFT, 24);
        ui.text(setup.has(selected) ? "当前：" + MiniAssembly.label(setup, selected) : "当前安装位为空", 936, 420, 14, setup.has(selected) ? TEAL : AMBER);
        ui.smallButton(936, 438, 274, 40, "装入所选零件", game::installAssemblyPart);
        ui.smallButton(936, 489, 274, 37, "取下当前零件", game::removeAssemblyPart);
        ui.smallButton(936, 542, 132, 37, "新空白套件", game::newKit);
        ui.smallButton(1078, 542, 132, 37, "撤销装配", game::undoAssembly);
        if(!game.roomActive()) ui.smallButton(936,590,274,36,"原厂或蓝图 · 装配对比",()->{
            if(game.checkAssembly()) {
                game.blueprints().reload();
                comparisonSlot=-1;
                choosingComparison=true;
            }
        });
        ui.smallButton(936, 642, 274, 37, "载入完整原厂车", game::stockSetup);
    }

    private void updateSetupReadings(MiniSetup setup) {
        if(setup.equals(displayedSetup)) return;
        var stats=setup.performance();
        boolean ready=setup.ready();
        massText=String.format(Locale.ROOT,"组装质量 %.0f g",setup.mass());
        accelerationText=String.format(Locale.ROOT,(ready?"速度":"装齐后预估")+" %.2f 格/秒   加速度 %.2f",stats.speed(),stats.acceleration());
        limitsText=String.format(Locale.ROOT,"弯道 %.2f   坡道 %.2f   续航 %.2f",stats.curveLimit(),stats.rampLimit(),stats.capacity());
        readinessText=ready?"组装完整 · 可以检查试跑":"尚有 "+MiniAssembly.issues(setup).size()+" 项需要完成";
        displayedSetup=setup;
    }

    private void chooseComparison(HudRenderer ui,Mini4wdGame game) {
        ui.text("选择对比车辆",76,222,26,LIGHT,true);
        ui.text("A · 当前装配",76,261,17,TEAL,true);
        ui.text("地图 "+game.activeName()+" · "+(game.inspection().closed()?"三圈":"单程"),76,298,16,LIGHT);
        ui.smallButton(76,326,774,41,(comparisonSlot<0?"● ":"")+"B · 原厂完整车",()->comparisonSlot=-1);
        for(int slot=0;slot<com.f1car.mini4wd.MiniBlueprintStore.SLOTS;slot++) {
            var blueprint=game.blueprints().get(slot);
            if(blueprint==null) continue;
            int index=slot;
            ui.smallButton(76,372+slot*45,774,39,(comparisonSlot==slot?"● ":"")+"B · "+(slot+1)+" · "+blueprint.name(),
                ()->comparisonSlot=index);
        }
        ui.text("相同起点和路线，两辆车分别试跑。",936,224,15,SOFT);
        ui.wrapped("固定步长、全程无冲刺。能完成的两套装配将比较用时，脱轨会显示停止位置和实际速度。",936,278,274,16,SOFT,25);
        ui.smallButton(936,453,274,42,"核对条件并开始",()->reviewComparison(ui,game));
        ui.smallButton(936,642,274,37,"返回组装台",()->choosingComparison=false);
    }

    private void reviewComparison(HudRenderer ui,Mini4wdGame game) {
        int slot=comparisonSlot;
        var blueprint=slot<0?null:game.blueprints().get(slot);
        if(slot>=0 && blueprint==null) {game.message("所选蓝图已移除，请重新选择。");return;}
        var a=game.setup();
        var b=blueprint==null?MiniSetup.stock():blueprint.setup();
        String bName=blueprint==null?"原厂完整车":blueprint.name();
        try {
            var plan=new MiniAssemblyComparison.Plan(game.track(),game.activeName(),a,bName,b);
            var details=new ArrayList<>(List.of(
                "路线 "+plan.trackName()+" · "+(game.inspection().closed()?"三圈":"单程"),
                "A 当前装配 · "+assemblyName(a),
                "B "+bName+" · "+assemblyName(b),
                "以相同的 0.01 秒步长从静止分别试跑，全程无冲刺。",
                "台架用时和脱轨位置供组装比较，个人计时成绩保持原样。"));
            for(var part:Slot.values())
                details.add(part.label+" · A "+MiniAssembly.label(a,part)+" · B "+MiniAssembly.label(b,part));
            ui.review("开始两车装配对比？",details,
                "运行两车对比",()->{
                    if(slot>=0 && !game.blueprints().matchesSaved(slot,blueprint)) {
                        game.message("无法核对所选蓝图，请返回重新读取后选择。");
                        return;
                    }
                    game.startAssemblyComparison(plan);
                });
        } catch(IllegalArgumentException error) {game.message("请先组装完整车辆并选择可试跑路线。");}
    }

    private void renderComparison(HudRenderer ui,Mini4wdGame game,MiniAssemblyComparison comparison) {
        var plan=comparison.plan();
        ui.text("无冲刺装配对比",76,223,26,LIGHT,true);
        ui.text(plan.trackName()+" · "+(plan.track().inspect().closed()?"三圈":"单程"),76,260,17,SOFT);
        renderReading(ui,76,325,"A · 当前装配",plan.a(),comparison.a(),TEAL);
        renderReading(ui,480,325,"B · "+plan.bName(),plan.b(),comparison.b(),AMBER);
        var delta=comparison.timeDifference();
        if(delta!=null)
            ui.text(String.format(Locale.ROOT,"B − A 完成用时 %+.3f s",delta),76,620,18,LIGHT,true);
        else if(comparison.complete()) ui.text("两套装配均完成时显示用时差。",76,620,16,SOFT);
        ui.text(comparison.complete()?"对比已结束":"正在试跑…",936,225,19,TEAL,true);
        ui.wrapped("每台车都使用同一条路线和相同的起跑条件。",936,283,274,16,SOFT,27);
        ui.smallButton(936,642,274,37,comparison.complete()?"返回对比选择":"停止本次对比",game::closeAssemblyComparison);
    }

    private void renderReading(HudRenderer ui,int x,int y,String title,MiniSetup setup,
                               MiniAssemblyComparison.Reading reading,Color color) {
        String state=switch(reading.outcome()) {
            case RUNNING -> "试跑中";
            case FINISHED -> "完成";
            case DERAILED -> "脱轨 · "+reading.stoppedAt().label;
            case LIMIT -> "达到 "+MiniAssemblyComparison.MAX_SIMULATED_SECONDS+" s 上限";
        };
        ui.text(title,x,y,19,color,true);
        ui.text(assemblyName(setup),x,y+34,15,SOFT);
        ui.text(state+" · "+PracticeSession.format(reading.seconds()),x,y+81,17,LIGHT,true);
        ui.text(String.format(Locale.ROOT,"进度 %.1f%% · 峰值 %.2f 格/秒",Math.min(1,reading.routeProgress())*100,reading.peakSpeed()),
            x,y+120,16,SOFT);
        if(!reading.laps().isEmpty()) ui.text("完整圈段 "+String.join(" / ",reading.laps().stream().map(PracticeSession::format).toList()),
            x,y+158,15,SOFT);
        if(reading.stoppedPosition()!=null) ui.text(String.format(Locale.ROOT,"脱轨位置 %.1f, %.1f 格",
            reading.stoppedPosition().x(),reading.stoppedPosition().y()),x,y+193,15,SOFT);
    }

    private static String assemblyName(MiniSetup setup) {
        return setup.motor().label+" · "+setup.gear().label+" · "+setup.tire().label+" · "+setup.roller().label;
    }
    private void renderPending(HudRenderer ui,Mini4wdGame game,MiniSetup pending) {
        var current=game.setup();
        var base=game.pendingAssemblyBase();
        ui.text("核对待存装配",76,225,27,LIGHT,true);
        ui.text("安装位",76,267,15,SOFT);
        ui.text("原基准",238,267,15,SOFT);
        ui.text("当前车辆",446,267,15,LIGHT);
        ui.text("待存装配",654,267,15,AMBER);
        int line=0;
        for(var slot:Slot.values()) {
            String before=base.has(slot)?MiniAssembly.label(base,slot):"空位";
            String installed=current.has(slot)?MiniAssembly.label(current,slot):"空位";
            String after=pending.has(slot)?MiniAssembly.label(pending,slot):"空位";
            if(before.equals(after) && before.equals(installed)) continue;
            int y=307+line++*31;
            ui.text(slot.label,76,y,16,LIGHT);
            ui.text(before,238,y,16,SOFT);
            ui.text(installed,446,y,16,LIGHT);
            ui.text(after,654,y,16,AMBER);
        }
        ui.text("当前车辆 "+(current.ready()?"组装完整":"尚未组装完成"),936,223,17,TEAL,true);
        ui.text("待存车辆 "+(pending.ready()?"组装完整":"尚未组装完成"),936,263,17,AMBER);
        String state=game.pendingAssemblyRecoverable()?"恢复副本已保存，可关闭后重开核对。":"本份候选保留在当前窗口。";
        if(pending.equals(current)) state="同一套装配已经保存，可以确认清理恢复副本。";
        else if(!current.equals(base)) state="当前车辆与本次改动的原基准不同，均保留原内容。";
        ui.wrapped(state,936,315,274,15,SOFT,23);
        ui.smallButton(936,444,274,42,pending.equals(current)?"确认已保存并清理副本":"重试保存同份装配",
            ()->game.retryAssemblySave(pending));
        ui.smallButton(936,504,274,42,"取消待存改动",()->ui.confirm("取消本次待存装配？",
            "当前车辆与装配撤销记录保留。",()->game.discardAssemblyChange(pending)));
    }
    private void renderBlueprints(HudRenderer ui,Mini4wdGame game) {
        ui.text("整车装配蓝图",76,215,24,LIGHT,true);
        for(int i=0;i<com.f1car.mini4wd.MiniBlueprintStore.SLOTS;i++) {
            int slot=i;var entry=game.blueprints().get(i);int y=235+i*70;
            ui.smallButton(76,y,774,58,(blueprintSlot==i?"● ":"")+(i+1)+" · "+(entry==null?"空蓝图位":entry.name()),()->blueprintSlot=slot);
        }
        var selected=game.blueprints().get(blueprintSlot);
        ui.text("蓝图位 "+(blueprintSlot+1),936,217,22,TEAL,true);
        ui.wrapped(selected==null?"尚未保存。完成当前车辆组装后，可保存到这个位置。":selected.name(),936,264,274,18,LIGHT,28);
        if(selected!=null) {
            var setup=selected.setup();
            ui.text(setup.chassis().label+" · "+setup.mount().label,936,350,15,SOFT);
            ui.text(setup.motor().label+" · "+setup.gear().label,936,380,15,SOFT);
            ui.text(setup.wheel().label+" · "+setup.tire().label,936,410,15,SOFT);
        }
        ui.smallButton(936,445,274,40,selected==null?"保存当前整车":"覆盖为当前整车",()->{
            int slot = blueprintSlot;
            var assembly = game.setup();
            String initial = "整车方案 " + (slot + 1);
            ui.editTextChecked("整车蓝图名称", selected == null ? initial : selected.name(), initial, 40, name -> {
                if (name.isBlank()) throw new IllegalArgumentException();
                if (!assembly.equals(game.setup()) || !Objects.equals(selected, game.blueprints().get(slot))) {
                    game.message("装配或蓝图位已变化，请重新选择后保存。");
                    return false;
                }
                return game.saveBlueprint(slot, name);
            });
        });
        ui.smallButton(936,497,274,40,"载入所选蓝图",()->game.loadBlueprint(blueprintSlot));
        ui.smallButton(936,549,274,37,"撤销装配／蓝图载入",game::undoAssembly);
        ui.smallButton(936,642,274,37,"返回组装台",()->blueprints=false);
        ui.smallButton(76,666,184,32,"重新读取蓝图",game.blueprints()::reload);
        ui.wrapped(game.blueprints().warning().isEmpty()?"蓝图只保存完整装配；载入后可继续改装，轨道与成绩独立保存。":game.blueprints().warning(),936,602,274,13,SOFT,18);
    }
}
