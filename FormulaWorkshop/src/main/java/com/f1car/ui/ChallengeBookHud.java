package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.time.*;
import java.time.format.DateTimeFormatter;

final class ChallengeBookHud {
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132),PANEL=new Color(10,29,39,245);
    private int historyPage;
    void book(HudRenderer h,FormulaGame app) {
        h.panel(38,32,1204,732,PANEL);h.text("LOCAL CHALLENGES",70,74,14,MINT,true);h.text("本地挑战册",70,119,34,INK,true);
        h.smallButton(880,68,160,42,"驾驶履历与外观",app::openJournal);h.smallButton(1060,68,146,42,"返回主菜单",app::mainMenu);
        h.text("固定原厂性能；沿用你的车队配色。奖牌目标不随其他玩家成绩改变。",72,157,16,MUTED);
        h.text("暂定规则，待试驾标定 · 当前赛道："+app.circuit.name,72,195,16,GOLD);
        for(int i=0;i<ChallengeBook.Kind.values().length;i++) {
            var kind=ChallengeBook.Kind.values()[i];var entry=new ChallengeBook.Entry(app.circuit.id,kind);int y=224+i*96;
            h.panel(70,y,1136,86,new Color(24,49,60));h.text(kind.id+"  "+kind.title,90,y+30,22,INK,true);
            var best=app.journal.state().medals().get(entry.key(app.vehicleSpec.physicsVersion));
            h.text(best==null?"尚未获得奖牌":best.medal().title+" · "+PracticeSession.format(best.elapsed()),90,y+61,16,best==null?MUTED:GOLD);
            h.text("铜："+kind.bronze,355,y+22,14,MUTED);h.text("银："+kind.silver,355,y+47,14,INK);h.text("金："+kind.gold,355,y+72,14,GOLD);
            h.smallButton(1060,y+22,124,42,"选择地图",()->h.navigate(()->app.prepareBook(kind)));
        }
        h.text("奖牌解锁纪念图案；快速比赛、普通课程、原有五种配色始终可用。",72,737,15,MUTED);
    }
    void driving(HudRenderer h,FormulaGame app) {
        var attempt=app.bookAttempt();h.panel(25,445,465,130,PANEL);
        h.text(attempt.entry().kind().id+" / "+attempt.entry().kind().title+" · 原厂",45,472,18,GOLD,true);
        if(attempt.entry().kind()==ChallengeBook.Kind.CLEAN_LAPS) {
            h.text("连续 "+attempt.consecutiveLaps()+" 圈 · 当前 "+attempt.medal().title,45,503,17,INK);
            h.text("三圈极差 ≤ max(0.75 秒, 均值×2.5%) 得金牌",45,530,13,MUTED);
            h.smallButton(45,543,420,28,"结束并保存当前奖牌",app::claimBookMedal);
        } else {
            h.wrapped("金牌目标："+attempt.entry().kind().gold,45,507,420,16,INK,23);
            h.text(app.drivingHint("R 重跑本次挑战；成绩按赛道和规则版本保存"),45,557,13,MUTED);
        }
    }
    void result(HudRenderer h,FormulaGame app) {
        var attempt=app.bookAttempt();h.text(attempt.entry().kind().title,320,339,24,INK,true);
        h.text(attempt.medal().title,320,394,38,GOLD,true);h.text("本次用时 "+PracticeSession.format(attempt.elapsed()),320,438,21,INK);
        var best=app.journal.state().medals().get(attempt.entry().key(app.vehicleSpec.physicsVersion));
        h.text("已获最高："+(best==null?"未获得":best.medal().title),320,479,18,MINT);
        h.wrapped(app.journal.pending()?"奖励暂存在内存，驾驶履历中可重试保存。":"本地记录已保存；较低奖牌不会覆盖更高奖牌。",320,526,635,15,MUTED,23);
    }
    void journal(HudRenderer h,FormulaGame app) {
        var state=app.journal.state();h.panel(38,32,1204,732,PANEL);h.text("DRIVER JOURNAL",70,74,14,MINT,true);h.text("驾驶履历与外观",70,119,34,INK,true);
        h.smallButton(880,68,160,42,"挑战册",app::openBook);h.smallButton(1060,68,146,42,"返回主菜单",app::mainMenu);
        int laps=state.tracks().values().stream().mapToInt(DriverJournal.TrackStats::laps).sum(),courses=state.tracks().values().stream().mapToInt(DriverJournal.TrackStats::courses).sum();
        int races=state.tracks().values().stream().mapToInt(DriverJournal.TrackStats::raceFinishes).sum(),wins=state.tracks().values().stream().mapToInt(DriverJournal.TrackStats::raceWins).sum();
        int experiments=state.tracks().values().stream().mapToInt(DriverJournal.TrackStats::experiments).sum();
        h.text("有效圈 "+laps+"   课程 "+courses+"   实验 "+experiments+"   比赛完赛 "+races+" / 获胜 "+wins+"   获奖项目 "+state.medals().size(),72,173,19,MINT,true);
        h.panel(70,207,540,279,new Color(24,49,60));h.text("车号与纪念图案",90,241,23,INK,true);
        var look=state.look();h.text("车号  "+look.number(),90,280,24,GOLD,true);
        h.smallButton(273,252,56,35,"−",()->app.changeLook(Math.floorMod(look.number()-2,99)+1,look.pattern()));
        h.smallButton(343,252,56,35,"+",()->app.changeLook(look.number()%99+1,look.pattern()));
        for(int i=0;i<DriverJournal.Pattern.values().length;i++) {
            var pattern=DriverJournal.Pattern.values()[i];int x=90+i%2*254,y=307+i/2*66;
            h.smallButton(x,y,234,36,(look.pattern()==pattern?"✓ ":"")+pattern.title+(state.unlocked(pattern)?"":"（未解锁）"),()->app.changeLook(look.number(),pattern));
            h.text(pattern.requirement,x,y+54,12,MUTED);
        }
        h.text("只改变本机车号与图案，不改变车辆性能。",90,466,15,MUTED);
        h.panel(70,506,540,219,new Color(24,49,60));h.text("奖杯架",90,541,23,INK,true);
        if(state.trophies().isEmpty()) h.wrapped("赢得单站周末或巡回杯后，纪念奖杯会保存在这里。",90,585,490,17,MUTED,26);
        else for(int i=0;i<Math.min(3,state.trophies().size());i++) h.text(state.trophies().get(state.trophies().size()-1-i).title(),90,579+i*35,17,GOLD);
        h.panel(636,207,570,518,new Color(24,49,60));h.text("最近记录",658,241,23,INK,true);
        int pages=Math.max(1,(state.recent().size()+5)/6);historyPage=Math.min(historyPage,pages-1);
        if(state.recent().isEmpty()) h.wrapped("完成课程、短局挑战、实验或比赛后，这里会记录实际结果；旧版纪录保留在原有存档。",658,291,510,17,MUTED,29);
        for(int i=0;i<6 && historyPage*6+i<state.recent().size();i++) {
            var row=state.recent().get(state.recent().size()-1-historyPage*6-i);int y=282+i*57;
            h.text(shorten(row.outcome(),27),658,y,17,row.success()?INK:MUTED);
            String date=DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(row.timestamp()).atZone(ZoneId.systemDefault()));
            h.text(shorten(row.trackName(),14)+" · "+date+(row.elapsed()>0?" · "+PracticeSession.format(row.elapsed()):""),658,y+24,12,MUTED);
        }
        h.smallButton(658,661,110,35,"较新",()->historyPage=Math.max(0,historyPage-1));h.text((historyPage+1)+" / "+pages,907,685,16,MUTED);
        h.smallButton(1074,661,110,35,"较早",()->historyPage=Math.min(pages-1,historyPage+1));
        if(app.journal.pending()) h.smallButton(70,735,1136,26,
                app.journal.conflictReviewAvailable()?"核对两份驾驶履历":"履历和奖励尚未保存 · 点击重试",app::retryJournalSave);
    }
    private String shorten(String text,int size) { return text.length()<=size?text:text.substring(0,size-1)+"…"; }
}
