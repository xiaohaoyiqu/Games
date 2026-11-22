package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.input.KeyboardSettings.Command;
import com.f1car.model.GhostLap;
import com.f1car.model.PracticeSession;
import com.f1car.model.TimeTrialSession;
import java.awt.Color;
import java.util.List;
import java.util.Locale;

final class TimeTrialHud {
    private static final Color LIGHT=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),ORANGE=new Color(255,169,114);
    private record SectorText(String time,String delta,Color color) {}
    private final SectorText[] sectorText=new SectorText[3];
    private List<Double> displayedTimes;
    private boolean displayedPrevious;

    void render(HudRenderer ui,FormulaGame app) {
        var trial=app.timeTrial;if(trial==null) return;
        ui.panel(25,101,376,330,new Color(10,29,39,237));
        ui.text("TIME ATTACK / 三段计时",45,135,16,MINT,true);
        ui.text("最佳  "+PracticeSession.format(trial.best()==null ? -1 : trial.best().duration()),45,177,24,LIGHT,true);
        ui.text(!trial.started() ? "驶过起点线开始计时" : trial.valid() ? "当前圈有效" : "当前圈无效，下一圈可重新挑战",45,209,15,trial.valid()?SOFT:ORANGE);
        var currentTimes=trial.sectorTimes();
        boolean previous=currentTimes.isEmpty() && trial.last()!=null;
        ui.text(previous ? "上圈分段" : "本圈分段",45,239,13,SOFT);
        ui.text("本段用时",113,239,13,SOFT);ui.text("累计差",268,239,13,SOFT);
        prepareSectorText(trial,previous?trial.last().sectors():currentTimes,previous?trial.last():null);
        for(int i=0;i<3;i++) {
            int y=271+i*31;
            var row=sectorText[i];
            ui.text("S"+(i+1),45,y,17,MINT,true);
            ui.text(row.time(),113,y,17,LIGHT);
            ui.text(row.delta(),268,y,17,row.color());
        }
        ui.smallButton(45,354,216,35,"幽灵 "+(app.store.profile.ghostEnabled?"开":"关")+"  "+app.keyLabel(Command.GHOST),app::toggleGhost);
        ui.smallButton(273,354,108,35,"透明度 "+Math.round(app.store.profile.ghostOpacity*100)+"%",app::cycleGhostOpacity);
        String label=previous?trial.lastReferenceLabel():trial.referenceLabel();
        ui.text((previous?"上圈参照：":"本圈参照：")+HudRenderer.shorten(label,18),45,416,13,SOFT);
        ui.panel(26,584,376,128,new Color(10,29,39,237));
        ui.text("本圈  "+PracticeSession.format(trial.elapsed()),45,619,21,LIGHT,true);
        ui.text("上圈  "+PracticeSession.format(trial.last()==null?-1:trial.last().duration()),45,653,18,MINT);
        var pending=trial.pendingBest();
        if(trial.last()!=null) ui.text(lastLapStatus(trial,pending),45,684,14,trial.last().valid()?SOFT:ORANGE);
        else ui.text(app.drivingHint("R 重开本次挑战 · 暂停会冻结计时"),45,684,14,SOFT);
        ui.smallButton(45,438,336,36,"查看圈速与操作对比",app::openTelemetry);
        ui.smallButton(45,484,336,36,pending==null?"好友幽灵／导入导出":"好友幽灵／最佳圈待保存",app::openGhosts);
    }

    private static String lastLapStatus(TimeTrialSession trial,GhostLap pending) {
        if(!trial.last().valid()) return "无效圈 · 本圈不计入最佳";
        if(trial.last().saved()) return "已保存新的最佳圈与幽灵";
        if(pending!=null) return pending==trial.completedLap()?"有效圈 · 本圈最佳与幽灵待保存":"有效圈 · 另有最佳圈待保存";
        return trial.best()==null?"有效圈":"有效圈 · 最佳纪录保留";
    }

    private void prepareSectorText(TimeTrialSession trial,List<Double> times,TimeTrialSession.Result previous) {
        boolean finished=previous!=null;
        if (displayedTimes==times && displayedPrevious==finished) return;
        for (int i=0;i<sectorText.length;i++) {
            if(i>=times.size()) {
                sectorText[i]=new SectorText(finished?"未记录":"等待","—",SOFT);
                continue;
            }
            double delta=finished?previous.deltas().get(i):trial.sectorDelta(i);
            boolean known=Double.isFinite(delta);
            sectorText[i]=new SectorText(String.format(Locale.ROOT,"%.3f s",times.get(i)),
                    known?CircuitSectionPaceHud.deltaLabel(delta):"—",known?CircuitSectionPaceHud.color(delta):SOFT);
        }
        displayedTimes=times;
        displayedPrevious=finished;
    }
}
