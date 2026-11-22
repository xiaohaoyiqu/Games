package com.f1car.mini4wd;

import com.f1car.mini4wd.MiniTrack.Inspection;
import com.f1car.mini4wd.MiniTrack.Kind;
import com.f1car.mini4wd.MiniTrack.Piece;
import com.f1car.mini4wd.MiniTrial.Motor;
import com.f1car.mini4wd.MiniTrial.Result;
import com.f1car.model.DriverName;
import com.f1car.persistence.ExportDestination;
import com.f1car.persistence.ProfileStore;
import com.f1car.persistence.MiniAssemblyRecovery;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import com.f1car.mini4wd.MiniTrack.Point;

public final class Mini4wdGame {
    public static final int CUSTOM_SLOTS = 4;
    public static final int BUILT_INS = MiniTrackCatalog.COURSES.size();

    public enum View { MAPS, EDITOR, TRIAL, GARAGE, ROOM }
    public enum EditorMode { TILES, SHAPES, SELECT, TEMPLATES }
    private enum Drag { MOVE, WIDTH, HEIGHT, ANGLE }

    private record Board(MiniTrack track,String name) {
        Board(MiniTrack track) { this(track, "未命名地图"); }
        Board {name=MiniTrackStore.cleanName(name);}
        Inspection inspection() {return track.inspect();}
        String key() {return track.recordKey();}
    }
    public record TrialRecord(String key,String trackName,MiniSetup setup,double seconds) {}
    private enum AssemblyHistory { NONE,REMEMBER,UNDO }
    private record AssemblyChange(MiniSetup before,MiniSetup after,AssemblyHistory history,MiniAssembly.Slot focus,String success) {}

    private static final class Draft {
        Board board;
        final Deque<Board> undo = new ArrayDeque<>();
        final Deque<Board> redo = new ArrayDeque<>();

        Draft(Board board) { this.board = board; }

        void replace(Board next) {
            if (next.track.sameLayout(board.track) && next.name.equals(board.name)) return;
            if (undo.size() == 80) undo.removeLast();
            undo.push(board);
            redo.clear();
            board = next;
        }
    }

    private final ProfileStore profile;
    private final MiniTrackStore storage;
    private final MiniBlueprintStore blueprints;
    private final MiniAssemblyRecovery assemblyRecovery;
    private final Board empty = new Board(new MiniTrack());
    private final Board[] saved = new Board[BUILT_INS + CUSTOM_SLOTS];
    private final Draft[] drafts = new Draft[CUSTOM_SLOTS];
    private final boolean[] loadFailed = new boolean[CUSTOM_SLOTS];
    private final MiniTrackStore.Revision[] slotRevisions = new MiniTrackStore.Revision[CUSTOM_SLOTS];

    private View view = View.MAPS, trialOrigin;
    private int selected;
    private Kind tool = Kind.STRAIGHT;
    private int rotation;
    private MiniTrial trial;
    private Board trialBoard;
    private MiniSetup trialSetup;
    private String trialKey;
    private TrialRecord completedTrialRecord;
    private final LinkedHashMap<String,TrialRecord> pendingRecords=new LinkedHashMap<>();
    private List<TrialRecord> pendingRecordRows=List.of();
    private MiniRace race;
    private boolean raceMode;
    private MiniRoom room;
    private Board roomBoard;
    private MiniTrackStore.MapData roomMap;
    private View garageOrigin = View.MAPS;
    private MiniAssembly.Slot assemblySlot = MiniAssembly.Slot.CHASSIS;
    private MiniSetup assemblyCandidate;
    private final Deque<MiniSetup> assemblyUndo = new ArrayDeque<>();
    private AssemblyChange pendingAssembly;
    private boolean pendingAssemblyOnDisk;
    private MiniAssemblyComparison assemblyComparison;
    private boolean keyboardBoostHeld, controllerBoostHeld, newBest;
    private String notice = "";
    private EditorMode editorMode = EditorMode.TILES;
    private MiniShape.Type shapeBrush;
    private MiniShape.Parameters shapeSettings = new MiniShape.Parameters(MiniShape.Type.LINE, 8, 5, 4, 4, 0, 0);
    private record ShapePlacement(MiniTrack track,MiniShape.Parameters settings,double x,double y,MiniShape preview) {}
    private ShapePlacement shapePlacement;
    private int selectedShape = -1;
    private Board dragBaseline;
    private MiniShape dragShape;
    private Point dragStart;
    private Drag drag;
    private final Set<Integer> selectedCells = new LinkedHashSet<>(), selectedShapes = new LinkedHashSet<>();
    private MiniTrack clipboard;
    private Board groupBaseline;
    private Point groupStart;
    private double groupDx, groupDy;
    private double zoom = 1, panX, panY;

    public Mini4wdGame(ProfileStore profile) {
        this.profile = profile;
        storage = new MiniTrackStore(profile.directory());
        blueprints = new MiniBlueprintStore(profile.directory());
        assemblyRecovery=new MiniAssemblyRecovery(profile.directory());
        var recovered=assemblyRecovery.current();
        if(recovered!=null) {
            pendingAssembly=new AssemblyChange(recovered.before(),recovered.after(),AssemblyHistory.REMEMBER,assemblySlot,"已保存重开后核对的装配。");
            pendingAssemblyOnDisk=true;
        }
        for (int i = 0; i < BUILT_INS; i++) saved[i] = new Board(MiniTrackCatalog.COURSES.get(i).track(), MiniTrackCatalog.COURSES.get(i).name());
        for (int i = 0; i < CUSTOM_SLOTS; i++) {
            try {
                var slot = storage.slot(i);
                slotRevisions[i] = slot.revision();
                loadFailed[i] = slot.revision().damaged();
                var map = slot.map();
                if (map != null) saved[BUILT_INS + i] = new Board(map.track(), map.name().equals("未命名地图") ? "自制地图 " + (i + 1) : map.name());
            } catch (IOException e) { loadFailed[i] = true; }
        }
    }

