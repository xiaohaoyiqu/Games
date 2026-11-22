package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.*;

/** Explicit editing of a new cup; the accepted running calendar is never edited in place. */
final class EventCalendarHud {
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),ORANGE=new Color(255,169,114);
    private static final String[] DIFFICULTIES={"轻松","标准","挑战"};
    private final java.util.List<EventSeries.Round> draft=new ArrayList<>();
    private int selected,difficulty,laps=3;
    void begin(FormulaGame app) {
        app.refreshCalendarTrackList();
        if(draft.isEmpty()) copyCurrent(app);
    }
    private void copyCurrent(FormulaGame app) {
        draft.clear();selected=0;var event=app.activeEvent();
        if(event==null) { draft.add(round(app.circuit));difficulty=app.store.profile.difficulty;laps=app.store.profile.raceLaps; }
        else { draft.addAll(event.state().calendar());difficulty=event.state().difficulty();laps=event.state().laps(); }
    }
    private EventSeries.Round round(TrackLayout track) { return new EventSeries.Round(track.id,track.geometryKey()); }
    void render(HudRenderer h,FormulaGame app,Runnable created) {
        if(draft.isEmpty()) copyCurrent(app);selected=Math.max(0,Math.min(selected,draft.size()-1));
        var chosen=draft.get(selected);
        var chosenEntry=app.availableCircuitEntry(chosen.trackId());
        h.text("自选积分杯 · 一至八站",70,269,26,MINT,true);h.text("每站可跳过练习，排位后跑正赛；允许重复地图与自定顺序。",72,302,16,MUTED);
        h.smallButton(930,238,276,39,"从当前赛事重新复制",()->copyCurrent(app));
        h.panel(70,322,600,330,new Color(24,49,60));h.panel(692,322,514,330,new Color(24,49,60));
        for(int i=0;i<draft.size();i++) {
            int index=i;
            var round=draft.get(i);
            var entry=selected==i?chosenEntry:app.availableCircuitEntry(round.trackId());
            h.smallButton(85,334+i*38,570,33,(selected==i?"● ":"")+(i+1)+"  "+name(round,entry,29),()->selected=index);
        }
        h.text("所选第 "+(selected+1)+" 站",712,355,20,INK,true);
        h.smallButton(712,374,474,42,"切换地图："+name(chosen,chosenEntry,19),()->{
            app.refreshCalendarTrackList();
            var tracks=app.availableTracks();
            int index=-1;
            for(int i=0;i<tracks.size();i++) if(tracks.get(i).id().equals(chosen.trackId())) index=i;
            draft.set(selected,round(tracks.get((index+1)%tracks.size()).layout()));
        });
        h.smallButton(712,429,474,38,"更新为此地图的当前修订",()->{
            if(!app.refreshCalendarTrackList()) return;
            var current=app.availableCircuitEntry(chosen.trackId());
            if(current!=null) draft.set(selected,round(current.layout()));
            else app.notifyUser("此图缺失或未通过当前四车验证，请切换地图。");
        });
        h.smallButton(712,482,226,42,"难度："+DIFFICULTIES[difficulty],()->difficulty=(difficulty+1)%3);
        h.smallButton(954,482,232,42,"每站正赛："+laps+" 圈",()->laps=laps==1?3:laps==3?5:1);
        h.text("创建时固定当前车库性能：",712,562,16,MUTED);h.text(app.store.profile.tuning.performanceKey(),712,592,21,INK,true);
        h.text("地图列表包括通过当前四车验证的自制图。",712,629,14,MUTED);
        h.smallButton(70,670,137,40,"复制所选站",()->{if(draft.size()<8) { draft.add(selected+1,draft.get(selected));selected++; }else app.notifyUser("一场自选杯最多八站。");});
        h.smallButton(220,670,137,40,"删除所选站",()->{if(draft.size()>1) { draft.remove(selected);selected=Math.min(selected,draft.size()-1); }});
        h.smallButton(370,670,137,40,"向前移动",()->move(-1));h.smallButton(520,670,137,40,"向后移动",()->move(1));
        h.smallButton(850,670,356,40,"预览并开始这份赛历",()->{
            var calendar=java.util.List.copyOf(draft);int frozenDifficulty=difficulty,frozenLaps=laps;
            EventHud.confirmReplacement(h,app,()->app.prepareCustomCalendar(calendar,frozenDifficulty,frozenLaps,()->{draft.clear();created.run();}));
        });
        h.text("这里只编辑新赛历；当前赛事不会随草稿变化。共用一个赛事槽，旧图修订需明确更新。",72,742,14,ORANGE);
    }
    private void move(int direction) { int target=selected+direction;if(target>=0 && target<draft.size()) { Collections.swap(draft,selected,target);selected=target; } }
    private String shorten(String value,int maximum) { return value.length()<=maximum?value:value.substring(0,maximum-1)+"…"; }
    private String name(EventSeries.Round round,TrackCatalog.Entry entry,int maximum) {
        String title=entry==null?round.trackId():entry.layout().name;
        String suffix=entry==null?"（不可用）":entry.layout().geometryKey().equals(round.geometry())?"":"（修订已变）";
        return shorten(title,maximum-suffix.length())+suffix;
    }
}
