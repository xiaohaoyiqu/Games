package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.*;

/** Shows like-conditioned results and explicitly separates non-finishers from timed comparisons. */
final class RivalHud {
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),ORANGE=new Color(255,169,114);
    private static final List<String> DIFFICULTIES=List.of("轻松","标准","挑战");
    private String conditionKey="",mapKey="";
    private int conditionIndex,index=-1,historySize;
    private RivalLedger.State source;
    private List<ConditionChoice> conditions=List.of();
    private Filtered view;
    private record ConditionChoice(String key,String label,String tuning,String physics) {}
    private record MapChoice(String key,String label) {}
    private record Filtered(List<MapChoice> maps,int selectedMap,List<RivalLedger.Meeting> meetings,RivalLedger.Stats stats) {}

    void clearView() {source=null;conditions=List.of();view=null;}

    private void refreshConditions(RivalLedger.State ledger) {
        if(source==ledger) return;
        source=ledger;
        view=null;
        var choices=new ArrayList<ConditionChoice>();
        int previous=-1;
        for(var condition:ledger.conditions()) {
            String key=condition.key();
            var tuning=condition.tuning();
            if(key.equals(conditionKey)) previous=choices.size();
            choices.add(new ConditionChoice(key,DIFFICULTIES.get(condition.difficulty())+" / "+condition.laps()
                    +" 圈 / "+tuning.performanceKey()+" / "+condition.controlsName(),
                    String.format(Locale.ROOT,"动／刹／悬／翼：%.3f / %.3f / %.3f / %.3f",tuning.power(),
                            tuning.brakes(),tuning.suspension(),tuning.wing()),"物理："+shorten(condition.physics(),37)));
        }
        conditions=List.copyOf(choices);
        if(!conditions.isEmpty()) {
            if(previous<0) selectCondition(0);
            else conditionIndex=previous;
        }
    }

    private void selectCondition(int selected) {
        conditionIndex=selected;
        conditionKey=conditions.get(selected).key();
        mapKey="";
        index=-1;
        view=null;
    }

    private Filtered filtered(RivalLedger.State ledger) {
        if(view!=null) return view;
        var all=ledger.filter(conditionKey,"");
        var mapLabels=new LinkedHashMap<String,String>();
        mapLabels.put("","全部地图修订");
        for(var meeting:all) mapLabels.putIfAbsent(meeting.mapKey(),meeting.trackName()+" · "+meeting.geometry().substring(0,8));
        if(!mapLabels.containsKey(mapKey)) mapKey="";
        var maps=new ArrayList<MapChoice>();
        int selected=0;
        for(var map:mapLabels.entrySet()) {
            if(map.getKey().equals(mapKey)) selected=maps.size();
            maps.add(new MapChoice(map.getKey(),map.getValue()));
        }
        var meetings=mapKey.isEmpty()?all:all.stream().filter(meeting->mapKey.equals(meeting.mapKey())).toList();
        view=new Filtered(List.copyOf(maps),selected,meetings,RivalLedger.stats(meetings,ledger.followed()));
        return view;
    }

    void render(HudRenderer h,FormulaGame app) {
        var ledger=app.rivals.state();int followed=ledger.followed();
        refreshConditions(ledger);
        for(int driver=1;driver<4;driver++) { int chosen=driver;h.smallButton(70+(driver-1)*380,238,356,42,(followed==driver?"关注 · ":"")+EventSeries.driverName(driver),()->{if(!app.rivals.follow(chosen)) app.notifyUser(app.rivals.warning);}); }
        if(conditions.isEmpty()) {
            h.text("等待一站已结算正赛",70,355,27,MINT,true);h.wrapped("完成周末或巡回杯正赛后，记录会按地图修订、物理、车辆性能、难度、圈数与正赛操作条件整理。你可以自由关注三位原创对手中的一位；关注只改变这里的展示。",70,405,1110,19,INK,33);
            footer(h,app);return;
        }
        var condition=conditions.get(conditionIndex);
        h.smallButton(70,296,740,42,condition.label(),()->selectCondition((conditionIndex+1)%conditions.size()));
        var shown=filtered(ledger);
        var maps=shown.maps();
        h.smallButton(830,296,376,42,maps.get(shown.selectedMap()).label(),()->{
            mapKey=maps.get((shown.selectedMap()+1)%maps.size()).key();
            index=-1;
            view=null;
        });
        h.panel(70,355,540,290,new Color(24,49,60));h.panel(636,355,570,290,new Color(24,49,60));var stats=shown.stats();
        h.text("筛选内 "+stats.rounds()+" 站 · 你与 "+EventSeries.driverName(followed),90,391,20,MINT,true);
        h.text("双方完赛用时：领先 "+stats.raceAhead()+" / 相同 "+stats.raceEqual()+" / 落后 "+stats.raceBehind(),90,432,16,INK);
        h.text("仅你完赛 "+stats.playerOnlyFinish()+" · 仅对手完赛 "+stats.rivalOnlyFinish(),90,469,16,INK);
        h.text("双方未完赛 "+stats.bothDnf()+"（不比较用时）",90,505,16,MUTED);
        h.text("同场排位用时：领先 "+stats.qualifyingAhead()+" / 相同 "+stats.qualifyingEqual()+" / 落后 "+stats.qualifyingBehind(),90,548,15,INK);
        h.text("排位有缺失成绩 "+stats.qualifyingUncompared()+" · 排位操作条件未单独记录",90,584,14,MUTED);
        h.text(condition.tuning(),90,608,13,MUTED);
        h.text(condition.physics(),90,634,13,MUTED);
        var meetings=shown.meetings();if(historySize!=meetings.size()) { historySize=meetings.size();index=-1; }index=index<0?meetings.size()-1:Math.min(index,meetings.size()-1);
        if(!meetings.isEmpty()) {
            var meeting=meetings.get(index);h.text("交手 "+(index+1)+" / "+meetings.size(),658,391,20,INK,true);
            h.smallButton(1030,366,72,32,"上一条",()->index=Math.max(0,index-1));h.smallButton(1114,366,72,32,"下一条",()->index=Math.min(meetings.size()-1,index+1));
            h.text(shorten(meeting.eventTitle(),30)+" · 第 "+(meeting.round()+1)+" 站",658,431,17,INK);h.text(shorten(meeting.trackName(),30)+" · "+meeting.geometry().substring(0,8),658,465,15,MUTED);
            var you=meeting.finish(0);var rival=meeting.finish(followed);h.text("你："+finish(you)+"    对手："+finish(rival),658,509,17,INK);
            var gap=meeting.raceGap(followed);h.text(gap.isPresent()?String.format(Locale.ROOT,"你 − 对手：%+.3f 秒（含实际罚时）",gap.getAsDouble()):"缺少双方完赛时间，不推算用时差。",658,551,16,gap.isPresent()?MINT:MUTED);
            var q=meeting.qualifying(0);var other=meeting.qualifying(followed);h.text("排位："+(q.valid()?PracticeSession.format(q.lap()):"无成绩")+" / "+(other.valid()?PracticeSession.format(other.lap()):"无成绩"),658,589,15,MUTED);
            h.text("时间相同按毫秒统计；不由显示顺序硬分胜负。",658,625,13,MUTED);
        }
        footer(h,app);
    }
    private String finish(EventSeries.Finish result) { return result.finished()?PracticeSession.format(result.total()):"未完赛"; }
    private String shorten(String value,int count) { return value.length()<=count?value:value.substring(0,count-1)+"…"; }
    private void footer(HudRenderer h,FormulaGame app) {
        h.text("最近最多 256 站；关注不改变 AI 配速，不设强制对手任务。统计仅描述已有成绩。",72,675,15,MUTED);
        if(app.rivals.pending() || !app.rivals.warning.isEmpty()) { h.smallButton(70,696,1136,40,
                app.rivals.conflictReviewAvailable()?"核对两份交手记录":"重试同步已保存赛事与交手记录",app::saveRivalRecords);h.text(shorten(app.rivals.warning,78),72,755,14,ORANGE); }
        else h.text("条件按钮切换记录组，地图按钮切换具体修订；历史未知条件单独显示。",72,731,15,MUTED);
    }
}
