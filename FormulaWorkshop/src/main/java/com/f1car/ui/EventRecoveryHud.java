package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.*;
import java.util.List;

/** Explicit review of a completed stage; stable rendering only reads captured facts. */
final class EventRecoveryHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private boolean showing,memory;private EventPendingResult view;private EventSeries.State accepted;private List<String> details=List.of(),facts=List.of();private String status="",warning="";
    void clear() {showing=false;memory=false;view=null;accepted=null;details=List.of();facts=List.of();status=warning="";}
    boolean showing() {return showing;}
    void open(FormulaGame app,boolean memory) {
        showing=true;this.memory=memory;view=memory?app.pendingEventResult():app.eventRecovery.entry();var current=app.events.current();accepted=current==null?null:current.state();
        warning=app.eventRecovery.warning();details=view==null?List.of():view.details();var values=new ArrayList<String>();
        if(view==null) {status=memory && app.eventSavePending()?"本次内存结果尚待保存，请返回中心明确重试保存。":"当前所选来源没有已读恢复结果。";}
        else {
            status=memory?(app.pendingEventRecoverable()?"本次结果尚未发布；本进程已核对写入同一恢复副本。":"本次结果尚未发布；未确认写入同一恢复副本，退出可能丢失。"):
                switch(view.relation(accepted)) {case READY->"已读主档仍是原基准，可以明确恢复为待保存；尚未采用。";case ALREADY_SAVED->"已读主档已经包含同一完整结果；只需确认清理副本，不重复计分。";case CONFLICT->"已读主档与原基准／候选不对应，只读查看；不覆盖或合并。";};
            var source=view.base();values.add("第 "+(source.round()+1)+" / "+source.calendar().size()+" 站 · "+source.calendar().get(source.round()).trackId());values.add("原阶段："+new EventSeries(source).phaseName()+" → 候选："+new EventSeries(view.candidate()).phaseName());values.add(new EventSeries(source).title()+" · "+new String[]{"轻松","标准","挑战"}[source.difficulty()]+"难度 · 正赛 "+source.laps()+" 圈");
            if(source.phase()==EventSeries.Phase.QUALIFYING) {for(var q:view.candidate().qualifying()) values.add(EventSeries.driverName(q.driver())+" · "+(q.valid()?"原排位 "+Double.toString(q.lap())+" s":"没有有效排位圈"));}
            else {var result=new EventRoundBrief(view.candidate(),view.candidate().results().size()-1);for(var row:result.rows()) values.add("P"+row.classification()+" · "+EventSeries.driverName(row.driver())+" · "+(row.finish().finished()?"总用时 "+Double.toString(row.finish().total())+" s":"未完赛")+" · "+row.points()+" 分");}
        }
        facts=List.copyOf(values);
    }
    void render(HudRenderer h,FormulaGame app) {
        var selected=view;var original=accepted;boolean memoryView=memory;h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("赛事 · 已结束阶段的结果恢复",60,98,29,INK,true);h.smallButton(1030,57,184,38,"返回赛事中心",this::clear);
        h.text("只恢复完整原成绩；驾驶到一半的位置、当前圈和车辆状态没有保存。",60,137,17,AMBER);
        h.smallButton(60,162,350,38,"查看本次内存候选",()->open(app,true));h.smallButton(430,162,350,38,"查看已读磁盘副本",()->open(app,false));h.smallButton(800,162,414,38,"明确重读主档与恢复副本",()->{app.reloadEventRecovery();open(app,memoryView);});
        h.text((memoryView?"本次内存候选":"已读磁盘副本")+" · 打开时已读状态",60,241,22,MINT,true);h.wrapped(status,60,279,1150,17,AMBER,28);
        int y=354;for(String line:facts) {h.text(line,60,y,17,SOFT);y+=29;}
        if(!warning.isEmpty()) h.wrapped(warning,60,565,1140,16,AMBER,25);
        if(selected==null) return;
        h.smallButton(60,610,1154,36,"核对完整原基准、候选与实际成绩",()->h.review("待存结果原文",details,"返回恢复页",()->{}));
        if(memoryView) h.smallButton(60,674,550,44,"核对后保存本次原结果",()->h.review("确认保存本次原结果？",details,"写恢复副本并保存原结果",()->{
            var current=app.events.current();if(!selected.equals(app.pendingEventResult()) || !Objects.equals(original,current==null?null:current.state())) app.notifyUser("内存候选或赛事来源已变化，请重新核对。");else app.saveEventResult();open(app,app.eventSavePending());
        }));
        else if(selected.relation(original)==EventPendingResult.Relation.READY) h.smallButton(60,674,550,44,"核对后恢复为待保存（不发布）",()->h.review("确认恢复这个已结束阶段？",details,"只恢复为待保存",()->{app.restoreEventRecovery(selected,original);open(app,app.eventSavePending());}));
        else if(selected.relation(original)==EventPendingResult.Relation.ALREADY_SAVED) h.smallButton(60,674,550,44,"确认已保存，仅清理恢复副本",()->h.review("确认主档已经保存同一结果？",details,"核对主档后仅清理副本",()->{app.acknowledgeEventRecovery(selected,original);open(app,false);}));
        h.smallButton(640,674,574,44,memoryView?"明确放弃所选内存候选":"明确移除所选磁盘副本",()->{
            var explanation=new ArrayList<>(details);explanation.add(memoryView?"将放弃所选内存结果；若磁盘也是同一副本，先清理成功才放弃内存。其它副本及赛事主档保持。":"只移除已核对的磁盘恢复副本，赛事主档和本次内存候选保持。");
            h.review("确认放弃所选原结果？",explanation,"确认放弃所选内容",()->{app.discardEventRecovery(selected,memoryView);open(app,memoryView && app.eventSavePending());});
        });
        h.text("操作前重新核对来源；失败保持原内容。没有自动恢复、自动重试或新的驾驶。",60,753,15,SOFT);
    }
}