    public View view() { return view; }
    public int selected() { return selected; }
    public Kind tool() { return tool; }
    public Motor motor() { return setup().motor(); }
    public MiniSetup setup() { return profile.profile.miniSetup; }
    public MiniBlueprintStore blueprints() { return blueprints; }
    public boolean saveBlueprint(int slot,String name) {
        if(roomActive()) {notice="请先离开房间，再操作装配蓝图。";return false;}
        if(!checkAssembly()) return false;
        try {
            boolean saved = blueprints.save(slot, name, setup());
            notice = saved ? "已保存整车装配蓝图 " + (slot + 1) + "。" : blueprints.warning();
            return saved;
        } catch(IllegalArgumentException error) {
            notice="蓝图名称需为 1–40 个字符，且不能含换行。";
            return false;
        }
    }
    public void loadBlueprint(int slot) {
        if(roomActive()) { notice="请先离开房间，再操作装配蓝图。";return; }
        var blueprint=blueprints.get(slot);
        if(blueprint==null) { notice="这个蓝图位尚未保存整车方案。";return; }
        if(!blueprints.matchesSaved(slot,blueprint)) {notice=blueprints.warning();return;}
        changeAssembly(blueprint.setup(),AssemblyHistory.REMEMBER,assemblySlot,
            "已载入「"+blueprint.name()+"」，可撤销恢复之前的装配。");
    }
    public MiniAssembly.Slot assemblySlot() { return assemblySlot; }
    public MiniSetup assemblyCandidate() { return assemblyCandidate == null ? setup() : assemblyCandidate; }
    public void selectAssemblySlot(MiniAssembly.Slot slot) { assemblySlot = slot; assemblyCandidate = setup(); }
    public void cycleAssemblyPart(int direction) {
        var choices = MiniAssembly.choices(setup(), assemblySlot);
        String current = MiniAssembly.label(assemblyCandidate(), assemblySlot);
        int index = 0;
        for (int i = 0; i < choices.size(); i++) if (MiniAssembly.label(choices.get(i), assemblySlot).equals(current)) index = i;
        assemblyCandidate = choices.get(Math.floorMod(index + direction, choices.size()));
    }
    public void installAssemblyPart() {
        String error = MiniAssembly.installationError(setup(), assemblySlot, assemblyCandidate());
        if (!error.isEmpty()) { notice = error; return; }
        changeAssembly(assemblyCandidate().withInstalled(setup().installedParts() | assemblySlot.bit()),
            AssemblyHistory.REMEMBER,assemblySlot,"已安装"+assemblySlot.label+"。");
    }
    public void removeAssemblyPart() {
        String error = MiniAssembly.removalError(setup(), assemblySlot);
        if (!error.isEmpty()) { notice = error; return; }
        changeAssembly(setup().withInstalled(setup().installedParts() & ~assemblySlot.bit()),
            AssemblyHistory.REMEMBER,assemblySlot,"已取下"+assemblySlot.label+"。");
    }
    private void rememberAssembly(MiniSetup previous) {
        if (assemblyUndo.size() >= 40) assemblyUndo.removeLast();
        assemblyUndo.push(previous);
    }
    public void newKit() {
        changeAssembly(MiniSetup.empty(),AssemblyHistory.REMEMBER,MiniAssembly.Slot.CHASSIS,"已准备一个空白套件。");
    }
    public void undoAssembly() {
        var previous=assemblyUndo.peek();
        if(previous!=null) changeAssembly(previous,AssemblyHistory.UNDO,assemblySlot,"已恢复上一套装配。");
    }
    public boolean checkAssembly() {
        if(pendingAssembly!=null) {notice="先在组装台保存或取消本次待存装配。";return false;}
        var issues = MiniAssembly.issues(setup());
        notice = issues.isEmpty() ? "组装检查通过，可以上轨试跑。" : String.join("；", issues.stream().limit(2).toList()) + (issues.size() > 2 ? "；另有 " + (issues.size() - 2) + " 项，见空安装位。" : "。");
        return issues.isEmpty();
    }
    public MiniRoom room() { return room; }
    public boolean roomActive() { return room != null && !room.closed(); }
    public int localRacerId() { return roomActive() ? room.localId() : 0; }
    public MiniRace.Frame raceFrame() { return roomActive() ? room.frame() : race == null ? null : race.frame(); }
    public MiniTrial trial() { return trial; }
    public boolean newBest() { return newBest; }
    public List<TrialRecord> pendingRecords() {
        if(pendingRecordRows==null) pendingRecordRows=List.copyOf(pendingRecords.values());
        return pendingRecordRows;
    }
    public TrialRecord currentPendingRecord() {return completedTrialRecord;}
    public String notice() { return notice; }
    public void message(String message) { notice = message; }
    public EditorMode editorMode() { return editorMode; }
    public MiniShape.Type shapeBrush() { return shapeBrush; }
    public int selectedShape() { return selectedShape; }
    public Set<Integer> selectedCells() { return Set.copyOf(selectedCells); }
    public Set<Integer> selectedShapes() { return Set.copyOf(selectedShapes); }
    public double zoom() { return view == View.EDITOR ? zoom : 1; }
    public double panX() { return view == View.EDITOR ? panX : 0; }
    public double panY() { return view == View.EDITOR ? panY : 0; }
    public void fitView() { zoom = 1; panX = 0; panY = 0; }
    public void pan(double dx, double dy) { panX -= dx / zoom; panY -= dy / zoom; }
    public void zoom(double factor, Point anchor) {
        double next = Math.max(.5, Math.min(3, zoom * factor));
        panX += anchor.x() / zoom - anchor.x() / next;
        panY += anchor.y() / zoom - anchor.y() / next;
        zoom = next;
    }
    public MiniShape selection() {
        return selectedShape >= 0 && selectedShape < track().shapes().size() ? track().shapes().get(selectedShape) : null;
    }
    public MiniShape.Parameters shapeSettings() { return selection() == null ? shapeSettings : selection().parameters(); }
    public MiniTrack track() { return board().track; }
    public Inspection inspection() { return board().inspection(); }
    public double best() {
        String key=view==View.TRIAL && trialKey!=null?trialKey:recordKey(board(),setup());
        return best(key);
    }
    private double best(String key) {return profile.profile.bestTimes.getOrDefault(key,-1d);}
    private static String recordKey(Board track,MiniSetup setup) {
        return track.key()+(setup.equals(MiniSetup.stock())?"":":setup:"+setup.key());
    }
    public Piece placement() { return tool == null ? null : new Piece(tool, rotation); }

