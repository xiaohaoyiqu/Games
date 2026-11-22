package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.network.RaceRoom;
import com.f1car.network.RaceSession;
import java.awt.Color;

/** The primary game's lobby, separate from the Mini 4WD side activity. */
final class RaceRoomHud {
    private static final Color LIGHT = new Color(0xeaf4ef), SOFT = new Color(0xb0c6c8), MINT = new Color(0x63e6bc), AMBER = new Color(0xffb267);
    private RaceRoom.TransferRate displayedRate;
    private boolean displayedHost;
    private String rateText;
    private RaceRoom.LinkTiming displayedTiming;
    private String latencyText;

    String timingText(RaceRoom.LinkTiming timing) {
        if (timing == null) return "房间往返延迟暂不可用";
        if (!timing.equals(displayedTiming)) {
            displayedTiming = timing;
            latencyText = "房间往返延迟约 " + timing.roundTripMs() + " ms";
            if (timing.variationMs() >= 0) latencyText += "  ·  波动约 " + timing.variationMs() + " ms";
        }
        return latencyText;
    }

    void drawTiming(HudRenderer ui, RaceRoom room, int x, int y, int size) {
        if (!room.host()) ui.text(timingText(room.linkTiming()),x,y,size,SOFT);
    }

    void drawTransferRate(HudRenderer ui, RaceRoom room, int x, int y) {
        var rate = room.transferRate();
        if (rate == null) return;
        if (!rate.equals(displayedRate) || displayedHost != room.host()) {
            displayedRate = rate;
            displayedHost = room.host();
            rateText = (displayedHost ? "房间合计" : "房间流量") + "  收 " + rate.receivedBytesPerSecond()
                    + " B/s  ·  发 " + rate.sentBytesPerSecond() + " B/s";
        }
        ui.text(rateText,x,y,14,SOFT);
    }

