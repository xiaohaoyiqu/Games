package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.EventRaceClock;
import com.f1car.model.EventRaceTiming;
import com.f1car.model.EventSeries;
import com.f1car.model.PracticeSession;
import java.awt.Color;
import java.util.List;
import java.util.Locale;

/** Event checkpoints, rules and results are presented alongside the driving modes. */
final class EventHud {
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),ORANGE=new Color(255,169,114);
    private static final Color PANEL=new Color(10,29,39,245);
    private int historyIndex=-1,historySize;
    private String historyEvent="";
    private int tab;
    private final EventCalendarHud calendarHud=new EventCalendarHud();
    private final RivalHud rivalHud=new RivalHud();
    private final EventDebriefHud debriefHud=new EventDebriefHud();
    private final EventRecoveryHud recoveryHud=new EventRecoveryHud();
    private final EventHistoryHud historyHud=new EventHistoryHud();
    private record TimingText(boolean finished,double seconds,String label) {}
    private final TimingText[] raceTimingText=new TimingText[EventSeries.DRIVERS];
    void synchronizeScreen(FormulaGame app) {
        if(app.screen!=FormulaGame.Screen.EVENTS || tab!=2) rivalHud.clearView();
        if(app.screen!=FormulaGame.Screen.EVENTS && !app.eventReplayViewing()) {
            debriefHud.close(app);
            recoveryHud.clear();
            if(tab==3) {historyHud.clear(app);tab=0;}
        }
    }
    boolean openRecovery(FormulaGame app) {
        if(app.online() || app.screen!=FormulaGame.Screen.EVENTS && app.screen!=FormulaGame.Screen.RESULTS) return false;
        if(app.screen==FormulaGame.Screen.RESULTS) app.openEvents();
        debriefHud.close(app);
        historyHud.closeRound(app);
        recoveryHud.open(app,app.pendingEventResult()!=null);
        return true;
    }
    boolean openReview(FormulaGame app,EventSeries.State expected,int round) {
        var actual=app.activeEvent();if(app.online() || app.screen!=FormulaGame.Screen.EVENTS && app.screen!=FormulaGame.Screen.RESULTS || expected==null || actual==null || !expected.equals(actual.state()) || round<0 || round>=expected.results().size()) {app.notifyUser("赛事来源已变化，或本站尚无结果，请重新选择。");return false;}
        if(app.screen==FormulaGame.Screen.RESULTS) app.openEvents();
        historyHud.closeRound(app);
        recoveryHud.clear();
        debriefHud.open(app,expected,round);
        return true;
    }
    void render(HudRenderer h,FormulaGame app) {
        if(recoveryHud.showing()) {recoveryHud.render(h,app);return;}
        if(debriefHud.showing()) {debriefHud.render(h,app);return;}
        if(tab==3) {historyHud.render(h,app,()->{historyHud.clear(app);tab=0;});return;}
        h.panel(38,32,1204,732,PANEL);
        h.text("RACE EVENTS",70,74,14,MINT,true);h.text("赛事中心",70,119,34,INK,true);
        h.text("练习可以跳过 · 每车一次计时圈 · 排位决定正赛发车 · 保存阶段进度",72,151,16,MUTED);
        h.smallButton(1060,68,146,42,"返回模式选择",()->h.navigate(app::openStartMenu));
        h.smallButton(70,176,356,42,"新建单站周末",()->newEvent(h,app,EventSeries.Kind.WEEKEND));
        h.smallButton(450,176,356,42,"新建三站巡回杯",()->newEvent(h,app,EventSeries.Kind.TOUR));
        h.smallButton(832,176,180,42,tab==1?"返回赛事进度":"自选赛历",()->{if(tab==1) tab=0;else { calendarHud.begin(app);tab=1; }});
        h.smallButton(1026,176,180,42,tab==2?"返回赛事进度":app.rivals.pending()?"交手记录 · 待保存":"交手记录",()->tab=tab==2?0:2);
        h.smallButton(397,82,221,38,"已完成赛事档案",()->tab=3);
        h.smallButton(650,82,386,38,app.eventRecoveryAttention() || app.eventSavePending()?"赛事结果恢复 · 待核对":"查看赛事结果恢复状态",()->openRecovery(app));
        if(tab==1) { calendarHud.render(h,app,()->tab=0);return; }
        if(tab==2) { rivalHud.render(h,app);return; }
        var event=app.activeEvent();
        if(event==null) {
            h.text("选择一个赛事开始",70,300,28,MINT,true);
            h.wrapped("单站周末和三站杯都可选择首站。三站杯的后两站按内置赛道顺序补齐，跳过首站。自选赛历可编排一至八站，也可重复地图。创建时固定车辆配置、难度和圈数。所有赛事共用一个进度槽。",70,351,1030,19,INK,32);
            h.wrapped("当前配置："+app.store.profile.tuning.performanceKey()+"，"+app.difficulty()+"难度，正赛 "+app.store.profile.raceLaps+" 圈。快速比赛与练习仍可直接进入。",70,460,1020,17,MUTED,30);
            if(!app.events.warning.isEmpty()) h.wrapped(app.events.warning,70,590,1030,17,ORANGE,28);
            return;
        }
        var state=event.state();
        if(!app.eventSavePending() && !app.rivals.warning.isEmpty()) h.text("交手记录待处理：请进入交手记录页重试保存。赛事阶段独立保留。",70,674,15,ORANGE);
        h.text(event.title()+" · 第 "+(state.round()+1)+" / "+state.calendar().size()+" 站 · "+event.phaseName(),70,273,26,MINT,true);
        h.text("赛道："+trackName(app,event)+"  |  正赛 "+state.laps()+" 圈  |  "+new String[]{"轻松","标准","挑战"}[state.difficulty()]+"难度  |  配置 "+state.tuning().performanceKey(),72,310,17,INK);
        h.panel(70,333,540,312,new Color(24,49,60));
        h.text("累计积分",90,368,22,INK,true);
        var standings=event.standings();
        for(int i=0;i<standings.size();i++) {
            var row=standings.get(i);int y=411+i*43;
            h.text("P"+event.placing(row.driver())+"  "+EventSeries.driverName(row.driver()),90,y,18,row.driver()==0?MINT:INK);
            h.text(row.points()+" 分",467,y,19,INK);
        }
        h.text("完赛积分 10 / 6 / 4 / 2；未完赛 0 分",90,590,14,MUTED);
        h.text("同分依次比正赛、排位名次次数；仍相同则并列。",90,620,13,MUTED);
        h.panel(636,333,570,312,new Color(24,49,60));
        if(state.phase()==EventSeries.Phase.RACE) {
            h.text("正赛发车顺序",658,368,22,INK,true);qualifyingRows(h,state.qualifying(),658,411);
        } else if(!state.results().isEmpty()) {
            if(!historyEvent.equals(state.eventId()) || historySize!=state.results().size()) {
                historyEvent=state.eventId();
                historySize=state.results().size();
                historyIndex=historySize-1;
            }
            historyIndex=Math.max(0,Math.min(historyIndex,historySize-1));
            var result=state.results().get(historyIndex);h.text("第 "+(historyIndex+1)+" 站正赛",658,368,22,INK,true);
            if(historySize>1) {
                h.smallButton(1034,343,66,34,"上一站",()->historyIndex=Math.max(0,historyIndex-1));
                h.smallButton(1114,343,66,34,"下一站",()->historyIndex=Math.min(historySize-1,historyIndex+1));
            }
            raceRows(h,result,658,411);
        } else {
            h.text("排位说明",658,368,22,INK,true);
            h.wrapped("先驶过起点开始计时，再完成一圈。无效圈、对手复位或 240 秒内未完成记为无成绩。同为无成绩时按固定车手序号排列。",658,412,513,17,MUTED,29);
        }
        if(!state.results().isEmpty()) {int index=state.phase()==EventSeries.Phase.RACE?state.results().size()-1:Math.max(0,Math.min(historyIndex,state.results().size()-1));h.smallButton(658,596,522,32,state.phase()==EventSeries.Phase.RACE?"查看上一站回顾与罚时":"查看所选站回顾、罚时与下一步",()->openReview(app,state,index));}
        if(app.eventSavePending()) {
            h.text(app.pendingEventRecoverable()?"原结果已写恢复副本，尚未发布；请明确重试保存。":"结果尚待保存；未确认恢复副本，退出可能丢失，请处理恢复页。",70,674,17,ORANGE,true);
            h.smallButton(636,697,570,42,"重试保存赛事成绩",app::saveEventResult);
        } else if(app.eventRecoveryAttention()) {
            h.text("请先核对赛事恢复副本；主档结果保持，不自动恢复或推进。",70,674,16,ORANGE);
            h.smallButton(636,697,570,42,"核对赛事结果恢复",()->openRecovery(app));
        } else if(!app.eventCompatible(event)) {
            h.text("地图或物理版本已改变，旧进度保留；请新建赛事。",70,699,17,ORANGE,true);
        } else {
            if(state.phase()==EventSeries.Phase.QUALIFYING) h.smallButton(70,697,540,42,"自由练习（可跳过）",()->h.navigate(()->app.prepareEventStage(true)));
            String action=switch(state.phase()) { case QUALIFYING->"开始单圈排位";case RACE->"按排位开始正赛";case REVIEW->"进入下一站";case COMPLETE->"赛事已完成 · 可查看结果或新建"; };
            if(state.phase()!=EventSeries.Phase.COMPLETE) h.smallButton(636,697,570,42,action,()->h.navigate(()->app.prepareEventStage(false)));
            else h.text(action,70,709,21,MINT,true);
        }
    }
    private String trackName(FormulaGame app,EventSeries event) {
        try { return app.circuitById(event.currentRound().trackId()).name; } catch(IllegalArgumentException error) { return event.currentRound().trackId(); }
    }
    private void newEvent(HudRenderer h,FormulaGame app,EventSeries.Kind kind) {
        confirmReplacement(h,app,()->{tab=0;app.prepareNewEvent(kind);});
    }
    static void confirmReplacement(HudRenderer h,FormulaGame app,Runnable prepare) {
        if(app.eventSavePending()) { app.notifyUser("请先保存待结算的赛事成绩。");return; }
        var reviewed=app.events.watchRevision();
        Runnable proceed=()->h.navigate(()->{
            if(app.screen==FormulaGame.Screen.EVENTS && app.reviewedEventUnchanged(reviewed)) prepare.run();
        });
        if(app.activeEvent()==null) proceed.run();
        else h.prompt("输入 NEW 替换当前赛事；交手记录与履历保留","",value->{if(value.strip().equalsIgnoreCase("NEW")) proceed.run();});
    }

    void raceClock(HudRenderer hud,EventRaceClock clock) {
        if(clock==null || clock.readyToSettle()) return;
        hud.text(raceClockLabel(clock),268,60,17,clock.displayedSeconds()<=10?ORANGE:INK,true);
    }

    static String raceClockLabel(EventRaceClock clock) {
        int remaining=clock.displayedSeconds();
        int seconds=remaining%60;
        String reason=clock.limit()==EventRaceClock.Limit.FIRST_FINISH?"首车完赛后剩余 ":"全场剩余 ";
        return reason+(remaining/60)+":"+(seconds<10?"0":"")+seconds;
    }
    void raceTiming(HudRenderer h,FormulaGame app) {
        var rows=app.eventRaceStandings();
        if(rows.isEmpty()) return;
        int position=0;
        for(int i=0;i<rows.size();i++) if(rows.get(i).car().driver()==0) position=i+1;
        boolean onGrid=app.countdown>0;
        h.panel(25,100,380,238,PANEL);
        h.text("P"+position+" / "+rows.size(),45,139,28,MINT,true);
        int lap=Math.min(app.sessionLaps,app.racers.get(0).progress.completedLaps()+1);
        h.text("第 "+lap+" / "+app.sessionLaps+" 圈",286,135,16,INK);
        h.text(onGrid?"排位发车顺序":"暂列 · 相对首位同门差 / 完赛含罚时",45,168,14,MUTED);
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);int driver=row.car().driver(),y=201+i*33;
            Color color=driver==0?MINT:INK;
            h.text((i+1)+"   "+EventSeries.driverName(driver),45,y,16,color,driver==0);
            if(!onGrid) h.text(timingLabel(row,i==0),250,y,15,color);
        }
    }
    String timingLabel(EventRaceTiming.Row row,boolean leading) {
        var car=row.car();
        boolean finished=car.finished();
        if(!finished) {
            if(leading) return "首位";
            if(row.gap()==null) return "—";
        }
        double seconds=finished?car.total():row.gap().seconds();
        var cached=raceTimingText[car.driver()];
        if(cached!=null && cached.finished()==finished && Double.compare(cached.seconds(),seconds)==0) return cached.label();
        String label=finished?"完赛 "+PracticeSession.format(seconds):String.format(Locale.ROOT,"%+.3f s",seconds);
        raceTimingText[car.driver()]=new TimingText(finished,seconds,label);
        return label;
    }
    void qualifying(HudRenderer h,FormulaGame app) {
        h.panel(25,101,430,248,PANEL);h.text("一次有效计时圈",45,135,22,MINT,true);
        h.text("剩余 "+Math.round(app.qualifying().remaining())+app.drivingHint(" 秒 · R 重跑本次排位"),45,170,15,MUTED);
        var order=app.qualifying().gridOrder();
        for(int rank=0;rank<order.size();rank++) {
            int driver=order.get(rank);
            var time=app.qualifying().time(driver);String text=time==null?"进行中":time.valid()?PracticeSession.format(time.lap()):"无有效圈";
            String name=time!=null && time.valid()?"P"+(rank+1)+"  "+EventSeries.driverName(driver):EventSeries.driverName(driver);
            h.text(name,45,209+rank*36,16,driver==0?MINT:INK);
            h.text(text,295,209+rank*36,16,INK);
        }
    }
    void result(HudRenderer h,FormulaGame app,int x,int y) {
        if(app.mode==FormulaGame.Mode.QUALIFYING) qualifyingRows(h,app.qualifying().results(),x,y);
        else {
            var event=app.activeEvent();var results=event.state().results();
            raceRows(h,results.get(results.size()-1),x,y);
        }
        if(app.eventSavePending()) h.text("保存失败；结果仍在内存，返回赛事中心后可重试保存。",x,y+188,15,ORANGE);
        else h.text("阶段已保存；返回赛事中心继续，已结算结果不会重复计分。",x,y+188,15,MUTED);
    }
    private void qualifyingRows(HudRenderer h,List<EventSeries.Qualifying> times,int x,int y) {
        for(int i=0;i<times.size();i++) {
            var time=times.get(i);h.text((i+1)+"  "+EventSeries.driverName(time.driver()),x,y+i*43,18,time.driver()==0?MINT:INK);
            h.text(time.valid()?PracticeSession.format(time.lap()):"无有效圈",x+330,y+i*43,17,INK);
        }
    }
    private void raceRows(HudRenderer h,EventSeries.Result result,int x,int y) {
        for(int i=0;i<result.race().size();i++) {
            var row=result.race().get(i);h.text((i+1)+"  "+EventSeries.driverName(row.driver()),x,y+i*43,18,row.driver()==0?MINT:INK);
            h.text((row.finished()?PracticeSession.format(row.total()):"未完赛")+"  +"+result.points(row.driver()),x+330,y+i*43,17,INK);
        }
    }
}
