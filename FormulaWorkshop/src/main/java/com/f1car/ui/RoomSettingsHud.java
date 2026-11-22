package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.PracticeSession;
import com.f1car.model.QualifyingSession;
import com.f1car.network.*;
import java.awt.Color;

/** Calendar, immutable series roster, map confirmation and human team scoring. */
final class RoomSettingsHud {
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xb0c6c8),MINT=new Color(0x63e6bc),AMBER=new Color(0xffb267),PANEL=new Color(0x19313e);
    void render(HudRenderer h,FormulaGame app) {
        h.panel(28,28,1224,738,new Color(0x13242e));
        h.text("房间地图与赛事",60,88,32,INK,true);
        h.smallButton(1040,53,176,42,"返回联机房间",app::openRaceRoom);
        var rules=app.roomRules();if(rules==null) { h.text("正在同步房间…",60,170,20,SOFT);return; }
        var room=app.raceRoom();boolean edit=app.roomRulesEditable();
        h.text(edit?"房主修改配置后，所有车手需要重新确认与准备。":"当前系列已固定规则、阵容与性能配置。",60,123,16,SOFT);
        if(edit) {
            h.smallButton(60,148,250,44,rules.format().title+" · 切换",app::cycleRoomFormat);
            h.smallButton(326,148,176,44,"正赛 "+rules.laps()+" 圈",app::cycleRoomLaps);
            h.smallButton(518,148,302,44,rules.vehicles().title+" · 切换",app::cycleRoomVehicleRule);
        } else h.text(rules.format().title+" · 正赛 "+rules.laps()+" 圈 · "+rules.vehicles().title,60,179,22,MINT,true);
        for(int i=0;i<rules.calendar().size();i++) {
            var map=rules.calendar().get(i);int y=216+i*91,slot=i;
            h.panel(60,y,760,76,PANEL);
            h.text("第 "+(i+1)+" 站  "+shorten(map.name(),26),80,y+29,20,INK,true);
            h.text(map.document()==null?"内置赛道":"房主自制地图 · 加入后临时使用，地图库保留原样",80,y+57,14,SOFT);
            if(edit) h.smallButton(684,y+18,116,39,"换图",()->app.cycleRoomMap(slot));
        }
        if(edit) h.smallButton(60,520,358,42,rules.teams()?"两对两车队积分 · 开启":"个人积分 · 切换车队",app::toggleRoomTeams);
        else h.text(rules.teams()?"两对两车队积分":"个人积分",60,548,20,MINT,true);
        h.wrapped(rules.vehicles()==RaceSession.VehicleRule.HOST_SETUP?"统一性能：动力 "+Math.round(rules.fixed().power()*100)+"% / 制动 "+Math.round(rules.fixed().brakes()*100)+"% / 悬挂 "+Math.round(rules.fixed().suspension()*100)+"% / 尾翼 "+Math.round(rules.fixed().wing()*100)+"%。保留个人涂装。":"有效完赛按 10 / 6 / 4 / 2 计分，退赛 0 分；同分依次比较完赛名次与排位名次，仍相同则并列。",60,594,760,16,SOFT,27);
        h.wrapped("周末与巡回杯：练习 → 单圈排位 → 正赛。断线车手在后续阶段退赛，至少两名原阵容车手连接才能继续。系列进度仅保留在当前房间。",60,680,760,15,SOFT,27);
        h.text("车手 / 车队",858,178,22,MINT,true);
        if(room==null) h.wrapped("建房后可分配车队；两对两需要四名车手，每队两人。",858,226,342,17,SOFT,30);
        else {
            int n=0;
            for(var member:room.members()) {
                int y=213+n++*67;String label=shorten(member.name(),13)+" · "+(member.team()==0?"蓝队":"橙队");
                if(edit && room.host()) h.smallButton(858,y,342,44,label,()->room.team(member.id(),1-member.team()));
                else h.text(label,858,y+30,18,INK);
            }
            var program=room.program();
            if(program!=null) {
                h.text("第 "+(program.state().round()+1)+" 站 · "+program.stageName(),858,517,19,MINT,true);
                var standings=rules.teams()?program.teams():program.standings();
                for(int i=0;i<standings.size();i++) {
                    var row=standings.get(i);int rank=i+1;while(rank>1 && RoomProgram.compare(standings.get(rank-2),row)==0) rank--;
                    String name=rules.teams()?(row.id()==0?"蓝队":"橙队"):program.state().drivers().stream().filter(d->d.id()==row.id()).findFirst().orElseThrow().name();
                    h.text(rank+"  "+shorten(name,11)+"  "+row.points()+" 分",858,551+i*31,16,INK);
                }
                if(room.host() && room.frame()==null && !program.complete()) h.smallButton(858,700,342,36,"结束当前系列",()->h.prompt("输入 END 结束系列；当前房间积分将清除","",value->{if(value.strip().equals("END")) room.resetProgram();}));
            }
            boolean practice=program==null || program.complete() || program.state().phase()==RoomProgram.Phase.PRACTICE;
            if(room.host() && room.frame()==null && practice && (rules.format()==RoomRules.Format.WEEKEND || rules.format()==RoomRules.Format.TOUR))
                h.smallButton(438,520,382,42,"跳过练习，进入排位（需已准备）",room::skipPractice);
        }
    }
    void qualifying(HudRenderer h,FormulaGame app) {
        h.panel(25,100,420,241,PANEL);
        h.text("单圈排位 · 剩余 "+Math.max(0,(int)Math.ceil(QualifyingSession.LIMIT-app.sessionTime))+" 秒",45,135,21,MINT,true);
        h.text(app.drivingHint("一圈机会；R 或退赛记为无成绩"),45,164,15,SOFT);
        var frame=app.raceRoom().frame();if(frame==null) return;
        var standings=frame.standings();
        for(int i=0;i<standings.size();i++) {
            var car=standings.get(i);
            double lap=car.qualifyingTime();
            String time=lap>0?PracticeSession.format(lap):car.retired() || car.progress().finished()?"无成绩":"进行中";
            h.text((i+1)+"  "+shorten(car.name(),10)+"  "+time,45,202+i*32,16,car.id()==app.raceRoom().localId()?MINT:INK);
        }
    }
    void qualifyingResult(HudRenderer h,FormulaGame app,int y) {
        var frame=app.raceRoom().frame();if(frame==null) return;
        var standings=frame.standings();
        for(int i=0;i<standings.size();i++) {
            var car=standings.get(i);h.text((i+1)+"   "+shorten(car.name(),18),320,y+i*49,20,car.id()==app.raceRoom().localId()?MINT:INK);
            double lap=car.qualifyingTime();
            h.text(lap>0?PracticeSession.format(lap):"无有效成绩",760,y+i*49,19,INK);
        }
    }
    private static String shorten(String name,int max) { return name.length()>max?name.substring(0,max-1)+"…":name; }
}