    void render(HudRenderer ui, FormulaGame app) {
        ui.panel(30,28,1220,738,new Color(0x13242e));
        ui.text("FORMULA / ONLINE",62,69,17,MINT,true);
        ui.text("方程式同场驾驶",62,121,36,LIGHT,true);
        ui.text("驾驶正常赛车，与朋友一起比赛或自由练习。",64,157,17,SOFT);
        ui.smallButton(1050,67,164,43,"返回模式选择",()->ui.navigate(()->{app.leaveRaceRoom();app.openStartMenu();}));
        RaceRoom room = app.raceRoom();
        ui.panel(62,193,730,468,new Color(0x1c343f));
        if (room == null) {
            ui.text("创建房间，邀请朋友加入",90,239,26,LIGHT,true);
            ui.wrapped("同一房间支持 2–4 名真实玩家。可选短赛、共同练习、赛事周末或三站积分杯；支持内置与已试跑的自制赛道。",90,296,660,19,LIGHT,33);
            ui.wrapped("油门、制动、转向和复位使用原有驾驶操作。车辆碰撞、计圈、成绩与罚时由房主统一计算。",90,425,660,17,SOFT,31);
            ui.smallButton(90,588,678,42,"地图、赛历与车辆规则",()->app.open(FormulaGame.Screen.RACE_SETUP));
            ui.text("车手昵称",842,221,16,SOFT);
            ui.smallButton(842,239,356,43,app.store.profile.playerName,() -> ui.editText("车手昵称",app.store.profile.playerName,"车手",20,value->{if(value.isBlank()) throw new IllegalArgumentException();app.racePlayerName(value);}));
            settings(ui,app,309,true);
            ui.smallButton(842,443,356,49,"创建方程式房间",() -> ui.prompt("TCP 端口","26951",value -> {
                try { int port = Integer.parseInt(value.strip()); if (port < 1 || port > 65535) throw new IllegalArgumentException(); app.hostRaceRoom(port); }
                catch (IllegalArgumentException error) { app.notifyUser("端口应在 1–65535 之间。"); }
            }));
            ui.smallButton(842,509,356,49,"加入方程式房间",() -> ui.prompt("房主地址 IP:端口","127.0.0.1:26951",app::joinRaceRoom));
            ui.smallButton(842,592,356,43,"查看车库配置",() -> app.open(FormulaGame.Screen.GARAGE));
        } else {
            if (room.host()) {
                var addresses = room.addresses();
                String heading = addresses.get(0).startsWith("127.") ? "房主 · 仅找到本机地址" : "房主 · 局域网候选地址";
                ui.text(heading,90,234,22,MINT,true);
                ui.smallButton(616,205,148,35,"刷新地址",room::refreshAddresses);
                for (int i = 0; i < Math.min(3, addresses.size()); i++)
                    ui.text(addresses.get(i),90,267 + i * 22,16,LIGHT);
            } else {
                ui.text("车手 · " + room.status(),90,234,22,MINT,true);
                drawTiming(ui,room,90,272,16);
            }
            drawTransferRate(ui,room,room.host() ? 360 : 90,300);
            int row = 0;
            var members=room.members();
            var settings=room.settings();
            RaceRoom.Member localMember = null;
            for (var member : members) {
                if (member.id() == room.localId()) localMember = member;
                var tuning=settings.apply(member.tuning());
                int y = 345 + row++ * 69;
                ui.panel(88,y - 26,678,56,new Color(0x263e48));
                ui.text(member.name() + (member.id() == room.localId() ? "  ·  你" : ""),106,y,19,member.id() == room.localId() ? MINT : LIGHT,true);
                ui.text("本场动力 " + Math.round(tuning.power()*100) + "%  制动 " + Math.round(tuning.brakes()*100) + "%  涂装 " + (tuning.livery()+1),106,y+22,13,SOFT);
                ui.text(!member.acknowledged()?"校验中":member.ready()?"已准备":"未准备",660,y+9,16,member.ready()?MINT:AMBER);
            }
            ui.text(members.size() + " / 4 位车手",842,222,23,MINT,true);
            settings(ui,app,276,app.roomRulesEditable());
            if (room.frame() == null) {
                if (!room.host()) {
                    if (localMember != null && localMember.acknowledged()) {
                        boolean wasReady = localMember.ready();
                        long reviewedRevision = room.revision();
                        ui.smallButton(842,417,356,45,wasReady ? "取消准备" : "准备",() -> {
                            if (!room.ready(!wasReady, reviewedRevision)) app.notifyUser("房间状态已变化，请查看当前设置后再操作。");
                        });
                    } else ui.text("等待房主确认房间设置",842,449,17,SOFT);
                }
                else if (room.canStart()) ui.smallButton(842,417,356,45,"开始 · "+room.stageName(),app::confirmRoomStart);
                else ui.text(room.rules().teams() && (room.program()==null || room.program().complete())?"车队赛需四人、每队两人并准备":"等待至少两名车手确认并准备",842,449,17,SOFT);
                if(room.editable()) ui.smallButton(842,484,356,43,"车库与改装",() -> app.open(FormulaGame.Screen.GARAGE));
                else ui.text("系列已固定阵容与车辆配置",842,510,16,SOFT);
            } else if (room.host()) ui.smallButton(842,417,356,45,"结束活动并返回房间",app::returnToRaceRoom);
            else ui.wrapped("等待房主结束本局。所有人返回房间后可以再次准备。",842,433,356,17,SOFT,29);
            ui.smallButton(842,538,356,36,"地图、赛历与系列积分",()->app.open(FormulaGame.Screen.RACE_SETUP));
            if(room.rules()!=null) { ui.text("赛道："+shortName(room.track().name),90,614,18,MINT);ui.text(room.stageName()+" · "+room.rules().vehicles().title+(room.rules().teams()?" · 两队计分":""),90,642,15,SOFT); }
            ui.smallButton(842,590,356,43,"退出方程式房间",app::leaveRaceRoom);
        }
        ui.text("局域网使用房主局域网地址；跨网络需要房主 TCP 端口可达。默认端口 26951。",64,706,15,SOFT);
        ui.text("同场活动中，暂停菜单只停止你的输入，其他车辆继续驾驶。",64,738,15,AMBER);
    }
    private void settings(HudRenderer ui, FormulaGame app, int y, boolean editable) {
        var settings = app.raceRoomSettings();
        if (settings == null) { ui.text("正在同步活动设置…",842,y,17,SOFT); return; }
        String activity=app.roomRules().format().title;
        if (editable) {
            ui.smallButton(842,y,356,42,activity + "  ·  切换",app::cycleRaceActivity);
            ui.smallButton(842,y+54,356,42,"短赛 " + settings.laps() + " 圈  ·  切换",app::cycleRoomLaps);
        } else {
            ui.text("活动："+activity,842,y+23,20,LIGHT,true);
            ui.text((app.raceRoom()!=null?app.raceRoom().stageName():"")+" · 正赛 "+app.roomRules().laps()+" 圈",842,y+76,18,SOFT);
        }
    }
    private static String shortName(String name) { return name.length()>25?name.substring(0,24)+"…":name; }
}