    public String name(int index) {
        if (index < BUILT_INS) return MiniTrackCatalog.COURSES.get(index).name();
        if (drafts[index - BUILT_INS] != null) return drafts[index - BUILT_INS].board.name;
        return saved[index] == null ? "自制地图 " + (index - BUILT_INS + 1) : saved[index].name;
    }
    public String activeName() {
        if(roomBoard!=null && (view==View.ROOM || view==View.TRIAL)) return roomBoard.name;
        return view==View.TRIAL && trialBoard!=null?trialBoard.name():name(selected);
    }
    public String playerName() { return profile.profile.playerName; }
    public void playerName(String name) {
        var previous=profile.profile.playerName;
        profile.profile.playerName=DriverName.clean(name);
        if(!profile.saveIfChanged()) {
            profile.profile.playerName=previous;
            notice="车手名称尚未保存，当前名称保留。";
            return;
        }
        if (roomActive()) room.configure(playerName(), setup());
    }

    public String description() {
        if (selected < BUILT_INS) return MiniTrackCatalog.COURSES.get(selected).description();
        if (loadFailed[slot()] && drafts[slot()] == null) return "地图读取失败，可以打开编辑器重建。";
        if (board() == empty) return "空地图。放入轨道积木，拼出自己的赛道。";
        return dirty() ? "有未保存的编辑。" : "地图已保存在本机。";
    }

    public boolean dirty() {
        return selected >= BUILT_INS && drafts[slot()] != null &&
            (saved[selected] == null || !saved[selected].track.sameLayout(board().track) || !saved[selected].name.equals(board().name));
    }

    public boolean customExists(int index) {
        return saved[index] != null || drafts[index - BUILT_INS] != null;
    }

    public void select(int index) {
        if(index!=selected) endShapeDrag();
        selected = index;
        notice = "";
    }

    public void chooseMotor(Motor motor) {
        changeAssembly(setup().withMotor(motor),AssemblyHistory.NONE,assemblySlot,"");
    }
    public void changePart(String part,int step) {
        changeAssembly(setup().change(part,step),AssemblyHistory.NONE,assemblySlot,"");
    }
    public void stockSetup() {
        changeAssembly(MiniSetup.stock(),AssemblyHistory.REMEMBER,assemblySlot,"已装好完整原厂车。");
    }
    public MiniSetup pendingAssembly() {return pendingAssembly==null?null:pendingAssembly.after();}
    public MiniSetup pendingAssemblyBase() {return pendingAssembly==null?null:pendingAssembly.before();}
    public boolean pendingAssemblyRecoverable() {return pendingAssemblyOnDisk;}
    public MiniAssemblyComparison assemblyComparison() {return assemblyComparison;}
    public boolean startAssemblyComparison(MiniAssemblyComparison.Plan plan) {
        if(plan==null || view!=View.GARAGE || roomActive() || pendingAssembly!=null || assemblyComparison!=null
                || !setup().equals(plan.a()) || !track().sameLayout(plan.track()) || !activeName().equals(plan.trackName())) {
            notice="装配、地图或房间状态已变化，请重新核对对比条件。";
            return false;
        }
        assemblyComparison=new MiniAssemblyComparison(plan);
        clearBoost();
        notice="";
        return true;
    }
    public void closeAssemblyComparison() {assemblyComparison=null;}
    private void changeAssembly(MiniSetup next,AssemblyHistory history,MiniAssembly.Slot focus,String success) {
        if(pendingAssembly!=null) {notice="请先保存或取消同份待存装配。";return;}
        pendingAssembly=new AssemblyChange(setup(),next,history,focus,success);
        retryAssemblySave(next);
    }
    public boolean retryAssemblySave(MiniSetup expected) {
        var change=pendingAssembly;
        if(change==null || !change.after().equals(expected)
                || change.history()==AssemblyHistory.UNDO && !change.after().equals(assemblyUndo.peek())) {
            notice="原装配或待存内容已变化，请重新查看。";
            return false;
        }
        boolean changed=!change.before().equals(change.after());
        var snapshot=changed?new MiniAssemblyRecovery.Snapshot(change.before(),change.after()):null;
        if(!change.before().equals(setup())) {
            if(pendingAssemblyOnDisk && change.after().equals(setup())) {
                if(assemblyRecovery.apply(snapshot,profile::verifyAcceptedProfile)==MiniAssemblyRecovery.Result.APPLIED) {
                    finishAssemblyChange(change,"已确认这套装配先前已保存，恢复副本已清理。");
                    return true;
                }
                notice="暂时无法核对磁盘配置或清理副本，待存装配保留。请重新打开游戏核对。";
                return false;
            }
            notice="当前装配与原基准不同，保留已存车辆和待存改动。";
            return false;
        }
        boolean cleanupPending=false;
        if(changed) {
            if(pendingAssemblyOnDisk) {
                var result=assemblyRecovery.apply(snapshot,()->saveAssemblyProfile(change));
                if(result==MiniAssemblyRecovery.Result.PREVIOUS_RETAINED) {
                    notice="装配尚未写入，当前车辆和恢复副本保留。请检查配置或重新打开核对。";
                    return false;
                }
                cleanupPending=result==MiniAssemblyRecovery.Result.APPLIED_NEEDS_CLEANUP;
            } else if(!saveAssemblyProfile(change)) {
                pendingAssemblyOnDisk=assemblyRecovery.retain(snapshot);
                notice=pendingAssemblyOnDisk?"装配尚未写入，原车保留，待存改动可重开核对。"
                    :"装配尚未写入；恢复文件也未保存，原车与内存候选保留。";
                return false;
            }
            if(change.history()==AssemblyHistory.REMEMBER) rememberAssembly(change.before());
            if(roomActive()) room.configure(playerName(),change.after());
        }
        if(change.history()==AssemblyHistory.UNDO) assemblyUndo.pop();
        if(cleanupPending) {
            pendingAssembly=new AssemblyChange(change.before(),change.after(),AssemblyHistory.NONE,change.focus(),change.success());
            selectAssemblySlot(change.focus());
            notice="装配已保存；请核对并清理原恢复副本。";
        } else finishAssemblyChange(change,change.success());
        return true;
    }
    private boolean saveAssemblyProfile(AssemblyChange change) {
        profile.profile.miniSetup=change.after();
        if(profile.save()) return true;
        profile.profile.miniSetup=change.before();
        return false;
    }
    private void finishAssemblyChange(AssemblyChange change,String message) {
        pendingAssembly=null;
        pendingAssemblyOnDisk=false;
        selectAssemblySlot(change.focus());
        notice=message;
    }
    public void discardAssemblyChange(MiniSetup expected) {
        var change=pendingAssembly;
        if(change==null || !change.after().equals(expected)) {notice="待存装配已变化，请重新查看。";return;}
        if(pendingAssemblyOnDisk && !assemblyRecovery.clear(new MiniAssemblyRecovery.Snapshot(change.before(),change.after()))) {
            notice="恢复副本发生变化或无法清理，原车辆和待存改动均保留。";
            return;
        }
        finishAssemblyChange(change,"已取消待存改动，当前装配保留。");
    }
    public void openGarage() { endShapeDrag(); clearBoost(); garageOrigin = view; view = View.GARAGE; assemblyCandidate = setup(); }
    public void showRooms() { view = View.ROOM; }
    public void showMaps() { endShapeDrag();leaveRoom();view=View.MAPS;notice=""; }

