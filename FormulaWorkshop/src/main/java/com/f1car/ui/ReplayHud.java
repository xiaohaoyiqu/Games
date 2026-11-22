package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Playback controls and archive discovery remain separate from race progression. */
final class ReplayHud {
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xb0c6c8),MINT=new Color(0x63e6bc),AMBER=new Color(0xffb267),PANEL=new Color(14,34,46,237);
    private static final int TIMELINE_X=60,TIMELINE_WIDTH=1160;
    private static final DateTimeFormatter LIBRARY_DATE=DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private int page;
    private ReplayMarkerHud markers;
    private boolean markerView;
    private RaceReplay timelineReplay;
    private BufferedImage timelineMarkers;
    private ReplayPlayer scrubPlayer;
    private double scrubTime;
    void keepMarkers(ReplayPlayer player) {
        if(scrubPlayer!=player) endScrub();
        if(markers!=null && markers.player()!=player) {
            markers=null;
            markerView=false;
        }
        if(player==null || timelineReplay!=player.replay()) {
            timelineReplay=null;
            timelineMarkers=null;
        }
    }
    boolean reviewing(FormulaGame app) {
        return markerView && app.replayViewing() && markers.player()==app.replayPlayer();
    }
    boolean openMarkers(FormulaGame app) {
        if(!app.replayViewing() || app.helpVisible) return false;
        var player=app.replayPlayer();
        keepMarkers(player);
        endScrub();
        if(markers==null) {
            var timeline=player.preparedReviewTimeline();
            if(timeline==null) {
                if(app.replayMarkersLoading()) app.cancelReplayMarkers();
                else if(app.requestReplayMarkers()) player.pause();
                return true;
            }
            markers=new ReplayMarkerHud(player,timeline);
        } else markers.reopen();
        player.pause();
        markerView=true;
        return true;
    }
    void closeMarkers() {
        if(markers!=null && markers.closeSiteMap()) return;
        showPlayback();
    }
    private void showPlayback() {
        markerView=false;
    }
    String markerContext(FormulaGame app) {
        if(!reviewing(app)) return "";
        return markers.showingSiteMap()?":replay-recovery-sites":":replay-markers";
    }
    void markerKey(HudRenderer hud,int key) {markers.key(hud,key,this::closeMarkers,this::showPlayback);}
    boolean click(FormulaGame app,float x,float y,boolean pointer) {
        if(!app.replayViewing() || app.helpVisible || !Float.isFinite(x) || !Float.isFinite(y)
                || x<TIMELINE_X || x>TIMELINE_X+TIMELINE_WIDTH || y<660 || y>695) return false;
        var player=app.replayPlayer();
        double target=timelineTime(player,x);
        seekPlayback(player,target);
        if(pointer) {
            scrubPlayer=player;
            scrubTime=target;
        }
        return true;
    }

    boolean scrubbing() {return scrubPlayer!=null;}
    void endScrub() {scrubPlayer=null;}

    void drag(FormulaGame app,float x) {
        if(scrubPlayer==null) return;
        if(!app.replayViewing() || app.replayPlayer()!=scrubPlayer || app.helpVisible || scrubPlayer.playing()) {
            endScrub();
            return;
        }
        if(!Float.isFinite(x)) return;
        double target=timelineTime(scrubPlayer,x);
        if(target==scrubTime) return;
        scrubTime=target;
        seekPlayback(scrubPlayer,target);
    }

    private static double timelineTime(ReplayPlayer player,float x) {
        double offset=Math.max(0,Math.min(TIMELINE_WIDTH,x-TIMELINE_X));
        return offset/TIMELINE_WIDTH*player.replay().duration();
    }

    private void seekPlayback(ReplayPlayer player,double target) {
        double before=player.time();
        int previousEvent=player.currentEventIndex();
        player.seek(target);
        notifyMarkerMove(player,before,previousEvent);
    }

    private void notifyMarkerMove(ReplayPlayer player,double before,int previousEvent) {
        if(markers!=null && markers.player()==player && (before!=player.time() || previousEvent!=player.currentEventIndex()))
            markers.playbackSeeked();
    }

    void stepPlayback(ReplayPlayer player,boolean forward) {
        endScrub();
        double target=player.adjacentFrameTime(forward);
        if(target==player.time()) player.pause();
        else seekPlayback(player,target);
    }

    private void nextEvent(ReplayPlayer player) {
        endScrub();
        double before=player.time();
        int previousEvent=player.currentEventIndex();
        player.nextEvent(true);
        notifyMarkerMove(player,before,previousEvent);
    }
    void library(HudRenderer h,FormulaGame app) {
        h.panel(28,28,1224,738,PANEL);h.text("REPLAY LIBRARY",60,70,14,MINT,true);h.text("比赛视觉回看",60,116,32,INK,true);
        boolean saving=app.replayStore.saving();
        if(!saving) h.smallButton(820,62,206,43,"重读回看目录",app::reloadReplayLibrary);
        h.smallButton(1050,62,164,43,"返回",app::backFromReplays);
        h.text("短赛、短局挑战、排位与正赛自动录制。回看在游戏内播放；不改变比赛成绩。",60,155,16,SOFT);
        if(app.hasCurrentReplay()) { h.smallButton(60,177,544,41,"查看当前比赛片段",app::viewCurrentReplay);h.smallButton(628,177,586,41,"保存当前片段",app::saveCurrentReplay); }
        if(!app.replayStore.warning.isEmpty()) h.text(app.replayStore.warning,60,232,14,AMBER);
        var entries=app.replayStore.entries();int pages=Math.max(1,(entries.size()+4)/5);page=Math.min(page,pages-1);
        if(entries.isEmpty()) h.wrapped("完成一场比赛后，回看会出现在这里。也可在比赛暂停时查看当前录制。退出房间后，可查看由房主快照记录的联机片段。",60,300,1080,21,SOFT,36);
        var zone=ZoneId.systemDefault();
        for(int i=0;i<5 && page*5+i<entries.size();i++) {
            var entry=entries.get(page*5+i);int y=239+i*84;h.panel(60,y,1154,72,new Color(27,52,64));
            String date=LIBRARY_DATE.format(Instant.ofEpochMilli(entry.recordedAt()).atZone(zone));
            boolean pending=app.replayStore.isPending(entry.id());
            boolean writing=app.replayStore.isSaving(entry.id());
            boolean reading=app.replayLoading(entry.id());
            var saved=pending?app.replayStore.savedEntry(entry.id()):null;
            String status=(writing?" · 保存中":pending?" · 待存":"")+(reading?" · 读取中":"");
            h.text(shorten(entry.title()+" · "+entry.track(),saved==null?36:28),80,y+29,21,INK,true);h.text(date+" · "+PracticeSession.format(entry.duration())+" · "+end(entry.end())+" · "+source(entry.source())+status,80,y+56,14,SOFT);
            if(saved!=null && !reading) h.smallButton(748,y+17,126,38,"查看已存版",()->app.requestSavedReplay(saved));
            if(pending && !writing) h.smallButton(890,y+17,156,38,"放弃待存",()->confirmLibraryChange(h,app,
                    "放弃待存回看？",entry.title()+"\n仅放弃本次运行中的待存片段。",
                    ()->app.replayStore.isCurrentPending(entry),()->app.discardPendingReplay(entry.id())));
            else if(!saving) h.smallButton(950,y+17,96,38,"删除",()->confirmLibraryChange(h,app,
                    "删除本场回放？",entry.title()+"\n只删除这场保存的回放；比赛成绩与进度保留。",
                    ()->!app.replayStore.isPending(entry.id()) && entry.equals(app.replayStore.savedEntry(entry.id())),
                    ()->app.deleteReplay(entry.id())));
            if(reading) h.smallButton(1060,y+17,133,38,"取消读取",app::cancelReplayLoading);
            else h.smallButton(1060,y+17,133,38,pending?"查看待存":"查看",()->app.requestListedReplay(entry));
        }
        if(saving) h.text(app.replayStore.savingStatus(),60,718,16,SOFT);
        else if(app.replayStore.pendingCount()>0) h.smallButton(60,691,366,38,"回放尚未保存 · 重试",app::retryReplaySave);
        else h.text("已保存 "+entries.size()+" / 5 场",60,718,16,SOFT);
        if(!saving) {
            if(!entries.isEmpty()) h.smallButton(440,691,224,38,"清空保存的回放",()->confirmLibraryChange(h,app,
                    "清空保存的回放？","删除保存列表中的全部回放及待保存片段。\n比赛成绩、赛事进度与当前录制保留。",
                    ()->app.replayStore.entries()==entries,app::clearReplays));
            h.smallButton(680,691,248,38,"清理遗留回放文件",()->h.confirm("清理遗留回放文件？","只清理未被保存列表引用的回放数据文件。\n已保存回放、备份和其他文件保留。",app::cleanupReplayFiles));
        }
        h.smallButton(950,691,100,38,"上一页",()->changeLibraryPage(app,Math.max(0,page-1)));
        h.text((page+1)+" / "+pages,1076,718,14,SOFT);
        h.smallButton(1110,691,104,38,"下一页",()->changeLibraryPage(app,Math.min(pages-1,page+1)));
        h.text("保存列表保留最近五场；录制上限 20 分钟，达到上限会保存已有片段。",60,752,14,SOFT);
    }

    private static void confirmLibraryChange(HudRenderer h, FormulaGame app, String title, String detail,
                                            BooleanSupplier current, Runnable change) {
        h.confirm(title,detail,()->{
            if(current.getAsBoolean()) change.run();
            else app.notifyUser("回看列表已变化，请核对后重新选择。");
        });
    }

    private void changeLibraryPage(FormulaGame app,int next) {
        if(next==page) return;
        app.cancelReplayLoading();
        page=next;
    }

    void playback(HudRenderer h,Graphics2D g,FormulaGame app) {
        if(reviewing(app)) {
            markers.render(h,g,this::closeMarkers,this::showPlayback);
            return;
        }
        if(markers!=null) markers.playbackDisplayed();
        var player=app.replayPlayer();var replay=player.replay();var frame=player.frame();
        h.panel(24,22,1232,72,PANEL);h.text("视觉回看 · "+shorten(replay.title(),32),44,52,20,MINT,true);h.text(replay.track().name()+" · "+end(replay.end())+" · "+source(replay.source()),44,78,14,SOFT);
        h.smallButton(1060,36,174,40,app.eventReplayViewing()?"返回本站回顾":"返回回看列表",app::closeReplay);
        String markerButton=app.replayMarkersLoading()?"取消标记整理  M"
                :markers!=null && markers.showingSiteMap()?"返回落点图  M":"定位标记  M";
        h.smallButton(859,36,181,40,markerButton,()->openMarkers(app));
        h.panel(24,113,310,236,PANEL);int row=0;
        for(var car:player.standings()) {
            var driver=player.driver(car.id());int y=148+row++*48;
            h.text(row+"  "+shorten(driver.name(),15),44,y,18,car.id()==player.follow()?MINT:INK,true);
            String status=status(car,player);
            h.text(status+" · "+Math.round(car.speed()*3.6)+" km/h",44,y+22,13,SOFT);
        }
        appearance(h,app,player);
        if(frame.countdown()>0) { h.panel(470,120,340,66,PANEL);h.centered("起跑灯 "+(int)Math.ceil(frame.countdown()),640,161,27,AMBER); }
        h.panel(24,601,1232,172,PANEL);
        h.text(PracticeSession.format(player.time())+" / "+PracticeSession.format(replay.duration())+" · 比赛用时 "+PracticeSession.format(frame.elapsed()),60,636,19,INK,true);
        h.text("ENTER 播放／暂停 · ←/→ 逐帧 · 点击或拖动时间轴",650,636,14,SOFT);
        if(frame.time()!=player.time()) h.text("录制缺口 · 显示 "+PracticeSession.format(frame.time())+" 的原始帧",700,586,15,AMBER);
        g.setColor(new Color(0x36515c));g.fillRect(TIMELINE_X,663,TIMELINE_WIDTH,24);
        g.drawImage(timeline(replay),TIMELINE_X,659,null);
        var focused=markers==null?null:markers.focused();
        if(focused!=null) {
            int start=TIMELINE_X+timelineOffset(replay,focused.time());
            int end=TIMELINE_X+timelineOffset(replay,focused.through());
            g.setColor(AMBER);
            g.fillRect(start,690,Math.max(2,end-start),4);
            h.text(markers.focusedLabel(),60,586,15,AMBER);
        } else if(player.currentEventIndex()>=0) {
            var event=replay.events().get(player.currentEventIndex());
            int x=TIMELINE_X+timelineOffset(replay,event.time());
            g.setColor(AMBER);
            g.fillRect(x,690,2,4);
            String driver=event.driver()<0?"":" · "+shorten(player.driver(event.driver()).name(),24);
            h.text("已定位 · "+kind(event.kind())+" · "+PracticeSession.format(event.time())+driver,60,586,15,AMBER);
        } else {
            var next=player.nextEventMarker();
            if(next!=null) h.text("下个标记："+kind(next.kind())+" · "+PracticeSession.format(next.time()),60,586,15,AMBER);
        }
        g.setColor(INK);g.fillRect(TIMELINE_X-2+timelineOffset(replay,player.time()),656,5,39);
        h.smallButton(60,713,170,37,player.playing()?"暂停":"播放",player::toggle);
        h.smallButton(244,713,112,37,"上一记录帧",()->stepPlayback(player,false));
        h.smallButton(370,713,112,37,"下一记录帧",()->stepPlayback(player,true));
        h.smallButton(496,713,124,37,player.speed()+" ×",player::speedNext);h.smallButton(634,713,172,37,"切换跟随车辆",player::nextCar);h.smallButton(820,713,172,37,player.overview()?"切换跟车":"赛道总览",player::overviewToggle);h.smallButton(1006,713,214,37,"下一条标记",()->nextEvent(player));
    }

    private static String status(RaceReplay.Car car,ReplayPlayer player) {
        var replay=player.replay();
        boolean atEnd=player.time()>=replay.duration() && replay.end()==RaceReplay.End.COMPLETED;
        if(replay.qualifying()) {
            double lap=car.qualifyingTime();
            if(lap>0) return PracticeSession.format(lap);
            if(!car.connected()) return "断线 · 无有效成绩";
            if(car.retired() || car.finished() || atEnd) return "无有效成绩";
            return "计时中";
        }
        return raceStatus(car,replay.laps(),atEnd);
    }

    static String raceStatus(RaceReplay.Car car,int laps,boolean atEnd) {
        if(car.finished()) return PracticeSession.format(car.finishTime()+car.penalty())+(car.connected()?"":" · 断线");
        if(!car.connected()) return "断线";
        if(car.retired()) return "退赛";
        return atEnd?"未完赛":"第 "+Math.min(laps,car.laps()+1)+" 圈";
    }
    private BufferedImage timeline(RaceReplay replay) {
        if(timelineReplay==replay) return timelineMarkers;
        var image=new BufferedImage(TIMELINE_WIDTH+2,32,BufferedImage.TYPE_INT_ARGB);
        var columns=new Color[image.getWidth()];
        double duration = replay.duration();
        for(var event:replay.events()) {
            if (event.time() > duration) break;
            int left=timelineOffset(replay,event.time());
            var color=event.kind().interruption()?AMBER:MINT;
            for(int x=left;x<left+2;x++) {
                if(color==AMBER || columns[x]==null) columns[x]=color;
            }
        }
        var graphics=image.createGraphics();
        try {
            for(int left=0;left<columns.length;) {
                var color=columns[left];
                int right=left+1;
                while(right<columns.length && columns[right]==color) right++;
                if(color!=null) {
                    graphics.setColor(color);
                    graphics.fillRect(left,0,right-left,image.getHeight());
                }
                left=right;
            }
        }finally {
            graphics.dispose();
        }
        timelineReplay=replay;
        timelineMarkers=image;
        return image;
    }
    private static int timelineOffset(RaceReplay replay,double time) {
        return (int)Math.round(Math.min(1,Math.max(0,time/replay.duration()))*TIMELINE_WIDTH);
    }
    private static void appearance(HudRenderer h,FormulaGame app,ReplayPlayer player) {
        var value=player.driver(player.follow()).appearance();h.panel(24,361,410,value==null?96:138,PANEL);
        if(value==null) {h.text("此车未记录自制图层",44,389,17,SOFT);h.wrapped("底色、车号与纪念图案按原记录显示。",44,419,370,14,SOFT,22);return;}
        h.text("录制时涂装："+shorten(value.design().name(),16),44,389,17,MINT,true);
        String note=!value.vehicle().equals(app.vehicleSpec.id)?"车型不匹配，图层暂不显示。":value.atlas().isEmpty()?"未记录底图身份，按当前素材绘制。":value.atlas().equals(h.carAtlasIdentity())?"底图身份一致；显示录制时图层。":"底图已变化，按当前素材重绘图层。";
        if(value.design().version()==2 && value.vehicle().equals(app.vehicleSpec.id)) {int missing=value.design().unsupportedLayers(value.usableRegions(app.vehicleSpec.id,h.carAtlasIdentity(),h.carFrameSize()));note=missing>0?missing+" 个部位层缺少匹配的录制蒙版，暂不显示。":"显示录制时保存的部位范围与图层。";}
        h.wrapped(note,44,417,370,14,SOFT,22);
        h.smallButton(44,454,370,32,"查看录制时涂装原文",()->{
            player.pause();
            var design=value.design();
            var text=new java.util.ArrayList<String>();
            text.add(design.name()+"\n署名声明："+design.author()+"\n车型："+value.vehicle()+"\n"+design.notes());
            text.add("保存的作品身份："+design.id()+"\n直接来源声明："+(design.sourceId().isEmpty()?"未声明":design.sourceId())+"\n底图身份："+(value.atlas().isEmpty()?"未记录":value.atlas())+"\n部位范围："+(value.regions()==null?"未记录":value.regions().regions().size()+" 个区域已保存"));
            text.add("只读的录制时副本，不查询当前作品库、不收录或采用外观、不授予纪念奖励。底图文件未嵌入，字节指纹不证明作者或驾驶真实性。");
            for(int i=0;i<design.layers().size();i++) {
                var layer=design.layers().get(i);
                text.add("层 "+(i+1)+" · "+layer.shape().title+" / "+layer.target().title+" · #"+String.format(Locale.ROOT,"%06X",layer.rgb())+"\n位置 "+layer.x()/10d+"% / "+layer.y()/10d+"% · 大小 "+layer.width()/10d+"% × "+layer.height()/10d+"%\n旋转 "+layer.rotation()+"° · 不透明度 "+layer.opacity()+"% · 镜像 "+layer.mirror());
            }
            h.review("录制时涂装原文",text,"返回回看",()->{});
        });
    }
    private static String end(RaceReplay.End end) { return switch(end) { case COMPLETED->"活动已结束";case STOPPED->"中途结束的片段";case LIMIT->"达到录制上限";case SNAPSHOT->"当前比赛快照"; }; }
    private static String source(RaceReplay.Source source) { return switch(source) { case LOCAL->"本机车辆观察";case HOST->"房主原始快照";case RECEIVED->"收到的房主快照"; }; }
    static String kind(RaceReplay.Kind kind) { return switch(kind) { case START->"发车";case LAP->"通过终点";case RECOVERY->"复位／位置跳变";case FINISH->"完赛";case RETIRE->"退赛";case DISCONNECT->"断线";case GAP->"快照间隔"; }; }
    private static String shorten(String text,int max) { return text.codePointCount(0,text.length())>max?text.substring(0,text.offsetByCodePoints(0,max-1))+"…":text; }
}
