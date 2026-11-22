package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.List;
import java.util.Locale;

/** Frozen round facts with explicit current-stage actions, separate from scoring and archives. */
final class EventDebriefHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72),PANEL=new Color(0x13242e);
    private static final Color ROW=new Color(0x203c49);
    private record ResultRow(int driver,String title,String time) {}
    private record RoundView(EventRoundBrief brief,List<ResultRow> rows,String summary,String focus) {}
    private RoundView selected;
    private RoundView[] rounds;
    private boolean acceptedAtOpen;
    private String phaseName="";

    boolean showing() {return selected!=null;}

    void close(FormulaGame app) {
        if(selected==null) return;
        app.cancelEventRaceReplay(selected.brief());
        selected=null;
        rounds=null;
        phaseName="";
        acceptedAtOpen=false;
    }

    void open(FormulaGame app,EventSeries.State source,int round) {
        if(selected==null || !selected.brief().source().equals(source)) {
            close(app);
            rounds=new RoundView[source.results().size()];
            phaseName=new EventSeries(source).phaseName();
        }
        if(selected!=null && selected!=rounds[round]) app.cancelEventRaceReplay(selected.brief());
        if(rounds[round]==null) rounds[round]=roundView(source,round);
        selected=rounds[round];
        var saved=app.events.current();
        acceptedAtOpen=saved!=null && saved.state().eventId().equals(source.eventId())
                && saved.state().results().size()>round && saved.state().results().get(round).equals(source.results().get(round));
    }

    private static RoundView roundView(EventSeries.State source,int round) {
        var brief=new EventRoundBrief(source,round);
        var you=brief.driver(0);
        String summary="你：排位 P"+you.grid()+" → 正赛分类 P"+you.classification()+" · "+(you.finish().finished()?"已完赛":"未完赛")
                +" · 本站 "+you.points()+" 分";
        var rows=brief.rows().stream().map(EventDebriefHud::resultRow).toList();
        return new RoundView(brief,rows,summary,String.join(" ",brief.focus()));
    }

    private static ResultRow resultRow(EventRoundBrief.Row row) {
        String title="P"+row.classification()+"  "+EventSeries.driverName(row.driver())+" · 排位 P"+row.grid()+" · "+row.points()+" 分"
                +(row.rankingTie()?" · 排序同值":"");
        var finish=row.finish();
        String time=finish.finished()
                ?String.format(Locale.ROOT,"原用时 %.3f s + 罚时 %.3f s = 总用时 %.3f s；相对首名排序差 %d ms",
                        finish.elapsed(),finish.penalty(),finish.total(),row.gapMilliseconds())
                :String.format(Locale.ROOT,"未完赛 · 记录进度 %.3f m · 不比较完赛用时 · 原罚时字段 %.3f s",finish.distance(),finish.penalty());
        return new ResultRow(row.driver(),title,time);
    }

    private boolean current(FormulaGame app,EventRoundBrief expected) {var actual=app.activeEvent();if(actual!=null && expected.source().equals(actual.state())) return true;app.notifyUser("赛事已变化；本页原始回顾保持，请明确刷新或返回赛事中心重新选择。");return false;}
    private void next(HudRenderer h,FormulaGame app,EventRoundBrief expected,boolean practice) {
        if(!current(app,expected)) return;
        if(app.eventSavePending()) {app.saveEventResult();return;}
        var phase=expected.source().phase();if(phase==EventSeries.Phase.QUALIFYING || phase==EventSeries.Phase.RACE) {app.prepareEventStage(practice);return;}
        if(phase==EventSeries.Phase.REVIEW) {
            var source=expected.source();var next=source.calendar().get(source.round()+1);
            h.review("确认进入下一站？",List.of("第 "+(source.round()+1)+" 站结果已保存。这里只推进原赛事进度，不重复计分，也不开始驾驶。","下一站：第 "+(source.round()+2)+" / "+source.calendar().size()+" 站 · "+next.trackId(),"路线几何 "+next.geometry(),"进入下一站排位前，可另选自由练习；本赛事固定参数保持，当前车库不变。"),"保存进度并进入下一站",()->{
                if(!current(app,expected)) return;if(app.eventSavePending()) {app.notifyUser("请先保存待结算的赛事成绩，再重新核对下一站。");return;}
                app.continueEvent();var actual=app.activeEvent();if(actual!=null && !source.equals(actual.state())) close(app);
            });return;
        }
        app.notifyUser("本赛事已完成，结果保持；可返回中心查看或明确新建另一赛事。");
    }
    void render(HudRenderer h,FormulaGame app) {
        var current=selected;var view=current.brief();var source=view.source();h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text("赛事 · 本站回顾与下一步",60,107,30,INK,true);
        if(source.phase()==EventSeries.Phase.QUALIFYING && !app.eventSavePending()) h.smallButton(630,57,180,38,"练习当前站（不计分）",()->next(h,app,view,true));
        h.smallButton(830,57,180,38,"明确刷新本页来源",()->{var actual=app.activeEvent();if(actual==null || !actual.state().eventId().equals(source.eventId()) || actual.state().results().size()<=view.round()) {app.notifyUser("原赛事已被替换或该站不存在；请返回中心重新选择。");return;}open(app,actual.state(),view.round());});h.smallButton(1030,57,184,38,"返回赛事中心",()->close(app));
        h.text("第 "+(view.round()+1)+" / "+source.calendar().size()+" 站 · "+source.calendar().get(view.round()).trackId()+" · 打开时"+(acceptedAtOpen?"已保存结果":"尚未保存到赛事档案"),60,153,17,SOFT);
        h.text("当前赛事第 "+(source.round()+1)+" 站 · "+phaseName+"；本页固定打开时原文，下一步入口仍会核对当前阶段。",60,187,15,AMBER);
        h.text(current.summary(),60,227,23,MINT,true);
        if(view.round()>0) h.smallButton(830,208,180,32,"查看前一站",()->open(app,source,view.round()-1));
        if(view.round()+1<source.results().size()) h.smallButton(1030,208,184,32,"查看后一站",()->open(app,source,view.round()+1));
        h.text(view.settlementSummary(),60,266,15,SOFT);
        for(int i=0;i<current.rows().size();i++) {
            var row=current.rows().get(i);int y=285+i*69;
            h.panel(48,y-5,1178,64,ROW);
            h.text(row.title(),60,y+17,17,row.driver()==0?MINT:INK);
            h.text(row.time(),60,y+43,14,SOFT);
            h.smallButton(1015,y+4,199,38,"核对本车原始成绩",()->h.review("原始成绩与排序",view.rowDetails(row.driver()),"返回本站回顾",()->{}));
        }
        h.wrapped(current.focus(),60,585,1154,16,SOFT,25);
        if(view.replayId()!=null) {
            if(app.replayLoading(view.replayId())) {
                h.smallButton(800,650,414,38,"正在读取本站回看 · 取消",app::cancelReplayLoading);
            } else if(app.replayStore.contains(view.replayId())) {
                h.smallButton(800,650,270,38,"查看本站正赛回看",()->app.requestEventRaceReplay(view,()->selected==current,false));
                h.smallButton(1082,650,132,38,"定位回看标记",()->app.requestEventRaceReplay(view,()->selected==current,true));
            }
            else h.text("本站回看已不在本机回看库。",800,675,15,SOFT);
        }
        h.smallButton(60,704,350,38,"返回赛事中心，保留结果",()->close(app));h.smallButton(430,704,350,38,app.eventSavePending()?"核对后重试保存赛事结果":source.phase()==EventSeries.Phase.QUALIFYING || source.phase()==EventSeries.Phase.RACE?"准备当前赛事阶段":source.phase()==EventSeries.Phase.REVIEW?"核对后进入下一站":"查看当前赛事下一步",()->next(h,app,view,false));
        h.smallButton(800,704,414,38,"冻结条件、排序与完整原始成绩",()->h.review("本站回顾的完整原文",view.details(),"返回本站回顾",()->{}));
    }
}