    public void hostRoom(int port) {
        if (!inspection().ready()) { notice = "请先选择可试跑的地图。"; return; }
        if (!checkAssembly()) return;
        try {
            room = MiniRoom.host(storage, new MiniTrackStore.MapData(activeName(), track()), playerName(), setup(), port);
            view = View.ROOM;
            notice = "";
        } catch (IOException | IllegalArgumentException e) { notice = "建房失败，请检查端口是否已占用。"; }
    }
    public void joinRoom(String address) {
        try {
            String host = address.strip();
            int port = MiniRoom.DEFAULT_PORT;
            int colon = host.lastIndexOf(':');
            if (colon >= 0) { port = Integer.parseInt(host.substring(colon + 1)); host = host.substring(0, colon); }
            if (port < 1 || port > 65535 || host.isBlank()) throw new IllegalArgumentException();
            room = MiniRoom.join(storage, host, port, playerName(), setup());
            view = View.ROOM;
            notice = "";
        } catch (RuntimeException e) { notice = "地址格式示例 192.168.1.10:26950"; }
    }
    public void leaveRoom() {
        if (room != null) room.close();
        room = null; roomBoard = null; roomMap = null; race = null; trial = null;
        clearBoost();
        clearTrialContext();
        closeAssemblyComparison();
        if (view == View.ROOM || view == View.TRIAL) view = View.MAPS;
    }

    public void edit() {
        if (selected < BUILT_INS) {
            for (int i = BUILT_INS; i < saved.length; i++) {
                if (!customExists(i) && !loadFailed[i - BUILT_INS]) {
                    drafts[i - BUILT_INS] = new Draft(saved[selected]);
                    select(i);
                    view = View.EDITOR;
                    prepareEditor();
                    return;
                }
            }
            notice = "没有空白槽位。可打开自制地图，再选择内置模板。";
            return;
        }
        if (drafts[slot()] == null) drafts[slot()] = new Draft(board());
        notice = slotReadNotice();
        view = View.EDITOR;
        prepareEditor();
    }

    public void chooseTool(Kind tool) { this.tool = tool; }
    public void rotate(int steps) {
        if (editorMode == EditorMode.SHAPES) deform("angle", steps * 15);
        else rotation = Math.floorMod(rotation + steps, 4);
    }

    public void place(int cell, boolean erase) {
        MiniTrack next = track().with(cell, erase ? null : placement());
        if (next != track()) replace(changed(next));
    }

    public void template(int index) {
        endShapeDrag();
        replace(saved[index]);
        prepareEditor();
    }
    public void clear() { endShapeDrag(); replace(changed(empty.track)); clearSelection(); }

    public void undo() {
        endShapeDrag();
        Draft draft = drafts[slot()];
        if (!draft.undo.isEmpty()) {
            draft.redo.push(draft.board);
            draft.board = draft.undo.pop();
            notice = "";
            selectedShape = -1;
            clearSelection();
        }
    }
    public void redo() {
        endShapeDrag();
        Draft draft = drafts[slot()];
        if (draft.redo.isEmpty()) return;
        draft.undo.push(draft.board); draft.board = draft.redo.pop();
        selectedShape = -1;
        clearSelection();
    }
    public void rename(String name) { replace(new Board(track(), name)); }
    public void exportMap(Path path) {
        exportMap(path,false);
    }
    public boolean exportMap(Path path,boolean replace) {
        try {
            return exportMap(path,ExportDestination.forWrite(path,replace));
        }catch(IOException | RuntimeException error) {
            notice="无法核对四驱车地图导出路径，请重新选择文件。";
            return false;
        }
    }
    public boolean exportMap(Path path,ExportDestination reviewed) {
        try {
            storage.write(path,track(),activeName(),reviewed);
            notice="地图已导出。";
            return true;
        }catch(IOException | RuntimeException error) {
            notice="地图导出失败，请选择独立文件或确认覆盖。";
            return false;
        }
    }
    public void importMap(Path path) {
        try {
            var imported = storage.read(path);
            if (selected < BUILT_INS) {
                int emptySlot = -1;
                for(int i=BUILT_INS;i<saved.length;i++) {
                    if(!customExists(i) && !loadFailed[i-BUILT_INS]) {emptySlot=i;break;}
                }
                if (emptySlot < 0) { notice = "请选择一个自制地图槽位再导入。"; return; }
                select(emptySlot);
            }
            if (drafts[slot()] == null) drafts[slot()] = new Draft(board());
            replace(new Board(imported.track(), imported.name()));
            view = View.EDITOR; prepareEditor();
            notice=loadFailed[slot()]?"地图已导入为草稿。"+slotReadNotice():"地图已导入，保存后可保留在此槽位。";
        } catch (IOException e) { notice = "地图文件无效或无法读取。"; }
    }

    public void save() {
        if (selected < BUILT_INS) return;
        endShapeDrag();
        Board current = board();
        try {
            slotRevisions[slot()] = storage.save(slot(), current.track, current.name, slotRevisions[slot()]);
            saved[selected] = current;
            loadFailed[slot()] = false;
            notice = current.inspection().ready() ? "地图已保存。" : "草稿已保存，连接后即可试跑。";
        } catch (MiniTrackStore.ChangedSlotException error) {
            notice = "此槽位已在另一窗口更新；本次草稿保留，可另存到空白槽或导出。";
        } catch (IOException e) {
            notice = slotRevisions[slot()] == null ? slotReadNotice() : "地图保存失败，请检查用户数据目录是否可写。";
        }
    }

