package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.EventRoundBrief;
import com.f1car.model.EventRoundComparison;
import com.f1car.model.EventSeries;
import com.f1car.model.PartsCatalog;
import com.f1car.model.PracticeSession;
import com.f1car.persistence.EventHistoryStore;
import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.Collectors;

/** Read-only completed events, with the original results and conditions for each round. */
final class EventHistoryHud {
    private static final Color PANEL=new Color(10,29,39,245),ROW=new Color(24,49,60);
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189);
    private static final Color MINT=new Color(76,235,193),AMBER=new Color(255,169,114);
    private static final int ROWS=4;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private record Card(EventHistoryStore.Entry entry,EventSeries event) {}
    private record ResultRow(int driver,String title,String result) {}
    private record RoundView(EventRoundBrief brief,List<ResultRow> rows) {}
    private record Selection(EventHistoryStore.Entry entry,EventRoundBrief brief) {
        boolean sameRound(Selection other) {
            return other!=null && entry.id().equals(other.entry.id()) && brief.round()==other.brief.round();
        }
        boolean available(FormulaGame app) {return entry.equals(app.eventHistory.find(entry.id()));}
    }
    private List<EventHistoryStore.Entry> viewed=List.of();
    private List<Card> cards=List.of();
    private Card selected;
    private RoundView[] rounds;
    private RoundView roundView;
    private Selection pinned;
    private int page;

    void clear(FormulaGame app) {
        closeRound(app);
        pinned=null;
        viewed=List.of();
        cards=List.of();
        page=0;
    }

    void render(HudRenderer h,FormulaGame app,Runnable back) {
        h.panel(38,32,1204,732,PANEL);
        if(selected!=null) {
            details(h,app);
            return;
        }
        h.text("已完成赛事档案",70,114,32,INK,true);
        h.text("保存完整赛事后，可在新建赛事后继续查看各站记录。",70,151,16,SOFT);
        h.smallButton(822,75,188,39,"重读档案",()->{
            if(!app.eventHistory.reload()) app.notifyUser(app.eventHistory.warning());
        });
        h.smallButton(1030,75,182,39,"返回赛事中心",back);
        var entries=app.eventHistory.entries();
        if(viewed!=entries) {
            viewed=entries;
            cards=entries.stream().map(entry->new Card(entry,new EventSeries(entry.event()))).toList();
            if(pinned!=null && !pinned.available(app)) pinned=null;
        }
        var event=app.events.current();
        if(event!=null && event.state().phase()==EventSeries.Phase.COMPLETE && !app.eventSavePending()) {
            var source=event.state();
            var existing=app.eventHistory.find(source.eventId());
            if(existing!=null && existing.event().equals(source)) h.text("当前完整赛事已收入档案。",70,197,16,MINT);
            else if(app.eventHistory.writable()) h.smallButton(70,171,298,41,"保存当前完整赛事",()->
                    h.confirm("保存完整赛事到档案？","保留 "+event.title()+" 的全部站次条件、排位、正赛原始结果和最终积分。",
                            ()->app.archiveCompletedEvent(source)));
        }
        if(cards.isEmpty()) h.text("还没有保存的赛事档案。",70,355,23,SOFT);
        int pages=Math.max(1,(cards.size()+ROWS-1)/ROWS);
        page=Math.min(page,pages-1);
        for(int i=0;i<ROWS && page*ROWS+i<cards.size();i++) {
            var card=cards.get(page*ROWS+i);
            int y=231+i*98;
            h.panel(70,y,1142,90,ROW);
            var archived=card.event();
            var player=archived.standings().stream().filter(row->row.driver()==0).findFirst().orElseThrow();
            h.text(archived.title()+" · P"+archived.placing(0)+" · "+player.points()+" 分",88,y+33,21,MINT,true);
            h.text("入档 "+DATE.format(Instant.ofEpochMilli(card.entry().savedAt()))+" · "
                    +archived.state().laps()+" 圈／站 · "+archived.state().results().size()+" 站",88,y+61,15,SOFT);
            h.smallButton(836,y+22,197,42,"查看原始结果",()->open(card));
            if(app.eventHistory.writable()) h.smallButton(1051,y+22,142,42,"移除此档案",()->
                    h.confirm("移除这份赛事档案？","只移除 "+archived.title()+" 的这份副本。当前赛事、回看和车手履历保留。",
                            ()->app.removeArchivedEvent(card.entry())));
        }
        if(!app.eventHistory.warning().isEmpty()) h.wrapped(app.eventHistory.warning(),70,649,1110,16,AMBER,20);
        h.smallButton(70,703,140,38,"上一页",()->page=Math.max(0,page-1));
        h.text((page+1)+" / "+pages+" · "+cards.size()+" / "+EventHistoryStore.CAPACITY+" 份",230,728,15,SOFT);
        h.smallButton(403,703,140,38,"下一页",()->page=Math.min(pages-1,page+1));
        if(pinned!=null) h.text("对照 A · "+pinned.brief().source().calendar().get(pinned.brief().round()).trackId()
                +" · 第 "+(pinned.brief().round()+1)+" 站",577,728,15,MINT);
    }

    private void open(Card card) {
        selected=card;
        rounds=new RoundView[card.entry().event().results().size()];
        roundView=viewForRound(rounds.length-1);
    }

    void closeRound(FormulaGame app) {
        if(roundView!=null) app.cancelEventRaceReplay(roundView.brief());
        selected=null;
        roundView=null;
        rounds=null;
    }

    private void selectRound(FormulaGame app,int index) {
        if(index<0 || index>=rounds.length || index==roundView.brief().round()) return;
        app.cancelEventRaceReplay(roundView.brief());
        roundView=viewForRound(index);
    }

    private RoundView viewForRound(int index) {
        if(rounds[index]==null) {
            var brief=new EventRoundBrief(selected.entry().event(),index);
            var rows=brief.rows().stream().map(row->{
                String title="P"+row.classification()+"  "+EventSeries.driverName(row.driver())+" · 排位 P"+row.grid()+" · "+row.points()+" 分";
                var finish=row.finish();
                String result=finish.finished()?"完赛 "+PracticeSession.format(finish.total())+" · 含罚时 "+finish.penalty()+" 秒"
                        :"未完赛 · 记录进度 "+Math.round(finish.distance())+" 米";
                return new ResultRow(row.driver(),title,result);
            }).toList();
            rounds[index]=new RoundView(brief,rows);
        }
        return rounds[index];
    }

    private void details(HudRenderer h,FormulaGame app) {
        var card=selected;
        var displayed=roundView;
        var round=displayed.brief();
        var state=card.entry().event();
        h.text(card.event().title()+" · 档案回顾",70,113,32,INK,true);
        h.smallButton(1022,75,190,42,"返回档案列表",()->closeRound(app));
        h.text("第 "+(round.round()+1)+" / "+state.results().size()+" 站 · "
                +state.calendar().get(round.round()).trackId()+" · 原始比赛记录",70,156,18,SOFT);
        if(round.round()>0) h.smallButton(858,140,160,35,"上一站",()->selectRound(app,round.round()-1));
        if(round.round()+1<state.results().size()) h.smallButton(1037,140,175,35,"下一站",()->selectRound(app,round.round()+1));
        h.text(round.settlementSummary(),70,206,16,SOFT);
        for(int index=0;index<displayed.rows().size();index++) {
            var row=displayed.rows().get(index);
            int y=225+index*87;
            h.panel(70,y,1142,77,ROW);
            h.text(row.title(),88,y+31,19,row.driver()==0?MINT:INK,true);
            h.text(row.result(),88,y+57,15,SOFT);
            h.smallButton(1000,y+20,192,39,"查看本车原读数",()->
                    h.review("原始分类与用时",round.rowDetails(row.driver()),"返回档案回顾",()->{}));
        }
        var replay=round.replayId();
        if(replay!=null && app.replayLoading(replay))
            h.smallButton(850,700,362,42,"正在读取回看 · 取消",app::cancelReplayLoading);
        else if(replay!=null && app.replayStore.contains(replay))
            h.smallButton(850,700,362,42,"观看本站原回看",()->
                    app.requestArchivedEventReplay(card.entry(),round,()->roundView==displayed));
        else if(replay!=null) h.text("此站的回看已不在本机回看库。",850,727,15,SOFT);
        h.smallButton(70,700,373,42,"核对冻结条件和原始结果",()->
                h.review("档案条件与原始结果",round.details(),"返回档案回顾",()->{}));
        var current=new Selection(card.entry(),round);
        boolean alreadyPinned=current.sameRound(pinned);
        h.smallButton(458,700,169,42,alreadyPinned?"取消对照 A":"固定本站为 A",()->pinned=alreadyPinned?null:current);
        if(pinned!=null && !alreadyPinned)
            h.smallButton(643,700,188,42,"与 A 并列查看",()->compare(h,app,current));
    }

    private void compare(HudRenderer h,FormulaGame app,Selection current) {
        if(pinned==null || current.sameRound(pinned) || !pinned.available(app) || !current.available(app)) {
            app.notifyUser("至少一份档案已变化，请重新选择两站。");
            return;
        }
        var comparison=new EventRoundComparison(pinned.brief(),current.brief());
        var lines=new ArrayList<String>();
        lines.addAll(conditions("A",comparison.first()));
        lines.addAll(conditions("B",comparison.second()));
        if(comparison.differences().isEmpty()) {
            lines.add("已记录的路线、物理、圈数、难度、性能参数、操作和发车顺序一致。下方用时差按 B 减 A，正数表示 B 用时较长。");
        } else {
            lines.add("条件有差异或缺项："+comparison.differences().stream().map(value->value.label)
                    .collect(Collectors.joining("、"))+"。各站保留原始用时与分类。");
            if(comparison.differences().contains(EventRoundComparison.Difference.SETUP)) {
                var a=comparison.first().source().tuning();
                var b=comparison.second().source().tuning();
                for(var field:PartsCatalog.Field.values()) {
                    if(Float.compare(field.ratio(a),field.ratio(b))!=0)
                        lines.add(field.title+" · A "+field.ratio(a)+" · B "+field.ratio(b));
                }
            }
        }
        for(var row:comparison.rows()) {
            lines.add(EventSeries.driverName(row.driver()));
            lines.add(result("A",row.first()));
            lines.add(result("B",row.second()));
            if(row.change()!=null) {
                var time=row.change();
                lines.add(String.format(Locale.ROOT,"B − A · 原用时 %+.3f 秒 · 罚时 %+.3f 秒 · 总用时 %+.3f 秒",
                        time.running(),time.penalty(),time.total()));
            }
        }
        h.review("两站原始成绩对照",lines,"返回档案回顾",()->{});
    }

    private static List<String> conditions(String side,EventRoundBrief brief) {
        var source=brief.source();
        var route=source.calendar().get(brief.round());
        return List.of(side+" · 第 "+(brief.round()+1)+" 站 · "+route.trackId()+" · "+source.eventId(),
                "路线几何 "+route.geometry()+" · 物理 "+source.physicsVersion()+" · "+source.laps()+" 圈 · 难度 "+source.difficulty(),
                "正赛操作 "+source.raceControls().get(brief.round())+" · "+brief.settlementSummary());
    }

    private static String result(String side,EventRoundBrief.Row row) {
        var finish=row.finish();
        String order=side+" · 发车 P"+row.grid()+" · 分类 P"+row.classification()+" · "+row.points()+" 分";
        return finish.finished()?order+" · 原用时 "+PracticeSession.format(finish.elapsed())
                +" · 罚时 "+finish.penalty()+" 秒 · 总用时 "+PracticeSession.format(finish.total())
                :order+" · 未完赛 · 记录进度 "+Math.round(finish.distance())+" 米 · 原罚时 "+finish.penalty()+" 秒";
    }
}
