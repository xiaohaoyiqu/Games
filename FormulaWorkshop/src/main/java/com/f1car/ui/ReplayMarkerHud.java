package com.f1car.ui;

import com.f1car.model.PracticeSession;
import com.f1car.model.RaceReplay;
import com.f1car.model.ReplayPlayer;
import com.f1car.model.ReplayReviewTimeline;
import com.jme3.input.KeyInput;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

final class ReplayMarkerHud {
    private static final int ROWS=6;
    private static final Color PANEL=new Color(0x162b38),ROW=new Color(0x254453),SELECTED=new Color(0x345e62);
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xb5c9cc),MINT=new Color(0x63e6bc);
    private final ReplayPlayer player;
    private final ReplayReviewTimeline timeline;
    private final List<ReplayReviewTimeline.Filter> filters;
    private enum Jump { BEFORE, START, END }
    private ReplayReviewTimeline.Filter filter=ReplayReviewTimeline.Filter.ALL;
    private int driverSlot,selected;
    private List<ReplayReviewTimeline.Mark> marks;
    private ReplayReviewTimeline.Mark preferred;
    private double anchor;
    private ReplayReviewTimeline.Mark focused;
    private ReplayRecoverySiteMap.Scene focusedScene;
    private ReplayRecoverySiteMap siteMap;
    private boolean reanchor;
    private double lastPlaybackTime;
    private int lastPlaybackEvent;

    ReplayMarkerHud(ReplayPlayer player,ReplayReviewTimeline timeline) {
        this.player=player;
        this.timeline=timeline;
        var available=new ArrayList<>(List.of(ReplayReviewTimeline.Filter.ALL,ReplayReviewTimeline.Filter.INTERRUPTIONS));
        if(timeline.nearStopsAvailable()) available.add(ReplayReviewTimeline.Filter.NEAR_STOPS);
        if(timeline.recoveryWaitsAvailable()) available.add(ReplayReviewTimeline.Filter.RECOVERY_WAITS);
        filters=List.copyOf(available);
        anchorToPlayback();
    }

    ReplayPlayer player() {return player;}
    ReplayReviewTimeline.Mark focused() {return focused;}
    boolean showingSiteMap() {return siteMap!=null;}

    void playbackSeeked() {
        clearFocus();
        reanchor=true;
    }

    private void clearFocus() {
        focused=null;
        focusedScene=null;
        siteMap=null;
    }

    void reopen() {
        if(!reanchor && !playbackMoved()) return;
        clearFocus();
        anchorToPlayback();
        reanchor=false;
    }

    void playbackDisplayed() {
        if(focusedScene!=null && playbackMoved()) playbackSeeked();
    }

    private boolean playbackMoved() {
        return player.time()!=lastPlaybackTime || player.currentEventIndex()!=lastPlaybackEvent;
    }

    private void rememberPlayback() {
        lastPlaybackTime=player.time();
        lastPlaybackEvent=player.currentEventIndex();
    }

    boolean closeSiteMap() {
        if(siteMap==null) return false;
        siteMap=null;
        return true;
    }

    String focusedLabel() {
        if(focusedScene!=null) {
            var scene=focusedScene;
            String observation=scene.phase().label+" · "+PracticeSession.format(scene.observationTime());
            String frame=scene.frameTime()==scene.observationTime()?"":" · 较早画面 "+PracticeSession.format(scene.frameTime());
            String driver=scene.phase()==ReplayRecoverySiteMap.Phase.RECOVERED?"复位车辆 ":scene.driver()==focused.driver()?"等待车辆 ":"排除车辆 ";
            return observation+frame+" · "+driver+driverLabel(scene.driver());
        }
        return "已选标记 · "+markLabel(focused)+" · "+markTime(focused)
                +(focused.driver()==-1?"":" · "+driverLabel(focused.driver()));
    }

    void render(HudRenderer hud,Graphics2D graphics,Runnable back,Runnable showPlayback) {
        if(siteMap!=null) {
            siteMap.render(hud,graphics,back,scene->jumpFromSite(hud,showPlayback,scene));
            return;
        }
        hud.panel(28,28,1224,738,PANEL);
        hud.text("回看标记",60,86,30,INK,true);
        hud.text("按车辆查看记录。选定一条后可定位，或从事件前最多两秒开始播放。",60,122,16,SOFT);
        hud.smallButton(1020,56,194,40,"返回画面",back);
        for(int i=0;i<filters.size();i++) {
            var option=filters.get(i);
            hud.smallButton(60+i*150,148,138,42,(option==filter?"● ":"")+filterLabel(option),()->selectFilter(option));
        }
        hud.smallButton(665,148,549,42,"车辆 · "+driverLabel(driverId())+"  Tab",this::nextDriver);
        String evidence=timeline.nearStopsAvailable()?"近停来自连续样本（≤ 1 m/s，≥ 0.3 秒）":"此片段显示原有时间标记";
        if(timeline.recoveryWaitsAvailable()) evidence="复位等待来自落点搜索 · "+evidence;
        else if(timeline.nearStopsAvailable()) evidence+=" · 此回看未记录落点搜索";
        hud.text(evidence,60,221,15,SOFT);
        int first=selected/ROWS*ROWS;
        if(marks.isEmpty()) hud.text("此筛选下没有记录。",80,301,22,SOFT);
        for(int index=first;index<Math.min(first+ROWS,marks.size());index++) {
            var entry=marks.get(index);
            int y=242+(index-first)*52;
            int target=index;
            hud.panel(60,y,1154,44,index==selected?SELECTED:ROW);
            hud.text(markTime(entry),78,y+29,16,INK,true);
            int titleY=y+(entry.waitEnd()==null?29:18);
            hud.text(markLabel(entry)+" · "+(entry.driver()==-1?"全场":driverLabel(entry.driver())),354,titleY,17,INK);
            if(entry.waitEnd()!=null) renderSearchEvidence(hud,entry.waitEvidence(),y+37);
            hud.smallButton(1022,y+4,178,36,index==selected?"● 已选":"选择",()->select(target));
        }
        int pages=Math.max(1,(marks.size()+ROWS-1)/ROWS);
        if(pages>1) {
            hud.smallButton(60,583,141,37,"上一页",()->move(-ROWS));
            hud.smallButton(216,583,141,37,"下一页",()->move(ROWS));
        }
        hud.text((first/ROWS+1)+" / "+pages+" 页 · "+marks.size()+" 条",380,609,15,SOFT);
        if(!marks.isEmpty()) {
            var entry=marks.get(selected);
            boolean beyondLastFrame = beyondLastFrame(entry);
            if(entry.waitLocations()!=null) hud.smallButton(60,642,192,43,"查看落点图  M",this::openSiteMap);
            else if(entry.waitEnd()!=null) hud.text("此等待未保存落点图",60,670,15,SOFT);
            if(entry.through()>entry.time())
                hud.smallButton(272,642,301,43,"定位区间结束  End",()->jump(hud,back,Jump.END));
            hud.smallButton(591,642,299,43,beyondLastFrame?"查看末帧  Enter":"定格在标记  Enter",()->jump(hud,back,Jump.START));
            hud.smallButton(906,642,308,43,beyondLastFrame?"从末帧前播放  Space":"从标记前播放  Space",()->jump(hud,back,Jump.BEFORE));
        }
        hud.text("↑ ↓ 选标记 · PgUp/PgDn 翻页 · ← → 换类型 · Esc 返回",60,727,15,SOFT);
    }

    private void openSiteMap() {
        if(marks.isEmpty()) return;
        var entry=marks.get(selected);
        if(entry.waitLocations()!=null) {
            select(selected);
            siteMap=new ReplayRecoverySiteMap(player.track(),timeline,entry,this::driverLabel);
        }
    }

    private static void renderSearchEvidence(HudRenderer hud,RaceReplay.RecoverySearchEvidence evidence,int y) {
        if(evidence==null) {
            hud.text("此记录未保存搜索原因",354,y,14,SOFT);
            return;
        }
        hud.text("几何无候选 "+evidence.staticBlocked()+" 次",354,y,14,SOFT);
        hud.text("交通预测 "+evidence.predictedTraffic()+" 次",584,y,14,SOFT);
        hud.text("读数不全 "+evidence.incompleteTraffic()+" 次",796,y,14,SOFT);
    }

    void key(HudRenderer hud,int key,Runnable back,Runnable showPlayback) {
        if(siteMap!=null) {
            siteMap.key(key,back,scene->jumpFromSite(hud,showPlayback,scene));
            return;
        }
        switch(key) {
            case KeyInput.KEY_ESCAPE -> back.run();
            case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> jump(hud,back,Jump.START);
            case KeyInput.KEY_SPACE -> jump(hud,back,Jump.BEFORE);
            case KeyInput.KEY_END -> jump(hud,back,Jump.END);
            case KeyInput.KEY_M -> openSiteMap();
            case KeyInput.KEY_UP -> move(-1);
            case KeyInput.KEY_DOWN -> move(1);
            case KeyInput.KEY_PGUP -> move(-ROWS);
            case KeyInput.KEY_PGDN -> move(ROWS);
            case KeyInput.KEY_LEFT -> moveFilter(-1);
            case KeyInput.KEY_RIGHT -> moveFilter(1);
            case KeyInput.KEY_TAB -> nextDriver();
            default -> {}
        }
    }

    private void jump(HudRenderer hud,Runnable back,Jump position) {
        if(marks.isEmpty()) return;
        var entry=marks.get(selected);
        if(position==Jump.END && entry.through()<=entry.time()) return;
        select(selected);
        double target=switch(position) {
            case BEFORE -> timeline.leadIn(entry.time());
            case START -> entry.time();
            case END -> entry.through();
        };
        int eventIndex=position==Jump.START?timeline.recordedEventIndex(entry):-1;
        if(eventIndex>=0 && target<=player.replay().duration()) player.seekEvent(eventIndex,true);
        else player.seekReview(target,entry.driver());
        if(position==Jump.BEFORE && player.time()<player.replay().duration()) player.toggle();
        rememberPlayback();
        focused=entry;
        focusedScene=null;
        hud.resetCamera();
        back.run();
    }

    private void jumpFromSite(HudRenderer hud,Runnable showPlayback,ReplayRecoverySiteMap.Scene scene) {
        player.seekReview(scene.frameTime(),scene.driver());
        rememberPlayback();
        focused=marks.get(selected);
        focusedScene=scene;
        hud.resetCamera();
        showPlayback.run();
    }

    private void selectFilter(ReplayReviewTimeline.Filter option) {
        if(option==filter) return;
        filter=option;
        refresh();
    }

    private void moveFilter(int delta) {
        selectFilter(filters.get(Math.floorMod(filters.indexOf(filter)+delta,filters.size())));
    }

    private void nextDriver() {
        driverSlot=(driverSlot+1)%(player.replay().drivers().size()+1);
        refresh();
    }

    private void move(int delta) {
        int index=Math.max(0,Math.min(marks.size()-1,selected+delta));
        if(index!=selected) select(index);
    }

    private void select(int index) {
        selected=index;
        preferred=marks.get(index);
        anchor=preferred.time();
    }

    private void anchorToPlayback() {
        rememberPlayback();
        preferred=lastPlaybackEvent>=0?timeline.recordedMark(lastPlaybackEvent):null;
        anchor=lastPlaybackTime;
        refresh();
    }

    private void refresh() {
        marks=timeline.marks(filter,driverId());
        selected=Math.max(0,timeline.selectionIndex(filter,driverId(),anchor,preferred));
    }

    private int driverId() {return driverSlot==0?-1:player.replay().drivers().get(driverSlot-1).id();}

    private String driverLabel(int id) {
        if(id==-1) return "全部车辆";
        var name=player.driver(id).name();
        return name.codePointCount(0,name.length())<=24?name:name.substring(0,name.offsetByCodePoints(0,23))+"…";
    }

    private static String markTime(ReplayReviewTimeline.Mark entry) {
        String start=PracticeSession.format(entry.time());
        return entry.through()>entry.time()?start+" → "+PracticeSession.format(entry.through()):start;
    }

    private boolean beyondLastFrame(ReplayReviewTimeline.Mark entry) { return entry.time() > player.replay().duration(); }

    private String markLabel(ReplayReviewTimeline.Mark entry) {
        if(entry.waitEnd()!=null) return switch(entry.waitEnd()) {
            case RECOVERED -> "复位等待 · 已复位";
            case REQUEST_CLEARED -> "复位等待 · 请求解除";
            case LAST_OBSERVATION -> "复位等待 · 最后一次仍在等待";
        };
        if(entry.nearStop()) return "近停片段";
        String label = ReplayHud.kind(entry.kind());
        return beyondLastFrame(entry) ? label + " · 晚于末帧" : label;
    }

    private static String filterLabel(ReplayReviewTimeline.Filter value) {
        return switch(value) {
            case ALL -> "全部记录";
            case INTERRUPTIONS -> "复位与中断";
            case NEAR_STOPS -> "近停片段";
            case RECOVERY_WAITS -> "复位等待";
        };
    }
}