    public boolean saveCopyToEmptySlot() {
        if (view != View.EDITOR) return false;
        endShapeDrag();
        Board source = board();
        for (int copy = 0; copy < CUSTOM_SLOTS; copy++) {
            int index = BUILT_INS + copy;
            if (index == selected || saved[index] != null || drafts[copy] != null || loadFailed[copy]) continue;
            try {
                slotRevisions[copy] = storage.save(copy, source.track, source.name, slotRevisions[copy]);
                saved[index] = source;
                drafts[copy] = new Draft(source);
                select(index);
                prepareEditor();
                notice = "草稿已另存到自制槽位 " + (copy + 1) + "。";
                return true;
            } catch (MiniTrackStore.ChangedSlotException occupied) {
                // This slot became occupied after opening the editor; checked saves can try the next slot.
            } catch (IOException error) {
                notice = "未能另存地图；原草稿保留，请检查用户数据目录。";
                return false;
            }
        }
        notice = "没有可另存的空白槽；可导出当前草稿。";
        return false;
    }

    private String slotReadNotice() {
        if (!loadFailed[slot()]) return "";
        return slotRevisions[slot()] == null ? "原文件无法核对，可另存到空白槽或导出。" : "原地图读取失败，保存前会备份原文件。";
    }

    public void startTrial() {startLocalRun(false);}

    public void startRace() {startLocalRun(true);}

    private void startLocalRun(boolean raceRun) {
        endShapeDrag();
        if(roomActive()) {notice="联机比赛由房主统一发车。";return;}
        if (view == View.TRIAL || !inspection().ready()) return;
        if (!checkAssembly()) return;
        trialBoard=board();
        trialSetup=setup();
        trialKey=recordKey(trialBoard,trialSetup);
        // A new run replaces the result that originally opened this workbench.
        // Closing the workbench must then return to its map/editor, not that cleared run.
        if (view == View.GARAGE && garageOrigin == View.TRIAL) garageOrigin = trialOrigin == View.GARAGE ? View.MAPS : trialOrigin;
        trialOrigin = view;
        view = View.TRIAL;
        raceMode = raceRun;
        race = null;
        trial = null;
        retry();
    }

    public void retry() {
        if (roomActive()) { notice = "联机比赛由房主统一发车。"; return; }
        if(view!=View.TRIAL || trialBoard==null || trialSetup==null) return;
        if (raceMode) {
            if(race==null) {
                var stable=MiniSetup.stock().change("tire",-1).change("roller",-1);
                race=new MiniRace(trialBoard.inspection(),List.of(new MiniRace.Entry(0,playerName(),trialSetup,false),
                        new MiniRace.Entry(1,"潮汐",stable,true),new MiniRace.Entry(2,"银杉",stable.change("battery",1),true),
                        new MiniRace.Entry(3,"曜石",stable.change("gear",-1),true)));
            } else race=race.restart();
            trial = race.car(0);
        } else trial=trial==null?new MiniTrial(trialBoard.inspection(),trialSetup,0):trial.restart();
        completedTrialRecord=null;
        clearBoost();
        newBest = false;
        notice = "";
    }

    public boolean keyboardBoostHeld() { return keyboardBoostHeld; }
    public boolean controllerBoostHeld() { return controllerBoostHeld; }
    public void keyboardBoost(boolean held) { setBoost(held && view == View.TRIAL, controllerBoostHeld); }
    public void controllerBoost(boolean held) { setBoost(keyboardBoostHeld, held && view == View.TRIAL); }
    public void clearBoost() { setBoost(false, false); }

    private boolean boostHeld() { return keyboardBoostHeld || controllerBoostHeld; }

    private void setBoost(boolean keyboard, boolean controller) {
        boolean wasHeld = boostHeld();
        keyboardBoostHeld = keyboard;
        controllerBoostHeld = controller;
        boolean held = boostHeld();
        if (wasHeld != held && roomActive()) room.boost(held);
    }

    public void update(float dt) {
        if(view==View.GARAGE && assemblyComparison!=null) {
            if(Float.isFinite(dt) && dt>0 && !assemblyComparison.complete()) assemblyComparison.advance();
            return;
        }
        if (room != null) {
            long raceEndsBeforePoll = room.raceEndCount();
            if (!room.closed()) room.poll(dt);
            if (room.closed()) { String message = room.status(); leaveRoom(); notice = message; return; }
            if (room.map() != null && room.map() != roomMap) { roomMap = room.map(); roomBoard = new Board(roomMap.track(), roomMap.name()); }
            boolean hasFrame = room.hasRaceFrame();
            if (room.raceEndCount() != raceEndsBeforePoll || (!hasFrame && view == View.TRIAL)) clearBoost();
            if (hasFrame) view = View.TRIAL;
            else if (view == View.TRIAL) view = View.ROOM;
            return;
        }
        if (view == View.TRIAL && race != null) { race.boost(0, boostHeld()); race.update(dt); return; }
        if (view != View.TRIAL || trial == null || trial.result() != Result.RUNNING) return;
        trial.update(dt, boostHeld());
        if(trial.result()==Result.FINISHED && trialKey!=null) {
            double previous=best(trialKey);
            if(previous<0 || trial.elapsed()<previous)
                finishTrialRecord(new TrialRecord(trialKey,trialBoard.name(),trial.setup(),trial.elapsed()));
        }
    }
    private void finishTrialRecord(TrialRecord result) {
        var earlier=pendingRecords.get(result.key());
        var candidate=earlier!=null && earlier.seconds()<=result.seconds()?earlier:result;
        if(profile.recordTime(candidate.key(),candidate.seconds())) {
            removePendingRecord(candidate.key());
            newBest=candidate==result;
            if(!newBest) notice="此前同条件的较快成绩已保存。";
            return;
        }
        completedTrialRecord=candidate;
        if(candidate!=earlier) {
            pendingRecords.put(candidate.key(),candidate);
            pendingRecordRows=null;
        }
        notice="个人最佳待保存，可在本页或地图列表重试。";
    }

    private void removePendingRecord(String key) {
        if(pendingRecords.remove(key)!=null) pendingRecordRows=null;
    }

