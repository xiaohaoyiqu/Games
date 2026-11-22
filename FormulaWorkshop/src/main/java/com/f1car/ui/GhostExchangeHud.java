package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.GhostStore;
import java.awt.Color;
import java.nio.file.Path;
import java.util.List;

final class GhostExchangeHud {
    private static final Color LIGHT=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),ORANGE=new Color(255,169,114);
    private int page,pendingIndex;
    void render(HudRenderer h,FormulaGame app) {
        var trial=app.timeTrial;if(trial==null) return;
        h.panel(38,32,1204,732,new Color(10,29,39,249));
        h.text("好友幽灵／离线交换",64,87,30,LIGHT,true);
        h.text("本圈参照："+shorten(trial.referenceLabel(),16)+" · 下圈选择："+shorten(trial.selectedLabel(),16),64,126,17,MINT);
        h.smallButton(1034,60,180,40,"返回暂停菜单",app::backFromGhosts);
        var displayedBest=trial.pendingBest();
        if(displayedBest!=null)
            h.smallButton(744,60,274,40,"重试保存 "+PracticeSession.format(displayedBest.duration()),
                    ()->app.retryTimeTrialBest(trial,displayedBest));
        h.text("赛道："+app.circuit.name+" · 导入数据仅作参照；个人最佳与奖励由本机实际驾驶产生。",64,163,15,SOFT);
        h.smallButton(64,187,240,38,"导入好友幽灵文件",()->h.prompt("幽灵文件完整路径", "",512,value->{try { app.importGhost(Path.of(value.strip())); }catch(RuntimeException error) { app.notifyUser("文件路径无效。"); }}));
        h.smallButton(320,187,240,38,"选择个人最佳参照",()->app.selectGhostReference(null));
        h.smallButton(576,187,300,38,"导出我的个人最佳",()->export(h,app,false));
        h.smallButton(892,187,322,38,"导出最近有效回应圈",()->export(h,app,true));
        var entries=app.ghostExchange.entries();int pages=Math.max(1,(entries.size()+3)/4);page=Math.max(0,Math.min(page,pages-1));
        for(int i=page*4;i<Math.min(entries.size(),page*4+4);i++) {
            var entry=entries.get(i);int y=246+(i-page*4)*100;String mismatch=GhostChallenge.mismatch(entry.conditions(),trial.conditions(),entry.routeGeometry(),trial.routeGeometry());
            h.panel(64,y,1150,90,new Color(24,49,60));
            h.text((entry.id().equals(trial.selectedSource())?"● ":"")+shorten(entry.author().isBlank()?"未署名好友":entry.author(),24)+" · "+PracticeSession.format(entry.duration()),82,y+29,19,LIGHT,true);
            h.text(shorten(entry.trackName(),32)+" · "+(mismatch.isEmpty()?"条件匹配":mismatch)+(entry.replyTo()==null?"":" · 回应文件"),82,y+58,14,mismatch.isEmpty()?MINT:ORANGE);
            h.text("文件 "+entry.id().substring(0,12),82,y+79,12,SOFT);
            h.smallButton(906,y+16,176,34,mismatch.isEmpty()?"选择为下圈参照":"条件不匹配",()->app.selectGhostReference(entry.id()));
            h.smallButton(1094,y+16,102,34,"移除",()->app.removeGhost(entry.id()));
        }
        if(entries.isEmpty()) h.text("幽灵库为空。导出你的最佳圈给好友，或先导入好友分享的文件。",82,291,20,SOFT);
        h.smallButton(64,668,120,34,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages+" · "+entries.size()+" / 12 个",202,692,15,SOFT);
        h.smallButton(366,668,120,34,"下一页",()->page=Math.min(pages-1,page+1));
        var pending=app.pendingTimeTrialBests();
        if(!pending.isEmpty()) {
            pendingIndex=Math.min(pendingIndex,pending.size()-1);
            var selected=pending.get(pendingIndex);
            h.smallButton(515,668,430,34,"待保存 "+(pendingIndex+1)+"/"+pending.size()+" · "
                    +shorten(selected.trackName(),12)+" · "+PracticeSession.format(selected.lap().duration()),
                    ()->reviewPending(h,app,selected));
            if(pending.size()>1) h.smallButton(960,668,254,34,"下一份待保存成绩",()->pendingIndex=(pendingIndex+1)%pending.size());
        }
        h.text(app.drivingHint("更换参照在下一圈生效；返回驾驶后按 R 重开，可立即使用新选择。"),64,738,14,SOFT);
    }
    private void reviewPending(HudRenderer h,FormulaGame app,GhostStore.PendingBest pending) {
        var conditions=pending.conditions();
        h.review("重试保存原最佳圈",List.of(
                pending.trackName()+" · "+PracticeSession.format(pending.lap().duration()),
                "赛道 "+conditions.trackId()+" · 修订 "+conditions.geometry().substring(0,12),
                "物理 "+conditions.physics()+" · "+Tuning.performanceDescription(conditions.performance()),
                "驾驶控制 "+conditions.controls(),
                "保存时会核对磁盘上的同条件最佳圈。"),
                "重试保存原圈",()->app.retryTimeTrialBest(pending));
    }
    private void export(HudRenderer h,FormulaGame app,boolean reply) {
        Path suggestion=app.store.directory().resolve(reply?"ghost-export-reply.json":"ghost-export-best.json");
        h.editText("你的署名","本机车手","本机车手",40,author->h.promptReviewedExportPath("导出文件完整路径",suggestion,
                destination->app.exportReviewedGhost(destination,author,reply)));
    }
    private static String shorten(String value,int limit) { return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit-1))+"…"; }
}
