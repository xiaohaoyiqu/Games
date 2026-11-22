package com.f1car.model;

import java.util.*;

/** Exactly one completed qualifying/race transition, paired with the actual accepted base. */
public record EventPendingResult(EventSeries.State base,EventSeries.State candidate) {
    public enum Relation {READY,ALREADY_SAVED,CONFLICT}
    public EventPendingResult {
        Objects.requireNonNull(base);Objects.requireNonNull(candidate);EventSeries.State expected;
        var event=new EventSeries(base);
        if(base.phase()==EventSeries.Phase.QUALIFYING) expected=event.qualify(candidate.qualifying()).state();
        else if(base.phase()==EventSeries.Phase.RACE && candidate.results().size()==base.results().size()+1) {
            int last=candidate.results().size()-1;var result=candidate.results().get(last);
            expected=event.finishRace(result.race(),candidate.raceControls().get(last),result.settlement()).state();
        } else throw new IllegalArgumentException("Recovery must be one completed qualifying or race stage");
        if(!expected.equals(candidate)) throw new IllegalArgumentException("Recovery changed the accepted event or skipped a stage");
    }
    public Relation relation(EventSeries.State accepted) {return candidate.equals(accepted)?Relation.ALREADY_SAVED:base.equals(accepted)?Relation.READY:Relation.CONFLICT;}
    public List<String> details() {
        var lines=new ArrayList<String>();var round=base.calendar().get(base.round());
        lines.add("赛事 "+base.eventId()+" · 第 "+(base.round()+1)+" / "+base.calendar().size()+" 站 · "+round.trackId());lines.add("原地图几何 "+round.geometry()+"；原物理 "+base.physicsVersion());
        lines.add("已经结束的阶段："+(base.phase()==EventSeries.Phase.QUALIFYING?"排位":"正赛")+"；候选阶段："+new EventSeries(candidate).phaseName());
        lines.add("恢复只把完整原结果放回待保存；需要另点保存，不开始驾驶，不自动推进或重复计分。");
        lines.add("难度 "+base.difficulty()+"；正赛 "+base.laps()+" 圈；建赛时实际配置：");for(var field:PartsCatalog.Field.values()) lines.add(field.title+"："+Float.toString(field.ratio(base.tuning()))+" 倍。");
        if(base.phase()==EventSeries.Phase.QUALIFYING) {
            for(var q:candidate.qualifying()) lines.add(EventSeries.driverName(q.driver())+(q.valid()?"：原排位 "+Double.toString(q.lap())+" s，原时刻 "+Double.toString(q.setAt())+" s":"：无有效排位圈"));
            lines.add("排位操作未单独记录，不补写当前操作条件。");
        } else lines.addAll(new EventRoundBrief(candidate,candidate.results().size()-1).details());
        lines.add("只有完整已结束阶段可以恢复；驾驶到一半的位置、速度、当前圈及事故原因没有记录。目录与车库不用于补写原安装来源。");return List.copyOf(lines);
    }
}