    public boolean retryTrialRecord(TrialRecord expected) {
        if(expected==null || roomActive()) return false;
        if(!expected.equals(pendingRecords.get(expected.key()))) {
            notice="这份待保存成绩已变化，请重新查看。";
            return false;
        }
        var outcome=profile.retryMiniTime(expected.key(),expected.seconds());
        if(outcome==ProfileStore.MiniTimeSave.FAILED) {
            notice="计时仍未写入："+profile.warning;
            return false;
        }
        boolean covered=outcome==ProfileStore.MiniTimeSave.COVERED;
        removePendingRecord(expected.key());
        if(expected.equals(completedTrialRecord)) {
            newBest=!covered && view==View.TRIAL && trial!=null && trial.elapsed()==expected.seconds();
            completedTrialRecord=null;
        }
        notice=covered?"已保存的同条件成绩更快或相同。":"原试跑成绩已保存。";
        return true;
    }

    public void back() {
        endShapeDrag();
        if(assemblyComparison!=null) {closeAssemblyComparison();return;}
        if (view == View.GARAGE) { view = garageOrigin; return; }
        if (roomActive()) {
            if (view == View.TRIAL && room.host()) { clearBoost(); room.finishRace(); view = View.ROOM; }
            else leaveRoom();
            return;
        }
        if (view == View.TRIAL) {
            trial = null;
            race = null;
            clearBoost();
            clearTrialContext();
            view = trialOrigin;
        } else view = View.MAPS;
        notice = "";
    }

    private void replace(Board next) {
        drafts[slot()].replace(next);
        notice = "";
    }

    private void prepareEditor() {
        clearSelection();
        selectedShape = -1;
        shapeBrush = null;
        shapePlacement=null;
        editorMode = track().shapes().isEmpty() ? EditorMode.TILES : EditorMode.SHAPES;
    }

    public void editorMode(EditorMode mode) { endShapeDrag(); clearSelection(); editorMode = mode; }

    public void chooseShape(MiniShape.Type type) {
        endShapeDrag();
        shapeBrush = type;
        shapePlacement=null;
        clearSelection();
        if (type != null) shapeSettings = new MiniShape.Parameters(type, 8, 5, type == MiniShape.Type.SPIRAL ? 6 : 4, 4, 0, type.extent);
    }

    public MiniShape previewShape(Point position) {
        var p = shapeSettings;
        double x=snapGrid(position.x()),y=snapGrid(position.y());
        var track=track();
        var cached=shapePlacement;
        if(cached!=null && cached.track()==track && cached.settings()==p && cached.x()==x && cached.y()==y)
            return cached.preview();
        var preview=shape(p,x,y,p.width(),p.height(),p.angle(),p.extent());
        if(shapeBrush!=null) {
            if(preview.fits()) preview=snapEndpoints(track,preview,null);
            shapePlacement=new ShapePlacement(track,p,x,y,preview);
        }
        return preview;
    }

    public void deform(String field, double step) {
        endShapeDrag();
        var p = shapeSettings();
        double width = p.width(), height = p.height();
        int angle = p.angle(), extent = p.extent();
        switch (field) {
            case "width" -> width = Math.max(.5, Math.min(16, width + step));
            case "height" -> height = Math.max(.5, Math.min(16, height + step));
            case "angle" -> angle += (int) step;
            case "extent" -> extent += (int) step;
            default -> throw new IllegalArgumentException("Unknown deformation");
        }
        MiniShape next = shape(p, p.x(), p.y(), width, height, angle, extent);
        if (selection() == null) shapeSettings = next.parameters();
        else if (next.fits()) replaceShape(next);
        else notice = "形状超出工作台，请先移到中间。";
    }

    public void beginShapePointer(Point position, boolean erase) {
        endShapeDrag();
        if (erase) {
            selectedShape = shapeAt(position);
            deleteShape();
            return;
        }
        if (shapeBrush != null) {
            MiniShape next = previewShape(position);
            if (!next.fits()) { notice = "形状超出工作台，可先缩小尺寸。"; return; }
            if (track().shapes().size() >= 256) { notice = "当前地图已有 256 个形状。"; return; }
            replace(changed(track().addShape(next)));
            selectedShape = track().shapes().size() - 1;
            shapeBrush = null;
            shapePlacement=null;
            return;
        }
        MiniShape selected = selection();
        drag = Drag.MOVE;
        if (selected != null && MiniShape.distance(position, widthHandle(selected)) < .28) drag = Drag.WIDTH;
        else if (selected != null && selected.parameters().type() != MiniShape.Type.LINE && MiniShape.distance(position, heightHandle(selected)) < .28) drag = Drag.HEIGHT;
        else if (selected != null && MiniShape.distance(position, angleHandle(selected)) < .28) drag = Drag.ANGLE;
        else selectedShape = shapeAt(position);
        if (selection() != null) {
            dragBaseline = board();
            dragShape = selection();
            dragStart = position;
        }
    }

    public boolean draggingSelectionOrShape() { return dragBaseline!=null || groupBaseline!=null; }

    public void dragShapePointer(Point position) {
        if (groupBaseline != null) { dragSelection(position); return; }
        if (dragBaseline == null) return;
        var p = dragShape.parameters();
        double x = p.x(), y = p.y(), width = p.width(), height = p.height();
        int angle = p.angle();
        double radians = Math.toRadians(-angle);
        double dx = position.x() - x, dy = position.y() - y;
        switch (drag) {
            case MOVE -> { x += snapGrid(position.x() - dragStart.x()); y += snapGrid(position.y() - dragStart.y()); }
            case WIDTH -> width = Math.max(.5, Math.min(16, snapGrid(Math.abs(dx * Math.cos(radians) - dy * Math.sin(radians)) * 2)));
            case HEIGHT -> height = Math.max(.5, Math.min(16, snapGrid(Math.abs(dx * Math.sin(radians) + dy * Math.cos(radians)) * 2)));
            case ANGLE -> angle = (int) Math.round(Math.toDegrees(Math.atan2(dy, dx)) / 15) * 15;
        }
        MiniShape next = shape(p, x, y, width, height, angle, p.extent());
        if (next.fits() && !next.equals(selection())) {
            List<MiniShape> shapes = new ArrayList<>(track().shapes());
            shapes.set(selectedShape, next);
            drafts[slot()].board = changed(track().withShapes(shapes));
        }
    }

