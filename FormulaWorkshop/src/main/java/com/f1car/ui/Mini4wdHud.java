package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.input.KeyboardSettings.Command;
import com.f1car.mini4wd.Mini4wdGame;
import com.f1car.mini4wd.MiniTrack;
import com.f1car.mini4wd.MiniTrack.Kind;
import com.f1car.mini4wd.MiniTrack.Piece;
import com.f1car.mini4wd.MiniTrack.Segment;
import com.f1car.mini4wd.MiniTrial;
import com.f1car.mini4wd.MiniTrial.Result;
import com.f1car.mini4wd.MiniShape;
import com.f1car.mini4wd.MiniRace;
import com.f1car.mini4wd.MiniRoom;
import com.f1car.mini4wd.MiniTrack.Point;
import com.f1car.mini4wd.MiniTrack.Rail;
import com.f1car.model.PracticeSession;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Set;

/** Pixel-scale drawing and controls for the Mini 4WD workbench. */
final class Mini4wdHud {
    static final int BOARD_X = 48, BOARD_Y = 178, TILE = 52;
    private static final int SIDE_X = 912, CONTENT_X = 936;
    private static final Color BACKGROUND = new Color(0x13242e), SIDEBAR = new Color(0x1c343f);
    private static final Color LIGHT = new Color(0xeaf4ef), SOFT = new Color(0xb0c6c8);
    private static final Color TEAL = new Color(0x63e6bc), AMBER = new Color(0xffb267);
    private static final Color TILE_A = new Color(0x273e48), TILE_B = new Color(0x2a424b);
    private static final Color RAIL = new Color(0xc7c9b1), ROAD = new Color(0x596365);
    private static final Color EDGE = new Color(0x253039), RAMP = new Color(0x76c0a8);
    private static final Color[] CAR_COLORS = {TEAL, new Color(0xffb267), new Color(0x96baff), new Color(0xeb92cd)};
    private boolean multiLane;
    private record BoardImage(MiniTrack track,boolean lanes,Set<Integer> selected,BufferedImage raster) {}
    private BoardImage boardImage;
    private int pendingRecordIndex;
    private final MiniAssemblyHud assemblyHud = new MiniAssemblyHud();

    void clearBoardImage() {boardImage=null;}

    String navigationContext(Mini4wdGame game) {
        return game.view()==Mini4wdGame.View.GARAGE ? "GARAGE:"+assemblyHud.navigationContext(game) : game.view().name();
    }

    void back(Mini4wdGame game) {
        if(game.view()!=Mini4wdGame.View.GARAGE || !assemblyHud.closeChooser(game)) game.back();
    }

    static int cellAt(float x, float y) {
        if (x < BOARD_X || y < BOARD_Y) return -1;
        return MiniTrack.cell((int) ((x - BOARD_X) / TILE), (int) ((y - BOARD_Y) / TILE));
    }

    void render(HudRenderer ui, Graphics2D g, FormulaGame app, float mouseX, float mouseY) {
        var game = app.mini4wd();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, 1280, 800);
        ui.text("四驱车小游戏", 48, 56, 22, TEAL, true);
        ui.text(game.view() == Mini4wdGame.View.GARAGE ? "迷你四驱车组装台" : shortText(game.activeName(), 18), 48, 116, 36, LIGHT, true);
        String subtitle = switch (game.view()) {
            case MAPS -> game.description();
            case EDITOR -> "滚轮缩放 · 中键平移 · Ctrl 多选，端点自动吸附。";
            case TRIAL -> game.inspection().closed() ? "三圈计时 · 四驱车自动沿轨行驶" : "单程计时 · 从绿色起点跑到橙色终点";
            case GARAGE -> "亲手安装底盘、传动、马达和四轮，检查后放上轨道。";
            case ROOM -> game.roomActive() ? game.room().status() : "为当前地图建房，或输入房主地址加入。";
        };
        ui.text(subtitle, 49, 149, 16, SOFT);
        if (game.view() == Mini4wdGame.View.MAPS) ui.smallButton(1074, 34, 158, 39, "返回主菜单", app::openMiniHome);
        else ui.smallButton(1074, 34, 158, 39, "返回  Esc", ()->back(game));
        if (game.view() == Mini4wdGame.View.EDITOR) {
            ui.smallButton(SIDE_X, 106, 150, 43, "保存地图", game::save);
            trialButton(ui, game, SIDE_X + 164, 106, 156, 43, "试跑  Enter");
            ui.smallButton(SIDE_X, 150, 320, 25, "另存到空白自制槽", game::saveCopyToEmptySlot);
            ui.smallButton(780, 34, 100, 34, "命名", () -> ui.editText("四驱车地图名称", game.activeName(),"自制地图 "+(game.selected()-Mini4wdGame.BUILT_INS+1),24,value->{if(value.isBlank()) throw new IllegalArgumentException();game.rename(value);}));
            ui.smallButton(616, 34, 74, 34, "导入", () -> ui.chooseMapFile(false));
            ui.smallButton(698, 34, 74, 34, "导出", () -> ui.chooseMapFile(true));
            ui.smallButton(620, 140, 54, 27, "−", () -> game.zoom(1 / 1.2, new Point(8, 5)));
            ui.smallButton(682, 140, 74, 27, (int) (game.zoom() * 100) + "%", game::fitView);
            ui.smallButton(764, 140, 54, 27, "+", () -> game.zoom(1.2, new Point(8, 5)));
            ui.smallButton(826, 140, 54, 27, "复原", game::fitView);
        } else if (game.view() == Mini4wdGame.View.MAPS) {
            ui.smallButton(SIDE_X, 106, 150, 43, "组装四驱车", game::openGarage);
            ui.smallButton(SIDE_X + 164, 106, 156, 43, "联机房间", game::showRooms);
            ui.smallButton(680, 34, 90, 34, "导入地图", () -> ui.chooseMapFile(false));
            ui.smallButton(782, 34, 98, 34, "导出地图", () -> ui.chooseMapFile(true));
            var pending=game.pendingRecords();
            if(!pending.isEmpty()) {
                pendingRecordIndex=Math.floorMod(pendingRecordIndex,pending.size());
                var selected=pending.get(pendingRecordIndex);
                ui.smallButton(SIDE_X,151,pending.size()==1?320:216,25,
                        "待保存成绩 "+(pendingRecordIndex+1)+" / "+pending.size(),()->reviewRecord(ui,game,selected));
                if(pending.size()>1) ui.smallButton(SIDE_X+226,151,94,25,"下一份",()->pendingRecordIndex++);
            }
        }

