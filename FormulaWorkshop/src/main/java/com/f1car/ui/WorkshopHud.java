package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.Locale;

final class WorkshopHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),AMBER=new Color(255,190,114),PANEL=new Color(10,29,39,245);
    private int history;
    void board(HudRenderer h,FormulaGame app) {
        h.panel(28,24,1224,752,PANEL);h.text("PADDOCK / EXPERIMENTS",60,65,14,MINT,true);h.text("围场委托与实验车间",60,108,31,INK,true);
        h.smallButton(1050,55,165,41,"返回主菜单",app::mainMenu);h.smallButton(660,119,555,38,"赛道："+app.circuit.name,app::openTracks);
        h.smallButton(60,128,260,32,app.workshopStore.responses()?"角色回应：开启":"角色回应：关闭",app::workshopResponseToggle);h.text(app.drivingHint("委托用原厂 · R 重试"),344,150,15,SOFT);
        var kinds=WorkshopSession.Kind.values();
        for(int i=0;i<kinds.length;i++) {
            var kind=kinds[i];int x=60+i*390;h.panel(x,190,370,325,new Color(25,50,61));h.text(kind.id+" · "+kind.title,x+18,226,23,INK,true);
            h.text(app.workshopStore.responses()?kind.engineer:kind==WorkshopSession.Kind.PARK?"综合训练场":"当前赛道",x+18,257,15,MINT);h.wrapped(kind.brief,x+18,293,330,17,INK,28);
            var best=app.workshopBest(kind);h.text(best==null?"当前条件尚无完成记录":"最佳 "+best.grade().title+" · "+value(best),x+18,392,15,AMBER);
            h.smallButton(x+18,432,334,43,"接受委托",()->app.startWorkshop(kind));
            if(kind==WorkshopSession.Kind.CUP) h.smallButton(x+18,483,334,25,"原版杯架自由挑战（车库配置）",()->app.start(FormulaGame.Mode.CUP,null));
        }
        var report=app.workshopReport();h.text("委托报告",60,548,20,INK,true);
        if(report==null) h.wrapped("完成或未通过后，这里会保留本次观测。任务只影响独立实验报告与履历中的实验次数，不改变正常比赛圈速、性能或奖牌。",60,588,1090,18,SOFT,30);
        else {
            h.text(report.kind().title+" · "+report.conditions().trackName()+" · "+report.grade().title,60,582,19,MINT,true);h.wrapped(report.observation(),60,615,1100,17,INK,27);
            if(app.workshopStore.responses()) h.wrapped(report.kind().engineer+"："+report.response(app.store.profile.humor),60,662,1100,16,SOFT,25);
            if(!report.conditions().physics().equals(app.vehicleSpec.physicsVersion)) h.text("历史车辆版本的报告，请在当前版本重新体验。",60,718,14,AMBER);
        }
        var reports=app.workshopStore.recent();
        if(!reports.isEmpty()) { history=Math.floorMod(history,reports.size());h.smallButton(60,740,230,29,"浏览报告 "+(history+1)+"/"+reports.size(),()->{app.viewWorkshopReport(reports.get(history).id());history=(history+1)%reports.size();}); }
        saveButton(h,app,310,740,600,29);
        h.text("角色回应只在任务板与结束页出现，可关闭。",925,760,12,SOFT);
    }
    void driving(HudRenderer h,FormulaGame app) {
        var trial=app.workshop();h.panel(25,101,570,185,PANEL);h.text(trial.kind().id+" · "+trial.kind().title,46,134,21,MINT,true);h.wrapped(trial.kind().brief,46,168,525,16,INK,25);h.wrapped(trial.feedback(),46,240,525,14,AMBER,22);
    }
    void result(HudRenderer h,FormulaGame app) {
        var report=app.workshopReport();h.text(report.kind().title+" · "+report.conditions().trackName(),320,341,20,INK,true);h.wrapped(report.observation(),320,377,630,18,MINT,29);
        if(app.workshopStore.responses()) { h.text(report.kind().engineer,320,440,16,INK,true);h.wrapped(report.response(app.store.profile.humor),320,472,630,16,SOFT,25); }
        h.smallButton(760,524,198,31,app.workshopStore.responses()?"关闭角色回应":"显示角色回应",app::workshopResponseToggle);
        h.smallButton(316,570,198,49,"重试委托  ENTER",app::retry);h.smallButton(538,570,198,49,"返回任务板",app::openWorkshop);h.smallButton(760,570,198,49,"正常比赛菜单",app::mainMenu);
        saveButton(h,app,316,626,642,28);
    }
    private void saveButton(HudRenderer h,FormulaGame app,int x,int y,int width,int height) {
        if(app.workshopStore.pending() || app.journal.pending()) {
            String label=app.workshopStore.conflictReviewAvailable()?"核对两份实验报告":"报告／履历待保存 · 重试";
            h.smallButton(x,y,width,height,label,app::retryWorkshopSave);
        }
    }
    private String value(WorkshopReport report) { return switch(report.kind()) { case PARK->String.format(Locale.ROOT,"中心偏差 %.2f m",report.value());case SOFT->String.format(Locale.ROOT,"强减速 %.2f s",report.value());case CUP->String.format(Locale.ROOT,"洒水 %.1f%%",report.value()); }; }
}