    public void endShapeDrag() {
        if (groupBaseline != null) {
            Board next = board();
            drafts[slot()].board = groupBaseline;
            groupBaseline = null;
            replace(next);
            Set<Integer> shifted = new LinkedHashSet<>();
            for (int cell : selectedCells) shifted.add(MiniTrack.cell(MiniTrack.column(cell) + (int) groupDx, MiniTrack.row(cell) + (int) groupDy));
            selectedCells.clear(); selectedCells.addAll(shifted);
            return;
        }
        if (dragBaseline == null) return;
        if (board().track.sameLayout(dragBaseline.track)) {
            dragBaseline = null;
            return;
        }
        MiniShape moved=selection();
        MiniShape snapped=snapEndpoints(track(),moved,moved);
        if (!snapped.equals(moved) && snapped.fits()) {
            List<MiniShape> shapes = new ArrayList<>(track().shapes());
            shapes.set(selectedShape, snapped);
            drafts[slot()].board = changed(track().withShapes(shapes));
        }
        Board next = board();
        drafts[slot()].board = dragBaseline;
        dragBaseline = null;
        replace(next);
    }

    public void deleteShape() {
        if (!selectedCells.isEmpty() || !selectedShapes.isEmpty()) { deleteSelection(); return; }
        endShapeDrag();
        if (selection() == null) return;
        List<MiniShape> shapes = new ArrayList<>(track().shapes());
        shapes.remove(selectedShape);
        replace(changed(track().withShapes(shapes)));
        selectedShape = -1;
    }

    public void convertTiles() {
        endShapeDrag();
        List<MiniShape> shapes = new ArrayList<>(track().shapes());
        MiniTrack next = new MiniTrack();
        for (int cell = 0; cell < MiniTrack.SIZE; cell++) {
            var piece = track().piece(cell);
            if (piece == null) continue;
            if (piece.kind() == Kind.START || piece.kind() == Kind.RAMP) {
                next = next.with(cell, piece);
                continue;
            }
            double x = MiniTrack.column(cell) + .5, y = MiniTrack.row(cell) + .5;
            int angle = piece.rotation() * 90;
            MiniShape.Type type = MiniShape.Type.LINE;
            int extent = 0;
            if (piece.kind() == Kind.CURVE) {
                int dx = piece.first().dx + piece.second().dx, dy = piece.first().dy + piece.second().dy;
                x += dx * .5;
                y += dy * .5;
                angle = (int) Math.round(Math.toDegrees(Math.atan2(-dy, -dx)));
                type = MiniShape.Type.ARC;
                extent = 90;
            }
            shapes.add(new MiniShape(new MiniShape.Parameters(type, x, y, 1, 1, angle, extent)));
        }
        if (shapes.size() > 256) {
            notice = "转换后的形状超过 256 个，请先删除部分片段。";
            return;
        }
        replace(changed(next.withShapes(shapes)));
        selectedShape = -1;
        notice = "普通直弯轨已转为形状；起跑线和坡道可在积木页编辑。";
    }

    public static Point widthHandle(MiniShape shape) { return shape.transform(shape.parameters().width() / 2, 0); }
    public static Point heightHandle(MiniShape shape) { return shape.transform(0, shape.parameters().height() / 2); }
    public static Point angleHandle(MiniShape shape) { return shape.transform(shape.parameters().width() / 2 + .7, 0); }

    private int shapeAt(Point point) {
        for (int i = track().shapes().size() - 1; i >= 0; i--) if (track().shapes().get(i).hitDistance(point) < .35) return i;
        return -1;
    }

    private void replaceShape(MiniShape next) {
        List<MiniShape> shapes = new ArrayList<>(track().shapes());
        shapes.set(selectedShape, next);
        replace(changed(track().withShapes(shapes)));
    }

    private static MiniShape snapEndpoints(MiniTrack track,MiniShape shape,MiniShape excluded) {
        MiniShape best = shape;
        double closest = .35;
        var ends=new Point[]{shape.point(0),shape.point(1)};
        var p=shape.parameters();
        for (MiniTrack.Rail rail : MiniShapeTopology.rails(track)) {
            if (rail == excluded) continue;
            Point entry=rail.point(0),exit=rail.point(1);
            for (int from = 0; from < 2; from++) {
                for (int to = 0; to < 2; to++) {
                    Point b=to==0?entry:exit;
                    double distance = MiniShape.distance(ends[from], b);
                    if (distance >= closest) continue;
                    double turn = MiniShapeTopology.outwardAngle(rail, to) + Math.PI - MiniShapeTopology.outwardAngle(shape, from);
                    int angle = p.angle() + (int) Math.round(Math.toDegrees(turn) / 15) * 15;
                    MiniShape rotated = shape(p, p.x(), p.y(), p.width(), p.height(), angle, p.extent());
                    Point endpoint = rotated.point(from);
                    MiniShape candidate = shape(p, p.x() + b.x() - endpoint.x(), p.y() + b.y() - endpoint.y(), p.width(), p.height(), angle, p.extent());
                    if (candidate.fits()) { closest = distance; best = candidate; }
                }
            }
        }
        return best;
    }

    private static MiniShape shape(MiniShape.Parameters p, double x, double y, double w, double h, int angle, int extent) {
        return new MiniShape(new MiniShape.Parameters(p.type(), x, y, w, h, angle, extent));
    }

    private static double snapGrid(double value) { return Math.round(value * 4) / 4.0; }

    private int slot() { return selected - BUILT_INS; }

    private Board board() {
        if (roomBoard != null && (view == View.ROOM || view == View.TRIAL)) return roomBoard;
        if(view==View.TRIAL && trialBoard!=null) return trialBoard;
        if (selected >= BUILT_INS && drafts[slot()] != null) return drafts[slot()].board;
        return saved[selected] == null ? empty : saved[selected];
    }

    private void clearTrialContext() {
        trialSetup=null;
        trialBoard=null;
        trialKey=null;
        completedTrialRecord=null;
        newBest=false;
    }

    private Board changed(MiniTrack next) { return new Board(next, board().name); }

    public void clearSelection() { selectedShape = -1; selectedCells.clear(); selectedShapes.clear(); }