        Point pointer = new Point((mouseX - BOARD_X) / TILE / game.zoom() + game.panX(), (mouseY - BOARD_Y) / TILE / game.zoom() + game.panY());
        int hover = cellAt(mouseX, mouseY) < 0 ? -1 : MiniTrack.cell((int) Math.floor(pointer.x()), (int) Math.floor(pointer.y()));
        var raceFrame = game.view() == Mini4wdGame.View.TRIAL ? game.raceFrame() : null;
        multiLane = raceFrame != null;
        if (game.view() != Mini4wdGame.View.GARAGE) board(g, game, hover, pointer, raceFrame);
        ui.panel(SIDE_X, BOARD_Y, 320, 520, SIDEBAR);
        String status = switch (game.view()) {
            case MAPS -> { maps(ui, g, game); yield game.inspection().message(); }
            case EDITOR -> { editor(ui, g, game); yield game.inspection().message(); }
            case TRIAL -> {
                String boostKey = app.keyLabel(Command.HANDBRAKE);
                String resetKey = game.roomActive() ? "" : app.keyLabel(Command.RESET);
                if (raceFrame != null) race(ui, g, game, raceFrame, boostKey, resetKey);
                else trial(ui, g, game, boostKey, resetKey);
                yield boostKey + " 冲刺   ·   " + (resetKey.isEmpty() ? "" : resetKey + " 重跑   ·   ") + "Esc 返回";
            }
            case GARAGE -> {
                assemblyHud.render(ui, g, game);
                yield game.setup().ready() ? "组装完整 · 已保存" : "未完成组装 · 可保存并稍后继续";
            }
            case ROOM -> { room(ui, game); yield game.inspection().message(); }
        };
        ui.text(status, 48, 735, 17, game.inspection().ready() ? TEAL : AMBER, true);
        if (game.view() == Mini4wdGame.View.EDITOR) {
            ui.text("Ctrl+C / V 复制粘贴   ·   Ctrl+A 全选   ·   Z 撤销   ·   Y 重做   ·   Del 删除", 48, 765, 14, SOFT);
            ui.text(game.dirty() ? "有未保存的修改" : "已保存", 729, 735, 15, game.dirty() ? AMBER : SOFT);
        } else {
            ui.text("轨道 " + game.inspection().pieces() + " 块", 48, 765, 14, SOFT);
            ui.text("最佳  " + PracticeSession.format(game.best()), 692, 735, 16, LIGHT);
        }
        if (!game.notice().isEmpty()) ui.wrapped(game.notice(), SIDE_X, 735, 320, 13, SOFT, 19);
    }

    private void maps(HudRenderer ui, Graphics2D g, Mini4wdGame game) {
        ui.text("内置赛道", CONTENT_X, 211, 17, LIGHT, true);
        for (int i = 0; i < Mini4wdGame.BUILT_INS; i++) mapButton(ui, g, game, i, 222 + 28 * i);
        ui.text("自制地图", CONTENT_X, 446, 17, LIGHT, true);
        for (int i = 0; i < Mini4wdGame.CUSTOM_SLOTS; i++) mapButton(ui, g, game, Mini4wdGame.BUILT_INS + i, 458 + 28 * i);
        ui.text(game.setup().ready() ? "车辆组装完成" : "先到组装台完成车辆", CONTENT_X, 605, 17, game.setup().ready() ? TEAL : AMBER);
        String edit = game.selected() < Mini4wdGame.BUILT_INS ? "复制并编辑" : game.customExists(game.selected()) ? "编辑地图" : "新建地图";
        ui.smallButton(CONTENT_X, 642, 86, 42, edit, game::edit);
        trialButton(ui, game, CONTENT_X + 94, 642, 86, 42, "试跑");
        if (game.inspection().ready()) ui.smallButton(CONTENT_X + 188, 642, 86, 42, "四车竞速", game::startRace);
    }

    private void mapButton(HudRenderer ui, Graphics2D g, Mini4wdGame game, int index, int y) {
        boolean empty = index >= Mini4wdGame.BUILT_INS && !game.customExists(index);
        ui.smallButton(CONTENT_X, y, 274, 25, shortText(game.name(index), 16) + (empty ? "  ·  空" : ""), () -> game.select(index));
        if (game.selected() == index) selection(g, CONTENT_X, y, 274, 25);
    }

    private void editor(HudRenderer ui, Graphics2D g, Mini4wdGame game) {
        String[] labels = {"积木", "形状", "选择", "模板"};
        for (var mode : Mini4wdGame.EditorMode.values()) {
            int x = CONTENT_X + mode.ordinal() * 70;
            ui.smallButton(x, 192, 64, 33, labels[mode.ordinal()], () -> game.editorMode(mode));
            if (game.editorMode() == mode) selection(g, x, 192, 64, 33);
        }
        switch (game.editorMode()) {
            case TILES -> tiles(ui, g, game);
            case SHAPES -> shapes(ui, g, game);
            case SELECT -> selectionTools(ui, game);
            case TEMPLATES -> templates(ui, game);
        }
        ui.smallButton(CONTENT_X, 642, 86, 37, "撤销 Z", game::undo);
        ui.smallButton(CONTENT_X + 94, 642, 86, 37, "重做 Y", game::redo);
        ui.smallButton(CONTENT_X + 188, 642, 86, 37, "清空", game::clear);
    }

    private void selectionTools(HudRenderer ui, Mini4wdGame game) {
        ui.text("已选 " + (game.selectedCells().size() + game.selectedShapes().size()) + " 个片段", CONTENT_X, 261, 19, TEAL, true);
        ui.smallButton(CONTENT_X, 283, 132, 40, "全选 Ctrl+A", game::selectAll);
        ui.smallButton(CONTENT_X + 142, 283, 132, 40, "取消选择", game::clearSelection);
        ui.smallButton(CONTENT_X, 340, 132, 42, "复制 Ctrl+C", game::copySelection);
        ui.smallButton(CONTENT_X + 142, 340, 132, 42, "粘贴 Ctrl+V", game::pasteSelection);
        ui.smallButton(CONTENT_X, 402, 274, 42, "删除选中 Del", game::deleteSelection);
        ui.wrapped("按住 Ctrl 点选多个片段，再拖动整组轨道。复制后也可以换到其他地图粘贴。", CONTENT_X, 490, 274, 15, SOFT, 25);
    }

    private void room(HudRenderer ui, Mini4wdGame game) {
        if (!game.roomActive()) {
            ui.panel(80, 268, 764, 290, SIDEBAR);
            ui.text("2–4 人四驱车同场比赛", 112, 319, 28, TEAL, true);
            ui.wrapped("房主选择地图并创建房间。其他车手输入房主的 IP 和端口加入，地图会自动同步。全员准备后由房主统一发车。", 112, 369, 680, 18, LIGHT, 32);
            ui.wrapped("同一局由房主计算车速、冲刺、脱轨和名次。跨网络连接需要房主地址和 TCP 端口可达。", 112, 478, 680, 15, SOFT, 27);
            ui.text("车手昵称", CONTENT_X, 225, 17, LIGHT, true);
            ui.smallButton(CONTENT_X, 244, 274, 42, shortText(game.playerName(), 16), () -> ui.editText("车手昵称", game.playerName(),"车手",20,value->{if(value.isBlank()) throw new IllegalArgumentException();game.playerName(value);}));
            ui.smallButton(CONTENT_X, 362, 274, 48, "创建房间", () -> ui.prompt("房间 TCP 端口", "26950", value -> {
                try { int port = Integer.parseInt(value.strip()); if (port < 1 || port > 65535) throw new NumberFormatException(); game.hostRoom(port); }
                catch (NumberFormatException e) { game.message("端口应在 1–65535 之间。 "); }
            }));
            ui.smallButton(CONTENT_X, 430, 274, 48, "加入房间", () -> ui.prompt("房主地址 IP:端口", "127.0.0.1:26950", game::joinRoom));
            ui.text("当前地图", CONTENT_X, 539, 15, SOFT);
            ui.text(shortText(game.activeName(), 16), CONTENT_X, 571, 19, LIGHT, true);
            return;
        }
        MiniRoom room = game.room();
        if (room.host()) {
            ui.panel(80, 210, 764, 136, SIDEBAR);
            ui.text("将朋友所在网络可达的地址发给对方", 104, 244, 19, TEAL, true);
            int index = 0;
            for (String address : room.addressHints().stream().limit(4).toList()) ui.text(address, 106, 272 + index++ * 19, 14, LIGHT);
        }
        ui.text(room.host() ? "房主 · " + room.members().size() + " / 4 人" : "已加入 · " + room.members().size() + " / 4 人", CONTENT_X, 219, 21, TEAL, true);
        ui.text(room.host() ? room.addressHint() : room.status(), CONTENT_X, 249, 14, SOFT);
        int row = 0;
        for (var member : room.members()) {
            int y = 278 + row++ * 59;
            ui.panel(CONTENT_X, y, 274, 49, BACKGROUND);
            ui.text(shortText(member.name(), 10), CONTENT_X + 12, y + 21, 16, member.id() == room.localId() ? TEAL : LIGHT, true);
            ui.text(member.setup().chassis().label + " · " + member.setup().motor().label, CONTENT_X + 12, y + 41, 12, SOFT);
            ui.text(!member.setup().ready() ? "组装中" : member.ready() ? "已准备" : "未准备", CONTENT_X + 204, y + 28, 13, member.ready() ? TEAL : AMBER);
        }
        if (!room.host()) ui.smallButton(CONTENT_X, 538, 132, 40, room.ready() ? "取消准备" : game.setup().ready() ? "准备" : "先完成组装", () -> { if (game.checkAssembly()) room.ready(!room.ready()); });
        ui.smallButton(room.host() ? CONTENT_X : CONTENT_X + 142, 538, room.host() ? 274 : 132, 40, "车辆组装台", game::openGarage);
        if (room.canStart()) ui.smallButton(CONTENT_X, 590, 274, 42, "统一发车", room::startRace);
        else ui.centered(room.host() ? "等待至少两人准备" : "准备后等待房主发车", CONTENT_X + 137, 616, 15, SOFT);
        ui.smallButton(CONTENT_X, 645, 274, 34, "退出房间", game::leaveRoom);
    }

    private void race(HudRenderer ui, Graphics2D g, Mini4wdGame game, MiniRace.Frame frame, String boostKey, String resetKey) {
        var local = frame.cars().stream().filter(c -> c.id() == game.localRacerId()).findFirst().orElse(frame.cars().get(0));
        ui.text(frame.complete() ? "比赛完成" : frame.countdown() > 0 ? "准备起跑" : "四驱车竞速", CONTENT_X, 223, 25, TEAL, true);
        ui.text(frame.countdown() > 0 ? Integer.toString((int) Math.ceil(frame.countdown())) : PracticeSession.format(frame.elapsed()), CONTENT_X, 276, 35, LIGHT, true);
        ui.text("你已完成 " + local.laps() + " / " + (frame.closed() ? 3 : 1) + (frame.closed() ? " 圈" : " 段"), CONTENT_X, 313, 16, SOFT);
        int row = 0;
        for (var car : frame.standings()) {
            int y = 343 + row++ * 48;
            ui.text(row + "  " + shortText(car.name(), 10), CONTENT_X, y, 17, CAR_COLORS[car.lane()], true);
            String result = car.result() == Result.FINISHED ? PracticeSession.format(car.elapsed()) : !car.connected() ? "已断线" : car.result() == Result.DERAILED ? "脱轨" : "第 " + Math.min(frame.closed() ? 3 : 1, car.laps() + 1) + (frame.closed() ? " 圈" : " 段");
            ui.text("车道 " + (car.lane() + 1) + " · " + result, CONTENT_X + 24, y + 21, 13, SOFT);
        }
        if (!local.lapTimes().isEmpty()) ui.text("你的最佳圈  " + PracticeSession.format(local.lapTimes().stream().mapToDouble(Double::doubleValue).min().orElse(0)), CONTENT_X, 533, 14, LIGHT);
        ui.text(local.boosting() ? "冲刺中" : boostKey + " 冲刺 · 电量", CONTENT_X, 556, 14, SOFT);
        g.setColor(EDGE); g.fillRect(CONTENT_X, 568, 274, 8);
        g.setColor(TEAL); g.fillRect(CONTENT_X, 568, (int) (274 * local.battery()), 8);
        if (game.roomActive()) {
            if (game.room().host()) ui.smallButton(CONTENT_X, 598, 274, 39, frame.complete() ? "返回房间" : "结束本局", game::back);
            else ui.centered(frame.complete() ? "等待房主返回房间" : "与房主同步比赛中", CONTENT_X + 137, 622, 14, SOFT);
            ui.smallButton(CONTENT_X, 647, 274, 32, "退出房间", game::leaveRoom);
        } else {
            ui.smallButton(CONTENT_X, 598, 274, 39, "重新发车  " + resetKey, game::retry);
            ui.smallButton(CONTENT_X, 647, 274, 32, "返回地图", game::back);
        }
    }

    private String decimal(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private String shortText(String value,int length) {
        if(value.length()<=length) return value;
        int end=length;
        if(end>0 && Character.isHighSurrogate(value.charAt(end-1))) end--;
        return value.substring(0,end)+"…";
    }

    private void tiles(HudRenderer ui, Graphics2D g, Mini4wdGame game) {
        for (Kind kind : Kind.values()) {
            int x = CONTENT_X + kind.ordinal() % 2 * 142;
            int y = 245 + kind.ordinal() / 2 * 50;
            ui.smallButton(x, y, 132, 42, "   " + kind.label, () -> game.chooseTool(kind));
            rail(g, game.tool() == kind ? game.placement() : new Piece(kind, 0), x + 8, y + 7, 28);
            if (game.tool() == kind) selection(g, x, y, 132, 42);
        }
        ui.smallButton(CONTENT_X, 350, 132, 39, "擦除", () -> game.chooseTool(null));
        if (game.tool() == null) selection(g, CONTENT_X, 350, 132, 39);
        ui.smallButton(CONTENT_X + 142, 350, 132, 39, "旋转 90°", () -> game.rotate(1));
        ui.wrapped("直轨、弯轨、坡道和起跑线按格子拼接。切到形状页可制作更大的圆弧与螺旋。", CONTENT_X, 439, 274, 15, SOFT, 25);
    }

    private void templates(HudRenderer ui, Mini4wdGame game) {
        ui.text("载入后可继续编辑与变形", CONTENT_X, 258, 16, SOFT);
        for (int i = 0; i < Mini4wdGame.BUILT_INS; i++) {
            int index = i;
            ui.smallButton(CONTENT_X, 279 + i * 44, 274, 36, game.name(i), () -> game.template(index));
        }
    }

    private void shapes(HudRenderer ui, Graphics2D g, Mini4wdGame game) {
        for (var type : MiniShape.Type.values()) {
            int x = CONTENT_X + type.ordinal() % 2 * 142, y = 242 + type.ordinal() / 2 * 46;
            ui.smallButton(x, y, 132, 37, type.label, () -> game.chooseShape(type));
            if (game.shapeBrush() == type) selection(g, x, y, 132, 37);
        }
        ui.smallButton(CONTENT_X, 337, 132, 36, "选中 / 拖动", () -> game.chooseShape(null));
        ui.smallButton(CONTENT_X + 142, 337, 132, 36, "删除  Del", game::deleteShape);
        var p = game.shapeSettings();
        parameter(ui, 409, "长度 / 宽", String.format(Locale.ROOT, "%.2f", p.width()), "width", .5, game);
        if (p.type() != MiniShape.Type.LINE) parameter(ui, 449, "高度", String.format(Locale.ROOT, "%.2f", p.height()), "height", .5, game);
        parameter(ui, 489, "旋转", p.angle() + "°", "angle", 15, game);
        if (p.type() == MiniShape.Type.ARC) parameter(ui, 529, "弧度", p.extent() + "°", "extent", 15, game);
        if (p.type() == MiniShape.Type.SPIRAL) parameter(ui, 529, "圈数", String.format(Locale.ROOT, "%.2f", p.extent() / 360.0), "extent", 90, game);
        ui.wrapped("拖动轨道移动。方块拉伸，圆点旋转。仅端点相接，交叉处保持各自路线。", CONTENT_X, 558, 274, 13, SOFT, 19);
        ui.smallButton(CONTENT_X, 598, 274, 32, "普通积木转为可变形片段", game::convertTiles);
    }

    private void parameter(HudRenderer ui, int y, String label, String value, String field, double step, Mini4wdGame game) {
        ui.text(label, CONTENT_X, y, 14, SOFT);
        ui.smallButton(1040, y - 23, 34, 30, "−", () -> game.deform(field, -step));
        ui.centered(value, 1124, y, 15, LIGHT);
        ui.smallButton(1176, y - 23, 34, 30, "+", () -> game.deform(field, step));
    }

    private void trial(HudRenderer ui, Graphics2D g, Mini4wdGame game, String boostKey, String resetKey) {
        MiniTrial trial = game.trial();
        if(trial==null) {
            ui.text("等待发车",CONTENT_X,225,25,TEAL,true);
            ui.smallButton(CONTENT_X,631,274,43,"返回地图",game::back);
            return;
        }
        Result result = trial.result();
        var pending=game.currentPendingRecord();
        String title = switch (result) {
            case RUNNING -> "计时中";
            case FINISHED -> game.newBest() ? "新个人最佳" : pending!=null?"成绩待保存":
                    game.inspection().closed() ? "完成三圈" : "抵达终点";
            case DERAILED -> trial.segment().kind() == Kind.RAMP ? "坡道脱轨" : "弯道脱轨";
        };
        ui.text(title, CONTENT_X, 225, 25, result == Result.DERAILED ? AMBER : TEAL, true);
        ui.text(PracticeSession.format(trial.elapsed()), CONTENT_X, 276, 35, LIGHT, true);
        ui.text("完成 " + trial.laps() + " / " + trial.targetLaps() + (game.inspection().closed() ? " 圈" : " 段"), CONTENT_X, 321, 17, LIGHT);
        ui.text(String.format(Locale.ROOT, "%.1f 格/秒", trial.speed()), 1103, 321, 16, SOFT);
        ui.text(trial.boosting() ? "冲刺中" : "冲刺电量", CONTENT_X, 366, 16, trial.boosting() ? AMBER : SOFT);
        g.setColor(new Color(0x344f58));
        g.fillRect(CONTENT_X, 381, 274, 10);
        g.setColor(trial.boosting() ? AMBER : TEAL);
        g.fillRect(CONTENT_X, 381, (int) (274 * trial.battery()), 10);

        if (result == Result.RUNNING) {
            ui.text("本次马达  " + trial.setup().motor().label, CONTENT_X, 449, 17, LIGHT);
            ui.text("按住 " + boostKey + " 冲刺，松开后补充电量。", CONTENT_X, 489, 15, SOFT);
            ui.text("入弯和上坡前松开，速度会回落。", CONTENT_X, 516, 15, SOFT);
        } else {
            String hint = result == Result.DERAILED ? "入弯前结束冲刺，或换较稳的马达。" : "可换马达再挑战一次。";
            ui.text(hint, CONTENT_X, 449, 15, SOFT);
            ui.smallButton(CONTENT_X, 493, 274, 39, "返回组装台调整零件", game::openGarage);
        }
        if(pending!=null) ui.smallButton(CONTENT_X,539,274,31,"核对并重试保存成绩",()->reviewRecord(ui,game,pending));
        ui.smallButton(CONTENT_X, 577, 274, 43, "重跑  " + resetKey, game::retry);
        ui.smallButton(CONTENT_X, 631, 274, 43, "返回地图", game::back);
    }

    private void reviewRecord(HudRenderer ui,Mini4wdGame game,Mini4wdGame.TrialRecord pending) {
        var setup=pending.setup();
        ui.review("保存原试跑成绩",java.util.List.of(
                pending.trackName()+" · "+PracticeSession.format(pending.seconds()),
                "马达 "+setup.motor().label+" · 发车时的组装 "+setup.key(),
                "按原路线和原组装保存。已有同条件最佳更快或相同时，会保留已有成绩。"),
                "重试保存",()->game.retryTrialRecord(pending));
    }

    private void trialButton(HudRenderer ui, Mini4wdGame game, int x, int y, int w, int h, String label) {
        if (game.inspection().ready()) ui.smallButton(x, y, w, h, label, game::startTrial);
        else {
            ui.panel(x, y, w, h, BACKGROUND);
            ui.centered("连接后可试跑", x + w / 2, y + h / 2 + 5, 14, SOFT);
        }
    }

    private void board(Graphics2D g, Mini4wdGame game, int hover, Point pointer, MiniRace.Frame raceFrame) {
        MiniTrack track = game.track();
        var inspection=track.inspect();
        var selected=game.selectedShapes();
        g.setColor(EDGE);
        g.fillRect(BOARD_X - 5, BOARD_Y - 5, MiniTrack.COLUMNS * TILE + 10, MiniTrack.ROWS * TILE + 10);
        java.awt.Shape previousClip = g.getClip();
        java.awt.geom.AffineTransform previousTransform = g.getTransform();
        g.clipRect(BOARD_X, BOARD_Y, MiniTrack.COLUMNS * TILE, MiniTrack.ROWS * TILE);
        g.translate(BOARD_X * (1 - game.zoom()) - game.panX() * TILE * game.zoom(), BOARD_Y * (1 - game.zoom()) - game.panY() * TILE * game.zoom());
        g.scale(game.zoom(), game.zoom());
        if (game.view() == Mini4wdGame.View.EDITOR) {
            terrain(g,track,inspection,selected);
            g.setStroke(new BasicStroke(2));
            g.setColor(AMBER);
            for (int cell : inspection.problemCells()) g.drawRect(cellX(cell) + 2, cellY(cell) + 2, TILE - 5, TILE - 5);
            for (int cell : game.selectedCells()) selection(g, cellX(cell) + 2, cellY(cell) + 2, TILE - 5, TILE - 5);
            if (hover >= 0 && game.editorMode() == Mini4wdGame.EditorMode.TILES) {
                Piece preview = game.placement();
                if (preview != null) {
                    Graphics2D ghost = (Graphics2D) g.create();
                    ghost.setComposite(AlphaComposite.SrcOver.derive(.7f));
                    ghost.setColor(TILE_A);
                    ghost.fillRect(cellX(hover), cellY(hover), TILE, TILE);
                    rail(ghost, preview, cellX(hover), cellY(hover), TILE);
                    ghost.dispose();
                }
                selection(g, cellX(hover) + 1, cellY(hover) + 1, TILE - 3, TILE - 3);
            }
            if (game.editorMode() == Mini4wdGame.EditorMode.SHAPES) {
                if (game.shapeBrush() != null && hover >= 0) {
                    var preview = game.previewShape(pointer);
                    shapeRail(g, preview, preview.fits() ? TEAL : AMBER);
                }
                if (game.selection() != null) shapeHandles(g, game.selection());
            }
        } else g.drawImage(boardImage(g,track,inspection,selected),BOARD_X,BOARD_Y,null);
        if (inspection.ready() && !inspection.closed()) {
            var route = inspection.route();
            endpoint(g, route.get(0).point(0), TEAL);
            endpoint(g, route.get(route.size() - 1).point(1), AMBER);
        }
        if (game.view() == Mini4wdGame.View.TRIAL) {
            if (raceFrame != null) {
                for (var car : raceFrame.cars()) drawCar(g, new Point(car.x(), car.y()), car.heading(), car.boosting(), car.result(), CAR_COLORS[car.lane()], .65, car.setup());
            } else if(game.trial()!=null) car(g, game.trial());
        }
        g.setTransform(previousTransform);
        g.setClip(previousClip);
    }

    private BufferedImage boardImage(Graphics2D g,MiniTrack track,MiniTrack.Inspection inspection,Set<Integer> selected) {
        if(boardImage!=null && boardImage.track()==track && boardImage.lanes()==multiLane
                && boardImage.selected().equals(selected)) return boardImage.raster();
        var raster=boardImage==null
                ?new BufferedImage(MiniTrack.COLUMNS*TILE,MiniTrack.ROWS*TILE,BufferedImage.TYPE_INT_RGB)
                :boardImage.raster();
        var layer=raster.createGraphics();
        try {
            layer.setRenderingHints(g.getRenderingHints());
            layer.setStroke(g.getStroke());
            layer.translate(-BOARD_X,-BOARD_Y);
            terrain(layer,track,inspection,selected);
        } finally {layer.dispose();}
        boardImage=new BoardImage(track,multiLane,selected,raster);
        return raster;
    }

    private void terrain(Graphics2D g,MiniTrack track,MiniTrack.Inspection inspection,Set<Integer> selected) {
        for (int cell = 0; cell < MiniTrack.SIZE; cell++) {
            int x = cellX(cell), y = cellY(cell);
            g.setColor((MiniTrack.column(cell) + MiniTrack.row(cell)) % 2 == 0 ? TILE_A : TILE_B);
            g.fillRect(x, y, TILE, TILE);
            g.setColor(SIDEBAR);
            g.drawRect(x, y, TILE - 1, TILE - 1);
            Piece piece = track.piece(cell);
            if (piece != null) rail(g, piece, x, y, TILE);
        }
        for (int i = 0; i < track.shapes().size(); i++) {
            Color edge = selected.contains(i) ? TEAL : inspection.problemShapes().contains(i) ? AMBER : RAIL;
            shapeRail(g, track.shapes().get(i), edge);
        }
    }

    private void shapeRail(Graphics2D g, Rail rail, Color edge) {
        Path2D path = new Path2D.Double();
        Point from = rail.point(0);
        path.moveTo(BOARD_X + from.x() * TILE, BOARD_Y + from.y() * TILE);
        int divisions = rail.kind() == Kind.STRAIGHT ? 1 : Math.max(1, (int) Math.ceil(rail.length() * 14));
        for (int i = 1; i <= divisions; i++) {
            Point p = rail.point((double) i / divisions);
            path.lineTo(BOARD_X + p.x() * TILE, BOARD_Y + p.y() * TILE);
        }
        stroke(g, path, TILE * (multiLane ? 1.08f : .64f), EDGE);
        stroke(g, path, TILE * (multiLane ? .98f : .54f), edge);
        stroke(g, path, TILE * (multiLane ? .84f : .39f), ROAD);
        if (multiLane) laneLines(g, rail, BOARD_X, BOARD_Y, TILE);
    }

    private void shapeHandles(Graphics2D g, MiniShape shape) {
        var p = shape.parameters();
        Point a = shape.transform(-p.width() / 2, 0), b = Mini4wdGame.widthHandle(shape);
        g.setColor(TEAL);
        g.setStroke(new BasicStroke(1));
        g.drawLine(screenX(a), screenY(a), screenX(b), screenY(b));
        handle(g, b, false);
        if (p.type() != MiniShape.Type.LINE) handle(g, Mini4wdGame.heightHandle(shape), false);
        handle(g, Mini4wdGame.angleHandle(shape), true);
        endpoint(g, shape.point(0), TEAL);
        endpoint(g, shape.point(1), AMBER);
    }

    private void handle(Graphics2D g, Point p, boolean circle) {
        int x = screenX(p), y = screenY(p);
        g.setColor(TEAL);
        if (circle) g.fillOval(x - 6, y - 6, 12, 12);
        else g.fillRect(x - 5, y - 5, 10, 10);
    }

    private void endpoint(Graphics2D g, Point p, Color color) {
        g.setColor(EDGE);
        g.fillOval(screenX(p) - 7, screenY(p) - 7, 14, 14);
        g.setColor(color);
        g.fillOval(screenX(p) - 4, screenY(p) - 4, 8, 8);
    }

    private int screenX(Point p) { return BOARD_X + (int) Math.round(p.x() * TILE); }
    private int screenY(Point p) { return BOARD_Y + (int) Math.round(p.y() * TILE); }

    private void rail(Graphics2D target, Piece piece, int x, int y, int size) {
        Graphics2D g = (Graphics2D) target.create(x, y, size, size);
        Segment segment = new Segment(0, piece.kind(), piece.first(), piece.second());
        Path2D path = new Path2D.Double();
        var start = segment.point(0);
        path.moveTo(start.x() * size, start.y() * size);
        int divisions = piece.kind() == Kind.CURVE ? 12 : 1;
        for (int i = 1; i <= divisions; i++) {
            var point = segment.point((double) i / divisions);
            path.lineTo(point.x() * size, point.y() * size);
        }
        stroke(g, path, size * (multiLane ? 1.08f : .72f), EDGE);
        stroke(g, path, size * (multiLane ? .98f : .62f), piece.kind() == Kind.RAMP ? RAMP : RAIL);
        stroke(g, path, size * (multiLane ? .84f : .46f), ROAD);
        if (multiLane) laneLines(g, segment, 0, 0, size);
        if (piece.kind() == Kind.START) {
            g.translate(size / 2.0, size / 2.0);
            g.rotate(segment.heading(0));
            int square = Math.max(2, size / 9);
            for (int col = 0; col < 2; col++) {
                for (int row = 0; row < 4; row++) {
                    g.setColor((col + row) % 2 == 0 ? LIGHT : EDGE);
                    g.fillRect((col - 1) * square, (row - 2) * square, square, square);
                }
            }
            g.setColor(TEAL);
            g.fillPolygon(new int[] {square + 3, square + 3, square * 2 + 3}, new int[] {-square / 2, square / 2, 0}, 3);
        } else if (piece.kind() == Kind.RAMP) {
            g.translate(size / 2.0, size / 2.0);
            g.rotate(segment.heading(0));
            g.setColor(RAMP);
            int stripe = size / 9;
            for (int i = -2; i <= 2; i++) g.fillRect(i * stripe, -size / 5, Math.max(1, size / 25), size * 2 / 5);
        }
        g.dispose();
    }

    private void stroke(Graphics2D g, Path2D path, float width, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(path);
    }

    private void car(Graphics2D target, MiniTrial trial) {
        Rail segment = trial.segment();
        var point = segment.point(trial.fraction());
        drawCar(target, point, segment.heading(trial.fraction()), trial.boosting(), trial.result(), TEAL, 1, trial.setup());
    }

    private void drawCar(Graphics2D target, Point point, double angle, boolean boosting, Result result, Color color, double scale, com.f1car.mini4wd.MiniSetup setup) {
        Graphics2D g = (Graphics2D) target.create();
        g.translate(BOARD_X + TILE * point.x(), BOARD_Y + TILE * point.y());
        g.scale(scale, scale);
        if (result == Result.DERAILED) {
            g.setColor(AMBER);
            g.drawOval(-19, -19, 38, 38);
            angle += .42;
        }
        g.rotate(angle);
        MiniCarPainter.paint(g, 0, 0, 0, .7, setup, boosting ? AMBER : color, false);
        if (boosting) {
            g.setColor(AMBER);
            g.fillRect(-23, -3, 7, 2);
            g.fillRect(-25, 2, 9, 2);
        }
        g.dispose();
    }

    private void laneLines(Graphics2D g, Rail rail, int x, int y, int scale) {
        int divisions = rail.kind() == Kind.CURVE ? Math.max(16, (int) (rail.length() * 12)) : 1;
        for (double offset : new double[] {-.18, 0, .18}) {
            Path2D path = new Path2D.Double();
            for (int i = 0; i <= divisions; i++) {
                double t = (double) i / divisions;
                Point p = rail.point(t);
                double angle = rail.heading(t);
                double px = x + (p.x() - Math.sin(angle) * offset) * scale;
                double py = y + (p.y() + Math.cos(angle) * offset) * scale;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            stroke(g, path, 1, new Color(185, 201, 197, 125));
        }
    }

    private void selection(Graphics2D g, int x, int y, int w, int h) {
        g.setStroke(new BasicStroke(2));
        g.setColor(TEAL);
        g.drawRect(x, y, w, h);
    }

    private int cellX(int cell) { return BOARD_X + MiniTrack.column(cell) * TILE; }
    private int cellY(int cell) { return BOARD_Y + MiniTrack.row(cell) * TILE; }
}