    public void selectAll() {
        endShapeDrag();
        clearSelection();
        for (int i = 0; i < MiniTrack.SIZE; i++) if (track().piece(i) != null) selectedCells.add(i);
        for (int i = 0; i < track().shapes().size(); i++) selectedShapes.add(i);
        editorMode = EditorMode.SELECT;
    }

    public void beginSelectionPointer(Point point, boolean toggle) {
        endShapeDrag();
        int shape = shapeAt(point);
        int cell = MiniTrack.cell((int) Math.floor(point.x()), (int) Math.floor(point.y()));
        if (cell >= 0 && track().piece(cell) == null) cell = -1;
        boolean already = shape >= 0 ? selectedShapes.contains(shape) : cell >= 0 && selectedCells.contains(cell);
        if (!toggle && !already) clearSelection();
        if (shape >= 0) {
            if (toggle && selectedShapes.contains(shape)) selectedShapes.remove(shape);
            else selectedShapes.add(shape);
        } else if (cell >= 0) {
            if (toggle && selectedCells.contains(cell)) selectedCells.remove(cell);
            else selectedCells.add(cell);
        }
        selectedShape = selectedCells.isEmpty() && selectedShapes.size() == 1 ? selectedShapes.iterator().next() : -1;
        if (!toggle && (!selectedCells.isEmpty() || !selectedShapes.isEmpty())) {
            groupBaseline = board(); groupStart = point; groupDx = 0; groupDy = 0;
        }
    }

    private void dragSelection(Point point) {
        double dx = point.x() - groupStart.x(), dy = point.y() - groupStart.y();
        if (selectedCells.isEmpty()) { dx = snapGrid(dx); dy = snapGrid(dy); }
        else { dx = Math.round(dx); dy = Math.round(dy); }
        if(dx==groupDx && dy==groupDy) return;
        if(dx==0 && dy==0) {
            drafts[slot()].board=groupBaseline;
            groupDx=groupDy=0;
            return;
        }
        MiniTrack original = groupBaseline.track;
        MiniTrack moving = new MiniTrack();
        MiniTrack staying = original;
        for (int cell : selectedCells) { moving = moving.with(cell, original.piece(cell)); staying = staying.with(cell, null); }
        List<MiniShape> shapes = new ArrayList<>(original.shapes());
        for (int index : selectedShapes) {
            var p = original.shapes().get(index).parameters();
            var next = shape(p, p.x() + dx, p.y() + dy, p.width(), p.height(), p.angle(), p.extent());
            if (!next.fits()) return;
            shapes.set(index, next);
        }
        MiniTrack shifted = translate(moving, dx, dy);
        if (shifted == null) return;
        for (int cell = 0; cell < MiniTrack.SIZE; cell++) {
            if (shifted.piece(cell) != null) {
                if (staying.piece(cell) != null) return;
                staying = staying.with(cell, shifted.piece(cell));
            }
        }
        drafts[slot()].board = changed(staying.withShapes(shapes));
        groupDx = dx; groupDy = dy;
    }

    public void copySelection() {
        endShapeDrag();
        Set<Integer> shapesToCopy = selectedShapes;
        if (selectedCells.isEmpty() && selectedShapes.isEmpty() && selectedShape >= 0) shapesToCopy = Set.of(selectedShape);
        if (selectedCells.isEmpty() && shapesToCopy.isEmpty()) { notice = "先选中要复制的轨道。"; return; }
        MiniTrack copied = new MiniTrack();
        for (int cell : selectedCells) copied = copied.with(cell, track().piece(cell));
        List<MiniShape> shapes = new ArrayList<>();
        for (int index : shapesToCopy) shapes.add(track().shapes().get(index));
        clipboard = copied.withShapes(shapes);
        notice = "已复制选中轨道。";
    }

    public void pasteSelection() {
        endShapeDrag();
        if (clipboard == null) { notice = "先复制一组轨道。"; return; }
        int[][] offsets = {{1, 1}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {-1, -1}, {0, 0}};
        for (int[] offset : offsets) {
            MiniTrack moved = translate(clipboard, offset[0], offset[1]);
            if (moved == null || track().shapes().size() + moved.shapes().size() > 256) continue;
            boolean collision = false;
            for (int i = 0; i < MiniTrack.SIZE; i++) if (moved.piece(i) != null && track().piece(i) != null) collision = true;
            for (MiniShape shape : moved.shapes()) if (track().shapes().contains(shape)) collision = true;
            if (collision) continue;
            MiniTrack next = track();
            clearSelection();
            for (int i = 0; i < MiniTrack.SIZE; i++) if (moved.piece(i) != null) { next = next.with(i, moved.piece(i)); selectedCells.add(i); }
            List<MiniShape> shapes = new ArrayList<>(next.shapes());
            for (MiniShape shape : moved.shapes()) { selectedShapes.add(shapes.size()); shapes.add(shape); }
            replace(changed(next.withShapes(shapes)));
            editorMode = EditorMode.SELECT;
            return;
        }
        notice = "工作台空间不足，可清理位置或换一个地图槽位粘贴。";
    }

    public void deleteSelection() {
        endShapeDrag();
        MiniTrack next = track();
        for (int cell : selectedCells) next = next.with(cell, null);
        List<MiniShape> shapes = new ArrayList<>();
        for (int i = 0; i < next.shapes().size(); i++) if (!selectedShapes.contains(i)) shapes.add(next.shapes().get(i));
        replace(changed(next.withShapes(shapes)));
        clearSelection();
    }

    public void multiSelect(Point point) {
        if (editorMode != EditorMode.SELECT) {
            if (selectedShape >= 0) selectedShapes.add(selectedShape);
            editorMode = EditorMode.SELECT;
        }
        beginSelectionPointer(point, true);
    }

    private MiniTrack translate(MiniTrack source, double dx, double dy) {
        MiniTrack result = new MiniTrack();
        for (int i = 0; i < MiniTrack.SIZE; i++) {
            if (source.piece(i) == null) continue;
            int cell = MiniTrack.cell(MiniTrack.column(i) + (int) dx, MiniTrack.row(i) + (int) dy);
            if (cell < 0) return null;
            result = result.with(cell, source.piece(i));
        }
        for (MiniShape item : source.shapes()) {
            var p = item.parameters();
            MiniShape next = shape(p, p.x() + dx, p.y() + dy, p.width(), p.height(), p.angle(), p.extent());
            if (!next.fits()) return null;
            result = result.addShape(next);
        }
        return result;
    }
}
