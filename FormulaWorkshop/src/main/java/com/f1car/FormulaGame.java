package com.f1car;

import com.f1car.mini4wd.Mini4wdGame;
import com.f1car.mini4wd.MiniTrack;
import com.f1car.mini4wd.MiniTrial;
import com.f1car.model.*;
import com.f1car.persistence.ProfileStore;
import com.f1car.persistence.GhostStore;
import com.f1car.persistence.GhostExchangeStore;
import com.f1car.persistence.EventStore;
import com.f1car.persistence.EventRecoveryStore;
import com.f1car.persistence.EventHistoryStore;
import com.f1car.persistence.JournalStore;
import com.f1car.persistence.CircuitStore;
import com.f1car.persistence.TelemetryStore;
import com.f1car.persistence.ExportDestination;
import static com.f1car.persistence.ExportDestination.resolvedPathForExport;
import com.f1car.persistence.EquipmentLapStore;
import com.f1car.persistence.ReceivedEquipmentLapStore;
import com.f1car.persistence.TuningLapStudyStore;
import com.f1car.persistence.TuningLapStudyCodec;
import com.f1car.persistence.EquipmentLapShareCodec;
import com.f1car.persistence.ReplayStore;
import com.f1car.persistence.TuningStore;
import com.f1car.persistence.PairTrialStore;
import com.f1car.persistence.PairTrialShareCodec;
import com.f1car.persistence.ReceivedPairTrialStore;
import com.f1car.persistence.TuningTraceStore;
import com.f1car.persistence.PartsDraftStore;
import com.f1car.persistence.SetupLibraryStore;
import com.f1car.persistence.PartsStore;
import com.f1car.persistence.PartsPlanStore;
import com.f1car.persistence.PartsPackCodec;
import com.f1car.persistence.ActivityCardCodec;
import com.f1car.persistence.WorkshopStore;
import com.f1car.persistence.ControlStore;
import com.f1car.persistence.KeyboardStore;
import com.f1car.persistence.ExperienceStore;
import com.f1car.persistence.ActivityStore;
import com.f1car.persistence.RivalStore;
import com.f1car.persistence.CreationStore;
import com.f1car.persistence.ContentTextStore;
import com.f1car.input.*;
import com.f1car.scene.*;
import com.f1car.ui.HudRenderer;
import com.f1car.network.RaceRoom;
import com.f1car.network.RaceSession;
import com.f1car.network.RoomRules;
import com.f1car.network.RoomTrack;
import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.bullet.BulletAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.*;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class FormulaGame extends SimpleApplication {
    public enum Screen { MENU, START_MENU, GAME_SETUP, MINI_HOME, COURSES, CHALLENGES, BOOK, JOURNAL, EVENTS, TRACKS, MAP_EDITOR, GARAGE, SETTINGS, CONTROLS, HELP, DRIVING, PAUSED, RESULTS, MINI4WD, RACE_ROOM, RACE_SETUP, TELEMETRY, GHOSTS, REPLAYS, REPLAY, TUNING_LAB, WORKSHOP, EXPERIENCE, ACTIVITIES, CREATION, KEYBOARD, DRIVING_GUIDE, TUNING_GUIDE, SETUP_LIBRARY, PARTS, LIVERY }
    public enum Mode { RACE, FREE_CIRCUIT, FREE_PAD, LESSON, CUP, TIME_TRIAL, CHALLENGE, EVENT_PRACTICE, QUALIFYING, EVENT_RACE, MAP_TRIAL, TUNING_TRIAL, WORKSHOP, EXPERIENCE, MAP_SOLO, ACTIVITY, MAP_SECTION }
    private static final int LOCAL_RACE_FINISH_WAIT_SECONDS=45;
    public static final class LaunchSetup {
        public final String title;
        public final Screen back;
        public final boolean selectable,training,configurable;
        public final TrackPreview preview;
        public final Tuning tuning;
        public int difficulty,laps;
        public boolean adjustableLaps;
        public String lengthRule;
        private RaceSession.VehicleRule roomVehicles;
        private boolean stockChallenge;
        public boolean roomLaunch() {return roomVehicles!=null;}
        public String drivingRule() {
            if(stockChallenge) return "车辆规则：统一原厂";
            return roomLaunch()?"车辆规则："+roomVehicles.title:"难度："+new String[]{"轻松","标准","挑战"}[difficulty];
        }
        private final EventStageReview eventStage;
        private BooleanSupplier reviewedEventRevision;
        private EventSeries plannedEvent;
        public EventStageReview eventStage() {return eventStage;}
        private final java.util.function.Consumer<TrackLayout> start;
        private LaunchSetup(String title,Screen back,boolean selectable,boolean training,boolean configurable,TrackLayout track,Tuning tuning,int difficulty,int laps,java.util.function.Consumer<TrackLayout> start) {
            this(title,back,selectable,training,configurable,track,tuning,difficulty,laps,start,null);
        }
        private LaunchSetup(String title,Screen back,boolean selectable,boolean training,boolean configurable,TrackLayout track,Tuning tuning,int difficulty,int laps,java.util.function.Consumer<TrackLayout> start,EventStageReview eventStage) {
            this.title=title;this.back=back;this.selectable=selectable;this.training=training;this.configurable=configurable;this.adjustableLaps=configurable;this.tuning=tuning;this.difficulty=difficulty;this.laps=laps;this.start=start;this.eventStage=eventStage;preview=new TrackPreview(track,training);
        }
    }
    private LaunchSetup launchSetup;
    private float launchReturnSpeed;
    public LaunchSetup launchSetup() { return launchSetup; }
    public void openStartMenu() { screen=Screen.START_MENU;pressed.clear();physics.setSpeed(0);if(hud!=null) hud.resetStartMenu(); }
    private void prepareLaunch(String title,TrackLayout track,boolean selectable,boolean training,boolean configurable,java.util.function.Consumer<TrackLayout> start) {
        showLaunch(new LaunchSetup(title,screen,selectable,training,configurable,track,store.profile.tuning,store.profile.difficulty,store.profile.raceLaps,start));
    }
    private void showLaunch(LaunchSetup setup) {
        if(hud!=null) hud.endPreviewPan();
        launchReturnSpeed=physics.getSpeed();
        launchSetup=setup;
        screen=Screen.GAME_SETUP;
        pressed.clear();
        physics.setSpeed(0);
        helpVisible=false;
    }
    public void prepareMode(Mode mode,PracticeSession.Lesson lesson) {
        boolean training=mode==Mode.FREE_PAD || mode==Mode.LESSON && !lesson.onCircuit();
        String title=lesson!=null?lesson.title:switch(mode) { case RACE->"正常单机比赛";case FREE_CIRCUIT->"赛道自由练习";case FREE_PAD->"训练场自由驾驶";case TIME_TRIAL->"计时挑战";default->throw new IllegalArgumentException("Unsupported launch mode"); };
        prepareLaunch(title,training?selectedCircuit():selectedCircuitForLaunch(),!training,training,true,track->start(mode,lesson));
        if(mode!=Mode.RACE) { launchSetup.adjustableLaps=false;launchSetup.lengthRule=mode==Mode.LESSON?"按课程目标完成":mode==Mode.TIME_TRIAL?"连续计时 · 可随时结束":"自由驾驶 · 可随时结束"; }
    }
    public void prepareChallenge(RaceChallenge.Kind kind) { prepareLaunch(kind.title,selectedCircuitForLaunch(),true,false,true,track->startChallenge(kind));launchSetup.adjustableLaps=false;launchSetup.laps=kind.laps;launchSetup.lengthRule="挑战固定："+kind.laps+" 圈"; }
    public void prepareBook(ChallengeBook.Kind kind) {
        var circuitChoice=TrackCatalog.isBuiltin(circuit)?circuit:TrackCatalog.require(TrackCatalog.DEFAULT_ID).layout();
        var stock=Tuning.STOCK.withLivery(store.profile.tuning.livery());
        var setup=new LaunchSetup(kind.title,screen,true,false,false,circuitChoice,stock,store.profile.difficulty,store.profile.raceLaps,
                track->startBook(new ChallengeBook.Entry(track.id,kind),stock));
        setup.stockChallenge=true;
        setup.lengthRule="按挑战目标完成";
        showLaunch(setup);
    }
    public List<TrackCatalog.Entry> launchTracks() { return launchSetup!=null && launchSetup.stockChallenge?TrackCatalog.all():availableTracks(); }
    public void chooseLaunchTrack(String id) {
        if(screen!=Screen.GAME_SETUP || launchSetup==null || !launchSetup.selectable) return;
        if (launchSetup.stockChallenge && !builtinTrackId(id)) return;
        if (!refreshTrackChoice(id)) return;
        try {
            var track = circuitById(id);
            if (!sameTrackPreview(launchSetup.preview.track(), track)) {
                if(hud!=null) hud.endPreviewPan();
                launchSetup.preview.select(track);
            }
        } catch (IllegalArgumentException unavailable) {
            notifyUser("该赛道已不在当前选图列表，请重新选择。");
        }
    }
    public void cancelLaunch() { cancelLaunch(launchSetup); }
    public void cancelLaunch(LaunchSetup expected) {
        if(screen!=Screen.GAME_SETUP || expected==null || launchSetup!=expected) return;
        launchSetup=null;
        screen=expected.back;
        physics.setSpeed(launchReturnSpeed);
        if(hud!=null) hud.resetCamera();
    }
    public void commitLaunch(LaunchSetup expected) {
        if(expected==null || screen!=Screen.GAME_SETUP || launchSetup!=expected) return;
        var track = expected.preview.track();
        if(expected.roomLaunch()) {
            if(!controlsReady(controls.settings()) || !confirmTrackPreview(track)) return;
            if(expected.roomVehicles==RaceSession.VehicleRule.OPEN && raceRoom!=null && raceRoom.editable()
                    && !garageCandidateAllowed(expected.tuning)) return;
            launchSetup=null;
            expected.start.accept(track);
            if(hud!=null) hud.resetCamera();
            return;
        }
        if(expected.eventStage()!=null) {
            var reviewed=expected.eventStage();var state=reviewed.state();var round=state.calendar().get(state.round());
            if (!refreshEventTracks(state.calendar())) return;
            if(!eventStageCurrent(reviewed) || !expected.tuning.equals(state.tuning()) || expected.difficulty!=state.difficulty() || expected.laps!=state.laps() || expected.adjustableLaps || !track.id.equals(round.trackId()) || !track.geometryKey().equals(round.geometry())) {notifyUser("赛事阶段、地图、预览或操作已变化；请返回赛事中心重新预览，车库保持。");return;}
            if (!confirmTrackPreview(track)) return;
            if(!controlsReady(reviewed.controls())) return;
            expected.start.accept(track);
            if(screen!=Screen.GAME_SETUP) {
                launchSetup=null;
                if(hud!=null) hud.resetCamera();
            }
            return;
        }
        if (expected.plannedEvent != null) {
            var event = expected.plannedEvent;
            var state = event.state();
            var first = event.currentRound();
            if (!refreshEventTracks(state.calendar())) return;
            if (!eventCompatible(event) || !track.id.equals(first.trackId()) || !track.geometryKey().equals(first.geometry())
                    || expected.difficulty != state.difficulty() || expected.laps != state.laps()
                    || !expected.tuning.equals(state.tuning()) || expected.adjustableLaps) {
                notifyUser("赛历中的地图、修订或预览条件已改变，请返回赛历核对后重新预览。");
                return;
            }
        } else if (!online() && !refreshTrackChoice(track.id)) return;
        if(!controlsReady(controls.settings())) return;
        if(expected.configurable && !expected.tuning.equals(store.profile.tuning)) {
            notifyUser("预览后车库调校已改变，请返回并重新预览。");
            return;
        }
        if(expected.stockChallenge) {
            if(!TrackCatalog.isBuiltin(track)) {notifyUser("请为挑战册选择内置赛道。");return;}
        } else if(!garageCandidateAllowed(expected.tuning)) return;
        if (!confirmTrackPreview(track)) return;
        if(expected.reviewedEventRevision!=null && (!reviewedEventUnchanged(expected.reviewedEventRevision) || !eventReplacementReady())) return;
        if(expected.plannedEvent==null && !saveLaunchPreferences(expected,track)) return;
        launchSetup=null;expected.start.accept(track);if(hud!=null) hud.resetCamera();
    }
    private boolean saveLaunchPreferences(LaunchSetup expected,TrackLayout track) {
        String previousTrack=store.profile.trackId;
        if(expected.stockChallenge && Objects.equals(previousTrack,track.id)) return true;
        int previousDifficulty=store.profile.difficulty,previousLaps=store.profile.raceLaps;
        if(expected.selectable) store.profile.trackId=track.id;
        if(expected.configurable) store.profile.difficulty=expected.difficulty;
        if(expected.adjustableLaps) store.profile.raceLaps=expected.laps;
        if(store.saveIfChanged()) return true;
        store.profile.trackId=previousTrack;
        store.profile.difficulty=previousDifficulty;
        store.profile.raceLaps=previousLaps;
        notifyUser("开赛配置保存失败，原配置与待确认选择保留，可重试。");
        return false;
    }
    public void prepareNewEvent(EventSeries.Kind kind) {
        if(!eventArchivesReady()) return;
        prepareLaunch(kind==EventSeries.Kind.TOUR?"三站巡回杯 · 选择首站":"单站赛事周末",selectedCircuitForLaunch(),true,false,true,track->{
            if(!eventArchivesReady()) {openEvents();return;}var next=EventSeries.createStartingAt(kind,track,vehicleSpec.physicsVersion,store.profile.tuning,store.profile.difficulty,store.profile.raceLaps);
            if(events.save(next)) continueEvent(false);else { openEvents();notifyUser(events.warning); }
        });
        launchSetup.reviewedEventRevision=events.watchRevision();
    }
    public void prepareEventStage(boolean practice) {
        var event=events.current();if(online() || event==null || eventSavePending() || eventRecoveryAttention()) { notifyUser("请先检查赛事进度、结果恢复与地图。");return; }
        if (!refreshEventTracks(event.state().calendar())) return;
        if (!eventCompatible(event)) { notifyUser("请先检查赛事进度、结果恢复与地图。");return; }
        if(practice && event.state().phase()!=EventSeries.Phase.QUALIFYING) {notifyUser("赛事自由练习入口只在当前站排位前开放；当前阶段保持。");return;}
        if(event.state().phase()==EventSeries.Phase.REVIEW || event.state().phase()==EventSeries.Phase.COMPLETE) { continueEvent(false);return; }
        var reviewed=new EventStageReview(event.state(),practice,controls.settings());
        var setup=new LaunchSetup(practice?"赛事自由练习":event.phaseName(),screen,false,false,false,circuitById(event.currentRound().trackId()),event.state().tuning(),event.state().difficulty(),event.state().laps(),track->{
            if(practice) startEventActivity(Mode.EVENT_PRACTICE);else continueEvent(false);
        },reviewed);
        setup.lengthRule=practice?"自由练习 · 可随时结束":event.state().phase()==EventSeries.Phase.QUALIFYING?"排位：每车一次计时圈":"正赛："+event.state().laps()+" 圈";showLaunch(setup);
    }
    public record EventStageReview(EventSeries.State state,boolean practice,ControlSettings controls) {
        public EventStageReview {Objects.requireNonNull(state);Objects.requireNonNull(controls);if(state.phase()!=EventSeries.Phase.QUALIFYING && state.phase()!=EventSeries.Phase.RACE || practice && state.phase()!=EventSeries.Phase.QUALIFYING) throw new IllegalArgumentException("No launchable event stage");}
        public List<String> lines() {
            var result=new ArrayList<String>();var round=state.calendar().get(state.round());result.add("赛事 "+state.eventId()+" · 第 "+(state.round()+1)+" / "+state.calendar().size()+" 站 · "+(practice?"自由练习":state.phase()==EventSeries.Phase.QUALIFYING?"单圈排位":"正赛"));
            result.add("路线 "+round.trackId()+"；几何 "+round.geometry());result.add("赛事物理 "+state.physicsVersion()+"；难度 "+new String[]{"轻松","标准","挑战"}[state.difficulty()]+"；正赛 "+state.laps()+" 圈。");
            if(state.phase()==EventSeries.Phase.RACE) {
                result.add("全场上限 "+EventRaceClock.fullRaceLimitSeconds(state.laps())+" 秒。");
                result.add("首车完赛后最多等 "+EventRaceClock.FIRST_FINISH_WAIT_SECONDS+" 秒。全员完赛则立即结算。");
            }
            result.add("以下是建赛时固定的实际数值，不从当前车库或装备目录补写：");for(var field:PartsCatalog.Field.values()) result.add(field.title+"："+Float.toString(field.ratio(state.tuning()))+" 倍。");
            result.add("外观编号 "+state.tuning().livery()+"；本次预览操作身份 "+controls.identity());result.add("确认不采用这些数值到当前车库，也不因当前装备范围另改赛事车辆。旧赛事只保存数值，未保存完整安装来源。");
            result.add("确认时再次核对赛事阶段、路线、物理和操作；返回或拒绝不会开始本阶段。");return List.copyOf(result);
        }
    }
    public boolean eventStageCurrent(EventStageReview expected) {var current=events.current();return expected!=null && !online() && pendingEvent==null && !eventRecoveryAttention() && current!=null && expected.state().equals(current.state()) && expected.controls().equals(controls.settings()) && eventCompatible(current);}
    public void prepareCustomCalendar(List<EventSeries.Round> calendar,int difficulty,int laps,Runnable accepted) {
        try {
            if(!eventArchivesReady()) return;
            var event=EventSeries.createCustom(calendar,vehicleSpec.physicsVersion,store.profile.tuning,difficulty,laps);
            if (!refreshEventTracks(event.state().calendar())) return;
            if (!eventCompatible(event)) { notifyUser("赛历地图缺失或修订已改变，请更新对应赛站。");return; }
            prepareLaunch("自选积分杯 · 确认首站",circuitById(event.currentRound().trackId()),false,false,false,track->{
                if(!eventArchivesReady()) { openEvents();return; }if(events.save(event)) { accepted.run();continueEvent(false); }else { openEvents();notifyUser(events.warning); }
            });
            launchSetup.plannedEvent=event;launchSetup.reviewedEventRevision=events.watchRevision();launchSetup.difficulty=difficulty;launchSetup.laps=laps;
        }catch(IllegalArgumentException | NullPointerException error) { notifyUser("请检查赛历地图和配置。"); }
    }
    public void openMiniHome() { mainMenu();if(mini4wd!=null) mini4wd.showMaps();screen=Screen.MINI_HOME;physics.setSpeed(0); }
    public void confirmRoomStart() {
        var room=raceRoom;if(room==null || !room.host() || !room.canStart() || hud==null) return;
        var track=room.track();
        var settings=room.settings();
        long revision=room.revision();
        var host=room.members().stream().filter(member->member.id()==room.localId()).findFirst().orElseThrow();
        var setup=new LaunchSetup("联机 · "+room.stageName(),Screen.RACE_ROOM,false,false,false,track,
                settings.apply(host.tuning()),0,settings.laps(),chosen->{
            screen=Screen.RACE_ROOM;
            if(raceRoom==room && room.canStart() && room.revision()==revision && room.track()==chosen && chosen==track) room.startSession();
            else notifyUser("房间状态已变化，请重新核对规则、车手准备与地图。");
        });
        setup.roomVehicles=settings.vehicles();
        setup.lengthRule=switch(settings.activity()) {
            case PRACTICE -> "共同练习 · 房主结束后返回房间";
            case QUALIFYING -> "单圈排位 · 一圈有效成绩";
            case RACE -> "正赛 "+settings.laps()+" 圈";
        };
        hud.navigate(()->showLaunch(setup));
    }
    public void closeCreationToMain() {
        if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return;
        if(!circuitPackEditing.flush()) notifyUser(circuitPackEditing.warning());
        if(!creationStore.flush()) { notifyUser(creationStore.warning);return; }mainMenu();
    }
    public static final class Racer {
        public final FormulaCar car;
        public final DriverAI ai;
        public final LapProgress progress;
        private final Vector3f observedPosition=new Vector3f();
        private final Vector3f previousObservedPosition=new Vector3f();
        public Vector3f previous;
        public float penalty;
        public int recovery;
        public boolean connected = true, retired;
        Racer(FormulaCar car, DriverAI ai, LapProgress progress) {
            this.car = car; this.ai = ai; this.progress = progress; previous = car.position();
        }
        public double result() { return progress.finished() ? progress.finishTime() + penalty : Double.POSITIVE_INFINITY; }
    }

    public final ProfileStore store;
    public final EventStore events;
    public final EventHistoryStore eventHistory;
    public final EventRecoveryStore eventRecovery;
    public final RivalStore rivals;
    public final CreationStore creationStore;
    public final com.f1car.persistence.CircuitLineageStore circuitLineages;
    public final com.f1car.persistence.CircuitFeedbackStore circuitFeedback;
    public final com.f1car.persistence.CircuitFeedbackDiscussionStore feedbackDiscussions;
    public final com.f1car.persistence.DiscussionReadingStore discussionReading;
    private record PendingLineage(CircuitLineage value,CircuitLineage previous) {}
    private PendingLineage pendingLineage;
    public final com.f1car.persistence.ContentTextStore contentText;
    private record BenchmarkReading(CircuitDocument document,String physics,List<CircuitBenchmark.Source> sources,
                                    Map<CircuitBenchmark.Reference,Optional<CircuitBenchmark>> resolved) {}
    private final Map<String,BenchmarkReading> benchmarkCache=new LinkedHashMap<>(8,.75f,true);
    private List<ActivityRun> benchmarkRuns;
    public final JournalStore journal;
    public final com.f1car.persistence.LiveryStore liveries;
    private LiveryEditor liveryEditor;
    private LiveryEditor.Snapshot observedLivery;
    private LiveryEditor observedLiveryEditor;
    private long observedLiveryRevision=-1;
    public final com.f1car.persistence.EditorDraftStore<LiveryEditor.Snapshot> liveryEditing;
    public final com.f1car.persistence.EditorDraftStore<TuningPurpose.Intent> purposeEditing;
    public final com.f1car.persistence.EditorDraftStore<DiscussionDraft> discussionEditing;
    public final com.f1car.persistence.EditorDraftStore<DiscussionRevisionDraft> revisionEditing;
    public final com.f1car.persistence.EditorDraftStore<CircuitFeedbackDraft> feedbackEditing;
    public final com.f1car.persistence.EditorDraftStore<CircuitFeedbackDiscussionDraft> feedbackDiscussionEditing;
    public final com.f1car.persistence.EditorDraftStore<PartsAuthoring.State> partsAuthoringEditing;
    public final com.f1car.persistence.EditorDraftStore<CircuitPackDraft> circuitPackEditing;
    private PartsAuthoring partsAuthoring;
    public final CircuitStore circuits;
    public final TelemetryStore telemetryStore;
    public final EquipmentLapStore equipmentLaps;
    public final ReceivedEquipmentLapStore receivedEquipmentLaps;
    public final TuningLapStudyStore tuningStudies;
    public final GhostExchangeStore ghostExchange;
    private final GhostStore personalBests;
    private record SelectedGhost(GhostExchangeStore.Entry entry,GhostChallenge source,GhostLap reference) {
        String id() { return entry.id(); }
    }
    private SelectedGhost selectedGhost;
    public final ReplayStore replayStore;
    public final TuningStore tuningStore;
    public final PairTrialStore pairTrials;
    public final ReceivedPairTrialStore receivedPairTrials;
    public final TuningTraceStore tuningTraceStore;
    public final SetupLibraryStore setupLibrary;
    public final PartsStore partsStore;
    public final com.f1car.persistence.FittedPartsStore fittedParts;
    public final PartsPlanStore partsPlans;
    public final com.f1car.persistence.PartsTrialEvidenceStore partsEvidence;
    public final com.f1car.persistence.PartsDiscussionStore partsDiscussions;
    public final com.f1car.persistence.PartsDiscussionRevisionStore discussionRevisions;
    public final com.f1car.persistence.PartsDiscussionTraceStore discussionTraces;
    public final com.f1car.persistence.PartsDiscussionLapStore discussionLaps;
    public record PartsEvidenceRequest(TuningExperiment.Snapshot report,TuningPurpose purpose) {}
    private PartsEvidenceRequest partsEvidenceRequest;
    private PartsWorkbench partsWorkbench;
    public final PartsDraftStore partsEditing;
    private long observedPartsRevision;
    public record PartsEditingReview(PartsWorkbench.State state,PartsCatalog catalog,PartsDraftStore.Stamp disk,List<String> lines,boolean backup) {}
    public final WorkshopStore workshopStore;
    public final ControlStore controls;
    public final KeyboardStore keyboard;
    private final Set<Integer> keyboardHeld=new HashSet<>();
    private final Deque<KeyInputEvent> keyPresses=new ArrayDeque<>();
    private final Map<Integer,KeyInputEvent> lastHeldPress=new HashMap<>();
    private boolean readingRawKeys;
    private static final Map<Integer,String> EDITOR_SHORTCUTS=Map.of(
            KeyInput.KEY_C,"miniCopy",KeyInput.KEY_V,"miniPaste",KeyInput.KEY_A,"miniSelectAll",
            KeyInput.KEY_O,"miniImport",KeyInput.KEY_S,"miniExport");
    private record ShortcutPress(KeyInputEvent event,String action,boolean control) {}
    private final Deque<ShortcutPress> shortcutPresses=new ArrayDeque<>();
    private record PointerButton(MouseButtonEvent event,boolean control) {}
    private final Deque<PointerButton> pointerButtons=new ArrayDeque<>();
    private KeyboardSettings.Command capturingKey;
    private boolean capturingAlternate;
    private Screen keyboardReturn=Screen.SETTINGS;
    public String keyboardMessage="";
    public final ExperienceStore experienceStore;
    public final ActivityStore activityStore;
    private ActivityTrial activityTrial;
    private Vector3f[] localFramePositions;
    private List<Racer> localResultOrder;
    private final PartsActivityRule.Cache activityPartsChecks=new PartsActivityRule.Cache();
    private Screen partsReturn=Screen.GARAGE;
    private record PendingActivityImport(ActivityCardCodec.Shared shared,ActivityCard card,com.f1car.persistence.ContentTextStore.Text text) {
        private PendingActivityImport(ActivityCardCodec.Shared shared,ActivityCard card) {this(shared,card,new com.f1car.persistence.ContentTextStore.Text(card.name(),shared.author(),shared.notes()));}
    }
    private PendingActivityImport pendingActivityImport;
    private boolean activityRecorded;
    private ExperienceTrial experience;
    private ExperienceRun experienceReference;
    private TrackLayout experienceCourseTrack;
    private ControlSettings experienceKeyControls;
    private String experienceInputIdentity;
    private List<CircuitExperience.Course> experienceCourses=List.of();
    private final String[] experienceKeys=new String[4];
    private ExperiencePlayer experiencePlayer;
    private int experiencePart;
    private boolean experienceRecorded;
    public final ControllerInput controllerInput=new ControllerInput();
    private JmeController controllerAdapter;
    private ControlSettings driveControls=ControlSettings.defaults(),tuningControls=ControlSettings.defaults();
    private ControllerInput.Drive keyboardDrive=ControllerInput.Drive.STOP;
    private Screen controlsReturn=Screen.SETTINGS,inputScreen=Screen.MENU;
    private AxisCalibration calibration;
    private ControlSettings.Command capturingButton;
    private long captureGeneration;
    private boolean inputHelp;
    private final AtomicLong focusGeneration=new AtomicLong();
    // Updated on the game thread only after the current focus change has cleared old input.
    private long readyFocusGeneration;
    public String controlMessage="";
    private WorkshopSession workshop;
    private WorkshopReport workshopReport;
    private TrackLayout workshopBestTrack;
    private ControlSettings workshopBestControls;
    private final EnumMap<WorkshopSession.Kind,String> workshopBestKeys=new EnumMap<>(WorkshopSession.Kind.class);
    private boolean workshopRecorded;
    private TuningExperiment tuningExperiment;
    private PairExperiment pairExperiment;
    private PairExperiment.Snapshot pairReport;
    private boolean pairRunActive,pairLabRequested;
    private int pairIndex;
    private TuningExperiment.Snapshot tuningReport;
    private TuningPurpose tuningPurpose;
    private TrackLayout tuningTrack;
    private TuningTrial tuningTrial;
    private int tuningIndex;
    private boolean tuningRecorded;
    private Screen tuningReturn=Screen.GARAGE;
    private ReplayRecorder replayRecorder;
    private final ArrayList<RaceReplay.Car> replayCaptureCars=new ArrayList<>(RaceReplay.MAX_DRIVERS);
    private final Quaternion replayCaptureRotation=new Quaternion();
    private final Vector3f replayCaptureVector=new Vector3f();
    private double[] replayLapCrossings;
    private final RecoveryPlanner.LocalLookup[] replayRecoverySearches=new RecoveryPlanner.LocalLookup[EventSeries.DRIVERS];
    private boolean replayRecoverySearchesObserved;
    private ReplayPlayer replayPlayer;
    private record ReplayOpen(Screen origin,EventRoundBrief round,java.util.function.BooleanSupplier stillSelected,
                              boolean showMarkers,ReplayStore.PlaybackRequest request) {}
    private ReplayOpen replayOpen;
    private ReplayPlayer replayMarkersFor;
    private Screen replayReturn=Screen.MENU;
    private Screen replayClose=Screen.REPLAYS;
    private double replayClock;
    private long replayOriginTick, replaySequence=-1;
    private boolean replayOnline;
    private TelemetryBook telemetryBook;
    private TelemetryComparison telemetryComparison;
    private TrackLayout telemetryTrack;
    private Screen telemetryReturn=Screen.COURSES;
    private String telemetryA,telemetryB;
    private float telemetryDistance;
    private int telemetryBrakeIndex=-1;
    public final Path assetDirectory;
    public TrackLayout circuit;
    public TrackGuide trackGuide;
    public final VehicleSpec vehicleSpec = VehicleSpec.load();
    public final List<Racer> racers = new ArrayList<>();
    private final List<FormulaCar> trafficCars=new ArrayList<>(EventSeries.DRIVERS);
    private final DriverAI.TrafficSampler trafficSampler=new DriverAI.TrafficSampler();
    private final VehicleContact.Query vehicleContact=new VehicleContact.Query();
    // A failed request keeps its place across driving updates.
    private final LinkedHashSet<Integer> pendingAiRecoveries = new LinkedHashSet<>();
    private RecoveryPlanner.LocalSearch localRecoverySearch;
    private final Vector3f recoveryPositionReading=new Vector3f(),recoveryVelocityReading=new Vector3f(),recoveryGravityReading=new Vector3f();
    private final List<RecoveryPlanner.LocalTraffic> recoveryOccupied=new ArrayList<>(EventSeries.DRIVERS-1);
    private RecoveryPlanner.LocalTraffic[] recoveryReadings=new RecoveryPlanner.LocalTraffic[EventSeries.DRIVERS];
    public Screen screen = Screen.MENU;
    public Mode mode = Mode.FREE_CIRCUIT;
    public PracticeSession practice;
    public CupChallenge cup;
    public TimeTrialSession timeTrial;
    public RaceChallenge challenge;
    private RaceChallenge.Kind challengeKind=RaceChallenge.Kind.DUEL;
    private EventSeries runningEvent,pendingEvent;
    private EventRaceTrafficReview eventRaceTrafficReview;
    private final List<EventRaceTrafficReview.DriverFrame> eventTrafficObservations=new ArrayList<>(EventSeries.DRIVERS);
    private final Vector3f eventTrafficVelocity=new Vector3f();
    private final Quaternion timeTrialRotation=new Quaternion();
    private final Vector3f timeTrialForward=new Vector3f(),timeTrialVelocity=new Vector3f();
    private EventRaceClock eventRaceClock;
    private EventRaceTiming eventRaceTiming;
    private final Vector3f eventStandingsPosition=new Vector3f();
    private EventPendingResult pendingEventRecord;
    private QualifyingSession qualifying;
    private List<LapProgress> qualifyingProgress=List.of();
    private ChallengeAttempt bookAttempt;
    private String attemptId=UUID.randomUUID().toString();
    private boolean finishRecorded;
    private CircuitEditor circuitEditor;
    private record PendingCircuitPractice(CircuitStore.ImportedDraft draft,String fileName) {}
    private record CircuitImportContext(CircuitEditor editor,CircuitDocument document,CircuitTrial.Proof proof,boolean dirty,long historyRevision) {
        CircuitImportContext(CircuitEditor editor) {
            this(editor,editor==null?null:editor.document(),editor==null?null:editor.proof(),editor!=null && editor.dirty(),
                    editor==null?0:editor.historyRevision());
        }
        boolean matches(CircuitEditor current) {
            return editor==current && (current==null || historyRevision==current.historyRevision()
                    && Objects.equals(document,current.document()) && Objects.equals(proof,current.proof()) && dirty==current.dirty());
        }
    }
    private PendingCircuitPractice pendingCircuitPractice;
    private final Deque<String> circuitVisits=new ArrayDeque<>();
    private final CircuitEditor.Clipboard circuitClipboard=new CircuitEditor.Clipboard();
    private CircuitTrial circuitTrial;
    private CircuitSectionPractice circuitSectionPractice;
    private record EquipmentSectionResult(CircuitSectionPractice trial,EquipmentSectionReturn destination,
                                          ControlSettings controls,double time,String title,String description) {}
    private record EquipmentSectionReturn(Screen screen,TrackLayout track,EquipmentSectionPracticeSession session,
                                          EquipmentSectionPracticeSession.Car car,Tuning tuning,
                                          EquipmentSectionResult original,Runnable reopenComparison,
                                          EquipmentSectionPracticeSession.SubsectionReference subsectionReference,String backLabel) {}
    private EquipmentSectionReturn equipmentSectionReturn;
    public record EquipmentSectionPreview(Screen origin,EquipmentSectionPracticeSession session,TrackLayout track,
                                          CircuitSectionPractice.Start start,ControlSettings controls,
                                          EquipmentSectionPracticeSession.Car car,CircuitSectionPractice sourceResult,
                                          EquipmentSectionPracticeSession.SubsectionReference subsectionReference) {
        public EquipmentSectionPreview {
            if(origin==null || session==null || !session.conditions().matchesTrack(track) || !track.closed
                    || start==null || controls==null || car==null || (origin==Screen.RESULTS)!=(sourceResult!=null)
                    || start.runUpMeters()>CircuitSectionPractice.Start.maximumRunUp(track)
                    || subsectionReference!=null && (subsectionReference.from()!=session.from() || subsectionReference.to()!=session.to()))
                throw new IllegalArgumentException("Invalid equipment section preview");
        }

        public float from() {return session.from();}
        public float to() {return session.to();}

        public EquipmentSectionPreview withStart(CircuitSectionPractice.Start value) {
            return new EquipmentSectionPreview(origin,session,track,value,controls,car,sourceResult,subsectionReference);
        }

        public EquipmentSectionPreview withCar(EquipmentSectionPracticeSession.Car value) {
            return new EquipmentSectionPreview(origin,session,track,start,controls,value,sourceResult,subsectionReference);
        }

        public CircuitSectionPractice practice() {return CircuitSectionPractice.fromTelemetry(track,from(),to(),start);}
    }
    private String sectionDriveKey;
    private CircuitObservationRetest.Source sectionObservation;
    private CircuitObservationRetest sectionObservationRetest;
    private CircuitFeedback.LocationSource sectionFeedback;
    private CircuitFeedbackTrial sectionFeedbackTrial;
    private FeedbackDiscussionReturn sectionFeedbackDiscussion;
    public record FeedbackDiscussionReturn(CircuitFeedbackDiscussion thread,CircuitFeedbackDiscussion.Message message) {
        public FeedbackDiscussionReturn {
            if(thread==null || message==null || message.trial()==null || !message.equals(thread.message(message.id())))
                throw new IllegalArgumentException("A trial attachment in the selected discussion is required");
        }
        public boolean matches(CircuitFeedbackDiscussion current) {
            if(thread==current) return true;
            if(current==null || !thread.id().equals(current.id()) || !thread.title().equals(current.title())
                    || thread.createdAt()!=current.createdAt() || !thread.feedback().equals(current.feedback())) return false;
            var original=message;
            while(original.equals(current.message(original.id()))) {
                if(original.parent()==null) return true;
                original=thread.message(original.parent());
            }
            return false;
        }
    }
    private float previousSectionSpeedMps;
    private record SectionResult(CircuitSectionPractice trial,ControlSettings controls,double sessionTime,String title,String description,
                                 CircuitSplitPracticeHistory splitHistory,CircuitObservationRetest observationRetest,
                                 CircuitFeedbackTrial feedbackTrial) {}
    private SectionResult originalSectionResult;
    private static final int OBSERVATION_RETEST_LIMIT=16;
    private final Deque<CircuitObservationRetest> observationRetests=new ArrayDeque<>();
    private CircuitDriveReview.Snapshot lastCircuitReview;
    private final Map<String,CircuitDriveReview.Snapshot> pendingCircuitReviews=new LinkedHashMap<>();
    private final Set<String> pendingPackInstalls=new LinkedHashSet<>();
    private int circuitReviewIndex=-1;
    private double nextCircuitContactReview;
    private List<LapProgress> circuitTrialProgress=List.of();
    private final List<CircuitDriveReview.Observation> circuitReviewObservations=new ArrayList<>(CircuitTrial.Kind.FOUR_CARS.drivers);
    private final Vector3f circuitReviewPosition=new Vector3f(),circuitReviewVelocity=new Vector3f();
    public FormulaCar player;
    public double sessionTime;
    public float countdown;
    public int sessionLaps = 3;
    public String toast = "", radio = "", resultTitle = "", resultDescription = "";
    public float toastLeft, radioLeft;
    public boolean newBest, helpVisible;
    private final boolean headless, smoke;
    private final String scenario;
    private final Path captures;
    private final Set<String> pressed = new HashSet<>();
    private final Map<String, Object> smokeReport = new LinkedHashMap<>();
    private BulletAppState physics;
    private WorldScene world;
    private SceneFactory factory;
    private HudRenderer hud;
    private Mini4wdGame mini4wd;
    private RaceRoom raceRoom;
    private RaceSession.Settings raceRoomSettings = new RaceSession.Settings(RaceSession.Activity.RACE, 3);
    private RoomRules plannedRoomRules;
    private final Map<Integer,Racer> onlineCars = new LinkedHashMap<>();
    private long onlineSession = -1, dismissedOnlineResult = -1;
    private int onlineLapsRecorded;
    private DriverAI onlineDriver;
    private float onlineFinishWait;
    public boolean controlDown() { return keyboardHeld.contains(KeyInput.KEY_LCONTROL) || keyboardHeld.contains(KeyInput.KEY_RCONTROL); }
    private GameAudio audio;
    private int musicPreviewIndex=-1;
    private float musicPreviewLeft;
    private ScreenCapture screenshots;
    private final Deque<ScreenCapture.Ticket> captureNotices=new ArrayDeque<>();
    private Screen previousScreen = Screen.MENU;
    private Screen trackReturn=Screen.MENU;
    private float trackReturnSpeed;
    private List<TrackCatalog.Entry> listedCustomTracks,listedTracks;
    private DriverAI autoDriver;
    private float uiClock, sceneClock, smokeClock;
    private long smokeCaptureWaitStarted;
    private boolean smokeFinishPending;
    private int smokeStep;
    private boolean practiceRecorded;
    public record PendingCompletion(ProfileStore.SessionCompletion result,String title,String trackName,Tuning tuning,String controls) {}
    private List<PendingCompletion> pendingCompletions=List.of();
    public record PendingLapBest(String key,double seconds,String trackName,Tuning tuning,String controls,boolean online) {}
    private final Map<String,PendingLapBest> pendingLapBests=new LinkedHashMap<>();
    private VerificationDriver verificationDriver;
    private float verificationStageClock, verificationResultWait = -1;
    private int verificationLesson;
    private Vector3f verificationPausePosition;
    private double verificationPauseTime;
    private int baselinePhysicsCount;

    public FormulaGame(Path data, Path captures, Path assetDirectory, boolean headless, boolean smoke, String scenario) {
        this(new ProfileStore(data),captures,assetDirectory,headless,smoke,scenario);
    }

    FormulaGame(ProfileStore profile,Path captures,Path assetDirectory,boolean headless,boolean smoke,String scenario) {
        this.store=Objects.requireNonNull(profile);
        Path data=store.directory();
        this.captures=captures;
        this.controls=new ControlStore(data);
        this.keyboard=new KeyboardStore(data);
        this.experienceStore=new ExperienceStore(data);
        this.activityStore=new ActivityStore(data);
        this.events = new EventStore(data);
        this.eventRecovery=new EventRecoveryStore(data);
        this.eventHistory=new EventHistoryStore(data);
        this.rivals = new RivalStore(data);
        this.circuits=new CircuitStore(data);
        this.creationStore=new CreationStore(data);
        this.circuitLineages=new com.f1car.persistence.CircuitLineageStore(data);
        this.circuitFeedback=new com.f1car.persistence.CircuitFeedbackStore(data);
        this.feedbackDiscussions=new com.f1car.persistence.CircuitFeedbackDiscussionStore(data);
        this.contentText=new com.f1car.persistence.ContentTextStore(data);
        this.telemetryStore=new TelemetryStore(data);
        this.equipmentLaps=new EquipmentLapStore(data);
        this.receivedEquipmentLaps=new ReceivedEquipmentLapStore(data);
        this.tuningStudies=new TuningLapStudyStore(data);
        this.ghostExchange=new GhostExchangeStore(data);
        this.personalBests=new GhostStore(store.directory());
        this.replayStore=new ReplayStore(data);
        this.tuningStore=new TuningStore(data);
        this.pairTrials=new PairTrialStore(data);
        this.receivedPairTrials=new ReceivedPairTrialStore(data);
        this.tuningTraceStore=new TuningTraceStore(data);
        this.partsEditing=new PartsDraftStore(data);
        this.liveryEditing=new com.f1car.persistence.EditorDraftStore<>(data,"livery-editing",LiveryEditor.Snapshot.class);
        this.purposeEditing=new com.f1car.persistence.EditorDraftStore<>(data,"purpose-editing",TuningPurpose.Intent.class);
        this.discussionEditing=new com.f1car.persistence.EditorDraftStore<>(data,"discussion-editing",DiscussionDraft.class);
        this.revisionEditing=com.f1car.persistence.DiscussionRevisionDraftCodec.store(data);
        this.feedbackEditing=new com.f1car.persistence.EditorDraftStore<>(data,"feedback-editing",CircuitFeedbackDraft.class);
        this.feedbackDiscussionEditing=new com.f1car.persistence.EditorDraftStore<>(data,"feedback-discussion-editing",CircuitFeedbackDiscussionDraft.class);
        this.partsAuthoringEditing=new com.f1car.persistence.EditorDraftStore<>(data,"parts-authoring",PartsAuthoring.State.class);
        this.circuitPackEditing = new com.f1car.persistence.EditorDraftStore<>(data, "circuit-pack-editing", CircuitPackDraft.class);
        this.setupLibrary=new SetupLibraryStore(data);
        this.partsStore=new PartsStore(data);
        this.fittedParts=new com.f1car.persistence.FittedPartsStore(data);
        this.partsPlans=new PartsPlanStore(data);
        this.partsEvidence=new com.f1car.persistence.PartsTrialEvidenceStore(data);
        this.partsDiscussions=new com.f1car.persistence.PartsDiscussionStore(data);
        this.discussionRevisions=new com.f1car.persistence.PartsDiscussionRevisionStore(data);
        this.discussionReading=new com.f1car.persistence.DiscussionReadingStore(data);
        observeDiscussionReading();
        this.discussionTraces=new com.f1car.persistence.PartsDiscussionTraceStore(data);
        this.discussionLaps=new com.f1car.persistence.PartsDiscussionLapStore(data);
        this.journal = new JournalStore(data);
        this.liveries=new com.f1car.persistence.LiveryStore(data);
        this.workshopStore=new WorkshopStore(data);workshopStore.reconcile(journal);
        journal.reconcile(events.current(),System.currentTimeMillis(),this::circuitById);
        rivals.reconcile(events.current(),System.currentTimeMillis(),this::circuitById);
        this.assetDirectory = assetDirectory;
        this.headless = headless; this.smoke = smoke; this.scenario = scenario;
        circuit=selectedCircuit();
    }

    @Override public void simpleInitApp() {
        setDisplayFps(false); setDisplayStatView(false);
        flyCam.setEnabled(false);
        // This 2-D game owns controller mappings. FlyCam otherwise assumes POV axes
        // during its later initialization, including on devices that have none.
        stateManager.detach(stateManager.getState(com.jme3.app.FlyCamAppState.class));
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        viewPort.setBackgroundColor(SceneFactory.color(0xb7d7dc));
        cam.setFrustumPerspective(57, (float) cam.getWidth() / cam.getHeight(), .1f, 1400);
        AmbientLight ambient = new AmbientLight(SceneFactory.color(0xdbe6dc).mult(.65f));
        rootNode.addLight(ambient);
        DirectionalLight sun = new DirectionalLight(new Vector3f(-.6f, -1, -.4f).normalizeLocal(), SceneFactory.color(0xfff2d7).mult(1.1f));
        rootNode.addLight(sun);
        viewPort.setEnabled(false); // The visible scene consists entirely of 2-D tiles and sprites.
        physics = new BulletAppState() {
            @Override public void update(float tpf) { super.update(simulationDelta(tpf)); }
        };
        stateManager.attach(physics);
        physics.getPhysicsSpace().setAccuracy(1f / 120);
        physics.getPhysicsSpace().setMaxSubSteps(8);
        factory = new SceneFactory(assetManager);
        bindInput();
        controllerAdapter=new JmeController(inputManager,controllerInput,task->enqueue(()->{task.run();return true;}),this::controllerButton,this::controllerConnectionChanged);
        if (!headless) {
            hud = new HudRenderer(this);
            try { audio = new GameAudio(assetManager); }
            catch (RuntimeException e) { notifyUser("音频暂不可用，其他功能可继续使用。"); }
            try {
                Path path = captures != null ? captures : store.directory().resolve("screenshots");
                Files.createDirectories(path);
                screenshots = new ScreenCapture(path.toAbsolutePath());
                guiViewPort.addProcessor(screenshots);
            } catch (Exception e) { notifyUser("截图目录不可写。"); }
        }
        preview();
        baselinePhysicsCount = physics.getPhysicsSpace().getRigidBodyList().size();
        if (!store.warning.isEmpty()) notifyUser(store.warning);
        if (smoke && !scenario.equals("menu") && !scenario.equals("tour") && !scenario.equals("verify") && !scenario.equals("comparison") && !scenario.equals("mini4wd") && !scenario.startsWith("network-") && !scenario.startsWith("formula-")) {
            switch (scenario) {
                case "race" -> start(Mode.RACE, null);
                case "cup" -> start(Mode.CUP, null);
                case "braking" -> start(Mode.LESSON, PracticeSession.Lesson.BRAKING);
                case "navigation", "audio", "setup-guide", "setup-library", "brake-balance", "vehicle-observation", "axle-suspension" -> mainMenu();
                case "garage" -> open(Screen.GARAGE);
                case "time-trial" -> start(Mode.TIME_TRIAL,null);
                case "editor" -> openCircuitEditor();
                case "editor-crud" -> openCircuitEditor();
                case "pack" -> openCreation();
                default -> start(Mode.FREE_CIRCUIT, null);
            }
        }
        smokeReport.put("initialized", true);
    }

    private void bindInput() {
        inputManager.addRawInputListener(new com.jme3.input.RawInputListener() {
            public void beginInput() {
                pointerButtons.clear();shortcutPresses.clear();
                keyPresses.clear();lastHeldPress.clear();
                readingRawKeys=true;
            }
            public void endInput() { readingRawKeys=false; }
            public void onJoyAxisEvent(com.jme3.input.event.JoyAxisEvent e) { }
            public void onJoyButtonEvent(com.jme3.input.event.JoyButtonEvent e) { }
            public void onMouseMotionEvent(com.jme3.input.event.MouseMotionEvent e) { }
            public void onMouseButtonEvent(MouseButtonEvent e) {
                if(e.getButtonIndex()==MouseInput.BUTTON_LEFT || e.getButtonIndex()==MouseInput.BUTTON_RIGHT
                        || e.getButtonIndex()==MouseInput.BUTTON_MIDDLE)
                    pointerButtons.addLast(new PointerButton(e,controlDown()));
            }
            public void onTouchEvent(com.jme3.input.event.TouchEvent e) { }
            public void onKeyEvent(KeyInputEvent e) {
                if(!windowFocused()) {e.setConsumed();return;}
                int key=e.getKeyCode();
                if(!e.isPressed()) releaseKeyboardKey(key);
                boolean modifier=keyboardModifier(key);
                if(modifier && e.isPressed()) keyboardHeld.add(key);
                if(capturingKey!=null) {
                    if(e.isPressed() && !e.isRepeating()) acceptKeyboardKey(key);
                    e.setConsumed();return;
                }
                boolean controlDown=controlDown();
                if (hud != null && hud.typing()) { hud.textKey(e, controlDown);e.setConsumed(); }
                else if(hud!=null && !controlDown && calibration==null && capturingButton==null
                    && hud.keyboardMenuKey(e,keyboardHeld.contains(KeyInput.KEY_LSHIFT) || keyboardHeld.contains(KeyInput.KEY_RSHIFT))) {
                    e.setConsumed();
                }
                else if(hud!=null && !controlDown && replayViewing() && !helpVisible && key==KeyInput.KEY_M) {
                    if(e.isPressed() && !e.isRepeating()) hud.showReplayMarkers();
                    e.setConsumed();
                }
                if (!modifier && e.isPressed() && !e.isRepeating() && !e.isConsumed()) {
                    keyPresses.addLast(e);
                    lastHeldPress.put(key,e);
                    String shortcut=EDITOR_SHORTCUTS.get(key);
                    if(shortcut!=null) shortcutPresses.addLast(new ShortcutPress(e,shortcut,controlDown));
                }
            }
        });
        bindKeyboard();
        bind("escape", KeyInput.KEY_ESCAPE);
        bind("enter", KeyInput.KEY_RETURN);
        bind("capture", KeyInput.KEY_F12);
        bind("assets", KeyInput.KEY_F6);
        bind("miniRotateLeft", KeyInput.KEY_Q);
        bind("miniRotateRight", KeyInput.KEY_E);
        bind("miniUndo", KeyInput.KEY_Z);
        bind("miniDelete", KeyInput.KEY_DELETE);
        bind("miniRedo", KeyInput.KEY_Y);
        EDITOR_SHORTCUTS.forEach((key,name)->{
            inputManager.addMapping(name,new KeyTrigger(key));
            inputManager.addListener((ActionListener)this::editorShortcut,name);
        });
        inputManager.addMapping("miniPan", new MouseButtonTrigger(MouseInput.BUTTON_MIDDLE));
        inputManager.addListener((ActionListener) this::action, "miniPan");
        inputManager.addMapping("miniZoomIn", new MouseAxisTrigger(MouseInput.AXIS_WHEEL, false));
        inputManager.addMapping("miniZoomOut", new MouseAxisTrigger(MouseInput.AXIS_WHEEL, true));
        inputManager.addListener((AnalogListener) (name, value, dt) -> {
            if (windowFocused() && hud != null) hud.zoomEditing(name.equals("miniZoomIn"));
        }, "miniZoomIn", "miniZoomOut");
        inputManager.addMapping("click", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addMapping("secondaryClick", new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        inputManager.addListener((ActionListener) this::action, "click", "secondaryClick");
    }
    private void bind(String name, int... keys) {
        Trigger[] triggers = Arrays.stream(keys).mapToObj(KeyTrigger::new).toArray(Trigger[]::new);
        inputManager.addMapping(name, triggers);
        ActionListener listener=heldDrivingCommand(name)==null?this::action:this::heldKeyboardAction;
        inputManager.addListener(listener,name);
    }
    private void heldKeyboardAction(String name,boolean down,float tpf) {
        if(down) {
            // jME marks an event consumed after dispatching its mappings. The first
            // unconsumed press is the one driving this callback, including after a reset.
            while(!keyPresses.isEmpty() && keyPresses.peekFirst().isConsumed()) keyPresses.removeFirst();
            var press=keyPresses.peekFirst();
            if(press==null || lastHeldPress.get(press.getKeyCode())!=press) return;
            keyboardHeld.add(press.getKeyCode());
        }
        action(name,down,tpf);
    }
    private void editorShortcut(String name,boolean down,float tpf) {
        if(!down) return;
        // jME delivers the entire raw batch before mapped actions. A later listener can consume a press.
        while(!shortcutPresses.isEmpty() && shortcutPresses.peekFirst().event().isConsumed()) shortcutPresses.removeFirst();
        var press=shortcutPresses.peekFirst();
        if(press==null || !press.action().equals(name)) return;
        shortcutPresses.removeFirst();
        if(press.control()) action(name,true,tpf);
    }
    private void bindKeyboard() {
        clearDrivingKeys();
        for(var command:KeyboardSettings.Command.values()) {
            if(inputManager.hasMapping(command.action)) inputManager.deleteMapping(command.action);
            bind(command.action,keyboard.settings().bindings().get(command).keys());
        }
    }
    private void action(String name, boolean down, float tpf) {
        var cursor=inputManager.getCursorPosition();
        int button=switch(name) {
            case "click" -> MouseInput.BUTTON_LEFT;
            case "secondaryClick" -> MouseInput.BUTTON_RIGHT;
            case "miniPan" -> MouseInput.BUTTON_MIDDLE;
            default -> -1;
        };
        boolean pointerControl=button>=0 && controlDown();
        if(button>=0) {
            while(!pointerButtons.isEmpty() && pointerButtons.peekFirst().event().isConsumed()) pointerButtons.removeFirst();
            var sample=pointerButtons.peekFirst();
            var event=sample!=null?sample.event():null;
            if(event!=null && event.getButtonIndex()==button && event.isPressed()==down) {
                pointerButtons.removeFirst();
                pointerControl=sample.control();
                // During dispatch the shared cursor may already contain a later event from this batch.
                cursor=new Vector2f(event.getX(),event.getY());
            }
        }
        if(!windowFocused()) return;
        if(hud!=null && name.equals("click") && !down) hud.releaseReplayPointer(cursor);
        if(capturingKey!=null && !name.equals("click") && !name.equals("capture")) return;
        if(hud!=null && hud.typing() && !name.equals("capture")) {
            if(name.equals("click")) { if(down) hud.click(cursor);else { hud.endCircuitPointer();hud.endMiniPointer();hud.endPreviewPan(); } }
            else if(name.equals("escape") && down) hud.cancelPrompt();
            return;
        }
        if(hud!=null && hud.transitioning() && !name.equals("capture")) return;
        if(screen==Screen.GAME_SETUP && hud!=null) {
            if(!down && (button==MouseInput.BUTTON_LEFT || button==MouseInput.BUTTON_MIDDLE))
                hud.releasePreviewPan(button,cursor);
            if(button==MouseInput.BUTTON_MIDDLE) {
                if(down) hud.beginMiddlePreviewPan(cursor);
                return;
            }
        }
        if(screen==Screen.MAP_EDITOR && !name.equals("capture")) { circuitEditorAction(name,down,cursor,pointerControl);return; }
        if (screen == Screen.MINI4WD && !name.equals("capture")) {
            mini4wdAction(name, down, cursor,pointerControl);
            return;
        }
        var drivingKey = heldDrivingCommand(name);
        if (drivingKey != null) {
            if (down && screen == Screen.DRIVING && !helpVisible) pressed.add(name);
            else if (screen != Screen.DRIVING || !keyboardBindingHeld(drivingKey)) pressed.remove(name);
            return;
        }
        if (!down) return;
        switch (name) {
            case "click" -> { if (hud != null) hud.pointerPress(cursor); }
            case "escape" -> {
                if(screen==Screen.KEYBOARD) backFromKeyboard();
                else if(screen==Screen.LIVERY) backFromLiveryEditor();
                else if(screen==Screen.TUNING_GUIDE) open(Screen.GARAGE);
                else if(screen==Screen.SETUP_LIBRARY) backFromSetupLibrary();
                else if(screen==Screen.PARTS) backFromPartsWorkbench();
                else if(screen==Screen.CONTROLS) { if(calibration!=null || capturingButton!=null) cancelCalibration();else backFromControls(); }
                else if (helpVisible) setHelpVisible(false);
                else if (screen == Screen.DRIVING) pause();
                else if (screen == Screen.PAUSED) resume();
                else if (screen == Screen.SETTINGS) backFromSettings();
                else if (screen == Screen.TRACKS) backFromTracks();
                else if (screen == Screen.GAME_SETUP) {
                    var setup=launchSetup;
                    if(hud!=null) hud.navigate(()->cancelLaunch(setup));else cancelLaunch(setup);
                }
                else if (screen == Screen.START_MENU && hud!=null) hud.backStartMenu();
                else if (screen==Screen.CREATION) {if(hud==null || !hud.backWithinCreation()) closeCreationToMain();}
                else if (screen == Screen.RACE_SETUP) openRaceRoom();
                else if (screen == Screen.TELEMETRY) backFromTelemetry();
                else if (screen == Screen.GHOSTS) backFromGhosts();
                else if (screen == Screen.REPLAY) closeReplay();
                else if (screen == Screen.REPLAYS) backFromReplays();
                else if (screen == Screen.TUNING_LAB) backFromTuningLab();
                else if (screen == Screen.WORKSHOP) mainMenu();
                else if (screen == Screen.EXPERIENCE) backFromExperience();
                else if (screen == Screen.RESULTS && (circuitTrial!=null || circuitSectionPractice!=null)) {
                    if(!returnToOriginalSectionResult() && !returnToEquipmentSection() && !returnToCircuitFeedback()) returnToCircuitEditor();
                }
                else if (screen == Screen.GARAGE && online()) backFromGarage();
                else if (screen != Screen.MENU) mainMenu();
            }
            case "enter" -> {
                if (screen == Screen.MENU) { if(hud!=null) hud.navigate(this::openStartMenu);else openStartMenu(); }
                else if(screen==Screen.START_MENU && hud!=null) hud.controllerConfirm();
                else if(screen==Screen.GAME_SETUP && hud!=null) hud.confirmLaunch();
                else if (screen == Screen.RACE_ROOM && raceRoom != null) { if (raceRoom.host()) confirmRoomStart(); else raceRoom.ready(!raceRoom.ready()); }
                else if (screen == Screen.PAUSED) resume();
                else if (screen == Screen.RESULTS) retry();
                else if (replayViewing() && !helpVisible) replayPlayer.toggle();
            }
            case "reset" -> { if (screen == Screen.DRIVING || screen == Screen.RESULTS) resetAttempt(); }
            case "camera" -> {
                if(replayViewing()) {
                    if(!helpVisible) replayPlayer.overviewToggle();
                } else { store.profile.camera=(store.profile.camera+1)%3;store.save(); }
            }
            case "help" -> { if(screen!=Screen.CONTROLS) setHelpVisible(!helpVisible); }
            case "ghost" -> { if (timeTrial!=null || experience!=null) toggleGhost(); }
            case "capture" -> capture("manual-");
            case "assets" -> {
                if (screen == Screen.DRIVING) pause();
                if (hud != null) hud.requestAssetReload();
            }
        }
    }
    private void circuitEditorAction(String name,boolean down,Vector2f cursor,boolean pointerControl) {
        if(hud!=null && hud.typing()) return;
        if(name.equals("click") && !down && hud!=null) hud.releaseCircuitPointer(cursor);
        if(name.equals("miniPan") && hud!=null) { hud.panCircuit(down,cursor);return; }
        if(!down) return;
        if(circuitEditor==null) { if(name.equals("click") && hud!=null) hud.click(cursor);else if(name.equals("escape")) closeCircuitEditor();return; }
        if(hud!=null && hud.circuitOriginalNearbyOpen() && !name.equals("click")) {
            if(name.equals("escape")) hud.closeCircuitOriginalNearby();
            return;
        }
        if(hud!=null && hud.circuitLibraryOpen() && !Set.of("click","escape","miniExport","miniImport","miniUndo","miniRedo").contains(name)) return;
        switch(name) {
            case "click" -> { if(hud!=null) hud.click(cursor,pointerControl); }
            case "secondaryClick" -> { if(hud!=null) hud.eraseCircuit(cursor,pointerControl); }
            case "escape" -> {
                if(hud!=null && hud.circuitLibraryOpen()) hud.closeCircuitLibrary();
                else if(hud==null || !hud.backFromCircuitNearby()) closeCircuitEditor();
            }
            case "miniUndo" -> circuitEditor.undo();
            case "miniRedo" -> circuitEditor.redo();
            case "miniCopy" -> copyCircuitSelection();
            case "miniPaste" -> pasteCircuitSelection();
            case "miniSelectAll" -> { if(circuitEditor.tool()==CircuitEditor.Tool.POINT || circuitEditor.selectedAnchorCount()>0) circuitEditor.selectAllAnchors();else circuitEditor.selectAllItems(); }
            case "miniDelete" -> circuitEditor.delete();
            case "miniRotateLeft" -> circuitEditor.rotate(-FastMath.HALF_PI);
            case "miniRotateRight" -> circuitEditor.rotate(FastMath.HALF_PI);
            case "miniExport" -> saveCircuitDraft();
            case "miniImport" -> { if(hud!=null) hud.chooseCircuitFile(false); }
            default -> { }
        }
    }

    private static boolean keyboardModifier(int key) {
        return key==KeyInput.KEY_LCONTROL || key==KeyInput.KEY_RCONTROL || key==KeyInput.KEY_LSHIFT || key==KeyInput.KEY_RSHIFT;
    }

    private static KeyboardSettings.Command heldDrivingCommand(String name) {
        return switch(name) {
            case "throttle" -> KeyboardSettings.Command.THROTTLE;
            case "brake" -> KeyboardSettings.Command.BRAKE;
            case "left" -> KeyboardSettings.Command.LEFT;
            case "right" -> KeyboardSettings.Command.RIGHT;
            case "handbrake" -> KeyboardSettings.Command.HANDBRAKE;
            default -> null;
        };
    }

    private void releaseKeyboardKey(int key) {
        lastHeldPress.remove(key);
        if(hud!=null) hud.releaseNavigationKey(key);
        if(!keyboardHeld.remove(key) || pressed.isEmpty()) return;
        // A prompt can consume the release before jME delivers the mapped action.
        for(var entry:keyboard.settings().bindings().entrySet()) {
            var binding=entry.getValue();
            if((key==binding.primary() || key==binding.alternate()) && !keyboardBindingHeld(entry.getKey()))
                pressed.remove(entry.getKey().action);
        }
    }

    private boolean keyboardBindingHeld(KeyboardSettings.Command command) {
        var binding = keyboard.settings().bindings().get(command);
        return keyboardHeld.contains(binding.primary())
                || (binding.alternate() >= 0 && keyboardHeld.contains(binding.alternate()));
    }

    private void mini4wdAction(String name, boolean down, Vector2f cursor,boolean pointerControl) {
        var view = mini4wd.view();
        if (name.equals("miniPan") && hud != null) { hud.panMini(down,cursor);return; }
        if (name.equals("click") && !down && hud != null) hud.releaseMiniPointer(cursor);
        if (!down) return;
        switch (name) {
            case "handbrake" -> { mini4wd.keyboardBoost(true); return; }
            case "click" -> {
                if (hud != null) hud.click(cursor,pointerControl);
                return;
            }
            case "secondaryClick" -> {
                if (hud != null) hud.eraseMiniTrack(cursor,pointerControl);
                return;
            }
            case "escape" -> {
                if (view == Mini4wdGame.View.MAPS) openMiniHome();
                else if (hud != null) hud.backMini();
                else mini4wd.back();
            }
            case "enter" -> {
                if (view == Mini4wdGame.View.ROOM && mini4wd.roomActive()) {
                    if (mini4wd.room().host()) mini4wd.room().startRace();
                    else mini4wd.room().ready(!mini4wd.room().ready());
                } else if (view == Mini4wdGame.View.MAPS || view == Mini4wdGame.View.EDITOR) mini4wd.startTrial();
                else if(view==Mini4wdGame.View.TRIAL && !mini4wd.roomActive() && mini4wd.trial()!=null
                    && mini4wd.trial().result()!=MiniTrial.Result.RUNNING) mini4wd.retry();
            }
            case "reset" -> { if (view == Mini4wdGame.View.TRIAL) mini4wd.retry(); }
            case "miniRotateLeft" -> { if (view == Mini4wdGame.View.EDITOR) mini4wd.rotate(-1); }
            case "miniRotateRight" -> { if (view == Mini4wdGame.View.EDITOR) mini4wd.rotate(1); }
            case "miniUndo" -> { if (view == Mini4wdGame.View.EDITOR) mini4wd.undo(); }
            case "miniRedo" -> { if (view == Mini4wdGame.View.EDITOR) mini4wd.redo(); }
            case "miniCopy" -> { if (view == Mini4wdGame.View.EDITOR) mini4wd.copySelection(); }
            case "miniPaste" -> { if (view == Mini4wdGame.View.EDITOR) mini4wd.pasteSelection(); }
            case "miniSelectAll" -> { if (view == Mini4wdGame.View.EDITOR) mini4wd.selectAll(); }
            case "miniImport" -> { if (hud != null && (view == Mini4wdGame.View.MAPS || view == Mini4wdGame.View.EDITOR)) hud.chooseMapFile(false); }
            case "miniExport" -> { if (hud != null && (view == Mini4wdGame.View.MAPS || view == Mini4wdGame.View.EDITOR)) hud.chooseMapFile(true); }
            case "miniDelete" -> { if (view == Mini4wdGame.View.EDITOR) mini4wd.deleteShape(); }
            default -> { return; }
        }
        if (hud != null) hud.render();
    }

    private void disposeSession() {
        pairRunActive=false;
        checkpointPartsEditing();
        rememberCircuitReview();nextCircuitContactReview=0;circuitTrialProgress=List.of();
        circuitSectionPractice=null;
        equipmentSectionReturn=null;
        sectionDriveKey=null;
        sectionObservation=null;
        sectionObservationRetest=null;
        sectionFeedback=null;
        sectionFeedbackTrial=null;
        sectionFeedbackDiscussion=null;
        originalSectionResult=null;
        activityTrial=null;localFramePositions=null;localResultOrder=null;activityRecorded=false;
        cancelReplayLoading();finishReplay(RaceReplay.End.STOPPED);replayPlayer=null;replayClose=Screen.REPLAYS;
        pressed.clear();
        onlineCars.clear();
        pendingAiRecoveries.clear();
        localRecoverySearch=null;
        for (Racer r : racers) r.car.close();
        racers.clear();
        qualifyingProgress=List.of();
        trafficCars.clear();
        trafficSampler.clear();
        player = null;
        if (eventRaceTrafficReview != null) {
            eventRaceTrafficReview.end(EventRaceTrafficReview.End.LEFT);
            eventRaceTrafficReview = null;
        }
        eventRaceClock=null;
        eventRaceTiming=null;
        if (world != null) { world.close(); world = null; }
        practice = null; cup = null; timeTrial = null;challenge=null;runningEvent=null;qualifying=null;bookAttempt=null;finishRecorded=false;circuitTrial=null;tuningTrial=null;workshop=null;experience=null;experienceReference=null;experiencePlayer=null;experienceRecorded=false;
        withAudio(GameAudio::stopEffects);
        radio = ""; radioLeft = 0;
    }
    private void createWorld(boolean training) {
        trackGuide=TrackGuide.forTrack(circuit);
        world = new WorldScene(factory, physics.getPhysicsSpace(), circuit, training);
        rootNode.attachChild(world.node);
    }
    private FormulaCar addCar(String name, Tuning tuning, float distance, float side, boolean ai, float pace, int laps) {
        FormulaCar car = new FormulaCar(name, factory, physics.getPhysicsSpace(), vehicleSpec, tuning);
        rootNode.attachChild(car.node);
        var s = circuit.sample(distance);
        car.recover(s.position().add(s.right().mult(side)), s.tangent());
        racers.add(new Racer(car, ai ? new DriverAI(circuit, pace, side * .35f) : null, new LapProgress(circuit, laps)));
        trafficCars.add(car);
        return car;
    }
    private void preview() {
        preview(online()?raceRoom.track():selectedCircuit());
    }
    private void preview(TrackLayout track) {
        disposeSession();circuit=track;
        createWorld(false);
        player = addCar("岚隼", store.profile.tuning, 80, 0, false, 1, 99999);
        screen = Screen.MENU; physics.setSpeed(1);
        player.halt(); cameraImmediate();
    }
    public void start(Mode newMode, PracticeSession.Lesson lesson) {
        var tuning=store.profile.tuning;
        if(newMode==Mode.TIME_TRIAL && !garageCandidateAllowed(tuning)) return;
        startSession(newMode,lesson,selectedCircuit(),tuning,fittedParts.state().installed());
    }
    private void startSession(Mode newMode,PracticeSession.Lesson lesson,TrackLayout track,Tuning tuning,FittedParts installed) {
        if(!controlsReady(controls.settings())) return;
        if(newMode==Mode.EVENT_PRACTICE || newMode==Mode.QUALIFYING || newMode==Mode.EVENT_RACE || newMode==Mode.MAP_TRIAL || newMode==Mode.TUNING_TRIAL || newMode==Mode.WORKSHOP || newMode==Mode.EXPERIENCE || newMode==Mode.MAP_SOLO || newMode==Mode.ACTIVITY || newMode==Mode.MAP_SECTION)
            throw new IllegalArgumentException("Use event checkpoint to start a stage");
        closeRaceRoom();
        disposeSession();freezeControls(controls.settings());circuit=track;mode=newMode;attemptId=UUID.randomUUID().toString();
        boolean training = newMode == Mode.FREE_PAD || (newMode == Mode.LESSON && !lesson.onCircuit());
        createWorld(training);
        sessionLaps = newMode==Mode.CHALLENGE?challengeKind.laps:store.profile.raceLaps;
        int laps = newMode == Mode.RACE || newMode==Mode.CHALLENGE ? sessionLaps : newMode == Mode.CUP ? 1 : 99999;
        player = addCar("你 · 岚隼", tuning, newMode==Mode.CHALLENGE?challengeKind.playerDistance:-18, newMode == Mode.RACE ? -2 : 0, false, 1, laps);
        if (newMode == Mode.RACE) {
            float pace = new float[]{.76f, .90f, 1f}[store.profile.difficulty];
            addCar("01 · 潮汐", new Tuning(1, 1, 1, 1, 1), -10, 2, true, pace * .97f, laps);
            addCar("02 · 银杉", new Tuning(1, 1, 1, 1, 2), -10, -2, true, pace, laps);
            addCar("03 · 曜石", new Tuning(1, 1, 1, 1, 3), -18, 2, true, pace * 1.025f, laps);
            localFramePositions=new Vector3f[racers.size()];
        } else if(newMode==Mode.CHALLENGE) {
            challenge=new RaceChallenge(challengeKind);
            addCar("对手 · 潮汐",new Tuning(1,1,1,1,1),challengeKind.opponentDistance,0,true,challengeKind.pace,laps);
            localFramePositions=new Vector3f[racers.size()];
        } else if (newMode == Mode.LESSON) {
            practice = new PracticeSession(lesson, circuit,new SplittableRandom().nextLong());
            var spawn = practice.spawn(); player.recover(spawn.position(), spawn.forward());
            racers.get(0).previous = player.position();
            if(lesson.traffic()) addCar(lesson==PracticeSession.Lesson.REJOIN?"来车 · 潮汐":"领车 · 潮汐",new Tuning(1,1,1,1,1),practice.opponentDistance(),0,true,practice.opponentPace(),99999);
        } else if (newMode == Mode.FREE_PAD) {
            player.recover(new Vector3f(-65, 0, -100), Vector3f.UNIT_Z);
            racers.get(0).previous = player.position();
        }
        if (newMode == Mode.CUP) {
            cup=new CupChallenge();
            localFramePositions=new Vector3f[racers.size()];
            player.installCup(factory);
        }
        if (newMode == Mode.TIME_TRIAL) {
            localFramePositions=new Vector3f[racers.size()];
            var source=new EquipmentLap.Source(vehicleSpec.id,vehicleSpec.physicsVersion,tuning,installed);
            timeTrial=new TimeTrialSession(circuit,vehicleSpec.physicsVersion,tuning,personalBests,telemetryStore,controlsIdentity(),source);
            restoreGhostReference();
            String warning=timeTrial.consumeNotice();if(!warning.isEmpty()) notifyUser(warning);
        }
        verificationDriver = practice != null ? new VerificationDriver(practice) : null;
        sessionTime = 0; countdown = newMode == Mode.RACE || newMode==Mode.CHALLENGE ? 4 : newMode == Mode.TIME_TRIAL || lesson==PracticeSession.Lesson.REACTION ? 0 : lesson!=null && lesson.traffic()?3:.8f;
        practiceRecorded = false; newBest = false; helpVisible = false;
        screen = Screen.DRIVING; physics.setSpeed(1); cameraImmediate();
        autoDriver = new DriverAI(circuit, .88f, 0);
        smokeReport.put("sessionStarted", newMode.name());
        if(newMode==Mode.RACE || newMode==Mode.CHALLENGE) beginReplay(false,null);
    }
    public void openTracks() {
        if(online() || screen!=Screen.MENU && screen!=Screen.COURSES && screen!=Screen.CHALLENGES && screen!=Screen.BOOK && screen!=Screen.WORKSHOP) return;
        if (screen != Screen.BOOK) refreshLocalRaceContent(true, false);
        trackReturn=screen;trackReturnSpeed=physics.getSpeed();screen=Screen.TRACKS;pressed.clear();physics.setSpeed(0);if(hud!=null) hud.beginTrackSelection(circuit.id);
    }
    public void backFromTracks() { if(screen==Screen.TRACKS) restoreTrackScreen(); }
    private void restoreTrackScreen() { screen=trackReturn;physics.setSpeed(trackReturnSpeed); }
    public void selectCircuit(String id) { selectCircuit(id, null); }
    public void selectCircuit(String id, TrackLayout preview) {
        if(screen!=Screen.TRACKS || online()) return;
        if (trackReturn == Screen.BOOK && !builtinTrackId(id)) return;
        if (!refreshTrackChoice(id)) return;
        TrackCatalog.Entry entry;
        try {entry=circuitEntry(id);}
        catch(IllegalArgumentException error) {notifyUser("该赛道已不在当前选图列表，请重新选择。");return;}
        if (preview != null && !sameTrackPreview(preview, entry.layout())) {
            notifyUser("赛道已修改，请查看更新后的卡片再选择。");
            return;
        }
        store.profile.trackId=entry.id();boolean saved=store.save();preview();restoreTrackScreen();
        notifyUser(saved?"已选择 "+circuit.name:"赛道已切换；配置保存失败，请检查用户目录。");
    }

    private static boolean builtinTrackId(String id) {
        for (var entry : TrackCatalog.all()) if (entry.id().equals(id)) return true;
        return false;
    }

    private boolean refreshTrackChoice(String id) { return builtinTrackId(id) || refreshLocalRaceContent(true, false); }

    private boolean refreshEventTracks(List<EventSeries.Round> calendar) {
        for (var round : calendar) if (!builtinTrackId(round.trackId())) return refreshLocalRaceContent(true, false);
        return true;
    }

    public boolean refreshCalendarTrackList() {
        return screen == Screen.EVENTS && !online() && refreshLocalRaceContent(true, false);
    }

    private boolean confirmTrackPreview(TrackLayout preview) {
        try {
            var accepted = circuitById(preview.id);
            if (online() ? preview.geometryKey().equals(accepted.geometryKey()) : sameTrackPreview(preview, accepted)) return true;
            notifyUser("赛道已修改，请重新选择或返回以重新预览。");
        } catch (IllegalArgumentException unavailable) {
            notifyUser("赛道缺失或资格已失效，请重新选择。");
        }
        return false;
    }

    private static boolean sameTrackPreview(TrackLayout before, TrackLayout after) {
        return before == after || (before.geometryKey().equals(after.geometryKey())
                && before.name.equals(after.name) && Objects.equals(before.scene(), after.scene()));
    }
    public List<TrackCatalog.Entry> availableTracks() {
        if(screen==Screen.TRACKS && trackReturn==Screen.BOOK) return TrackCatalog.all();
        var custom=circuits.playable(vehicleSpec.physicsVersion);
        if(listedCustomTracks!=custom) {
            listedCustomTracks=custom;
            if(custom.isEmpty()) listedTracks=TrackCatalog.all();
            else {
                var result=new ArrayList<>(TrackCatalog.all());
                result.addAll(custom);
                listedTracks=List.copyOf(result);
            }
        }
        return listedTracks;
    }
    public TrackCatalog.Entry circuitEntry(String id) {
        var entry = availableCircuitEntry(id);
        if(entry!=null) return entry;
        throw new IllegalArgumentException("赛道尚未通过当前版本的试跑");
    }
    /** Returns the current cached selection entry, or null for an unavailable map. */
    public TrackCatalog.Entry availableCircuitEntry(String id) {
        for(var entry:TrackCatalog.all()) if(entry.id().equals(id)) return entry;
        return circuits.playable(id,vehicleSpec.physicsVersion);
    }
    public TrackLayout circuitById(String id) { return circuitEntry(id).layout(); }
    private TrackLayout selectedCircuit() {
        try { return circuitById(store.profile.trackId); } catch(IllegalArgumentException error) { return TrackCatalog.require(TrackCatalog.DEFAULT_ID).layout(); }
    }
    private TrackLayout selectedCircuitForLaunch() {
        boolean refreshed = !online() && refreshLocalRaceContent(true, false);
        var selected = selectedCircuit();
        if (refreshed && !selected.id.equals(store.profile.trackId))
            notifyUser("原赛道已缺失或失去资格，当前显示默认赛道，可重新选择。");
        return selected;
    }
    public boolean eventCompatible(EventSeries event) {
        return event.compatible(vehicleSpec.physicsVersion, id -> {
            var entry = availableCircuitEntry(id);
            return entry == null ? null : entry.layout();
        });
    }
    public CircuitEditor circuitEditor() { return circuitEditor; }
    private void setCircuitEditor(CircuitDocument document,CircuitTrial.Proof proof,boolean saved) {
        if(circuitEditor!=null && !circuitEditor.document().id().equals(document.id())) { circuitVisits.remove(circuitEditor.document().id());circuitVisits.addLast(circuitEditor.document().id()); }
        circuitEditor=new CircuitEditor(document,proof,saved,circuitClipboard);circuitEditor.restrict(creationStore.commission(document.id()));
        reloadCircuitReviews();
        if(hud!=null) hud.resetCircuitEditorState();
    }
    public List<CircuitStore.Revision> circuitHistory() { return circuitEditor==null?List.of():circuits.find(circuitEditor.document().id()).map(CircuitStore.Saved::history).orElse(List.of()); }
    public List<CircuitStore.Review> circuitReviews() { return circuitEditor==null?List.of():circuits.find(circuitEditor.document().id()).map(CircuitStore.Saved::reviews).orElse(List.of()); }
    public int circuitReviewIndex() { return circuitReviewIndex; }
    public int circuitReviewCapacity() { return circuitEditor==null?CircuitStore.REVIEW_LIMIT:circuits.find(circuitEditor.document().id()).map(CircuitStore.Saved::reviewCapacity).orElse(CircuitStore.REVIEW_LIMIT); }
    private void reloadCircuitReviews() {
        var reviews=circuitReviews();
        var pending=circuitEditor==null?null:pendingCircuitReviews.get(circuitEditor.document().id());
        circuitReviewIndex=pending==null?reviews.size()-1:-1;
        lastCircuitReview=pending!=null?pending:circuitReviewIndex<0?null:reviews.get(circuitReviewIndex).snapshot();
    }
    public void changeCircuitReviewCapacity(int capacity) {
        if(circuitEditor==null) return;
        if(circuits.find(circuitEditor.document().id()).isEmpty() && !persistCircuitDraft()) return;
        if(circuits.reviewCapacity(circuitEditor.document().id(),capacity)) { reloadCircuitReviews();notifyUser("试跑记录保留上限已设为 "+capacity+" 次。"); }else notifyUser(circuits.warning);
    }
    public void deleteCircuitReview(CircuitStore.Review review) {
        if(circuitEditor==null) return;if(circuits.deleteReview(circuitEditor.document().id(),review)) { reloadCircuitReviews();notifyUser("本次试跑记录已删除。"); }else notifyUser(circuits.warning);
    }
    public void clearCircuitReviews() {
        if(circuitEditor==null) return;String id=circuitEditor.document().id();
        if(circuits.clearReviews(id)) { pendingCircuitReviews.remove(id);reloadCircuitReviews();notifyUser("本图试跑记录已清空；地图和版本历史保留。"); }else notifyUser(circuits.warning);
    }
    public void discardPendingCircuitReview() { if(circuitEditor!=null) { pendingCircuitReviews.remove(circuitEditor.document().id());reloadCircuitReviews();notifyUser("待保存的试跑记录已删除，已存记录保留。"); } }
    public boolean circuitReviewPending() { return circuitEditor!=null && pendingCircuitReviews.containsKey(circuitEditor.document().id()); }
    public void cycleCircuitReview(int delta) {
        var reviews=circuitReviews();if(reviews.isEmpty() || circuitTrial!=null) return;
        circuitReviewIndex=circuitReviewIndex<0?(delta<0?reviews.size()-1:0):Math.floorMod(circuitReviewIndex+delta,reviews.size());
        lastCircuitReview=reviews.get(circuitReviewIndex).snapshot();
    }
    public void restoreCircuitRevision(String id) {
        if(screen!=Screen.MAP_EDITOR || circuitEditor==null) return;
        var revision=circuitHistory().stream().filter(value->value.id().equals(id)).findFirst().orElse(null);if(revision==null) { notifyUser("此版本已不在历史中。");return; }
        if(circuitEditor.restore(revision.document())) { if(hud!=null) hud.resetCircuitEditorView();notifyUser("已还原为待保存草稿，可撤销；道路改变后需重新四车验证。"); }
        else notifyUser(circuitEditor.restriction().isEmpty()?"当前草稿已经是此版本。":circuitEditor.restriction());
    }
    public boolean restoreCircuitRevision(CircuitStore.Revision expected,CircuitDocument expectedCurrent) {
        if(online() || screen!=Screen.MAP_EDITOR || circuitEditor==null || expected==null || expectedCurrent==null) return false;
        if(!circuitEditor.document().equals(expectedCurrent) || !circuitHistory().contains(expected)) {notifyUser("草稿或所选历史已变化，请重新比较后再还原。");return false;}
        restoreCircuitRevision(expected.id());return circuitEditor.document().equals(expected.document());
    }
    public List<CircuitRevisionImpact.Reference> circuitActivityReferences() {
        var values=new ArrayList<CircuitRevisionImpact.Reference>();for(var card:activityStore.cards()) values.add(new CircuitRevisionImpact.Reference("活动卡库",card));var active=activityStore.active();if(active!=null) values.add(new CircuitRevisionImpact.Reference("当前活动场次",active.card()));for(var entry:creationStore.packs()) values.add(new CircuitRevisionImpact.Reference("赛事包："+entry.original().title(),entry.activity()));return List.copyOf(values);
    }
    public void copyCircuitSelection() {
        if(circuitEditor==null) return;
        notifyUser(circuitEditor.copySelected()?"已复制 "+circuitEditor.clipboardSize()+" 个场景物件，可切换草稿粘贴。":circuitEditor.restriction());
    }
    public void pasteCircuitSelection() {
        if(circuitEditor==null) return;
        if(circuitEditor.paste()) notifyUser("已粘贴 "+circuitEditor.selectedItemCount()+" 个物件，可整组拖动；需要重新四车验证。");
        else notifyUser(circuitEditor.restriction());
    }
    public CircuitCommission circuitCommission() { return circuitEditor==null?null:creationStore.commission(circuitEditor.document().id()); }
    public void openCreation() {
        if(online()) return;if(screen==Screen.MAP_EDITOR && circuitEditor!=null && !persistCircuitDraft()) return;mainMenu();if(circuitEditor==null) loadAvailableCircuitDraft();screen=Screen.CREATION;physics.setSpeed(0);recordCreationResult();
    }
    public void startCommission(CircuitCommission.Kind kind) {
        if(screen!=Screen.CREATION || creationStore.pending()) { notifyUser("请先保存暂存的创作记录。");return; }
        if(creationStore.briefs().size()>=16 || circuits.all().size()>=32) { notifyUser("委托或地图槽位已满，请先整理现有草稿。");return; }
        if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return;var brief=CircuitCommission.start(kind);
        if(!circuits.save(brief.template(),null)) { notifyUser(circuits.warning);return; }boolean saved=creationStore.begin(brief);setCircuitEditor(brief.template(),null,true);screen=Screen.MAP_EDITOR;physics.setSpeed(0);if(hud!=null) hud.resetCircuitEditorView();
        if(kind==CircuitCommission.Kind.REPAIR) circuitEditor.focus(new CircuitInspector.Issue(CircuitInspector.Code.CURVATURE,"委托修改区域",List.of(new CircuitInspector.Location(126,96)),4,-1));else if(kind==CircuitCommission.Kind.CLEAR_BUFFER) circuitEditor.focus(new CircuitInspector.Issue(CircuitInspector.Code.OBJECT,"需要移动的护栏",List.of(new CircuitInspector.Location(40,-130)),-1,0));else if(kind==CircuitCommission.Kind.SIGNPOST) circuitEditor.tool(CircuitEditor.Tool.SIGN);notifyUser(saved?kind.description:creationStore.warning);
    }
    public void abandonCommission() { if(circuitEditor==null) return;boolean saved=creationStore.abandon(circuitEditor.document().id());circuitEditor.restrict(creationStore.commission(circuitEditor.document().id()));notifyUser(saved?"已解除委托限制，地图草稿与历史报告保留。":creationStore.warning); }
    private void recordCreationResult() {
        var brief=circuitCommission();if(brief!=null && brief.assess(circuitEditor.document(),circuitEditor.proof(),vehicleSpec.physicsVersion).complete()) if(!creationStore.record(brief.report(circuitEditor.document(),circuitEditor.proof(),vehicleSpec.physicsVersion,System.currentTimeMillis()))) notifyUser(creationStore.warning);
    }
    public void saveCreation() {
        if(!creationStore.flush()) { notifyUser(creationStore.warning);return; }
        for(String id:List.copyOf(pendingPackInstalls)) if(!installCircuitPackMaps(id)) return;
        if(pendingLineage!=null) {retryCircuitLineage();return;}
        notifyUser("创作记录与赛事包已保存。");
    }
    public boolean retryCreationRead(com.f1car.persistence.CreationStore.Status expected) {
        if(online() || screen!=Screen.CREATION || expected==null || !expected.equals(creationStore.status()) || !creationStore.canRetryRead()) {notifyUser("创作记录状态已变化，请重新查看保存状态。");return false;}
        boolean loaded=creationStore.retryRead();if(loaded && circuitEditor!=null) circuitEditor.restrict(creationStore.commission(circuitEditor.document().id()));notifyUser(loaded?"原有创作记录已重新读取；地图草稿与车库保持。":creationStore.warning);return loaded;
    }
    public CircuitDocument packCircuit(String id) { return circuitEditor!=null && circuitEditor.document().id().equals(id)?circuitEditor.document():circuits.find(id).map(CircuitStore.Saved::document).orElse(null); }
    public List<CircuitDocument> packMapOptions() {
        var result=new ArrayList<CircuitDocument>();if(circuitEditor!=null) result.add(circuitEditor.document());
        for(var saved:circuits.all()) if((circuitEditor==null || !saved.document().id().equals(circuitEditor.document().id())) && saved.proof()!=null && saved.proof().matches(saved.document(),vehicleSpec.physicsVersion)) result.add(saved.document());
        return List.copyOf(result);
    }
    public CircuitBenchmark circuitBenchmark() { return circuitEditor==null?null:circuitBenchmark(circuitEditor.document().id()); }
    public CircuitBenchmark circuitBenchmark(String id) {
        return circuitBenchmark(id,null);
    }
    public List<CircuitBenchmark.Source> circuitBenchmarkSources(String id) {
        var reading=benchmarkReading(id);
        return reading==null?List.of():reading.sources();
    }
    public CircuitBenchmark circuitBenchmark(String id,CircuitBenchmark.Reference reference) {
        var reading=benchmarkReading(id);
        if(reading==null || reading.sources().isEmpty()) return null;
        var source=reference==null?reading.sources().get(0):reading.sources().stream()
            .filter(candidate->candidate.reference().equals(reference)).findFirst().orElse(null);
        if(source==null) return null;
        return reading.resolved().computeIfAbsent(source.reference(),key->{
            try {return Optional.of(source.benchmark(reading.document(),reading.physics()));}
            catch(IllegalArgumentException error) {return Optional.empty();}
        }).orElse(null);
    }
    private BenchmarkReading benchmarkReading(String id) {
        var document=packCircuit(id);
        if(document==null) {
            benchmarkCache.remove(id);
            return null;
        }
        var runs=activityStore.savedRecent();
        if(benchmarkRuns!=runs) {
            benchmarkCache.clear();
            benchmarkRuns=runs;
        }
        var existing=benchmarkCache.get(id);
        String physics=vehicleSpec.physicsVersion;
        if(existing!=null && existing.document()==document && existing.physics().equals(physics)) return existing;
        var reading=new BenchmarkReading(document,physics,CircuitBenchmark.sourcesFor(document,physics,runs),new HashMap<>());
        benchmarkCache.put(id,reading);
        if(benchmarkCache.size()>8) benchmarkCache.remove(benchmarkCache.keySet().iterator().next());
        return reading;
    }
    public void startAuthorBenchmark() { if(circuitEditor!=null) startAuthorBenchmark(circuitEditor.document().id()); }
    public void startAuthorBenchmark(String id) {
        startAuthorBenchmark(packCircuit(id),activityStore.active());
    }
    public void startAuthorBenchmark(CircuitDocument expected,ActivityRun previous) {
        var settings=controls.settings();
        if(screen!=Screen.CREATION || online() || !controlsReady(settings)) return;
        if(pendingActivityImport!=null) {notifyUser("请先重试保存导入活动的作者与说明。");return;}
        if(!refreshCircuitAuthoring(true,false)) return;
        if(expected==null || !expected.equals(packCircuit(expected.id())) || !Objects.equals(previous,activityStore.active())) {
            notifyUser("所选地图或当前活动进度已变化，请重新确认作者标定。");
            return;
        }
        if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return;
        var entry=circuits.playable(expected.id(),vehicleSpec.physicsVersion);
        if(entry==null) {notifyUser("先完成并保存所选地图的本机四车验证，再标定原厂计时目标。");return;}
        var track=entry.layout();
        int end=Math.min(24,track.name.length());
        if(end<track.name.length() && Character.isHighSurrogate(track.name.charAt(end-1))) end--;
        var card=ActivityCard.create("作者标定 · "+track.name.substring(0,end),0,vehicleSpec.physicsVersion,
                List.of(ActivityCard.Stage.create(ActivityCard.Kind.LAP,track,0,1,0)));
        var existing=activityStore.cards().stream().filter(c->c.key().equals(card.key())).findFirst().orElse(null);
        if(existing==null && !activityStore.put(card)) {notifyUser(activityStore.warning);return;}
        beginValidatedActivity(existing==null?card:existing,null,track,settings);
    }
    public void createCircuitPack(String title,String author,String notes,List<ActivityCard.Kind> kinds,long seed,int difficulty,int laps,int placing,boolean timed) {
        if(circuitEditor!=null) createCircuitPack(title,author,notes,kinds,Collections.nCopies(kinds.size(),circuitEditor.document().id()),seed,difficulty,laps,placing,timed);
    }
    public void createCircuitPack(String title,String author,String notes,List<ActivityCard.Kind> kinds,List<String> maps,long seed,int difficulty,int laps,int placing,boolean timed) {
        createCircuitPack(title,author,notes,kinds,maps,seed,difficulty,laps,placing,timed,null,"");
    }
    public void createCircuitPack(String title,String author,String notes,List<ActivityCard.Kind> kinds,List<String> maps,long seed,int difficulty,int laps,int placing,boolean timed,PartsActivityRule rule,String suggestionNotes) {
        createCircuitPack(title,author,notes,kinds,maps,seed,difficulty,laps,placing,timed,rule,suggestionNotes,List.of());
    }
    public void createCircuitPack(String title,String author,String notes,List<ActivityCard.Kind> kinds,List<String> maps,long seed,int difficulty,int laps,int placing,boolean timed,PartsActivityRule rule,String suggestionNotes,List<ActivityObjective> objectives) {
        if(kinds==null || maps==null || kinds.size()!=maps.size() || kinds.isEmpty() || kinds.size()>8) {
            notifyUser("请检查赛事包活动项与路线。");
            return;
        }
        var plans=new ArrayList<CircuitPackDraft.StagePlan>(kinds.size());
        for(int i=0;i<kinds.size();i++)
            plans.add(new CircuitPackDraft.StagePlan(kinds.get(i),maps.get(i),difficulty,laps,placing,timed));
        createCircuitPack(title,author,notes,plans,seed,rule,suggestionNotes,objectives,null);
    }
    public void createCircuitPack(CircuitPackDraft draft) {
        createCircuitPack(draft,null);
    }
    public void createCircuitPack(CircuitPackDraft draft,BooleanSupplier confirmationCurrent) {
        if(draft!=null)
            createCircuitPack(draft.title(),draft.author(),draft.notes(),draft.plans(),draft.seed(),
                    draft.rule(),draft.suggestionNotes(),draft.objectives(),confirmationCurrent);
    }
    private void createCircuitPack(String title,String author,String notes,List<CircuitPackDraft.StagePlan> plans,long seed,
            PartsActivityRule rule,String suggestionNotes,List<ActivityObjective> objectives,BooleanSupplier confirmationCurrent) {
        if(creationStore.pending() || !refreshCircuitAuthoring(plans.stream().anyMatch(plan->plan.timed() && plan.kind()==ActivityCard.Kind.LAP),rule!=null)) return;
        if(confirmationCurrent!=null && !confirmationCurrent.getAsBoolean()) {
            notifyUser("草稿、地图、资格、目标或推荐方案已变化，请重新预览。");
            return;
        }
        try {
            if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return;
            var included=new LinkedHashMap<String,CircuitStore.Saved>();var stages=new ArrayList<ActivityCard.Stage>();
            var tracks=new HashMap<String,TrackLayout>();
            for(int i=0;i<plans.size();i++) {
                var plan=plans.get(i);
                var saved=included.get(plan.map());
                if(saved==null) {
                    saved=circuits.find(plan.map()).orElse(null);
                    if(saved==null || saved.proof()==null || !saved.proof().matches(saved.document(),vehicleSpec.physicsVersion)) {
                        notifyUser("第 "+(i+1)+" 项的地图缺失或需要本机四车验证。");
                        return;
                    }
                    included.put(plan.map(),saved);
                }
                boolean timedLap=plan.timed() && plan.kind()==ActivityCard.Kind.LAP;
                var benchmark=timedLap?circuitBenchmark(saved.document().id(),plan.reference()):null;
                if(timedLap && benchmark==null) {
                    notifyUser("第 "+(i+1)+" 项"+(plan.reference()==null?"地图没有原厂有效作者圈，请先标定。"
                        :"选定的作者圈与当前保存记录或地图不符，请重新选择。"));
                    return;
                }
                stages.add(plan.on(tracks.computeIfAbsent(plan.map(),this::circuitById),benchmark));
            }
            var card=ActivityCard.create(title,seed,vehicleSpec.physicsVersion,stages,rule,objectives);var documents=included.values().stream().map(CircuitStore.Saved::document).toList();var proofs=included.values().stream().map(CircuitStore.Saved::proof).toList();
            int titleEnd=Math.min(34,title.length());if(titleEnd>0 && titleEnd<title.length() && Character.isHighSurrogate(title.charAt(titleEnd-1))) titleEnd--;
            var bundle=rule==null?null:CircuitPartsBundle.capture(partsStore.catalog(),rule,activityPartsBuild(),title.substring(0,titleEnd)+" · 推荐",suggestionNotes);
            var pack=card.hasSections()?CircuitEventPack.createSectioned(title,author,notes,documents,proofs,card,vehicleSpec.physicsVersion,bundle):card.objectives()!=null?CircuitEventPack.createCompound(title,author,notes,documents,proofs,card,vehicleSpec.physicsVersion,bundle):bundle==null?CircuitEventPack.create(title,author,notes,documents,proofs,card,vehicleSpec.physicsVersion):CircuitEventPack.create(title,author,notes,documents,proofs,card,vehicleSpec.physicsVersion,bundle);
            if(creationStore.put(pack.own())) notifyUser("已保存 "+included.size()+" 张地图的赛事包，可导出或加入活动库。");else notifyUser(creationStore.warning);
        }catch(IllegalArgumentException | IllegalStateException error) { notifyUser("请检查名称、活动设置、地图四车资格和作者目标。"); }
    }
    public void openPackMap(String id) { openPackMap(id,0); }
    public void openPackMap(String id,int index) {
        var pack=creationStore.pack(id);if(pack==null || index<0 || index>=pack.localCircuits().size()) return;if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return;var saved=circuits.find(pack.localCircuits().get(index).id()).orElse(null);if(saved==null) { notifyUser("本机草稿缺失，请在赛事包库点击补齐缺失地图。");return; }setCircuitEditor(saved.document(),saved.proof(),true);screen=Screen.MAP_EDITOR;physics.setSpeed(0);if(hud!=null) hud.resetCircuitEditorView();
    }
    public enum PackMapState {
        MISSING("缺少地图"), NEEDS_VALIDATION("待四车验证"), DIFFERENT("条件不符"), READY("资格匹配");
        public final String label;
        PackMapState(String label) { this.label=label; }
    }
    public record PackMapReadiness(String name,PackMapState state,String issue) {}
    public record PackReadiness(List<PackMapReadiness> maps,String issue) {
        public PackReadiness { maps=List.copyOf(maps); }
        public int firstPendingMap() {
            for(int index=0;index<maps.size();index++) if(maps.get(index).state()!=PackMapState.READY) return index;
            return -1;
        }
    }
    public PackReadiness packReadiness(CircuitEventPack.Entry pack) {
        var maps=new ArrayList<PackMapReadiness>();
        String issue="";
        for(var document:pack.localCircuits()) {
            var map=packMapReadiness(document,pack.activity());
            maps.add(map);
            if(issue.isEmpty()) issue=map.issue();
        }
        if(issue.isEmpty()) issue=activityCompatibility(pack.activity());
        return new PackReadiness(maps,issue);
    }
    private PackMapReadiness packMapReadiness(CircuitDocument document,ActivityCard card) {
        var saved=circuits.find(document.id()).orElse(null);
        if(saved==null) return new PackMapReadiness(document.name(),PackMapState.MISSING,"请先补齐包内地图："+document.name());
        String name=saved.document().name();
        if(saved.proof()==null || !saved.proof().matches(saved.document(),vehicleSpec.physicsVersion))
            return new PackMapReadiness(name,PackMapState.NEEDS_VALIDATION,"请先验证包内地图："+name);
        TrackLayout track;
        try {track=circuitById(document.id());}
        catch(IllegalArgumentException error) {
            return new PackMapReadiness(name,PackMapState.NEEDS_VALIDATION,"地图尚未通过几何检查："+name);
        }
        for(var stage:card.stages()) if(stage.trackId().equals(document.id())) {
            String issue=stage.trackIssue(track,card.physics());
            if(!issue.isEmpty()) return new PackMapReadiness(name,PackMapState.DIFFERENT,issue);
        }
        return new PackMapReadiness(name,PackMapState.READY,"");
    }
    public boolean refreshCircuitPackInputs(CircuitEventPack.Entry expected) {
        if(screen!=Screen.CREATION || online()) return false;
        if(expected==null || creationStore.pending()) {notifyUser("请先保存赛事包记录。");return false;}
        if(!creationStore.refreshForPackActivation()) {notifyUser(creationStore.warning);return false;}
        if(!expected.equals(creationStore.pack(expected.id()))) {
            notifyUser("已存赛事包或本地地图对应已变化，请重新选择。");
            return false;
        }
        return refreshLocalRaceContent(true,expected.activity().partsRule()!=null);
    }

    public boolean refreshCircuitAuthoring(boolean authorTimes,boolean parts) {
        return screen==Screen.CREATION && !online() && (!authorTimes || refreshActivityArchive())
                && refreshLocalRaceContent(true,parts);
    }

    private boolean refreshLocalRaceContent(boolean customMaps,boolean parts) {
        if(customMaps) {
            var editor=circuitEditor;
            boolean preserve=editor!=null && editor.dirty();
            var maps=circuits.all();
            if(!circuits.refreshIfChanged(preserve?editor.document().id():null)) {notifyUser(circuits.warning);return false;}
            if(maps!=circuits.all() && editor!=null && !preserve) {
                reconcileCleanCircuitEditor(editor);
                reloadCircuitReviews();
            }
        }
        if(parts) {
            if(!partsStore.refreshIfChanged()) {notifyUser(partsStore.warning());return false;}
            if(partsWorkbench!=null && partsWorkbench.catalog()!=partsStore.catalog()) partsWorkbench.catalog(partsStore.catalog());
        }
        return true;
    }

    public void activateCircuitPack(String id) {activateCircuitPack(creationStore.pack(id));}
    public void activateCircuitPack(CircuitEventPack.Entry pack) {
        if(!refreshCircuitPackInputs(pack)) return;
        if(activityStore.pending() || pendingActivityImport!=null) {notifyUser("请先在活动卡库重试保存上一份活动资料。");return;}
        String reason=packReadiness(pack).issue();if(!reason.isEmpty()) { notifyUser(reason);return; }
        if(pack.original().partsBundle()!=null || pack.original().version()>=4) {
            var original=pack.original();var metadata=contentText.get("pack:"+pack.id(),new com.f1car.persistence.ContentTextStore.Text(original.title(),original.author(),original.notes()));
            var target=new com.f1car.persistence.ContentTextStore.Text(metadata.name(),metadata.author(),metadata.notes());var card=pack.activity();boolean saved=activityStore.put(card);
            if(activityStore.cards().stream().noneMatch(value->value.id().equals(card.id()))) {notifyUser(activityStore.warning);return;}
            pendingActivityImport=new PendingActivityImport(null,card,target);openActivities();if(saved) finishActivityAttribution();else notifyUser(activityStore.warning);return;
        }
        if(activityStore.put(pack.activity())) {openActivities();notifyUser("已加入活动卡库："+pack.activity().name());}else notifyUser(activityStore.warning);
    }
    private boolean currentCircuitParts(CircuitEventPack.Entry expected,PartsCatalog catalog) {return !online() && screen==Screen.CREATION && !creationStore.pending() && expected!=null && expected.original().partsBundle()!=null && expected.equals(creationStore.pack(expected.id())) && catalog==partsStore.catalog();}
    public boolean installCircuitPackParts(CircuitEventPack.Entry expected,PartsCatalog catalog) {
        if(!currentCircuitParts(expected,catalog)) {notifyUser("赛事包或部件目录已变化，请重新预览。");return false;}
        try {var bundle=expected.original().partsBundle();var preview=partsStore.previewPacks(bundle.packs());var review=expected.activity().partsRule().review(bundle.suggestion().build(),preview,vehicleSpec.id,vehicleSpec.physicsVersion,0);if(!review.valid()) {notifyUser(review.issues().get(0));return false;}
            if(bundle.packs().stream().allMatch(pack->partsStore.packs().contains(pack))) {notifyUser("包内部件与当前目录一致，无需重复收录。");return true;}
            if(!partsStore.installPacks(bundle.packs(),true)) {notifyUser(partsStore.warning());return false;}if(partsWorkbench!=null) partsWorkbench.catalog(partsStore.catalog());notifyUser("包内部件已收录；旧草稿、命名方案、车库和历史保留，推荐方案需单独读取。");return true;
        }catch(IllegalArgumentException error) {notifyUser("部件命名空间或容量不适用，已有目录保留。");return false;}
    }
    public boolean loadCircuitPackSuggestion(CircuitEventPack.Entry expected,PartsCatalog catalog) {
        if(!currentCircuitParts(expected,catalog)) {notifyUser("赛事包或目录已变化，请重新查看推荐方案。");return false;}var suggestion=expected.original().partsBundle().suggestion();var review=expected.activity().partsRule().review(suggestion.build(),catalog,vehicleSpec.id,vehicleSpec.physicsVersion,store.profile.tuning.livery());if(!review.valid()) {notifyUser(review.issues().get(0));return false;}
        activityPartsBuild();partsWorkbench.adopt(suggestion.build());openCreationPartsWorkbench();notifyUser("推荐方案已读为可撤销草稿；未应用车库，也未覆盖命名方案。");return true;
    }
    public PartsPlan saveCircuitPackSuggestion(CircuitEventPack.Entry expected,PartsCatalog catalog) {
        if(!currentCircuitParts(expected,catalog)) {notifyUser("赛事包或目录已变化，请重新查看推荐方案。");return null;}var source=expected.original().partsBundle().suggestion();var review=expected.activity().partsRule().review(source.build(),catalog,vehicleSpec.id,vehicleSpec.physicsVersion,0);if(!review.valid()) {notifyUser(review.issues().get(0));return null;}
        var copy=PartsPlan.capture(source.name(),source.notes(),source.build());if(!partsPlans.save(copy,PartsPlanSource.circuit(expected.original()))) {notifyUser(partsPlans.warning());return null;}notifyUser("推荐方案及来源已另存为独立命名卡；原方案、车库与草稿保留。");return copy;
    }
    public void importCircuitPack(Path path) {
        try {
            installCircuitPack(com.f1car.persistence.CircuitEditionCodec.read(path,creationStore));
        }catch(java.io.IOException | RuntimeException error) { notifyUser("赛事包导入失败：请检查格式、大小和地图／规则对应关系。"); }
    }
    /** Adopt the immutable content shown in the file preview, without rereading a changed file. */
    public void importCircuitPack(com.f1car.persistence.CircuitEditionCodec.Shared shared) {
        if(screen!=Screen.CREATION || online()) return;
        try {installCircuitPack(Objects.requireNonNull(shared));}catch(RuntimeException error) {notifyUser("赛事包导入失败：请检查地图／规则对应关系，已有内容保留。");}
    }
    public Runnable reviewCircuitPackImport(com.f1car.persistence.CircuitEditionCodec.Shared shared) {
        String id=shared.pack().id();
        var draftCurrent=captureCircuitImportDraft();
        var previous=creationStore.pack(id);
        return ()->{
            if(screen!=Screen.CREATION || online()) return;
            if(!draftCurrent.getAsBoolean() || !Objects.equals(previous,creationStore.pack(id))) {
                notifyUser("当前草稿或已收录的赛事包已变化，请重新预览导入。");
                return;
            }
            importCircuitPack(shared);
        };
    }
    public java.util.function.BooleanSupplier captureCircuitImportDraft() {
        var context=new CircuitImportContext(circuitEditor);
        return ()->context.matches(circuitEditor);
    }
    private void installCircuitPack(com.f1car.persistence.CircuitEditionCodec.Shared shared) {
            if(pendingLineage!=null) {notifyUser("请先在修订页重试或取消上一份待保存声明。");return;}
            var pack=shared.pack();var old=creationStore.pack(pack.id());if(old!=null) { if(!old.original().digest().equals(pack.digest())) throw new IllegalArgumentException("Conflicting package");installCircuitPackMaps(old.id());acceptImportedLineage(shared);return; }
            if(creationStore.pending() || creationStore.packs().size()>=12 || circuits.all().size()+pack.circuits().size()>32) { notifyUser("请先保存并整理槽位，整份包的所有地图都需要空位。");return; }
            if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return;
            if(circuits.all().size()+pack.circuits().size()>32) { notifyUser("当前草稿已保存；剩余槽位不足以安装整份包。");return; }
            var entry=pack.importedCopy();pendingPackInstalls.add(entry.id());
            if(!creationStore.put(entry)) {acceptImportedLineage(shared);notifyUser(creationStore.warning);return;}installCircuitPackMaps(entry.id());acceptImportedLineage(shared);
    }
    private void acceptImportedLineage(com.f1car.persistence.CircuitEditionCodec.Shared shared) {if(shared.lineage()==null) return;var previous=circuitLineages.find(shared.pack().id());var issues=circuitLineages.review(shared.lineage(),false);if(!issues.isEmpty()) {notifyUser("包内容已保留，修订声明未采用："+issues.get(0));return;}if(creationStore.pending()) {pendingLineage=new PendingLineage(shared.lineage(),previous);return;}saveCircuitLineage(shared.lineage(),previous,false);}
    public boolean circuitLineagePending() {return pendingLineage!=null;}
    public void cancelCircuitLineageSave() {if(screen==Screen.CREATION) {pendingLineage=null;notifyUser("已取消待保存声明；包、地图和已保存关系保留。");}}
    private boolean saveCircuitLineage(CircuitLineage value,CircuitLineage previous,boolean replace) {var issues=circuitLineages.review(value,replace);if(!issues.isEmpty()) {notifyUser(issues.get(0));return false;}if(!circuitLineages.put(value,replace)) {pendingLineage=new PendingLineage(value,previous);notifyUser(circuitLineages.warning());return false;}pendingLineage=null;notifyUser("修订声明已保存；地图、活动和成绩保持。");return true;}
    public boolean retryCircuitLineage() {
        if(online() || screen!=Screen.CREATION || pendingLineage==null || creationStore.pending()) return false;
        var request=pendingLineage;
        var child=creationStore.pack(request.value().childId());
        if(child==null || !child.original().digest().equals(request.value().childDigest())) {
            notifyUser("待保存声明对应的包已变化，可取消后重新声明。");
            return false;
        }
        var current=circuitLineages.find(child.id());
        if(!request.value().equals(current) && !Objects.equals(request.previous(),current)) {
            notifyUser("已保存声明已变化，请取消旧请求并重新预览。");
            return false;
        }
        return saveCircuitLineage(request.value(),request.previous(),true);
    }
    public boolean declareCircuitLineage(CircuitEventPack.Entry parent,CircuitEventPack.Entry child,CircuitLineage previous,CircuitLineage value) {
        if(online() || screen!=Screen.CREATION || creationStore.pending() || pendingLineage!=null || parent==null || child==null || !parent.equals(creationStore.pack(parent.id())) || !child.equals(creationStore.pack(child.id())) || !Objects.equals(previous,circuitLineages.find(child.id()))) {notifyUser("包或声明已变化，请重新选择父包与地图对应。");return false;}
        try {value.validateChild(child.original());if(!value.parent().matches(parent.original())) throw new IllegalArgumentException("Parent differs");return saveCircuitLineage(value,previous,true);}catch(IllegalArgumentException error) {notifyUser("父包或地图对应关系无效，请重新预览。");return false;}
    }
    public void exportCircuitEdition(CircuitEventPack.Entry expected,CircuitLineage relation,Path path) {
        var reviewed=expected==null?null:reviewCircuitPackExport(expected.id());
        if(reviewed!=null && !expected.equals(reviewed.entry())) reviewed=null;
        exportCircuitEdition(reviewed,relation,path,false);
    }
    public boolean exportCircuitEdition(CircuitPackExport reviewed,CircuitLineage relation,Path path,boolean replace) {
        try {
            return exportReviewedCircuitEdition(reviewed,relation,ExportDestination.forWrite(path,replace));
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("无法核对修订包导出路径，请重新选择文件。");
            return false;
        }
    }
    public boolean exportReviewedCircuitEdition(CircuitPackExport reviewed,CircuitLineage relation,ExportDestination destination) {
        if(online() || screen!=Screen.CREATION || reviewed==null || relation==null || creationStore.pending()
                || !reviewed.equals(reviewCircuitPackExport(reviewed.entry().id()))
                || !relation.equals(circuitLineages.find(reviewed.entry().id()))) {
            notifyUser("包、介绍文字或修订声明已变化，请重新预览导出。");
            return false;
        }
        if(!circuitLineages.verifyAccepted(relation)) {
            notifyUser(circuitLineages.warning());
            return false;
        }
        try {
            var text=reviewed.text();
            var pack=reviewed.entry().original().withText(text.name(),text.author(),text.notes());
            var shared=new com.f1car.persistence.CircuitEditionCodec.Shared(pack,relation.retarget(pack));
            com.f1car.persistence.CircuitEditionCodec.write(contentExportTarget(destination.path()),shared,creationStore,destination);
            notifyUser("包与修订声明已导出，部件与推荐方案可分别确认，地图需本机验证。");
            return true;
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("修订包未导出，请核对版本声明，再重新选择目标文件。");
            return false;
        }
    }
    public record CircuitMigration(CircuitEventPack.Entry parent,CircuitEventPack.Entry child,CircuitLineage lineage,int map,CircuitMapMigration merge) {}
    public CircuitMigration prepareCircuitMigration(String childId,int map) {
        if(screen!=Screen.CREATION || online()) return null;
        if(!creationStore.refreshForPackActivation()) {notifyUser(creationStore.warning);return null;}
        if(!circuitLineages.refreshIfChanged()) {notifyUser(circuitLineages.warning());return null;}
        if(!refreshLocalRaceContent(true,false)) return null;
        var child=creationStore.pack(childId);var relation=circuitLineages.find(childId);if(child==null || relation==null || map<0 || map>=child.localCircuits().size()) {notifyUser("请先保存修订声明并选择对应地图。");return null;}
        try {relation.validateChild(child.original());var parent=creationStore.pack(relation.parent().id());if(parent==null || !relation.parent().matches(parent.original())) {notifyUser("父包尚未收录或与声明不符，请保留原文件并核对。");return null;}String after=child.original().circuits().get(map).id();var link=relation.links().stream().filter(row->row.after().equals(after)).findFirst().orElse(null);if(link==null) {notifyUser("所选地图没有声明父图对应，不自动按名称匹配。");return null;}int before=-1;for(int i=0;i<parent.original().circuits().size();i++) if(parent.original().circuits().get(i).id().equals(link.before())) before=i;if(before<0) return null;var local=packCircuit(parent.localCircuits().get(before).id());var target=packCircuit(child.localCircuits().get(map).id());if(local==null || target==null) {notifyUser("请先补齐父包与当前包的本地地图。");return null;}return new CircuitMigration(parent,child,relation,map,new CircuitMapMigration(parent.original().circuits().get(before),local,target));}catch(IllegalArgumentException error) {notifyUser("修订声明与包内容不同，请核对来源。");return null;}
    }
    public boolean applyCircuitMigration(CircuitMigration expected,Map<CircuitMapMigration.Section,CircuitMapMigration.Choice> choices) {
        if(online() || screen!=Screen.CREATION || expected==null || creationStore.pending() || pendingLineage!=null) return false;
        var current=prepareCircuitMigration(expected.child().id(),expected.map());
        if(current==null) return false;
        if(!expected.equals(current)) {notifyUser("父包、声明或任一草稿已变化，请重新比较迁移。");return false;}
        try {var candidate=expected.merge().candidate(Map.copyOf(choices));if(candidate.equals(expected.merge().target())) {notifyUser("选择结果与当前新版草稿一致，无需迁移。");return false;}if(circuitEditor!=null && circuitEditor.document().id().equals(candidate.id())) returnToCircuitEditor();else if(!editCircuitDraft(candidate.id())) return false;if(!circuitEditor.restore(candidate)) {notifyUser(circuitEditor.restriction().isEmpty()?"草稿未改变。":circuitEditor.restriction());return false;}if(hud!=null) hud.resetCircuitEditorView();notifyUser("所选内容已读为可撤销草稿，尚未保存；道路或布景改变需重新验证，活动与成绩保留。");return true;}catch(IllegalArgumentException | IllegalStateException error) {notifyUser("请先解决全部冲突，再预览迁移结果。");return false;}
    }
    public boolean installCircuitPackMaps(String id) {
        var entry=creationStore.pack(id);if(entry==null) { pendingPackInstalls.remove(id);return true; }
        String openDraft=circuitEditor==null?null:circuitEditor.document().id();
        if(!circuits.refreshIfChanged(openDraft)) {
            pendingPackInstalls.add(id);
            notifyUser(circuits.warning);
            return false;
        }
        if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return false;
        if(!creationStore.flush()) { notifyUser(creationStore.warning);return false; }
        var missing=entry.localCircuits().stream().filter(document->circuits.find(document.id()).isEmpty()).toList();
        if(!missing.isEmpty() && !circuits.installUnverified(missing)) { pendingPackInstalls.add(id);notifyUser("赛事包已保留，地图尚未安装；可在包库补齐。 "+circuits.warning);return false; }
        pendingPackInstalls.remove(id);var saved=circuits.find(entry.local().id()).orElseThrow();setCircuitEditor(saved.document(),saved.proof(),true);screen=Screen.CREATION;if(physics!=null) physics.setSpeed(0);
        notifyUser(missing.isEmpty()?"此包已安装；已有地图和修改保持原样。":"已安装 "+missing.size()+" 张新草稿；每张均需本机四车验证。");return true;
    }
    private Path contentExportTarget(Path path) throws java.io.IOException {
        var data=store.directory().toAbsolutePath().normalize();var target=path.toAbsolutePath().normalize();
        if(target.getFileName()==null || Files.isSymbolicLink(target) || Files.isDirectory(target))
            throw new java.io.IOException("Not an exchange file");
        var resolved=resolvedPathForExport(target);
        String name=resolved.getFileName().toString();
        if(!exchangeFilename(name) && resolved.startsWith(resolvedPathForExport(data)))
            throw new java.io.IOException("Player data is reserved");
        if(Files.exists(target) && Files.exists(data)) {
            try(var files=Files.list(data)) {
                for(var saved:files.filter(Files::isRegularFile).filter(file->!exchangeFilename(file.getFileName().toString())).toList()) {
                    if(Files.isSameFile(target,saved)) throw new java.io.IOException("Player data is reserved");
                }
            }
        }return resolved;
    }
    private static boolean exchangeFilename(String name) {
        return name.startsWith("activity-export-") || name.startsWith("circuit-export-") || name.startsWith("parts-export-")
                || name.startsWith("livery-export-") || name.startsWith("setup-export-") || name.startsWith("ghost-export-")
                || name.startsWith("telemetry-export-") || name.equals("ghost-best.json") || name.equals("ghost-reply.json");
    }

    public record CircuitPackExport(CircuitEventPack.Entry entry, ContentTextStore.Text text) {
        public CircuitPackExport {
            Objects.requireNonNull(entry);
            Objects.requireNonNull(text);
        }
    }

    public CircuitPackExport reviewCircuitPackExport(String id) {
        var entry = creationStore.pack(id);
        if (entry == null) return null;
        var original = entry.original();
        var text = contentText.get("pack:" + id, new ContentTextStore.Text(original.title(), original.author(), original.notes()));
        return new CircuitPackExport(entry, text);
    }

    public boolean exportCircuitPack(String id, Path path) {
        return exportCircuitPack(reviewCircuitPackExport(id), path, false);
    }

    public boolean exportCircuitPack(CircuitPackExport reviewed, Path path, boolean replace) {
        try {
            return exportReviewedCircuitPack(reviewed,ExportDestination.forWrite(path,replace));
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("无法核对赛事包导出路径，请重新选择文件。");
            return false;
        }
    }
    public boolean exportReviewedCircuitPack(CircuitPackExport reviewed,ExportDestination destination) {
        if (reviewed == null || !reviewed.equals(reviewCircuitPackExport(reviewed.entry().id()))) {
            notifyUser("赛事包或导出文字已变化，请重新选择后导出。");
            return false;
        }
        try {
            var text = reviewed.text();
            var pack = reviewed.entry().original().withText(text.name(), text.author(), text.notes());
            creationStore.exportPack(pack,contentExportTarget(destination.path()),destination);
            notifyUser("赛事包已导出；对方导入后需本机四车验证，部件与推荐方案分别确认。");
            return true;
        } catch (java.io.IOException | RuntimeException error) {
            notifyUser("赛事包导出失败，请重新选择并确认独立的导出文件。");
            return false;
        }
    }
    public CircuitTrial circuitTrial() { return circuitTrial; }
    public CircuitSectionPractice circuitSectionPractice() {return circuitSectionPractice;}
    public CircuitDriveReview.Snapshot circuitReview() { return circuitTrial==null?lastCircuitReview:circuitTrial.review().snapshot(); }
    public CircuitObservationRetest.Source circuitObservationSource(CircuitDriveReview.Snapshot snapshot,int markerIndex) {
        if(screen!=Screen.MAP_EDITOR || circuitEditor==null || snapshot==null || snapshot!=lastCircuitReview
                || snapshot.status()==CircuitDriveReview.Status.RUNNING || markerIndex<0 || markerIndex>=snapshot.markers().size()) return null;
        String id=circuitEditor.document().id();
        if(pendingCircuitReviews.get(id)==snapshot)
            return new CircuitObservationRetest.Source(id,CircuitObservationRetest.Source.PENDING,vehicleSpec.physicsVersion,snapshot,markerIndex);
        var reviews=circuitReviews();
        if(circuitReviewIndex<0 || circuitReviewIndex>=reviews.size()) return null;
        var selected=reviews.get(circuitReviewIndex);
        if(selected.snapshot()!=snapshot) return null;
        return new CircuitObservationRetest.Source(id,selected.savedAt(),selected.physicsVersion(),snapshot,markerIndex);
    }

    private CircuitObservationRetest.Source matchingCircuitObservation(CircuitObservationRetest.Source expected) {
        var current=circuitObservationSource(circuitReview(),expected.markerIndex());
        if(current==null || !expected.sameObservation(current)) return null;
        if(current.savedAt()!=CircuitObservationRetest.Source.PENDING
                && CircuitStore.observationReviewIndex(circuitReviews(),current)!=circuitReviewIndex) return null;
        return current.snapshot()==expected.snapshot()?expected:current;
    }

    public CircuitObservationRetest latestCircuitObservationRetest(CircuitDriveReview.Snapshot snapshot,int markerIndex) {
        if(observationRetests.isEmpty()) return null;
        var source=circuitObservationSource(snapshot,markerIndex);
        if(source==null) return null;
        for(var retest:observationRetests) if(retest.source().sameObservation(source)) return retest;
        return null;
    }

    public CircuitObservationRetest sectionObservationRetest() {return sectionObservationRetest;}

    public boolean currentCircuitObservationRetest(CircuitObservationRetest shown,boolean saved) {
        return reviewedObservationRetest(shown,saved)!=null;
    }

    private CircuitObservationRetest.Source reviewedObservationRetest(CircuitObservationRetest shown,boolean saved) {
        if(screen!=Screen.MAP_EDITOR || online() || circuitEditor==null || shown==null) return null;
        var current=matchingCircuitObservation(shown.source());
        if(current==null
                || (saved?!shown.result().equals(savedCircuitObservationResult(current))
                         :shown!=latestCircuitObservationRetest(current.snapshot(),current.markerIndex()))) {
            notifyUser("原观测或复测结果已变化，请返回原观测重新打开详情。");
            return null;
        }
        return currentObservationOnDisk(current,saved?shown.result():null)?current:null;
    }

    private boolean currentObservationOnDisk(CircuitObservationRetest.Source source,CircuitObservationRetest.Result expectedRetest) {
        if(source.savedAt()==CircuitObservationRetest.Source.PENDING || circuits.currentObservation(source,expectedRetest)) return true;
        notifyUser("无法核对磁盘上的原观测或复测结果，请重读地图资料库。原结果仍可查看。");
        return false;
    }

    public CircuitObservationRetest savedCircuitObservationRetest(CircuitDriveReview.Snapshot snapshot,int markerIndex) {
        var source=circuitObservationSource(snapshot,markerIndex);
        var result=circuits.observationRetest(source);
        return result==null?null:new CircuitObservationRetest(source,result);
    }

    public CircuitObservationRetest.Result savedCircuitObservationResult(CircuitObservationRetest.Source source) {
        return circuits.observationRetest(source);
    }

    private boolean currentObservationRetestForSaving(CircuitObservationRetest shown) {
        return screen==Screen.RESULTS && shown==sectionObservationRetest
                || screen==Screen.MAP_EDITOR && shown==latestCircuitObservationRetest(circuitReview(),shown.source().markerIndex());
    }

    public boolean saveCircuitObservationRetest(CircuitObservationRetest shown,CircuitObservationRetest.Result expected) {
        if(shown==null || circuitEditor==null || online() || !circuitEditor.document().id().equals(shown.source().mapId()))
            return false;
        var source=shown.source();
        if(!currentObservationRetestForSaving(shown)) {
            notifyUser("待保存的复测已变化，请重新打开详情。");
            return false;
        }
        if(source.savedAt()==CircuitObservationRetest.Source.PENDING) {
            notifyUser("请先保存原试跑记录，再从原观测打开本次复测。");
            return false;
        }
        if(!circuits.replaceObservationRetest(source,expected,shown.result())) {
            notifyUser(circuits.warning);
            return false;
        }
        notifyUser("复测已保存，重开地图后可从原观测查看。");
        return true;
    }

    public enum ObservationRetestReload { REOPENED, SOURCE_MISSING, FAILED }

    public ObservationRetestReload reloadObservationRetest(CircuitObservationRetest shown) {
        if(shown==null || circuitEditor==null || online() || !circuitEditor.document().id().equals(shown.source().mapId())
                || shown.source().savedAt()==CircuitObservationRetest.Source.PENDING || !currentObservationRetestForSaving(shown)) {
            notifyUser("待保存的复测已变化，请从原观测重新打开详情。");
            return ObservationRetestReload.FAILED;
        }
        if(!readCircuitLibrary(null)) return ObservationRetestReload.FAILED;
        if(!openCircuitObservation(shown.source(),false)) {
            notifyUser("已重读资料库，原观测已移除或无法唯一定位。");
            return ObservationRetestReload.SOURCE_MISSING;
        }
        notifyUser("已回到原观测，请核对重读后的复测记录。");
        return ObservationRetestReload.REOPENED;
    }

    public boolean deleteSavedCircuitObservationRetest(CircuitObservationRetest shown) {
        if(shown==null || online()) return false;
        var source=matchingCircuitObservation(shown.source());
        if(source==null) {
            notifyUser("原观测已变化，请重新打开已存复测。");
            return false;
        }
        if(!circuits.replaceObservationRetest(source,shown.result(),null)) {
            notifyUser(circuits.warning);
            return false;
        }
        notifyUser("这条已存复测已删除。");
        return true;
    }

    private void rememberAuthorTrial() {
        var trial=circuitSectionPractice;
        if(trial==null || !trial.complete() || sectionObservation==null && sectionFeedback==null) return;
        var result=new CircuitObservationRetest.Result(sectionDriveKey,vehicleSpec.physicsVersion,controlsIdentity(),
                trial.range(),trial.start(),trial.focusedSplit(),trial.state(),trial.elapsed(),trial.failure());
        if(sectionFeedback!=null) {
            sectionFeedbackTrial=CircuitFeedbackTrial.from(sectionFeedback,result);
            return;
        }
        sectionObservationRetest=new CircuitObservationRetest(sectionObservation,result);
        observationRetests.removeIf(retest->retest.source().sameObservation(sectionObservation));
        observationRetests.addLast(sectionObservationRetest);
        if(observationRetests.size()>OBSERVATION_RETEST_LIMIT) observationRetests.removeFirst();
    }

    private void linkSavedObservationRetests(String id,CircuitDriveReview.Snapshot snapshot,CircuitStore.Review saved) {
        if(observationRetests.isEmpty() || !saved.snapshot().equals(snapshot)) return;
        int count=observationRetests.size();
        for(int i=0;i<count;i++) {
            var retest=observationRetests.removeFirst();
            var source=retest.source();
            if(source.savedAt()==CircuitObservationRetest.Source.PENDING && source.mapId().equals(id) && source.snapshot()==snapshot
                    && source.physicsVersion().equals(saved.physicsVersion())) {
                retest=retest.withSource(new CircuitObservationRetest.Source(id,saved.savedAt(),saved.physicsVersion(),saved.snapshot(),source.markerIndex()));
            }
            observationRetests.addLast(retest);
        }
    }
    private boolean observeCircuitReview(Vector3f[] positions) {
        if(circuitTrial==null || racers.size()!=circuitTrial.kind().drivers) return false;
        try {
            for(int i=0;i<racers.size();i++) {
                var racer=racers.get(i);
                var point=positions==null?racer.car.position(circuitReviewPosition):positions[i];
                var progress=racer.progress;
                boolean finished=progress.finished();
                float speed=racer.car.velocity(circuitReviewVelocity).length();
                circuitReviewObservations.add(new CircuitDriveReview.Observation(i,racer.car.name,point.x,point.z,speed,
                        racer.car.throttle,progress.nextGate(),finished?progress.lastLapValid():progress.validLap(),finished,racer.recovery));
            }
            return circuitTrial.review().observe(Math.min(CircuitTrial.LIMIT,Math.max(0,sessionTime)),circuitReviewObservations);
        } finally {
            circuitReviewObservations.clear();
        }
    }
    private void rememberCircuitReview() {
        if(circuitTrial==null || circuitEditor==null) return;
        if(!circuitTrial.complete()) observeCircuitReview(null);circuitTrial.stop();lastCircuitReview=circuitTrial.review().snapshot();
        pendingCircuitReviews.put(circuitEditor.document().id(),lastCircuitReview);
        circuitReviewIndex=-1;
        flushCircuitReviews();
    }
    private boolean flushCircuitReviews() {
        var iterator=pendingCircuitReviews.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();
            if(!circuits.saveReview(entry.getKey(),entry.getValue(),vehicleSpec.physicsVersion)) {
                notifyUser("试跑位置记录尚未保存；返回编辑器保存草稿可重试。 "+circuits.warning);
                return false;
            }
            var reviews=circuits.find(entry.getKey()).orElseThrow().reviews();
            var saved=reviews.get(reviews.size()-1);
            if(circuitEditor!=null && circuitEditor.document().id().equals(entry.getKey()) && lastCircuitReview==entry.getValue()) {
                lastCircuitReview=saved.snapshot();
                circuitReviewIndex=reviews.size()-1;
            }
            linkSavedObservationRetests(entry.getKey(),entry.getValue(),saved);
            iterator.remove();
        }
        return true;
    }
    public void openCircuitEditor() {
        mainMenu();if(circuitEditor==null) loadAvailableCircuitDraft();
        screen=Screen.MAP_EDITOR;physics.setSpeed(0);if(hud!=null) hud.resetCircuitEditorView();if(!circuits.warning.isEmpty()) notifyUser(circuits.warning);
    }
    private void loadAvailableCircuitDraft() {
        var existing=circuits.find(store.profile.trackId).orElse(circuits.all().isEmpty()?null:circuits.all().get(circuits.all().size()-1));
        if(existing==null) { circuitEditor=null;lastCircuitReview=null;circuitReviewIndex=-1;if(hud!=null) hud.resetCircuitEditorState(); }else setCircuitEditor(existing.document(),existing.proof(),true);
    }
    public void newCircuit(String template) {
        if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return;
        if(circuits.all().size()>=32) { notifyUser("自制地图槽位已满，请先删除不需要的草稿。");return; }
        var document=template==null?CircuitDocument.create():CircuitDocument.copy(TrackCatalog.require(template).layout());
        setCircuitEditor(document,null,false);if(hud!=null) hud.resetCircuitEditorView();
    }
    public void cycleCircuitDraft(int delta) {
        var editor=circuitEditor;
        if(editor!=null) {
            editor.endPointer();
            if(editor.dirty() && !persistCircuitDraft()) return;
        }
        var maps=circuits.all();
        if(maps.isEmpty()) return;
        int index=0;
        if(editor!=null) {
            String id=editor.document().id();
            for(int i=0;i<maps.size();i++) {
                if(maps.get(i).document().id().equals(id)) {
                    index=i;
                    break;
                }
            }
        }
        var saved=maps.get(Math.floorMod(index+delta,maps.size()));
        if(editor!=null && saved.document().equals(editor.document()) && Objects.equals(saved.proof(),editor.proof())) return;
        setCircuitEditor(saved.document(),saved.proof(),true);
    }
    public boolean editCircuitDraft(String id) {
        if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return false;
        var saved=circuits.find(id).orElse(null);if(saved==null) { notifyUser("这张草稿已不在本机地图库中。");return false; }
        if(screen!=Screen.MAP_EDITOR) { mainMenu();screen=Screen.MAP_EDITOR;physics.setSpeed(0); }
        setCircuitEditor(saved.document(),saved.proof(),true);if(hud!=null) hud.resetCircuitEditorView();return true;
    }
    public void openCircuitCard(String id) { if(editCircuitDraft(id) && hud!=null) hud.showCircuitLibrary(); }
    private boolean feedbackEditing() {return !online() && screen==Screen.MAP_EDITOR;}
    private void observeDiscussionReading() {discussionReading.observe(partsDiscussions.entries(),feedbackDiscussions.entries());}
    public boolean markDiscussionReading(Object expected,String message,boolean read) {
        if(online()) return false;
        boolean current=expected instanceof PartsDiscussion value && screen==Screen.PARTS && value.equals(partsDiscussions.find(value.id()))
            || expected instanceof CircuitFeedbackDiscussion feedbackValue && screen==Screen.MAP_EDITOR && feedbackValue.equals(feedbackDiscussions.find(feedbackValue.id()));
        if(!current) {notifyUser("话题已变化，请重新查看后标记。");return false;}
        observeDiscussionReading();
        try {boolean ok=discussionReading.mark(expected,message,read);notifyUser(ok?(discussionReading.warning().isEmpty()?"本机阅读标记已保存；讨论原文和意见处理保持。":discussionReading.warning()):discussionReading.warning().isEmpty()?"阅读状态待确认，请重新查看本机标记。":discussionReading.warning());return ok;}
        catch(IllegalArgumentException error) {notifyUser("留言已变化，请重新查看后标记。");return false;}
    }
    public boolean saveFeedbackDiscussion(CircuitFeedbackDiscussion value,CircuitFeedbackDiscussion expected) {
        if(!feedbackEditing() || value==null) return false;var preview=feedbackDiscussions.preview(value);
        if(!Objects.equals(expected,preview.before()) || !preview.valid() || (expected==null?value.messages().size()!=1:preview.added()!=1 || !value.equals(preview.candidate()))) {notifyUser("话题或原文已变化，请保留草稿并重新查看合并。");return false;}
        boolean ok=feedbackDiscussions.accept(preview);if(ok) observeDiscussionReading();notifyUser(ok?"反馈讨论已保存；地图、资格、原反馈与本地处理保持。":feedbackDiscussions.warning());return ok;
    }
    public boolean receiveFeedbackDiscussion(CircuitFeedbackDiscussion.Merge expected) {
        if(!feedbackEditing() || expected==null) return false;boolean ok=feedbackDiscussions.accept(expected);if(ok) observeDiscussionReading();notifyUser(ok?"已追加一致的反馈留言；未安装地图或更改本地处理。":feedbackDiscussions.warning());return ok;
    }
    public boolean removeFeedbackDiscussion(CircuitFeedbackDiscussion expected) {if(!feedbackEditing()) return false;boolean ok=feedbackDiscussions.remove(expected);if(ok) observeDiscussionReading();notifyUser(ok?"已移除此话题副本；原反馈、地图和答复库保持。":feedbackDiscussions.warning());return ok;}
    public boolean exportFeedbackDiscussion(CircuitFeedbackDiscussion expected,Path path) {
        return exportFeedbackDiscussion(expected,path,false);
    }
    public boolean exportFeedbackDiscussion(CircuitFeedbackDiscussion expected,Path path,boolean replace) {
        if(!feedbackEditing() || expected==null || !expected.equals(feedbackDiscussions.find(expected.id()))) {notifyUser("话题已变化，请重新查看导出。");return false;}
        try {var target=contentExportTarget(path);if(target.startsWith(resolvedPathForExport(store.directory().toAbsolutePath().normalize())) && !target.getFileName().toString().startsWith("circuit-export-")) throw new java.io.IOException("Player data is reserved");com.f1car.persistence.CircuitFeedbackDiscussionCodec.write(target,expected,replace);notifyUser("已导出完整反馈讨论；署名、观察与答复均为提供者声明。");return true;}
        catch(java.io.IOException | RuntimeException error) {notifyUser("反馈讨论未导出，请使用独立文件路径。");return false;}
    }
    public boolean receiveCircuitFeedback(CircuitFeedback value) {
        if(!feedbackEditing() || value==null) return false;if(!circuitFeedback.receive(value)) {notifyUser(circuitFeedback.warning());return false;}notifyUser("玩家原始意见已保存；请明确选择对应的本地地图。");return true;
    }
    public boolean resolveCircuitFeedback(CircuitFeedback.Entry expected,CircuitFeedback.Resolution value) {
        if(!feedbackEditing() || expected==null) return false;try {if(!circuitFeedback.update(expected,expected.resolve(value))) {notifyUser(circuitFeedback.warning());return false;}notifyUser("处理状态和答复已在本地保存；玩家原文保持。");return true;}catch(IllegalArgumentException error) {notifyUser("请核对意见、处理状态和答复。");return false;}
    }
    public boolean receiveCircuitFeedbackReply(CircuitFeedbackReply reply) {
        if(!feedbackEditing() || reply==null) return false;if(!circuitFeedback.receiveReply(reply)) {notifyUser(circuitFeedback.warning());return false;}notifyUser("作者答复已独立收录；本地对应与处理状态保持，不安装地图或资格。");return true;
    }
    public boolean removeCircuitFeedbackReply(CircuitFeedbackReply expected) {if(!feedbackEditing()) return false;if(!circuitFeedback.removeReply(expected)) {notifyUser(circuitFeedback.warning());return false;}notifyUser("已移除这份答复副本，玩家原文与本地处理保持。");return true;}
    public boolean bindCircuitFeedback(CircuitFeedback.Entry expected,CircuitDocument target) {
        if(!feedbackEditing() || expected==null) return false;if(target!=null && !target.equals(packCircuit(target.id()))) {notifyUser("目标草稿已变化，请重新预览对应关系。");return false;}if(!circuitFeedback.update(expected,expected.bind(target))) {notifyUser(circuitFeedback.warning());return false;}notifyUser(target==null?"本地对应关系已解除，原始意见与处理记录保留。":"已保存本地对应；源地图快照和玩家原文保持。");return true;
    }
    public boolean removeCircuitFeedback(CircuitFeedback.Entry expected) {
        if(!feedbackEditing()) return false;if(!circuitFeedback.remove(expected)) {notifyUser(circuitFeedback.warning());return false;}notifyUser("已移除本地反馈；地图、资格、活动与试跑记录保留。");return true;
    }
    public boolean focusCircuitFeedback(CircuitFeedback.Entry expected,String noteId) {
        return openCircuitFeedbackLocation(expected,noteId,false);
    }

    public boolean inspectCircuitFeedbackNearby(CircuitFeedback.Entry expected,String noteId) {
        return openCircuitFeedbackLocation(expected,noteId,true);
    }

    private boolean openCircuitFeedbackLocation(CircuitFeedback.Entry expected,String noteId,boolean nearby) {
        if(!feedbackEditing() || expected==null || !expected.equals(circuitFeedback.find(expected.feedback().id())) || expected.binding()==null) {notifyUser("请重新查看反馈并明确对应本地地图。");return false;}
        var note=expected.feedback().notes().stream().filter(row->row.id().equals(noteId)).findFirst().orElse(null);var target=packCircuit(expected.binding().targetId());var alignment=expected.alignment(target);
        if(note==null || note.location()==null || alignment!=CircuitFeedback.Alignment.MATCHED && alignment!=CircuitFeedback.Alignment.REFERENCE) {notifyUser("这条意见没有位置，或目标地图已变化；请先重新对应。");return false;}
        if(circuitEditor==null || !circuitEditor.document().id().equals(target.id())) {if(!editCircuitDraft(target.id())) return false;}
        if(hud!=null) {
            if(nearby) hud.inspectCircuitFeedbackNearby(expected,note);
            else hud.focusCircuitFeedback(expected,note,target);
        }
        notifyUser(alignment==CircuitFeedback.Alignment.REFERENCE?"已定位玩家旧版参考位置，请结合当前路线判断。":"已定位玩家意见；查看位置不修改地图。");
        return true;
    }
    public boolean exportCircuitFeedback(CircuitFeedback.Entry expected,Path path) {
        return exportCircuitFeedback(expected,path,false);
    }
    public boolean exportCircuitFeedback(CircuitFeedback.Entry expected,Path path,boolean replace) {
        if(!feedbackEditing() || expected==null || !expected.equals(circuitFeedback.find(expected.feedback().id()))) {notifyUser("反馈已变化，请重新选择导出。");return false;}
        try {com.f1car.persistence.CircuitFeedbackCodec.write(feedbackExportTarget(path),expected.feedback(),replace);notifyUser("玩家原始反馈已导出；处理状态和答复不在这份文件中。");return true;}catch(java.io.IOException | RuntimeException error) {notifyUser("反馈未导出，请检查独立文件路径；已有意见保留。");return false;}
    }
    public boolean exportCircuitFeedbackReply(CircuitFeedback.Entry expected,CircuitFeedbackReply reply,Path path) {
        return exportCircuitFeedbackReply(expected,reply,path,false);
    }
    public boolean exportCircuitFeedbackReply(CircuitFeedback.Entry expected,CircuitFeedbackReply reply,Path path,boolean replace) {
        if(!feedbackEditing() || expected==null || reply==null || !expected.equals(circuitFeedback.find(expected.feedback().id())) || !reply.matches(expected) || !reply.physics().equals(vehicleSpec.physicsVersion) || !reply.target().equals(packCircuit(reply.target().id())) || !expected.feedback().notes().stream().map(note->expected.resolution(note.id())).sorted(Comparator.comparing(CircuitFeedback.Resolution::noteId)).toList().equals(reply.resolutions())) {notifyUser("原文、处理记录或地图已变化，请重新预览答复。");return false;}
        var alignment=expected.alignment(reply.target());if(alignment!=CircuitFeedback.Alignment.MATCHED && alignment!=CircuitFeedback.Alignment.REFERENCE) {notifyUser("请先明确对应当前地图，再导出答复。");return false;}
        try {com.f1car.persistence.CircuitFeedbackReplyCodec.write(feedbackExportTarget(path),reply,replace);notifyUser("处理答复与对应版本快照已导出；这是作者声明，不携带本机资格。");return true;}catch(java.io.IOException | RuntimeException error) {notifyUser("答复未导出，请检查独立路径；已有意见与答复保持。");return false;}
    }
    private Path feedbackExportTarget(Path path) throws java.io.IOException {var target=contentExportTarget(path);var reserved=circuitFeedback.file().toAbsolutePath().normalize();if(target.equals(reserved) || Files.exists(target) && Files.exists(reserved) && Files.isSameFile(target,reserved)) throw new java.io.IOException("Feedback archive is reserved");return target;}
    private boolean persistCircuitDraft() {
        return persistCircuitDraft(null,null,0,null,null);
    }
    private boolean persistCircuitDraft(CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,
                                        int slot,CircuitStore.PracticeSlot expected,Integer split) {
        if(circuitEditor==null) return false;circuitEditor.endPointer();
        if(!flushCircuitReviews()) return false;
        var document=circuitEditor.document();
        var proof=circuitEditor.proof();
        boolean saved=range==null?circuits.save(document,proof):circuits.savePracticeSlot(document,proof,slot,expected,range,start,split);
        if(!saved) { notifyUser(circuits.warning);return false; }
        circuitEditor.saved();return true;
    }
    public void saveCircuitDraft() {
        if(persistCircuitDraft()) notifyUser(circuitEditor.proof()!=null && circuitEditor.proof().matches(circuitEditor.document(),vehicleSpec.physicsVersion)?"地图已保存，可在选图中使用。":"草稿已保存；四车试跑通过后可加入选图。");
    }
    public boolean reloadCircuitLibrary(CircuitDocument preview) {
        if(screen!=Screen.MAP_EDITOR || online()) return false;
        return readCircuitLibrary(preview);
    }
    private boolean readCircuitLibrary(CircuitDocument preview) {
        if(preview!=null && !reviewedCircuitSectionDraft(preview)) return false;
        var editor=circuitEditor;
        if(editor!=null) editor.endPointer();
        boolean preserve=editor!=null && (preview!=null || editor.dirty());
        String id=editor==null?null:editor.document().id();
        var maps=circuits.all();
        if(!circuits.reload(preserve?id:null)) {notifyUser(circuits.warning);return false;}
        if(!preserve) reconcileCleanCircuitEditor(editor);
        if(maps!=circuits.all()) reloadCircuitReviews();
        notifyUser(preserve?"已重读地图资料库，当前编辑保留。":"已重读地图资料库。");
        return true;
    }
    private void reconcileCleanCircuitEditor(CircuitEditor editor) {
        var disk=editor==null?null:circuits.find(editor.document().id()).orElse(null);
        if(disk==null) loadAvailableCircuitDraft();
        else if(!disk.document().equals(editor.document()) || !Objects.equals(disk.proof(),editor.proof()))
            setCircuitEditor(disk.document(),disk.proof(),true);
    }
    public boolean saveCircuitDraftCopy(CircuitDocument reviewed,String name,CircuitStore.PracticeSlot preview) {
        if(!reviewedCircuitSectionDraft(reviewed)) return false;
        var copy=circuits.saveDraftCopy(reviewed,name,preview);
        if(copy==null) {notifyUser(circuits.warning);return false;}
        setCircuitEditor(copy,null,true);
        notifyUser(preview==null?"已打开另存的新草稿。可在草稿库返回原地图。"
                :"已打开另存的新草稿。当前区间已保存到练习预设第 "+preview.number()+" 槽。");
        return true;
    }
    public void closeCircuitEditor() {checkpointAuthoring();if(circuitEditor==null || persistCircuitDraft()) mainMenu();}
    public void deleteCircuitDraft() {
        if(circuitEditor!=null) deleteCircuitDraft(circuitEditor.document().id());
    }
    public void deleteCircuitDraft(String id) {
        var before=circuits.all();int index=-1;for(int i=0;i<before.size();i++) if(before.get(i).document().id().equals(id)) index=i;
        boolean current=circuitEditor!=null && circuitEditor.document().id().equals(id);
        if(index<0 && !current) { notifyUser("这张草稿已经删除。");return; }
        if(index>=0 && !circuits.delete(id)) { notifyUser(circuits.warning);return; }
        boolean briefSaved=creationStore.commission(id)==null || creationStore.abandon(id);
        pendingCircuitReviews.remove(id);
        if(store.profile.trackId.equals(id)) { store.profile.trackId=TrackCatalog.DEFAULT_ID;store.save(); }
        if(current) {
            circuitEditor=null;lastCircuitReview=null;circuitReviewIndex=-1;
            com.f1car.persistence.CircuitStore.Saved previous=null;
            while(!circuitVisits.isEmpty() && previous==null) { String visit=circuitVisits.removeLast();if(!visit.equals(id)) previous=circuits.find(visit).orElse(null); }
            if(previous==null && !circuits.all().isEmpty()) previous=circuits.all().get(index>0?Math.min(index-1,circuits.all().size()-1):circuits.all().size()-1);
            if(previous!=null) setCircuitEditor(previous.document(),previous.proof(),true);
            if(hud!=null) hud.resetCircuitEditorState();
        }
        circuitVisits.remove(id);notifyUser("草稿已删除。"+(circuitEditor==null?"当前没有草稿。":"")+(briefSaved?"":"关联委托的移除待保存，请到创作页重试。"));
    }
    public void importCircuit(Path path) {
        if(!feedbackEditing()) return;
        try {
            acceptCircuitImport(circuits.importDraftFile(path),path.getFileName().toString());
        } catch(java.io.IOException | RuntimeException error) { notifyUser("导入失败，请检查地图或区间草稿文件。当前草稿保留。"); }
    }
    public void acceptCircuitImport(CircuitStore.ImportedDraft draft,String fileName) {
        if(!feedbackEditing()) return;
        if(draft.slot()!=null) {
            stageCircuitPracticeImport(new PendingCircuitPractice(draft,fileName));
            return;
        }
        if(circuitEditor!=null && circuitEditor.dirty() && !persistCircuitDraft()) return;
        if(circuits.all().size()>=32) { notifyUser("自制地图槽位已满，请先删除不需要的草稿。");return; }
        setCircuitEditor(draft.document(),null,false);
        if(hud!=null) hud.resetCircuitEditorView();
        notifyUser("地图已导入为新草稿；请在本机重新四车试跑。");
    }
    private void stageCircuitPracticeImport(PendingCircuitPractice next) {
        var previous=pendingCircuitPractice;
        if(previous==null) {
            pendingCircuitPractice=next;
            reviewPendingCircuitPracticeImport();
        } else if(hud!=null) {
            String detail="当前 · "+previous.draft().document().name()+"\n"+previous.fileName()
                    +"\n更换为 · "+next.draft().document().name()+"\n"+next.fileName();
            hud.confirm("更换待导入的区间草稿？",detail,()->{
                if(!feedbackEditing() || pendingCircuitPractice!=previous) return;
                pendingCircuitPractice=next;
                reviewPendingCircuitPracticeImport();
            });
        }
    }
    public boolean circuitPracticeImportPending() {return pendingCircuitPractice!=null;}

    public void reviewPendingCircuitPracticeImport() {
        var pending=pendingCircuitPractice;
        if(!feedbackEditing() || pending==null || hud==null) return;
        var context=new CircuitImportContext(circuitEditor);
        var target=practiceImportTarget(pending.draft(),context.document());
        hud.reviewCircuitPracticeImport(pending.draft(),pending.fileName(),context.dirty()?context.document().name():null,
                ()->commitCircuitPracticeImport(pending,context),target,
                target==null?null:number->commitCircuitPracticeSlot(pending,context,target,number));
    }
    private CircuitStore.Saved practiceImportTarget(CircuitStore.ImportedDraft draft,CircuitDocument working) {
        if(working==null || !working.sameDrivingLayout(draft.document())) return null;
        var saved=circuits.find(working.id()).orElse(null);
        return saved!=null && saved.document().sameDrivingLayout(working)?saved:null;
    }
    public void discardPendingCircuitPracticeImport() {
        var pending=pendingCircuitPractice;
        if(!feedbackEditing() || pending==null || hud==null) return;
        hud.confirm("放弃待导入的区间草稿？",pending.draft().document().name(),()->{
            if(feedbackEditing() && pendingCircuitPractice==pending) pendingCircuitPractice=null;
        });
    }
    private void commitCircuitPracticeImport(PendingCircuitPractice pending,CircuitImportContext context) {
        if(!matchesPracticeImport(pending,context)) return;
        if(context.dirty() && !flushCircuitReviews()) return;
        var draft=pending.draft();
        if(!circuits.saveImportedPractice(draft,context.dirty()?context.document():null,context.proof())) {
            notifyUser(circuits.warning);
            return;
        }
        if(context.dirty()) context.editor().saved();
        pendingCircuitPractice=null;
        setCircuitEditor(draft.document(),null,true);
        notifyUser("区间草稿已导入。打开「区间试驾」可读取第 "+draft.slot().number()+" 槽。");
    }
    private void commitCircuitPracticeSlot(PendingCircuitPractice pending,CircuitImportContext context,CircuitStore.Saved target,int number) {
        if(!matchesPracticeImport(pending,context)) return;
        if(!circuits.applyImportedPracticeSlot(pending.draft(),target.document(),number,target.practiceSlot(number))) {
            notifyUser(circuits.warning);
            return;
        }
        pendingCircuitPractice=null;
        if(hud!=null) hud.refreshCircuitPracticeSlots();
        notifyUser("练习区间已存到当前地图第 "+number+" 槽。");
    }
    private boolean matchesPracticeImport(PendingCircuitPractice pending,CircuitImportContext context) {
        if(!feedbackEditing() || pendingCircuitPractice!=pending) return false;
        if(context.matches(circuitEditor)) return true;
        notifyUser("当前编辑已变化，请重新打开待导入区间草稿。");
        return false;
    }
    public void exportCircuit(Path path) {
        if(circuitEditor==null) { notifyUser("请先新建或选择草稿。");return; }
        circuitEditor.endPointer();
        exportCircuit(circuitEditor.document(),path,false);
    }
    public boolean exportCircuit(CircuitDocument expected,Path path,boolean replace) {
        return exportCircuitDraft(expected,null,path,replace);
    }
    public boolean exportCircuitPractice(CircuitDocument source,CircuitStore.PracticeSlot preview,Path path,boolean replace) {
        return preview!=null && exportCircuitDraft(source,preview,path,replace);
    }
    private boolean exportCircuitDraft(CircuitDocument source,CircuitStore.PracticeSlot preview,Path path,boolean replace) {
        if(!feedbackEditing() || circuitEditor==null) return false;
        try {
            return exportReviewedCircuitDraft(source,preview,ExportDestination.forWrite(path,replace));
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("地图未导出，请选择独立文件或确认覆盖。");
            return false;
        }
    }
    public boolean exportReviewedCircuitDraft(CircuitDocument source,CircuitStore.PracticeSlot preview,ExportDestination destination) {
        if(!feedbackEditing() || circuitEditor==null) return false;
        circuitEditor.endPointer();
        if(!circuitEditor.document().equals(source)) {
            notifyUser("地图草稿已变化，请重新选择导出。");
            return false;
        }
        try {
            var target=contentExportTarget(destination.path());
            if(preview==null) {
                circuits.exportFile(source,target,destination);
                notifyUser("地图已导出；对方导入后需要重新试跑。");
            } else {
                circuits.exportPracticeFile(source,preview,target,destination);
                notifyUser("已导出当前地图和区间设置。可从地图编辑器导入为新草稿。");
            }
            return true;
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("地图未导出，请重新核对目标文件。当前草稿已保留。");
            return false;
        }
    }
    public CircuitDocument circuitShowcaseDocument(String id) {return circuitEditor!=null && circuitEditor.document().id().equals(id)?circuitEditor.document():circuits.find(id).map(com.f1car.persistence.CircuitStore.Saved::document).orElse(null);}
    public CircuitTrial.Proof circuitShowcaseProof(String id) {return circuitEditor!=null && circuitEditor.document().id().equals(id)?circuitEditor.proof():circuits.find(id).map(com.f1car.persistence.CircuitStore.Saved::proof).orElse(null);}
    public boolean exportCircuitShowcase(CircuitShowcase expected,Path path,boolean png) {
        return exportCircuitShowcase(expected,path,png,false);
    }
    public boolean exportCircuitShowcase(CircuitShowcase expected,Path path,boolean png,boolean replace) {
        try {
            return exportReviewedCircuitShowcase(expected,ExportDestination.forWrite(path,replace),png);
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("无法核对导出文件，请重新选择目标。");
            return false;
        }
    }
    public boolean exportReviewedCircuitShowcase(CircuitShowcase expected,ExportDestination destination,boolean png) {
        if(online() || screen!=Screen.MAP_EDITOR || expected==null || !expected.matches(circuitShowcaseDocument(expected.document().id()),circuitShowcaseProof(expected.document().id()),vehicleSpec.physicsVersion)) {notifyUser("地图或本机验证状态已变化，请重新预览封面。");return false;}
        try {
            var target=contentExportTarget(destination.path());
            com.f1car.persistence.CircuitShowcaseFiles.extension(target,png?".png":".txt");
            if(png) com.f1car.persistence.CircuitShowcaseFiles.png(target,com.f1car.ui.CircuitShowcaseRenderer.png(expected),destination);
            else com.f1car.persistence.CircuitShowcaseFiles.text(target,expected.text(),destination);
            notifyUser(png?"布局封面已导出；完整介绍与可驾驶地图分别分享。":"完整作品说明已导出；接收者仍需地图 JSON 与本机验证。");
            return true;
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("作品导出未完成，请重新核对目标文件后重试；地图草稿保留。");
            return false;
        }
    }
    public void returnToCircuitEditor() { mainMenu();screen=Screen.MAP_EDITOR;physics.setSpeed(0); }

    public CircuitFeedback.LocationSource sectionFeedback() {return sectionFeedback;}

    public CircuitFeedbackTrial sectionFeedbackTrial() {return sectionFeedbackTrial;}
    public String sectionFeedbackReturnLabel() {return sectionFeedbackDiscussion==null?"返回意见附近":"返回讨论留言";}

    public boolean canReplyWithFeedbackTrial(CircuitFeedbackTrial trial) {
        return screen==Screen.RESULTS && trial!=null && trial==sectionFeedbackTrial && sectionFeedbackDiscussion!=null
                && trial.equals(circuitFeedback.trial(trial.feedbackId(),trial.noteId()));
    }

    public CircuitFeedbackDiscussionDraft prepareFeedbackTrialReply(CircuitFeedbackTrial trial) {
        if(!canReplyWithFeedbackTrial(trial) || circuitEditor==null
                || !currentCircuitFeedbackLocation(sectionFeedback,circuitEditor.document()) || !circuitFeedback.currentTrial(trial)) {
            notifyUser("本次试驾或本机反馈库已变化，请先核对已存成绩。");
            return null;
        }
        var origin=sectionFeedbackDiscussion;
        var thread=feedbackDiscussions.find(origin.thread().id());
        if(!origin.matches(thread) || !feedbackDiscussions.currentOnDisk()) {
            notifyUser("原讨论或留言已变化，请重新读取讨论库后核对。");
            return null;
        }
        if(thread.messages().size()>=CircuitFeedbackDiscussion.CAPACITY) {
            notifyUser("原话题已满。可从讨论库另起话题并附上已存试驾。");
            return null;
        }
        return CircuitFeedbackDiscussionDraft.begin(thread.feedback(),thread,origin.message().id(),store.profile.playerName)
                .scope(CircuitFeedbackDiscussion.Kind.REPLY,trial.noteId(),null).trial(trial);
    }

    public CircuitFeedback.LocationSource savedCircuitFeedbackTrialSource(CircuitFeedbackTrial trial) {
        if(trial==null || screen!=Screen.MAP_EDITOR || online() || circuitEditor==null
                || !trial.equals(circuitFeedback.trial(trial.feedbackId(),trial.noteId()))) return null;
        var draft=circuitEditor.document();
        var entry=circuitFeedback.find(trial.feedbackId());
        if(!trial.belongsTo(entry) || !trial.binding().equals(entry.binding()) || !draft.id().equals(trial.binding().targetId())
                || !trial.result().driveKey().equals(draft.driveKey())) return null;
        var note=entry.feedback().notes().stream().filter(value->value.id().equals(trial.noteId())).findFirst().orElseThrow();
        var source=new CircuitFeedback.LocationSource(entry,note);
        return currentCircuitFeedbackLocation(source,draft)?source:null;
    }

    public boolean saveCircuitFeedbackTrial(CircuitFeedbackTrial shown,CircuitFeedbackTrial expected) {
        if(screen!=Screen.RESULTS || shown==null || shown!=sectionFeedbackTrial || circuitEditor==null
                || !currentCircuitFeedbackLocation(sectionFeedback,circuitEditor.document())) {
            notifyUser("意见或试驾已经变化，请返回意见重新核对。");
            return false;
        }
        if(!circuitFeedback.saveTrial(sectionFeedback,shown,expected)) {
            notifyUser(circuitFeedback.warning());
            return false;
        }
        notifyUser("作者试驾已保存到这条意见。");
        return true;
    }

    public boolean currentCircuitFeedbackLocation(CircuitFeedback.LocationSource source,CircuitDocument draft) {
        if(source==null || !source.matches(circuitFeedback.find(source.entry().feedback().id()))) return false;
        var alignment=source.entry().alignment(draft);
        return alignment==CircuitFeedback.Alignment.MATCHED || alignment==CircuitFeedback.Alignment.REFERENCE;
    }

    public boolean checkCircuitFeedbackPractice(CircuitFeedback.LocationSource source,CircuitDocument draft,CircuitFeedbackTrial expectedTrial) {
        if(!currentCircuitFeedbackLocation(source,draft)) {
            notifyUser("反馈或地图对应已变化，请返回意见重新预览。");
            return false;
        }
        if(!circuitFeedback.currentLocation(source,expectedTrial)) {
            notifyUser("反馈库已变化，请重新读取后核对所选意见、地图对应和试驾。");
            return false;
        }
        return true;
    }

    public boolean returnToCircuitFeedback() {
        var source=sectionFeedback;
        if(source==null || screen!=Screen.RESULTS && screen!=Screen.PAUSED) return false;
        var discussion=sectionFeedbackDiscussion;
        var thread=discussion==null?null:currentFeedbackDiscussion(discussion);
        returnToCircuitEditor();
        if(hud!=null && thread!=null && circuitEditor!=null) {
            hud.showCircuitFeedbackDiscussion(thread,discussion.message());
            return true;
        }
        boolean sourceExists=circuitFeedback.currentLocation(source,null);
        boolean current=sourceExists && circuitEditor!=null && currentCircuitFeedbackLocation(source,circuitEditor.document());
        if(hud!=null) {
            if(current) hud.resumeCircuitFeedbackNearby(source);
            else if(sourceExists) hud.showCircuitFeedbackNote(source);
            else hud.showCircuitFeedback();
        }
        if(discussion!=null) notifyUser("原讨论或留言已变化。已返回反馈入口，可在讨论库重新读取后核对。");
        else if(!current) notifyUser("反馈或地图对应已变化，请在反馈库重新读取后核对。");
        return true;
    }

    private CircuitFeedbackDiscussion currentFeedbackDiscussion(FeedbackDiscussionReturn destination) {
        return feedbackDiscussions.currentThread(destination.thread().id(),destination::matches);
    }

    public boolean checkFeedbackDiscussionAttachment(FeedbackDiscussionReturn destination) {
        if(currentFeedbackDiscussion(destination)!=null) return true;
        notifyUser("原讨论或留言已变化，请重新读取讨论库后核对附件。");
        return false;
    }

    public void viewCircuitReview() { returnToCircuitEditor();if(hud!=null) hud.showCircuitReview(); }
    public boolean returnToCircuitObservation() {
        if(screen!=Screen.RESULTS || sectionObservationRetest==null) return false;
        var source=sectionObservationRetest.source();
        if(circuitEditor==null || !source.mapId().equals(circuitEditor.document().id())) {
            notifyUser("当前草稿找不到原观测，复测成绩仍可查看。");
            return false;
        }
        return openCircuitObservation(source,true);
    }
    private boolean openCircuitObservation(CircuitObservationRetest.Source source,boolean recheckDisk) {
        if(circuitEditor==null || !source.mapId().equals(circuitEditor.document().id())) return false;
        var snapshot=pendingCircuitReviews.get(source.mapId());
        int index=-1;
        if(source.savedAt()!=CircuitObservationRetest.Source.PENDING || source.snapshot()!=snapshot) {
            var reviews=circuitReviews();
            index=CircuitStore.observationReviewIndex(reviews,source);
            snapshot=index<0?null:reviews.get(index).snapshot();
        }
        if(snapshot==null) {
            if(recheckDisk) notifyUser("原观测已移除或无法唯一定位，复测成绩仍可查看。");
            return false;
        }
        if(recheckDisk && !currentObservationOnDisk(source,null)) return false;
        if(screen==Screen.RESULTS) returnToCircuitEditor();
        lastCircuitReview=snapshot;
        circuitReviewIndex=index;
        if(hud!=null) hud.showCircuitObservation(source.markerIndex());
        return true;
    }
    public boolean saveCircuitSectionPreset(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start) {
        var first=reviewed==null?null:circuits.find(reviewed.id()).map(saved->saved.practiceSlot(1)).orElse(null);
        return saveCircuitSectionPreset(reviewed,1,first,range,start);
    }
    public boolean saveCircuitSectionPreset(CircuitDocument reviewed,int slot,CircuitStore.PracticeSlot expected,
                                            CircuitSectionPractice.Range range,CircuitSectionPractice.Start start) {
        return saveCircuitSectionPreset(reviewed,slot,expected,range,start,null);
    }
    public boolean saveCircuitSectionPreset(CircuitDocument reviewed,int slot,CircuitStore.PracticeSlot expected,
                                            CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,Integer split) {
        if(range==null || start==null || !reviewedCircuitSectionDraft(reviewed)) return false;
        if(!persistCircuitDraft(range,start,slot,expected,split)) return false;
        notifyUser("草稿和练习预设已保存。");
        return true;
    }
    public boolean clearCircuitSectionPreset(CircuitDocument reviewed,CircuitStore.PracticeSlot expected) {
        if(!reviewedCircuitSectionDraft(reviewed)) return false;
        if(!circuits.clearPracticeSlot(reviewed.id(),expected)) {notifyUser(circuits.warning);return false;}
        notifyUser("已清除本机练习预设。");
        return true;
    }
    public boolean renameCircuitSectionPreset(CircuitDocument reviewed,CircuitStore.PracticeSlot expected,String name) {
        if(!reviewedCircuitSectionDraft(reviewed)) return false;
        if(!circuits.renamePracticeSlot(reviewed.id(),expected,name)) {notifyUser(circuits.warning);return false;}
        return true;
    }
    public boolean startCircuitSection(CircuitDocument reviewed,CircuitSectionPractice.Range range) {
        return startCircuitSection(reviewed,range,null);
    }
    public boolean startCircuitSection(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start) {
        return startCircuitSection(reviewed,range,start,0);
    }
    public boolean startCircuitSection(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,int split) {
        return startCircuitSection(reviewed,range,start,split,null);
    }
    public boolean startCircuitSection(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,int split,
                                       CircuitObservationRetest.Source observation) {
        if(observation!=null) {
            observation=matchingCircuitObservation(observation);
            if(observation==null) {
                notifyUser("原观测已变化，请重新选择复测区间。");
                return false;
            }
            if(!currentObservationOnDisk(observation,null)) return false;
        }
        return startCircuitSection(reviewed,range,start,split,observation,null,null);
    }

    public boolean startCircuitObservationRetest(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,
                                                  int split,CircuitObservationRetest shown,boolean saved) {
        if(reviewed==null || shown==null) return false;
        if(!shown.result().driveKey().equals(reviewed.driveKey())) {
            notifyUser("复测对应的草稿已变化，请从原观测按当前草稿重新选段。");
            return false;
        }
        var source=reviewedObservationRetest(shown,saved);
        return source!=null && startCircuitSection(reviewed,range,start,split,source,null,null);
    }

    public boolean startCircuitFeedbackSection(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,int split,
                                               CircuitFeedback.LocationSource feedback) {
        return startCircuitFeedbackSection(reviewed,range,start,split,feedback,null);
    }

    public boolean startCircuitFeedbackSection(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,int split,
                                               CircuitFeedback.LocationSource feedback,FeedbackDiscussionReturn discussion) {
        return startCircuitFeedbackSection(reviewed,range,start,split,feedback,discussion,null);
    }

    public boolean startCircuitFeedbackSection(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,int split,
                                               CircuitFeedback.LocationSource feedback,FeedbackDiscussionReturn discussion,CircuitFeedbackTrial expectedTrial) {
        if(feedback==null) return false;
        if(discussion!=null) {
            if(!checkFeedbackDiscussionAttachment(discussion)) return false;
            var trial=discussion.message().trial();
            if(!discussion.thread().feedback().equals(feedback.entry().feedback()) || expectedTrial!=null && !expectedTrial.equals(trial)) {
                notifyUser("原反馈、试驾或地图草稿已变化，请返回留言重新预览。");
                return false;
            }
            expectedTrial=trial;
        }
        if(expectedTrial!=null && (reviewed==null || !expectedTrial.result().driveKey().equals(reviewed.driveKey()))) {
            notifyUser("试驾所用草稿已变化，请从这条意见按当前草稿重新选段。");
            return false;
        }
        if(!checkCircuitFeedbackPractice(feedback,reviewed,expectedTrial)) return false;
        return startCircuitSection(reviewed,range,start,split,null,feedback,discussion);
    }

    private boolean startCircuitSection(CircuitDocument reviewed,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,int split,
                                        CircuitObservationRetest.Source observation,CircuitFeedback.LocationSource feedback,
                                        FeedbackDiscussionReturn discussion) {
        if(range==null || !reviewedCircuitSectionDraft(reviewed)) return false;
        var driveSettings=controls.settings();
        if(!controlsReady(driveSettings)) return false;
        var track=circuitEditor.inspection().track();
        if(track==null) {
            notifyUser("路线尚不能试驾，请先在检查面板调整路线点。");
            return false;
        }
        var settings=start==null?CircuitSectionPractice.Start.defaults(track):start;
        if(settings.runUpMeters()>CircuitSectionPractice.Start.maximumRunUp(track)) {
            notifyUser("助跑距离须短于一圈，请缩短后再试。");
            return false;
        }
        if(split<0 || split>0 && (range.splitCount()<2 || split>range.splitCount())) {
            notifyUser("原区间没有这个分段，请重新核对预设。");
            return false;
        }
        var trial=new CircuitSectionPractice(track,range,settings,split);
        if(trial.spawnBlocked()) {
            notifyUser("发车点被场景占用，请调整助跑距离、区间起点或布景。");
            return false;
        }
        launchCircuitSection(trial,driveSettings,null,reviewed.driveKey(),observation,feedback,discussion,null);
        return true;
    }

    public EquipmentSectionPreview previewEquipmentSection(EquipmentSectionPracticeSession session) {
        if(session==null || !equipmentSectionOrigin()) return null;
        var road=equipmentSectionLaunchTrack(session.conditions());
        if(road==null || !road.closed) {
            notifyUser("找不到完整匹配的原赛道，已存走线仍可查看。");
            return null;
        }
        var car=equipmentSectionCarAvailable(session,session.lastCar())?session.lastCar():EquipmentSectionPracticeSession.Car.STOCK;
        return new EquipmentSectionPreview(screen,session,road,session.lastStart(road),controls.settings(),car,null,null);
    }

    public EquipmentSectionPreview equipmentSectionResultContext(CircuitSectionPractice expected) {
        if(!currentEquipmentSectionResult(expected)) return null;
        return new EquipmentSectionPreview(screen,equipmentSectionReturn.session(),expected.track(),expected.start(),driveControls,
                equipmentSectionReturn.car(),expected,equipmentSectionReturn.subsectionReference());
    }

    public boolean equipmentSectionCarAvailable(EquipmentSectionPracticeSession session,EquipmentSectionPracticeSession.Car car) {
        return session.available(car,vehicleSpec.id,vehicleSpec.physicsVersion);
    }

    private boolean equipmentSectionOrigin() {
        if(online() || screen!=Screen.TELEMETRY && screen!=Screen.TUNING_LAB && screen!=Screen.PARTS) return false;
        if(screen==Screen.TELEMETRY && (telemetryReturn==Screen.DRIVING || telemetryReturn==Screen.PAUSED)) {
            notifyUser("当前驾驶仍暂停在遥测后面。结束本场后，可从记录库开始独立区间练习。");
            return false;
        }
        return true;
    }

    public boolean startEquipmentSection(EquipmentSectionPreview expected) {
        return startEquipmentSection(expected,null);
    }

    public boolean startEquipmentSection(EquipmentSectionPreview expected,Runnable reopenComparison) {
        return startEquipmentSection(expected,reopenComparison,reopenComparison==null?"返回整圈对照":"返回计时门对照");
    }

    public boolean startEquipmentSection(EquipmentSectionPreview expected,Runnable reopenComparison,String backLabel) {
        if(expected==null || screen!=expected.origin()) return false;
        var session=expected.session();
        var car=expected.car();
        var source=expected.sourceResult();
        if(source==null) {
            if(!equipmentSectionOrigin()) return false;
        } else if(!currentEquipmentSectionResult(source) || !session.subsectionOf(equipmentSectionReturn.session())) return false;
        var current=source==null?equipmentSectionLaunchTrack(session.conditions()):source.track();
        if(!session.conditions().matchesTrack(current) || !expected.controls().equals(controls.settings())) {
            notifyUser("原赛道或驾驶控制已变化，请重新预览区间练习。");
            return false;
        }
        int livery=source==null?store.profile.tuning.livery():equipmentSectionReturn.tuning().livery();
        var tuning=session.tuning(car,livery);
        var original=source==null?null:new EquipmentSectionResult(source,equipmentSectionReturn,driveControls,sessionTime,resultTitle,resultDescription);
        var back=new EquipmentSectionReturn(screen,circuit,session,car,tuning,original,reopenComparison,expected.subsectionReference(),backLabel);
        return launchEquipmentSection(back,current,expected.start(),expected.controls());
    }

    private boolean launchEquipmentSection(EquipmentSectionReturn back,TrackLayout road,CircuitSectionPractice.Start start,
                                           ControlSettings settings) {
        var session=back.session();
        var car=back.car();
        if(!equipmentSectionCarAvailable(session,car)) {
            notifyUser("记录中的车型或物理版本与当前游戏不同，可选原厂车辆练习。");
            return false;
        }
        if(!controlsReady(settings)) return false;
        var trial=session.newAttempt(road,start,car,settings.identity());
        if(trial.spawnBlocked()) {
            notifyUser("区间助跑点被布景占用，请重新预览。");
            return false;
        }
        launchCircuitSection(trial,settings,null,null,null,null,null,back);
        session.noteLaunch(car,start);
        return true;
    }

    public boolean equipmentSectionActive() {return equipmentSectionReturn!=null;}

    public String equipmentSectionReturnLabel() {
        return equipmentSectionReturn==null?"返回整圈对照":equipmentSectionReturn.backLabel();
    }

    public EquipmentSectionPracticeSession.Car equipmentSectionCar() {
        return equipmentSectionReturn==null?null:equipmentSectionReturn.car();
    }

    public EquipmentSectionPracticeSession.SubsectionReference equipmentSectionReference() {
        return equipmentSectionReturn==null?null:equipmentSectionReturn.subsectionReference();
    }

    private boolean currentEquipmentSectionResult(CircuitSectionPractice expected) {
        return screen==Screen.RESULTS && !online() && equipmentSectionReturn!=null && expected!=null
                && expected==circuitSectionPractice && expected.focusedSplit()==0 && expected.complete();
    }

    public boolean canSwitchEquipmentSectionCar(CircuitSectionPractice expected,EquipmentSectionPracticeSession.Car car) {
        return currentEquipmentSectionResult(expected) && car!=null && car!=equipmentSectionReturn.car()
                && equipmentSectionCarAvailable(equipmentSectionReturn.session(),car);
    }

    public boolean switchEquipmentSectionCar(CircuitSectionPractice expected,EquipmentSectionPracticeSession.Car car) {
        if(!canSwitchEquipmentSectionCar(expected,car)) return false;
        if(!driveControls.equals(controls.settings())) {
            notifyUser("驾驶控制已变化，请"+equipmentSectionReturnLabel()+"重新预览。");
            return false;
        }
        var back=equipmentSectionReturn;
        var tuning=back.session().tuning(car,back.tuning().livery());
        var next=new EquipmentSectionReturn(back.screen(),back.track(),back.session(),car,tuning,back.original(),back.reopenComparison(),
                back.subsectionReference(),back.backLabel());
        return launchEquipmentSection(next,expected.track(),expected.start(),driveControls);
    }

    public EquipmentSectionPracticeSession.Progress equipmentSectionProgress(EquipmentSectionPracticeSession.Car car) {
        var back=equipmentSectionReturn;
        var trial=circuitSectionPractice;
        return back==null || trial==null || trial.focusedSplit()!=0?null:back.session().progress(car,trial.start(),controlsIdentity());
    }

    public boolean equipmentSectionHasRuns() {
        var trial=circuitSectionPractice;
        return equipmentSectionReturn!=null && trial!=null && trial.focusedSplit()==0
                && equipmentSectionReturn.session().hasEquipmentRuns(trial.start(),controlsIdentity());
    }

    public boolean returnToEquipmentSection() {
        var back=equipmentSectionReturn;
        if(back==null || screen!=Screen.PAUSED && screen!=Screen.RESULTS) return false;
        if(hud!=null) hud.cancelTransition();
        var original=back.original();
        if(original==null) {
            preview(back.track());
            screen=back.screen();
        } else {
            launchCircuitSection(original.trial(),original.controls(),null,null,null,null,null,original.destination());
            sessionTime=original.time();
            resultTitle=original.title();
            resultDescription=original.description();
            countdown=0;
            finishRecorded=true;
            screen=Screen.RESULTS;
        }
        physics.setSpeed(0);
        if(back.reopenComparison()!=null) back.reopenComparison().run();
        return true;
    }

    public CircuitStore.PracticePreset finishedSplitPreset(CircuitSectionPractice source) {
        if(screen!=Screen.RESULTS || online() || source==null || source!=circuitSectionPractice
                || source.range()==null || source.focusedSplit()==0 || source.state()!=CircuitSectionPractice.State.FINISHED) return null;
        if(circuitEditor==null || sectionDriveKey==null || !sectionDriveKey.equals(circuitEditor.document().driveKey())) {
            notifyUser("地图路线或布景已变化，请在当前草稿重新试驾。");
            return null;
        }
        return new CircuitStore.PracticePreset(sectionDriveKey,source.range(),source.start(),source.focusedSplit());
    }

    private boolean reviewedCircuitSectionDraft(CircuitDocument reviewed) {
        if(screen!=Screen.MAP_EDITOR || online() || circuitEditor==null) return false;
        circuitEditor.endPointer();
        if(circuitEditor.document().equals(reviewed)) return true;
        notifyUser("当前草稿已改变，请重新打开区间预览。");
        return false;
    }

    public boolean startCircuitSplit(CircuitSectionPractice source,int number) {
        return source!=null && startCircuitSplit(source,number,source.start());
    }

    public boolean startCircuitSplit(CircuitSectionPractice source,int number,CircuitSectionPractice.Start start) {
        if(screen!=Screen.RESULTS || online() || source==null || source!=circuitSectionPractice || start==null
                || source.state()!=CircuitSectionPractice.State.FINISHED || source.splitCount()<2
                || number<1 || number>source.splitCount()) return false;
        var original=originalSectionResult;
        if(original==null || original.trial()!=source)
            original=new SectionResult(source,driveControls,sessionTime,resultTitle,resultDescription,new CircuitSplitPracticeHistory(source),
                    sectionObservationRetest,sectionFeedbackTrial);
        return launchCircuitSplit(original,number,start,controls.settings());
    }

    public boolean startCircuitSplitFromProgress(CircuitSectionPractice source,CircuitSplitPracticeHistory.Progress progress) {
        var original=originalSectionResult;
        if(screen!=Screen.RESULTS || online() || source==null || source!=circuitSectionPractice || original==null || progress==null)
            return false;
        var selection=progress.selection();
        if(source!=original.trial() && source.focusedSplit()!=selection.number()) return false;
        if(original.splitHistory().progress(selection.number(),selection.start(),progress.controls())!=progress) {
            notifyUser("练习记录已变化，请重新打开对照。");
            return false;
        }
        var settings=controls.settings();
        if(!settings.identity().equals(progress.controls())) {
            notifyUser("当前驾驶控制与记录不同，请恢复原配置后再练习。");
            return false;
        }
        if(!launchCircuitSplit(original,selection.number(),selection.start(),settings)) return false;
        toastLeft=0;
        return true;
    }

    private boolean launchCircuitSplit(SectionResult original,int number,CircuitSectionPractice.Start start,ControlSettings settings) {
        if(!controlsReady(settings)) return false;
        if(start.runUpMeters()>CircuitSectionPractice.Start.maximumRunUp(original.trial().track())) {
            notifyUser("助跑距离须短于一圈，请缩短后再试。");
            return false;
        }
        var trial=original.splitHistory().newAttempt(number,start,settings.identity());
        if(trial.spawnBlocked()) {
            notifyUser("分段发车点被场景占用，请在成绩地图调整助跑距离。");
            return false;
        }
        launchCircuitSection(trial,settings,original,sectionDriveKey,sectionObservation,sectionFeedback,sectionFeedbackDiscussion,equipmentSectionReturn);
        return true;
    }

    public CircuitSplitPracticeHistory.Selection lastCircuitSplitPractice(CircuitSectionPractice source) {
        var original=originalSectionResult;
        return original!=null && source==circuitSectionPractice && source==original.trial()?original.splitHistory().lastSelection():null;
    }

    public CircuitSplitPracticeHistory.Progress splitPracticeProgress(CircuitSectionPractice source,int number,CircuitSectionPractice.Start start) {
        var original=originalSectionResult;
        if(screen!=Screen.RESULTS || original==null || source==null || source!=circuitSectionPractice) return null;
        var history=original.splitHistory();
        if(source==original.trial()) return history.progress(number,start,controls.settings().identity());
        return source.focusedSplit()==number && source.start().equals(start)?history.lastLaunchProgress():null;
    }

    public List<CircuitSplitPracticeHistory.Progress> splitPracticeAlternateStarts(CircuitSectionPractice source,CircuitSplitPracticeHistory.Progress base) {
        var selection=base.selection();
        if(splitPracticeProgress(source,selection.number(),selection.start())!=base) return List.of();
        return originalSectionResult.splitHistory().alternateStarts(base);
    }

    public boolean returnToOriginalSectionResult() {
        var original=originalSectionResult;
        if(original==null || circuitSectionPractice==null || circuitSectionPractice.focusedSplit()==0
                || screen!=Screen.RESULTS && screen!=Screen.PAUSED) return false;
        if(hud!=null) hud.cancelTransition();
        circuitSectionPractice=original.trial();
        sectionObservationRetest=original.observationRetest();
        sectionFeedbackTrial=original.feedbackTrial();
        freezeControls(original.controls());
        sessionTime=original.sessionTime();
        resultTitle=original.title();
        resultDescription=original.description();
        countdown=0;
        helpVisible=false;
        screen=Screen.RESULTS;
        physics.setSpeed(0);
        return true;
    }

    private void launchCircuitSection(CircuitSectionPractice trial,ControlSettings settings,SectionResult original,String driveKey,
                                      CircuitObservationRetest.Source observation,CircuitFeedback.LocationSource feedback,
                                      FeedbackDiscussionReturn discussion,EquipmentSectionReturn equipmentBack) {
        closeRaceRoom();
        disposeSession();
        freezeControls(settings);
        circuit=trial.track();
        circuitSectionPractice=trial;
        equipmentSectionReturn=equipmentBack;
        sectionDriveKey=driveKey;
        sectionObservation=observation;
        sectionFeedback=feedback;
        sectionFeedbackDiscussion=discussion;
        originalSectionResult=original;
        mode=Mode.MAP_SECTION;
        sessionLaps=1;
        createWorld(false);
        var tuning=equipmentBack==null?Tuning.STOCK.withLivery(store.profile.tuning.livery()):equipmentBack.tuning();
        player=addCar("你 · 区间试驾",tuning,trial.spawnDistance(),0,false,1,1);
        previousSectionSpeedMps=sectionSpeed(player);
        autoDriver=new DriverAI(circuit,.84f,0);
        sessionTime=0;
        countdown=.8f;
        newBest=false;
        helpVisible=false;
        screen=Screen.DRIVING;
        physics.setSpeed(1);
        cameraImmediate();
        if(original!=null) original.splitHistory().noteLaunch(trial,settings.identity());
    }
    public void startCircuitSolo() {
        if(circuitEditor==null || online() || !controlsReady(controls.settings())) return;circuitEditor.endPointer();
        CircuitTrial trial;try { trial=circuitEditor.soloTrial(Math.max(0,circuitEditor.selectedAnchor())); }
        catch(IllegalArgumentException error) { notifyUser("路线尚不能构造，请先在检查面板调整路线点。");return; }
        if(!persistCircuitDraft()) return;startCircuitSolo(trial);
    }
    private void startCircuitSolo(CircuitTrial trial) {
        closeRaceRoom();disposeSession();freezeControls(controls.settings());circuit=trial.track();circuitTrial=trial;mode=Mode.MAP_SOLO;sessionLaps=1;createWorld(false);
        player=addCar("你 · 作者试驾",new Tuning(1,1,1,1,store.profile.tuning.livery()),-12,0,false,1,1);
        localFramePositions=new Vector3f[racers.size()];
        circuitTrialProgress=List.of(racers.get(0).progress);
        autoDriver=new DriverAI(circuit,.84f,0);sessionTime=0;countdown=.8f;newBest=false;helpVisible=false;screen=Screen.DRIVING;physics.setSpeed(1);cameraImmediate();
    }
    private void retryCircuitTrial() {
        if(circuitTrial.kind()==CircuitTrial.Kind.FOUR_CARS) startCircuitTrial();
        else if(controlsReady(controls.settings())) startCircuitSolo(circuitEditor.soloTrial(circuitTrial.startAnchor()));
    }
    public void startCircuitTrial() {
        if(circuitEditor==null || !controlsReady(controls.settings())) return;
        circuitEditor.endPointer();var inspection=circuitEditor.inspection();
        if(!inspection.ready()) { notifyUser(inspection.errors().get(0));return; }
        if(!persistCircuitDraft()) return;
        var trial=circuitEditor.fourCarTrial();closeRaceRoom();disposeSession();freezeControls(controls.settings());circuit=trial.track();circuitTrial=trial;mode=Mode.MAP_TRIAL;sessionLaps=1;createWorld(false);
        for(int driver=0;driver<4;driver++) {
            var car=addCar(driver==0?"你 · 地图作者":"验证 AI "+driver,new Tuning(1,1,1,1,driver==0?store.profile.tuning.livery():driver),-8-driver*7,driver%2==0?-2:2,driver>0,.76f+driver*.02f,1);
            if(driver==0) player=car;
        }
        localFramePositions=new Vector3f[racers.size()];
        circuitTrialProgress=racers.stream().map(racer->racer.progress).toList();
        sessionTime=0;countdown=4;newBest=false;helpVisible=false;autoDriver=new DriverAI(circuit,.84f,0);screen=Screen.DRIVING;physics.setSpeed(1);cameraImmediate();
    }
    public void openChallenges() { mainMenu();screen=Screen.CHALLENGES; }
    public ActivityTrial activityTrial() { return activityTrial; }
    public ActivityRun activityCheckpoint() {
        var active=activityStore.active();
        if(activityTrial==null) return active;
        var original=activityTrial.checkpoint();
        if(activityTrial.complete() && active!=null && original.id().equals(active.id())
                && original.last().equals(active.last()) && original.results().equals(active.results())) return active;
        return original;
    }
    public void openActivities() { if(online()) return;mainMenu();screen=Screen.ACTIVITIES;physics.setSpeed(0);if(!activityStore.warning.isEmpty()) notifyUser(activityStore.warning); }
    public String activityCompatibility(ActivityCard card) {String reason=card.incompatibility(vehicleSpec.physicsVersion,this::circuitById);return !reason.isEmpty() || card.partsRule()==null?reason:card.partsRule().incompatibility(partsStore.catalog(),vehicleSpec.id,vehicleSpec.physicsVersion);}
    public PartsBuild activityPartsBuild() {if(partsWorkbench==null) partsWorkbench=createPartsWorkbench();return partsWorkbench.build();}
    public PartsBuild.Review activityPartsReview(ActivityCard card) {if(card.partsRule()==null) throw new IllegalArgumentException("Stock card has no parts entry");return activityPartsChecks.get(card.partsRule(),activityPartsBuild(),partsStore.catalog(),vehicleSpec.id,vehicleSpec.physicsVersion,store.profile.tuning.livery());}
    public int activityPartsReviewComputations() {return activityPartsChecks.computations();}
    public void openActivityPartsWorkbench() {if(online() || screen!=Screen.ACTIVITIES) return;screen=Screen.GARAGE;openPartsWorkbench();partsReturn=Screen.ACTIVITIES;}
    public void openCreationPartsWorkbench() {if(online() || screen!=Screen.CREATION) return;screen=Screen.GARAGE;openPartsWorkbench();partsReturn=Screen.CREATION;}
    public boolean importActivityCard(ActivityCardCodec.Shared shared) {
        if(online() || screen!=Screen.ACTIVITIES || activityStore.pending()) return false;
        if(pendingActivityImport!=null && !Objects.equals(pendingActivityImport.shared(),shared)) {notifyUser("请先重试保存上一张导入卡的作者与说明。");return false;}
        if(pendingActivityImport==null) {var card=shared.copy();if(activityStore.cards().stream().anyMatch(existing->existing.key().equals(card.key()))) {notifyUser("相同条件的活动卡已在库中，原名称、备注和进度保留。");return false;}pendingActivityImport=new PendingActivityImport(shared,card);if(!activityStore.put(card)) {if(activityStore.cards().stream().noneMatch(existing->existing.id().equals(card.id()))) pendingActivityImport=null;notifyUser(activityStore.warning);return false;}}
        return finishActivityAttribution();
    }
    private boolean finishActivityAttribution() {var pending=pendingActivityImport;if(pending==null) return true;if(!contentText.put("activity:"+pending.card().id(),pending.text(),pending.text())) {notifyUser(contentText.warning);return false;}pendingActivityImport=null;notifyUser("活动卡、作者与说明已收录；地图与部件需匹配，不含场次进度或资格。");return true;}
    public boolean activityImportPending() {return pendingActivityImport!=null;}
    public void exportActivityCard(ActivityCard card,String author,String notes,Path path) {
        exportActivityCard(card,author,notes,path,false);
    }
    public boolean exportActivityCard(ActivityCard card,String author,String notes,Path path,boolean replace) {
        if(online() || screen!=Screen.ACTIVITIES) return false;
        try {
            var target=contentExportTarget(path);
            ActivityCardCodec.write(target,new ActivityCardCodec.Shared(author,notes,card),replace);
            notifyUser("活动卡已导出，作者目标可含参考圈与来源快照。");
            return true;
        }
        catch(java.io.IOException | RuntimeException error) {notifyUser("活动卡导出失败；已有玩家资料保留，请检查文件名与路径。");return false;}
    }
    public void saveActivityCard(ActivityCard card) { if(activityStore.put(card)) notifyUser("活动卡已保存，可随时再玩。");else notifyUser(activityStore.warning); }
    public void beginActivity(String cardId) {
        var card=activityStore.cards().stream().filter(c->c.id().equals(cardId)).findFirst().orElse(null);
        beginActivity(card);
    }
    public void beginActivity(ActivityCard expected,PartsBuild entry,PartsCatalog catalog) {
        beginActivity(expected,activityStore.active(),entry,catalog);
    }
    public void beginActivity(ActivityCard expected) {
        beginActivity(expected,activityStore.active(),null,null);
    }
    public void beginActivity(ActivityCard expected,ActivityRun previous,PartsBuild previewedBuild,PartsCatalog previewedCatalog) {
        var settings=controls.settings();
        if(online() || screen!=Screen.ACTIVITIES || !controlsReady(settings)) return;
        if(pendingActivityImport!=null) {notifyUser("请先重试保存导入活动的作者与说明。");return;}
        if(!refreshActivityArchive()) return;
        if(expected==null || !activityStore.cards().contains(expected) || !Objects.equals(previous,activityStore.active())) {
            notifyUser("活动卡或当前组合进度已变化，请重新预览后再开始。");
            return;
        }
        var track=validatedActivityTrack(expected,0);
        if(track==null) return;
        PartsBuild entry=null;
        if(expected.partsRule()!=null) {
            var draft=activityPartsBuild();
            if(previewedCatalog!=null && (partsStore.catalog()!=previewedCatalog || draft!=previewedBuild)) {
                notifyUser("活动方案或部件目录已改变，请重新预览参赛条件。");
                return;
            }
            var review=activityPartsReview(expected);
            if(!review.valid()) {notifyUser(review.issues().get(0));return;}
            entry=expected.partsRule().normalize(draft);
        }
        beginValidatedActivity(expected,entry,track,settings);
    }

    private void beginValidatedActivity(ActivityCard card,PartsBuild entry,TrackLayout track,ControlSettings settings) {
        if(!activityStore.begin(card,settings.identity(),store.profile.tuning.livery(),System.currentTimeMillis(),entry)) {
            notifyUser(activityStore.warning);
            return;
        }
        launchActivity(activityStore.active(),track,settings);
    }
    public void saveActivities() {if(activityStore.flush()) {if(pendingActivityImport!=null) finishActivityAttribution();else notifyUser("活动卡与阶段进度已保存。");}else notifyUser(activityStore.warning);}
    public void continueActivity() {
        continueActivity(activityCheckpoint());
    }
    public void continueActivity(ActivityRun expected) {
        var settings=controls.settings();
        if(online() || !controlsReady(settings) || !refreshActivityArchive()) return;
        var run=activityStore.active();
        if(!Objects.equals(expected,run)) {
            if(activityTrial!=null && !activityTrial.complete() && !activityTrial.run().equals(run)) openActivities();
            notifyUser("组合进度已更新，请从活动库核对后再继续。");
            return;
        }
        if(run==null || run.complete()) { openActivities();return; }
        if(!run.controls().equals(settings.identity())) {notifyUser("请恢复本次组合开始时的操作设置，或重新开始组合。");return;}
        var track=validatedActivityTrack(run.card(),run.stage());
        if(track==null) return;
        if(run.entry()!=null) {var review=run.card().partsRule().review(run.entry(),partsStore.catalog(),vehicleSpec.id,vehicleSpec.physicsVersion,run.livery());if(!review.valid()) {notifyUser(review.issues().get(0));return;}}
        launchActivity(run,track,settings);
    }

    private boolean refreshActivityArchive() {
        if(activityStore.pending()) {notifyUser("请先重试保存活动卡与本段成绩。");return false;}
        if(!activityStore.refreshBeforeLaunch()) {notifyUser("当前活动档案无法核对，请返回活动库重试。");return false;}
        return true;
    }

    private TrackLayout validatedActivityTrack(ActivityCard card,int stage) {
        boolean customMaps=card.stages().stream().anyMatch(s->TrackCatalog.all().stream().noneMatch(entry->entry.id().equals(s.trackId())));
        if(!refreshLocalRaceContent(customMaps,card.partsRule()!=null)) return null;
        var tracks=new HashMap<String,TrackLayout>();
        String issue=card.incompatibility(vehicleSpec.physicsVersion,id->tracks.computeIfAbsent(id,this::circuitById));
        if(!issue.isEmpty()) {notifyUser(issue);return null;}
        return tracks.get(card.stages().get(stage).trackId());
    }

    private void launchActivity(ActivityRun run,TrackLayout track,ControlSettings settings) {
        var trial=new ActivityTrial(run);var stage=trial.stage();
        disposeSession();closeRaceRoom();freezeControls(settings);activityTrial=trial;circuit=track;mode=Mode.ACTIVITY;attemptId=UUID.randomUUID().toString();sessionLaps=stage.laps();createWorld(false);
        boolean chase=stage.kind()==ActivityCard.Kind.CHASE,race=stage.kind()==ActivityCard.Kind.RACE;
        player=addCar("你 · 岚隼",run.tuning(run.livery()),chase?-42:-18,race?-2:0,false,1,sessionLaps);
        for(int i=1;i<stage.kind().drivers;i++) addCar(EventSeries.driverName(i),run.tuning(i),chase?-8:i==3?-18:-10,race?(i==2?-2:2):0,true,run.card().pace(run.stage(),i),sessionLaps);
        localFramePositions=new Vector3f[racers.size()];
        autoDriver=new DriverAI(circuit,.88f,0);sessionTime=0;countdown=4;newBest=false;helpVisible=false;screen=Screen.DRIVING;physics.setSpeed(1);cameraImmediate();
    }
    public void startChallenge(RaceChallenge.Kind kind) { challengeKind=Objects.requireNonNull(kind);start(Mode.CHALLENGE,null); }
    public ChallengeAttempt bookAttempt() { return bookAttempt; }
    public void openBook() { mainMenu();if(!TrackCatalog.isBuiltin(circuit)) preview(TrackCatalog.require(TrackCatalog.DEFAULT_ID).layout());screen=Screen.BOOK; }
    public void openJournal() { mainMenu();screen=Screen.JOURNAL;if(!journal.warning.isEmpty()) notifyUser(journal.warning); }
    public void startBook(ChallengeBook.Entry entry) {
        startBook(entry,Tuning.STOCK.withLivery(store.profile.tuning.livery()));
    }
    private void startBook(ChallengeBook.Entry entry,Tuning tuning) {
        if(!controlsReady(controls.settings())) return;
        Mode activity=switch(entry.kind()) { case CLEAN_LAPS->Mode.TIME_TRIAL;case CONSISTENCY,START->Mode.LESSON;case CHASE->Mode.CHALLENGE;case SMOOTH->Mode.CUP; };
        PracticeSession.Lesson lesson=entry.kind()==ChallengeBook.Kind.CONSISTENCY?PracticeSession.Lesson.CONSISTENCY:entry.kind()==ChallengeBook.Kind.START?PracticeSession.Lesson.REACTION:null;
        if(entry.kind()==ChallengeBook.Kind.CHASE) challengeKind=RaceChallenge.Kind.CHASE;
        startSession(activity,lesson,entry.track(),tuning,null);bookAttempt=new ChallengeAttempt(entry);
    }
    public void claimBookMedal() {
        if(screen!=Screen.DRIVING || bookAttempt==null || bookAttempt.entry().kind()!=ChallengeBook.Kind.CLEAN_LAPS) return;
        bookAttempt.claim();finish("挑战结束",bookAttempt.detail());
    }
    public void changeLook(int number,DriverJournal.Pattern pattern) {
        if(!journal.state().unlocked(pattern)) { notifyUser(pattern.requirement);return; }
        if(!journal.look(new DriverJournal.Look(number,pattern))) notifyUser(journal.warning);
    }
    public void retryJournalSave() {
        if(journal.flush()) {
            notifyUser("驾驶履历已保存。");
            return;
        }
        var conflict=journal.reviewConflict();
        if(conflict!=null && hud!=null) hud.reviewJournalConflict(conflict);
        else notifyUser(journal.warning);
    }
    public void applyJournalConflict(JournalStore.ConflictReview review,boolean useLocalLook) {
        if(journal.applyConflict(review,useLocalLook)) notifyUser("两份驾驶履历已合并并保存。");
        else notifyUser(journal.warning);
    }
    public EventSeries activeEvent() { return pendingEvent!=null?pendingEvent:events.current(); }
    public EventSeries runningEvent() { return runningEvent; }
    public QualifyingSession qualifying() { return qualifying; }
    public boolean eventSavePending() { return pendingEvent!=null; }
    public EventPendingResult pendingEventResult() {return pendingEventRecord;}
    public boolean eventRecoveryAttention() {return eventRecovery.needsAttention();}
    public boolean pendingEventRecoverable() {return pendingEvent!=null && pendingEventRecord!=null && !eventRecovery.blocked() && pendingEventRecord.equals(eventRecovery.entry());}
    public boolean eventDriving() { return runningEvent!=null; }
    public EventRaceClock eventRaceClock() {return eventRaceClock;}
    public List<EventRaceTiming.Row> eventRaceStandings() {
        if(eventRaceTiming==null || racers.size()!=EventSeries.DRIVERS) return List.of();
        if(!eventRaceTiming.settled().isEmpty()) return eventRaceTiming.settled();
        var cars=new ArrayList<EventRaceTiming.Car>(EventSeries.DRIVERS);
        for(int driver=0;driver<racers.size();driver++) {
            var racer=racers.get(driver);
            var progress=racer.progress;
            boolean finished=progress.finished();
            var position=finished?null:racer.car.position(eventStandingsPosition);
            cars.add(new EventRaceTiming.Car(driver,finished,progress.finishTime(),racer.penalty,
                    progress.raceDistance(position)));
        }
        return eventRaceTiming.order(cars,countdown>0);
    }
    EventRaceTrafficReview beginEventRaceTrafficReview() {
        if (screen != Screen.DRIVING || mode != Mode.EVENT_RACE || runningEvent == null
                || sessionTime != 0 || racers.size() != EventSeries.DRIVERS) return null;
        if (eventRaceTrafficReview == null) eventRaceTrafficReview = new EventRaceTrafficReview(runningEvent.state());
        return eventRaceTrafficReview;
    }
    public void openEvents() { mainMenu();screen=Screen.EVENTS;if(!events.warning.isEmpty()) notifyUser(events.warning); }
    public boolean archiveCompletedEvent(EventSeries.State reviewed) {
        var current=events.current();
        if(screen!=Screen.EVENTS || online() || eventSavePending() || current==null || reviewed==null
                || reviewed.phase()!=EventSeries.Phase.COMPLETE || !reviewed.equals(current.state()) || !events.matchesSaved(reviewed)) {
            notifyUser("请先保存并核对完整赛事，再收入档案。");
            return false;
        }
        boolean saved=eventHistory.add(reviewed,System.currentTimeMillis());
        notifyUser(saved?"已保存本次完整赛事的档案。":eventHistory.warning());
        return saved;
    }
    public boolean removeArchivedEvent(EventHistoryStore.Entry reviewed) {
        if(screen!=Screen.EVENTS || online()) return false;
        boolean removed=eventHistory.remove(reviewed);
        notifyUser(removed?"已移除该赛事档案，当前赛事和车手履历保留。":eventHistory.warning());
        return removed;
    }
    public void createEvent(EventSeries.Kind kind) {
        if(!eventReplacementReady()) return;
        var next=EventSeries.create(kind,selectedCircuit(),vehicleSpec.physicsVersion,store.profile.tuning,store.profile.difficulty,store.profile.raceLaps);
        if(events.save(next)) openEvents();else notifyUser(events.warning);
    }
    private boolean eventReplacementReady() {
        if(!eventArchivesReady()) return false;
        boolean careerSaved=journal.reconcile(events.current(),System.currentTimeMillis(),this::circuitById);
        boolean rivalsSaved=rivals.reconcile(events.current(),System.currentTimeMillis(),this::circuitById);
        if(!careerSaved || !rivalsSaved) { notifyUser(!careerSaved?journal.warning:rivals.warning);return false; }return true;
    }
    public boolean reviewedEventUnchanged(BooleanSupplier reviewed) {
        if(!reviewed.getAsBoolean()) {
            notifyUser("待替换的赛事已变化，请返回赛事中心重新核对。");
            return false;
        }
        return true;
    }
    private boolean eventArchivesReady() {
        if(pendingEvent!=null) {notifyUser("还有待保存的赛事成绩，请先重试保存。");return false;}
        if(eventRecoveryAttention()) {notifyUser("请先在赛事结果恢复页核对原副本，再新建赛事。");return false;}
        if(!events.matchesCurrentFile()) {
            notifyUser("赛事主档已改变或无法核对；请在赛事结果恢复页明确重读。");
            return false;
        }
        return emptyEventRecoveryVerified();
    }
    private boolean emptyEventRecoveryVerified() {
        if(eventRecovery.verify(null)) return true;
        notifyUser(eventRecovery.warning());
        return false;
    }
    public boolean createCustomEvent(List<EventSeries.Round> calendar,int difficulty,int laps) {
        if(online() || screen!=Screen.EVENTS) return false;
        try {
            var event=EventSeries.createCustom(calendar,vehicleSpec.physicsVersion,store.profile.tuning,difficulty,laps);
            if (!refreshEventTracks(event.state().calendar())) return false;
            if(!eventCompatible(event)) { notifyUser("赛历中的地图缺失或已修改，请重新选择对应赛站。");return false; }
            if (!eventReplacementReady()) return false;
            if(!events.save(event)) { notifyUser(events.warning);return false; }openEvents();return true;
        }catch(IllegalArgumentException | NullPointerException error) { notifyUser("请设置一至八站有效赛历、难度和圈数。");return false; }
    }
    public void saveRivalRecords() {
        if(rivals.reconcile(events.current(),System.currentTimeMillis(),this::circuitById) && rivals.warning.isEmpty()) {
            notifyUser("交手记录已保存。");
            return;
        }
        var conflict=rivals.reviewConflict();
        if(conflict!=null && hud!=null) hud.reviewRivalConflict(conflict);
        else notifyUser(rivals.warning);
    }
    public void applyRivalConflict(RivalStore.ConflictReview review,boolean useLocalFollow) {
        if(rivals.applyConflict(review,useLocalFollow)) notifyUser("交手记录已合并并保存。");
        else notifyUser(rivals.warning);
    }
    public void continueEvent() { continueEvent(true); }
    private void continueEvent(boolean refreshTracks) {
        if(pendingEvent!=null) { saveEventResult();return; }
        if(eventRecoveryAttention()) {notifyUser("请先核对赛事结果恢复副本，再推进或开始驾驶。");return;}
        var event=events.current();if(event==null) return;
        if (refreshTracks && !refreshEventTracks(event.state().calendar())) return;
        if(!eventCompatible(event)) { notifyUser("赛事使用的地图或物理版本已改变，请新建赛事；旧进度保留。");return; }
        switch(event.state().phase()) {
            case QUALIFYING -> startEventActivity(Mode.QUALIFYING);
            case RACE -> startEventActivity(Mode.EVENT_RACE);
            case REVIEW -> {
                if(!emptyEventRecoveryVerified()) return;
                if(events.saveIfCurrent(event.state(),event.advance())) openEvents();else notifyUser(events.warning);
            }
            case COMPLETE -> openEvents();
        }
    }
    public void startEventPractice() {
        var event=events.current();
        if(pendingEvent==null && event!=null && event.state().phase()==EventSeries.Phase.QUALIFYING
                && refreshEventTracks(event.state().calendar())) startEventActivity(Mode.EVENT_PRACTICE);
    }
    private void startEventActivity(Mode activity) {
        if(!controlsReady(controls.settings())) return;
        var event=events.current();
        if(event==null || pendingEvent!=null || eventRecoveryAttention() || !eventCompatible(event)) {
            notifyUser("无法开始此赛事阶段；请检查进度与地图版本。");return;
        }
        if(!events.matchesSaved(event.state())) {
            notifyUser("赛事主档已改变或无法核对；请在赛事结果恢复页明确重读，再预览本阶段。");
            return;
        }
        if(!emptyEventRecoveryVerified()) return;
        closeRaceRoom();disposeSession();freezeControls(controls.settings());attemptId=UUID.randomUUID().toString();runningEvent=event;circuit=circuitById(event.currentRound().trackId());mode=activity;createWorld(false);
        sessionLaps=activity==Mode.QUALIFYING?1:event.state().laps();
        int laps=activity==Mode.EVENT_PRACTICE?99999:sessionLaps;
        float basePace=new float[]{.76f,.90f,1}[event.state().difficulty()];
        for(int driver=0;driver<(activity==Mode.EVENT_PRACTICE?1:EventSeries.DRIVERS);driver++) {
            int grid=event.grid().indexOf(driver);
            float distance=activity==Mode.EVENT_RACE?-8-grid*7:-18-driver*80;
            float side=activity==Mode.EVENT_RACE?(grid%2==0?-2:2):0;
            var tuning=driver==0?event.state().tuning():new Tuning(1,1,1,1,driver);
            var car=addCar(EventSeries.driverName(driver),tuning,distance,side,driver!=0,basePace*new float[]{1,.97f,1,1.025f}[driver],laps);
            if(driver==0) player=car;
        }
        if(activity==Mode.QUALIFYING) {
            qualifying=new QualifyingSession();
            qualifyingProgress=racers.stream().map(racer->racer.progress).toList();
        }
        sessionTime=0;countdown=activity==Mode.EVENT_PRACTICE?.8f:4;newBest=false;helpVisible=false;practiceRecorded=false;
        if(activity==Mode.EVENT_RACE) {
            localFramePositions=new Vector3f[racers.size()];
            eventRaceClock=EventRaceClock.read(sessionLaps,sessionTime,0,Double.POSITIVE_INFINITY);
            eventRaceTiming=new EventRaceTiming(event.grid(),sessionLaps);
        }
        autoDriver=new DriverAI(circuit,.88f,0);screen=Screen.DRIVING;physics.setSpeed(1);cameraImmediate();
        if(activity==Mode.EVENT_RACE || activity==Mode.QUALIFYING) beginReplay(false,null);
    }
    private void commitEvent(EventSeries next) {
        if(pendingEvent!=null) {notifyUser("原赛事结果尚待保存，本次不替换它。");return;}
        pendingEventRecord=new EventPendingResult(runningEvent.state(),next.state());pendingEvent=next;saveEventResult();
    }
    public void saveEventResult() {
        if(pendingEvent==null) return;
        if(pendingEventRecord==null) {
            try {var saved=eventRecovery.entry();pendingEventRecord=saved!=null && saved.candidate().equals(pendingEvent.state())?saved:new EventPendingResult(events.current().state(),pendingEvent.state());}
            catch(IllegalArgumentException | NullPointerException error) {notifyUser("待存结果与原赛事基准不对应；两边保持，请核对恢复页。");return;}
        }
        var reviewed=pendingEventRecord;var accepted=events.current();
        if(!reviewed.candidate().equals(pendingEvent.state())) {notifyUser("本次候选已改变，原结果保持，请重新核对。");return;}
        if(!eventRecovery.capture(reviewed)) {notifyUser(eventRecovery.warning()+" 本次结果尚未发布到赛事主档。");return;}
        var relation=accepted==null?EventPendingResult.Relation.CONFLICT:reviewed.relation(accepted.state());
        if(relation==EventPendingResult.Relation.CONFLICT) {
            notifyUser("赛事主档已改变或无法核对；已写恢复副本，待存结果保持，请明确重读后处理。");
            return;
        }
        if(relation==EventPendingResult.Relation.READY && !events.saveIfCurrent(accepted.state(),pendingEvent)) {
            notifyUser(events.warning+" 已写赛事恢复副本，可重开后核对。");
            return;
        }
        var cleanup=events.clearPublishedRecovery(reviewed,eventRecovery);
        if(relation==EventPendingResult.Relation.ALREADY_SAVED && cleanup==EventStore.RecoveryCleanup.MAIN_UNVERIFIED) {
            notifyUser("赛事主档无法核对；恢复副本与待存结果保持，请明确重读后处理。");
            return;
        }
        pendingEvent=null;pendingEventRecord=null;
        if(cleanup==EventStore.RecoveryCleanup.MAIN_UNVERIFIED) {
            notifyUser("赛事阶段已写入；再次核对主档失败，恢复副本保持，请明确重读后处理。");
            return;
        }
        boolean careerSaved=journal.reconcile(events.current(),System.currentTimeMillis(),this::circuitById);boolean rivalsSaved=rivals.reconcile(events.current(),System.currentTimeMillis(),this::circuitById);
        notifyUser(cleanup==EventStore.RecoveryCleanup.RECOVERY_RETAINED?"赛事阶段已保存；恢复副本清理未完成，请在恢复页重新核对。":!careerSaved?journal.warning:!rivalsSaved?rivals.warning:"赛事阶段已保存，可从赛事中心继续。");
    }
    private EventSeries.State acceptedEventState() {var event=events.current();return event==null?null:event.state();}
    public boolean reloadEventRecovery() {
        if(online() || screen!=Screen.EVENTS) return false;boolean main=events.reload(),recovery=eventRecovery.reload();notifyUser(main && recovery?"已重读赛事主档与恢复副本；本次内存候选保持，请重新核对。":!main?events.warning:eventRecovery.warning());return main && recovery;
    }
    public boolean restoreEventRecovery(EventPendingResult reviewed,EventSeries.State accepted) {
        if(online() || screen!=Screen.EVENTS || pendingEvent!=null || reviewed==null || !Objects.equals(accepted,acceptedEventState()) || reviewed.relation(accepted)!=EventPendingResult.Relation.READY || !events.matchesSaved(accepted) || !eventRecovery.verify(reviewed)) {notifyUser("恢复预览已过期、基准不符或已有待存结果；原内容保持，请重新核对。");return false;}
        pendingEventRecord=reviewed;pendingEvent=new EventSeries(reviewed.candidate());notifyUser("原阶段结果已恢复为待保存；尚未发布，请另点保存。");return true;
    }
    public boolean acknowledgeEventRecovery(EventPendingResult reviewed,EventSeries.State accepted) {
        if(online() || screen!=Screen.EVENTS || pendingEvent!=null || reviewed==null || !Objects.equals(accepted,acceptedEventState()) || reviewed.relation(accepted)!=EventPendingResult.Relation.ALREADY_SAVED) {notifyUser("已存结果核对失败或来源改变；恢复副本保持，请重新核对。");return false;}
        var cleanup=events.clearPublishedRecovery(reviewed,eventRecovery);
        if(cleanup!=EventStore.RecoveryCleanup.CLEARED) {
            notifyUser(cleanup==EventStore.RecoveryCleanup.MAIN_UNVERIFIED?
                    "已存结果核对失败或主档锁被占用；恢复副本保持，请重新核对。":eventRecovery.warning());
            return false;
        }
        notifyUser("已核对赛事主档含有同一结果，仅清理恢复副本，没有重复计分。");return true;
    }
    public boolean discardEventRecovery(EventPendingResult reviewed,boolean memory) {
        if(online() || screen!=Screen.EVENTS || reviewed==null) return false;
        if(memory) {
            if(pendingEvent==null || !reviewed.equals(pendingEventRecord) || !reviewed.candidate().equals(pendingEvent.state())) {notifyUser("本次内存候选已变化，未放弃任何内容。");return false;}
            if(reviewed.equals(eventRecovery.entry()) && !eventRecovery.clear(reviewed)) {notifyUser(eventRecovery.warning());return false;}
            pendingEvent=null;pendingEventRecord=null;notifyUser("已明确放弃本次内存候选；赛事主档和其它恢复副本保持。");return true;
        }
        if(!eventRecovery.clear(reviewed)) {notifyUser(eventRecovery.warning());return false;}notifyUser("已明确移除所选赛事恢复副本；赛事主档与本次内存候选保持。");return true;
    }
    public void mainMenu() { launchSetup=null;if(hud!=null) hud.cancelTransition();closeRaceRoom(); if (mini4wd != null && mini4wd.roomActive()) mini4wd.leaveRoom(); setPauseOnLostFocus(!smoke); preview(); helpVisible = false; store.saveIfChanged(); }
    public RaceRoom raceRoom() { return raceRoom; }
    public boolean online() { return raceRoom != null && !raceRoom.closed(); }
    public RaceSession.Settings raceRoomSettings() { return raceRoom==null?roomRules().settings(roomRules().initialPhase()):raceRoom.settings(); }
    public RoomRules roomRules() {
        if(raceRoom!=null) return raceRoom.rules();
        if(plannedRoomRules==null) plannedRoomRules=RoomRules.quick(roomTrack(selectedCircuit().id),raceRoomSettings);
        return plannedRoomRules;
    }
    private RoomTrack roomTrack(String id) {
        if(TrackCatalog.all().stream().anyMatch(entry->entry.id().equals(id))) return RoomTrack.builtin(id);
        circuitEntry(id); // Only maps with a current four-car proof may be hosted through the UI.
        return RoomTrack.custom(circuits.find(id).orElseThrow().document());
    }
    public boolean roomRulesEditable() { return raceRoom==null || raceRoom.host() && raceRoom.editable(); }
    public void roomRules(RoomRules rules) {
        if(!roomRulesEditable()) return;
        plannedRoomRules=rules;if(online()) raceRoom.rules(rules);
    }
    public void cycleRoomFormat() { var rules=roomRules();if(rules!=null) roomRules(rules.format(RoomRules.Format.values()[(rules.format().ordinal()+1)%RoomRules.Format.values().length])); }
    public void cycleRoomVehicleRule() {
        var rules=roomRules();if(rules==null) return;var next=RaceSession.VehicleRule.values()[(rules.vehicles().ordinal()+1)%RaceSession.VehicleRule.values().length];
        roomRules(new RoomRules(rules.format(),rules.laps(),next,store.profile.tuning,rules.teams(),rules.calendar()));
    }
    public void toggleRoomTeams() { var rules=roomRules();if(rules!=null) roomRules(new RoomRules(rules.format(),rules.laps(),rules.vehicles(),rules.fixed(),!rules.teams(),rules.calendar())); }
    public void cycleRoomMap(int slot) {
        var rules=roomRules();if(rules==null || !roomRulesEditable()) return;var maps=availableTracks();int index=-1;
        for(int i=0;i<maps.size();i++) if(maps.get(i).id().equals(rules.calendar().get(slot).id())) index=i;
        roomRules(rules.map(slot,roomTrack(maps.get((index+1)%maps.size()).id())));
    }
    public void openRaceRoom() { screen = Screen.RACE_ROOM; pressed.clear(); helpVisible = false; }
    public void hostRaceRoom(int port) {
        if (raceRoom != null) return;
        try { raceRoom = RaceRoom.host(store.profile.playerName,store.profile.tuning,roomRules(),port); openRaceRoom(); }
        catch (java.io.IOException | RuntimeException error) { notifyUser("方程式建房失败，请检查端口是否占用。"); }
    }
    public void joinRaceRoom(String address) {
        if (raceRoom != null) return;
        try { raceRoom = RaceRoom.join(store.profile.playerName,store.profile.tuning,RaceRoom.Address.parse(address)); openRaceRoom(); }
        catch (RuntimeException error) { notifyUser("地址格式示例 192.168.1.10:26951"); }
    }
    public void racePlayerName(String name) {
        store.profile.playerName = RaceRoom.cleanName(name); store.save();
        if (online()) raceRoom.configure(store.profile.playerName,store.profile.tuning);
    }
    public void cycleRaceActivity() {
        cycleRoomFormat();
    }
    public void cycleRoomLaps() {
        var rules=roomRules();if(rules==null) return;
        roomRules(new RoomRules(rules.format(),rules.laps()==1?3:rules.laps()==3?5:1,rules.vehicles(),rules.fixed(),rules.teams(),rules.calendar()));
    }
    public void returnToRaceRoom() {
        if (!online()) { openRaceRoom(); return; }
        var frame = raceRoom.frame();
        if (raceRoom.host()) raceRoom.finishSession();
        else if (frame != null) { if (!frame.complete()) raceRoom.retire(); dismissedOnlineResult = frame.session(); }
        screen = Screen.RACE_ROOM; helpVisible = false; pressed.clear(); raceRoom.control(RaceSession.Input.STOP);
    }
    public void leaveRaceRoom() { closeRaceRoom(); mainMenu(); }
    private void closeRaceRoom() {
        if(replayOnline) finishReplay(RaceReplay.End.STOPPED);
        if (raceRoom != null) raceRoom.close(); raceRoom = null;
        onlineSession = dismissedOnlineResult = -1; onlineCars.clear();
    }
    public void backFromGarage() {
        if (online()) { raceRoom.configure(store.profile.playerName,store.profile.tuning); openRaceRoom(); }
        else if (previousScreen == Screen.RACE_ROOM) openRaceRoom();
        else mainMenu();
    }
    public boolean garageFromRaceRoom() { return online() || previousScreen == Screen.RACE_ROOM; }

    /** On the game thread, input is ready once the latest focus change has been applied. */
    public boolean windowFocused() {return readyFocusGeneration==focusGeneration.get();}

    @Override public void loseFocus() {
        focusGeneration.incrementAndGet();
        withAudio(GameAudio::silence);
        super.loseFocus();
        enqueue(() -> {
            resetFocusInput();
            if(capturingKey!=null) cancelKeyboardCapture();
            if(online()) { raceRoom.control(RaceSession.Input.STOP);if(screen==Screen.DRIVING) pause(); }
            else if(player!=null && screen==Screen.DRIVING) player.drive(0,0,1,0);
            if (mini4wd != null) { mini4wd.clearBoost(); mini4wd.endShapeDrag(); }
            if (hud != null) {
                hud.endMiniPointer();
                hud.cancelCircuitPointer();
                hud.endReplayPointer();
                hud.endPreviewPan();
            }
            return Boolean.TRUE;
        });
    }
    @Override public void gainFocus() {
        long generation=focusGeneration.incrementAndGet();
        super.gainFocus();
        enqueue(()->{
            if(focusGeneration.get()==generation) {
                resetFocusInput();
                readyFocusGeneration=generation;
            }
            return true;
        });
    }
    public Mini4wdGame mini4wd() { return mini4wd; }
    public void exportMiniMap(Path path,boolean replace) {
        if(screen!=Screen.MINI4WD || mini4wd==null) return;
        try {
            mini4wd.exportMap(contentExportTarget(path),replace);
        }catch(java.io.IOException | RuntimeException error) {
            notifyUser("请选择独立的四驱车地图导出文件。");
        }
    }
    public void exportReviewedMiniMap(ExportDestination destination) {
        if(screen!=Screen.MINI4WD || mini4wd==null) return;
        try {
            mini4wd.exportMap(contentExportTarget(destination.path()),destination);
        }catch(java.io.IOException | RuntimeException error) {
            notifyUser("请选择独立的四驱车地图导出文件。");
        }
    }
    public void openMini4wd() {
        closeRaceRoom();
        if (mini4wd == null) mini4wd = new Mini4wdGame(store);
        else mini4wd.clearBoost();
        disposeSession();
        physics.setSpeed(0);
        helpVisible = false;
        toastLeft = 0;
        screen = Screen.MINI4WD;
    }
    public void open(Screen target) {
        if(screen==Screen.LIVERY && target!=screen && hud!=null) {
            hud.cancelCreationImport();
            hud.cancelLiveryExportReview();
        }
        previousScreen = screen; screen = target; pressed.clear();
        if (screen == Screen.GARAGE && player != null && !player.tuning.equals(store.profile.tuning)) rebuildPreview();
    }
    public LiveryEditor liveryEditor() {return liveryEditor;}
    public void captureAuthoringDrafts() {
        if(partsAuthoring!=null) partsAuthoringEditing.capture(partsAuthoring.state());
        if(liveryEditor!=null && (observedLiveryEditor!=liveryEditor || observedLiveryRevision!=liveryEditor.revision())) {var next=liveryEditor.snapshot();if(!next.equals(observedLivery)) {liveryEditing.capture(next);observedLivery=next;}observedLiveryEditor=liveryEditor;observedLiveryRevision=liveryEditor.revision();}
        if(hud!=null) hud.captureAuthoringDrafts();
    }
    private void checkpointAuthoring() {captureAuthoringDrafts();liveryEditing.flush();purposeEditing.flush();discussionEditing.flush();revisionEditing.flush();feedbackEditing.flush();feedbackDiscussionEditing.flush();partsAuthoringEditing.flush();circuitPackEditing.flush();}
    public CarLivery activeLivery() {var value=liveries.active();return !online() && value!=null && value.vehicle().equals(vehicleSpec.id)?value:null;}
    public void openLiveryEditor() {if(online()) return;if(liveryEditor==null) {if(liveryEditing.current()!=null) {liveryEditor=LiveryEditor.restore(liveryEditing.current());observedLivery=liveryEditor.snapshot();}else newLiveryDraft();}open(Screen.LIVERY);}
    public void backFromLiveryEditor() {checkpointAuthoring();open(Screen.GARAGE);}
    public void newLiveryDraft() {String credit=store.profile.playerName;if(!PartsCatalog.text(credit,40)) credit="";liveryEditor=new LiveryEditor(CarLivery.create(vehicleSpec.id,credit),false);}
    public boolean loadLivery(CarLivery expected) {
        if(!liveries.confirmSavedWork(expected)) {
            notifyUser(liveries.warning());
            return false;
        }
        liveryEditor=new LiveryEditor(expected,true);
        return true;
    }
    public void copyLiveryDraft() {if(liveryEditor!=null) liveryEditor=new LiveryEditor(liveryEditor.value().copy(),false);}
    public boolean saveLiveryDraft() {if(liveryEditor==null) return false;var value=liveryEditor.value();if(!liveries.save(value,liveryEditor.saved())) {notifyUser(liveries.warning());return false;}liveryEditor.markSaved(value);notifyUser("作品已保存；当前采用外观保持，采用需另选。");return true;}
    public boolean applyLiveryDraft() {if(liveryEditor==null || liveryEditor.dirty()) {notifyUser("请先保存当前草稿，再采用已保存版本。");return false;}return applyLivery(liveryEditor.value());}
    public boolean applyLivery(CarLivery value) {if(value!=null && value.unsupportedLayers(hud==null?null:hud.paintRegions())>0) {notifyUser("当前素材缺少所需部位蒙版，请更换匹配素材或修改图层部位后再采用。");return false;}if(!liveries.apply(value,vehicleSpec.id)) {notifyUser(liveries.warning());return false;}notifyUser("已采用像素涂装；原车号和纪念图案继续保留。");return true;}
    public boolean resetLivery() {boolean ok=liveries.reset();notifyUser(ok?"已恢复车库底色与原纪念装饰，作品库保留。":liveries.warning());return ok;}
    public boolean receiveLivery(CarLivery value) {boolean ok=liveries.receive(value);notifyUser(ok?"已收录作品；没有采用外观。":liveries.warning());return ok;}
    public boolean exportLivery(CarLivery value,Path path,boolean replace) {
        try {
            writeReviewedLivery(value,inspectLiveryDestination(path,replace));
            notifyUser("涂装已导出；署名与来源为作品声明。");
            return true;
        }catch(java.io.IOException | RuntimeException error) {
            notifyUser("涂装未导出，请使用独立的导出文件。");
            return false;
        }
    }
    public ExportDestination inspectLiveryDestination(Path path,boolean replace) throws java.io.IOException {
        contentExportTarget(path);
        return ExportDestination.forContentWrite(path,replace);
    }
    public void writeReviewedLivery(CarLivery value,ExportDestination destination) throws java.io.IOException {
        var target=contentExportTarget(destination.path());
        com.f1car.persistence.LiveryCodec.write(target,value,destination);
    }
    private void clearDrivingKeys() {
        if(hud!=null) hud.clearNavigationKeys();
        // A transition inside a raw listener can invalidate earlier presses.
        // During mapped dispatch later presses in this batch have yet to take effect.
        if(readingRawKeys) lastHeldPress.clear();
        pressed.clear();
        keyboardHeld.removeIf(key -> !keyboardModifier(key));
    }
    private void blockDrivingInput() {
        clearDrivingKeys();
        controllerInput.block();
    }
    public void setHelpVisible(boolean visible) {
        if(helpVisible==visible) return;
        helpVisible=visible;
        if(visible && replayViewing() && hud!=null) hud.endReplayPointer();
        if(screen==Screen.DRIVING) blockDrivingInput();
    }
    public void pause() { if (screen == Screen.DRIVING) { screen = Screen.PAUSED; blockDrivingInput(); physics.setSpeed(0); if (online()) raceRoom.control(RaceSession.Input.STOP); } }
    public void resume() {
        if(!controlsReady(driveControls)) return;
        screen=Screen.DRIVING;
        blockDrivingInput();
        physics.setSpeed(online()?0:1);
    }
    public void backFromSettings() { if (previousScreen == Screen.PAUSED) screen = Screen.PAUSED; else mainMenu(); }
    public void retry() {
        if(online()) { returnToRaceRoom();return; }
        if(circuitSectionPractice!=null) {
            var settings=controls.settings();
            var trial=circuitSectionPractice;
            if(equipmentSectionReturn!=null && trial.focusedSplit()==0) {
                launchEquipmentSection(equipmentSectionReturn,trial.track(),trial.start(),settings);
                return;
            }
            if(controlsReady(settings)) {
                var original=trial.focusedSplit()==0?null:originalSectionResult;
                var next=original==null?trial.restart(controlsIdentity().equals(settings.identity()))
                        :original.splitHistory().newAttempt(trial.focusedSplit(),trial.start(),settings.identity());
                launchCircuitSection(next,settings,original,sectionDriveKey,sectionObservation,sectionFeedback,sectionFeedbackDiscussion,equipmentSectionReturn);
            }
            return;
        }
        if(activityTrial!=null) { if(activityTrial.complete() && activityTrial.result().success()) openActivities();else continueActivity();return; }
        if(experience!=null) { startExperience(experience.course());return; }
        if(workshop!=null) { startWorkshop(workshop.kind());return; }
        if(mode==Mode.TUNING_TRIAL && tuningTrial!=null) { if(pairRunActive) startPairRun(pairIndex);else startTuningRun(tuningIndex);return; }
        if(circuitTrial!=null) { retryCircuitTrial();return; }
        if(bookAttempt!=null) { startBook(bookAttempt.entry(),player.tuning);return; }
        if(eventDriving()) {
            if(screen==Screen.RESULTS) { openEvents();return; }
            startEventActivity(mode);return;
        }
        start(mode,practice!=null?practice.lesson:null);
    }
    public void resetAttempt() {
        blockDrivingInput();
        if (online()) { raceRoom.recover(); return; }
        if(activityTrial!=null || circuitSectionPractice!=null) { retry();return; }
        if(experience!=null) { startExperience(experience.course());return; }
        if(workshop!=null) { startWorkshop(workshop.kind());return; }
        if(mode==Mode.TUNING_TRIAL && tuningTrial!=null) { if(pairRunActive) startPairRun(pairIndex);else startTuningRun(tuningIndex);return; }
        if(circuitTrial!=null) { retryCircuitTrial();return; }
        if(eventDriving() && (screen==Screen.RESULTS || mode==Mode.QUALIFYING)) { retry();return; }
        if (mode == Mode.LESSON || mode == Mode.CUP || mode == Mode.TIME_TRIAL || mode==Mode.CHALLENGE || screen == Screen.RESULTS) { retry(); return; }
        if (player == null) return;
        Racer r = racers.get(0);
        if(mode==Mode.FREE_PAD) player.recover(new Vector3f(-65,0,-100),Vector3f.UNIT_Z);
        else {
            var traffic=racers.stream().filter(other->other!=r && !other.retired)
                    .map(other->readLocalRecoveryTraffic(other.car,RecoveryPlanner.UNKNOWN_DRIVER)).toList();
            float gravityY=physics.getPhysicsSpace().getGravity(recoveryGravityReading).y;
            var recovery=findLocalRecovery(r,traffic,gravityY,false);
            if(recovery.spawn().isEmpty()) {
                notifyUser(switch(recovery.failure()) {
                    case NO_STATIC_CANDIDATE -> "附近候选都未通过路宽或场景间距检查。";
                    case TRAFFIC_PREDICTION -> "候选落点可能与来车重叠，请稍后再按 R。";
                    case INCOMPLETE_TRAFFIC -> "部分落点的车辆占用暂时无法排除，请稍后再按 R。";
                });
                return;
            }
            var spawn=recovery.spawn().get();player.recover(spawn.position(),spawn.tangent());
        }
        if(autoDriver!=null) autoDriver.recovered();
        pendingAiRecoveries.remove(0);
        r.recovery++;
        if (eventRaceTrafficReview != null) eventRaceTrafficReview.recovered(0);
        r.previous = player.position();
        if(r.progress.finished()) {
            notifyUser("完赛后已复位，过线成绩与罚时保留。");
        } else {
            r.progress.invalidate();
            if (mode == Mode.RACE || mode==Mode.EVENT_RACE) { r.penalty += 5; notifyUser("已回到上一合法区段 · 罚时 +5 秒 · 本圈不计个人纪录"); }
            else notifyUser("已复位；重新完整跑圈后可记录成绩。");
        }
        cameraImmediate();
    }

    private RecoveryPlanner.LocalLookup findLocalRecovery(Racer racer,List<RecoveryPlanner.LocalTraffic> occupied,float gravityY,boolean captureSites) {
        if(localRecoverySearch==null || !localRecoverySearch.matches(circuit))
            localRecoverySearch=new RecoveryPlanner.LocalSearch(circuit);
        return localRecoverySearch.findWithSweeps(racer.progress.recoveryDistance(),occupied,gravityY,captureSites);
    }
    private RecoveryPlanner.LocalTraffic readLocalRecoveryTraffic(FormulaCar car,int driver) {
        var position=car.body.getPhysicsLocation(recoveryPositionReading);
        var velocity=car.body.getLinearVelocity(recoveryVelocityReading);
        return new RecoveryPlanner.LocalTraffic(position,velocity,driver);
    }
    public void changeTuning(String field, float step) {
        var previous=store.profile.tuning;var next=previous.change(field,step);
        if(!field.equals("livery") && !garageCandidateAllowed(next)) return;
        store.profile.tuning=next;if(!store.save()) {store.profile.tuning=previous;notifyUser("车库配置保存失败，原配置保留。");return;}rebuildPreview();
    }
    private boolean garageCandidateAllowed(Tuning next) {
        if(fittedParts.blocked() || fittedParts.pending()) {notifyUser("请先处理装备记录或未完成的安装。");return false;}
        var installed=fittedParts.state().installed();
        if(installed!=null && (!installed.compatible(vehicleSpec.id,vehicleSpec.physicsVersion) || !installed.accepts(next))) {notifyUser("参数超出已装装备范围；可先更换装备，或在车库解除范围。");return false;}
        return true;
    }
    public record GarageNumberReview(Tuning tuning,PartsCatalog.Field field,PartsCatalog.Range range,
                                     com.f1car.persistence.FittedPartsStore.State fitting,String vehicle,String physics) {}
    public GarageNumberReview reviewGarageNumber(PartsCatalog.Field field) {
        if(screen!=Screen.GARAGE) return null;
        if(fittedParts.blocked() || fittedParts.pending()) {notifyUser("请先查看装备记录，处理读取或未完成的安装。");return null;}
        var tuning=store.profile.tuning;var fitting=fittedParts.state();var installed=fitting.installed();
        if(installed!=null && (!installed.compatible(vehicleSpec.id,vehicleSpec.physicsVersion) || !installed.accepts(tuning))) {notifyUser("车库配置与已装装备不符，请重新应用装备参数或解除装备范围。");return null;}
        var range=installed==null?new PartsCatalog.Range(field.min,field.max,field.percent(tuning)):installed.range(field);
        return new GarageNumberReview(tuning,field,range,fitting,vehicleSpec.id,vehicleSpec.physicsVersion);
    }
    public boolean setGarageNumber(GarageNumberReview expected,int value) {
        if(expected==null || screen!=Screen.GARAGE || fittedParts.blocked() || fittedParts.pending()
            || !expected.tuning().equals(store.profile.tuning) || !expected.fitting().equals(fittedParts.state())
            || !expected.vehicle().equals(vehicleSpec.id) || !expected.physics().equals(vehicleSpec.physicsVersion)) {notifyUser("车库或装备已经变化，请取消后重新打开数值。");return false;}
        var current=reviewGarageNumber(expected.field());if(current==null || !current.range().equals(expected.range())) return false;
        if(value<current.range().min() || value>current.range().max()) {notifyUser("输入值超出当前装备范围。");return false;}
        var next=current.tuning().withPercent(current.field(),value);if(next.equals(current.tuning())) return true;
        store.profile.tuning=next;if(!store.save()) {store.profile.tuning=current.tuning();notifyUser("车库配置未保存，输入保留，可重试。");return false;}
        rebuildPreview();return true;
    }
    public record FittingReview(PartsBuild build,PartsCatalog catalog,Tuning garage,
                                com.f1car.persistence.FittedPartsStore.State before,FittedParts candidate) {
        public FittingReview {
            java.util.Objects.requireNonNull(garage);java.util.Objects.requireNonNull(before);java.util.Objects.requireNonNull(candidate);
            if(!candidate.equals(FittedParts.freeze(build,catalog,build.vehicle(),build.physics()))) throw new IllegalArgumentException("Fitting differs from reviewed draft");
        }
    }
    public FittingReview reviewFitting() {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || fittedParts.blocked() || fittedParts.pending()) {notifyUser("请先在车库处理装备记录，再安装新方案。");return null;}
        try {var build=partsWorkbench.build();var catalog=partsWorkbench.catalog();return new FittingReview(build,catalog,store.profile.tuning,fittedParts.state(),FittedParts.freeze(build,catalog,vehicleSpec.id,vehicleSpec.physicsVersion));}
        catch(IllegalArgumentException error) {notifyUser(error.getMessage());return null;}
    }
    public boolean installFitting(FittingReview expected) {
        if(expected==null || online() || screen!=Screen.PARTS || partsWorkbench==null || fittedParts.blocked() || fittedParts.pending()
            || !expected.build().equals(partsWorkbench.build()) || expected.catalog()!=partsWorkbench.catalog()
            || !expected.garage().equals(store.profile.tuning) || !expected.before().equals(fittedParts.state())
            || !expected.candidate().compatible(vehicleSpec.id,vehicleSpec.physicsVersion)) {notifyUser("草稿、车库或装备已变化，请重新查看安装。");return false;}
        if(!fittedParts.stage(expected.candidate())) {notifyUser(fittedParts.warning());return false;}
        return resumeFitting();
    }
    public boolean resumeFitting() {
        if(online() || screen!=Screen.GARAGE && screen!=Screen.PARTS || fittedParts.blocked() || !fittedParts.pending()) return false;
        var pending=fittedParts.state().pending();if(!pending.compatible(vehicleSpec.id,vehicleSpec.physicsVersion)) {notifyUser("待安装记录属于其他车型或物理版本，不能应用。");return false;}
        var previous=store.profile.tuning;var next=pending.initialTuning(previous.livery());
        var result=fittedParts.completeAfterProfile(pending,()->{
            store.profile.tuning=next;
            if(store.saveIfChanged()) return true;
            store.profile.tuning=previous;
            return false;
        });
        switch(result) {
            case FITTING_FAILED -> {
                notifyUser(fittedParts.warning());
                return false;
            }
            case PROFILE_FAILED -> {
                notifyUser("车库参数保存失败，安装记录保留，可重试或取消。");
                return false;
            }
            case COMPLETE, PROFILE_SAVED -> {
                if(!previous.equals(next)) {rebuildPreview();if(screen==Screen.PARTS) physics.setSpeed(0);}
            }
        }
        boolean complete=result==com.f1car.persistence.FittedPartsStore.Completion.COMPLETE;
        notifyUser(complete?"整套装备已安装，车库可在各部件范围内微调。":"参数已保存，装备确认尚未落盘，请重试完成安装。");
        return complete;
    }
    public boolean reapplyFitting(com.f1car.persistence.FittedPartsStore.State expected) {
        return reapplyFitting(expected,store.profile.tuning);
    }
    public boolean reapplyFitting(com.f1car.persistence.FittedPartsStore.State expected,Tuning expectedGarage) {
        if(online() || screen!=Screen.GARAGE || fittedParts.blocked() || fittedParts.pending() || !fittedParts.state().equals(expected) || expected.installed()==null
            || !Objects.equals(expectedGarage,store.profile.tuning)
            || !expected.installed().compatible(vehicleSpec.id,vehicleSpec.physicsVersion)) return false;
        if(!fittedParts.stage(expected.installed())) {notifyUser(fittedParts.warning());return false;}return resumeFitting();
    }
    public boolean detachFitting(com.f1car.persistence.FittedPartsStore.State expected,boolean cancelPending) {
        if(online() || screen!=Screen.GARAGE || !fittedParts.state().equals(expected) || fittedParts.blocked()) return false;
        boolean ok=cancelPending?fittedParts.cancelPending():fittedParts.detach();notifyUser(ok?"装备记录已更新；当前车库参数保留。":fittedParts.warning());return ok;
    }
    public record FittedSetupReview(Tuning garage,com.f1car.persistence.FittedPartsStore.State before,
                                    FittedParts snapshot,String vehicle,String physics) {
        public FittedSetupReview {
            Objects.requireNonNull(garage);Objects.requireNonNull(before);Objects.requireNonNull(snapshot);
            if(before.installed()==null || before.pending()!=null || !before.installed().snapshot(garage).equals(snapshot)
                || !snapshot.compatible(vehicle,physics)) throw new IllegalArgumentException("Fitted snapshot differs from reviewed garage");
        }
    }
    public FittedSetupReview reviewFittedSetup() {
        if(online() || screen!=Screen.GARAGE && screen!=Screen.PARTS) return null;
        if(fittedParts.blocked() || fittedParts.pending()) {notifyUser("请先处理装备读取或未完成的安装。");return null;}
        var before=fittedParts.state();var installed=before.installed();
        if(installed==null) {notifyUser("当前尚未安装装备，可先在工作台明确安装整套。");return null;}
        if(!installed.compatible(vehicleSpec.id,vehicleSpec.physicsVersion) || !installed.accepts(store.profile.tuning)) {notifyUser("当前车型或参数与装备不符，请重新应用装备基准或解除范围。");return null;}
        try {return new FittedSetupReview(store.profile.tuning,before,installed.snapshot(store.profile.tuning),vehicleSpec.id,vehicleSpec.physicsVersion);}
        catch(IllegalArgumentException error) {notifyUser("当前旧参数含非整数百分比，不能无损读为部件方案；请先在车库逐项确认整数值。");return null;}
    }
    public boolean saveFittingBaseline(FittedSetupReview expected) {
        if(screen!=Screen.GARAGE || expected==null || !expected.equals(reviewFittedSetup())) {notifyUser("车库或装备已变化，请重新查看基准。");return false;}
        boolean ok=fittedParts.checkpoint(expected.before(),expected.snapshot());
        notifyUser(ok?"当前微调已保存为装备基准；车库参数和工作台草稿保持。":fittedParts.warning());return ok;
    }
    public record FittedDraftReview(FittedSetupReview setup,PartsWorkbench.State before,long revision,
                                   PartsCatalog catalog,List<String> issues) {
        public FittedDraftReview {Objects.requireNonNull(setup);Objects.requireNonNull(before);Objects.requireNonNull(catalog);issues=List.copyOf(issues);}
    }
    public FittedDraftReview reviewFittedDraft() {
        if(screen!=Screen.PARTS || partsWorkbench==null) return null;var setup=reviewFittedSetup();if(setup==null) return null;
        var catalog=partsWorkbench.catalog();return new FittedDraftReview(setup,partsWorkbench.state(),partsWorkbench.revision(),catalog,
            setup.snapshot().build().review(catalog,vehicleSpec.id,vehicleSpec.physicsVersion,store.profile.tuning.livery()).issues());
    }
    public boolean readFittedDraft(FittedDraftReview expected) {
        if(expected==null || !expected.equals(reviewFittedDraft())) {notifyUser("装备、车库、目录或工作台已变化，请重新查看读取。");return false;}
        partsWorkbench.adopt(expected.setup().snapshot().build());
        notifyUser(expected.issues().isEmpty()?"已装装备当前微调已读为草稿；可撤销，车库与装备基准保持。":"已读为保留原指纹的草稿；目录存在差异，请单独处理，未更换装备。");return true;
    }
    public record PartReplacementReview(PartsWorkbench.State before,long revision,PartsCatalog catalog,Tuning garage,
                                        String vehicle,String physics,PartsReplacement replacement,PartsBuild.Review validation) {
        public PartReplacementReview {
            Objects.requireNonNull(before);Objects.requireNonNull(catalog);Objects.requireNonNull(garage);Objects.requireNonNull(replacement);Objects.requireNonNull(validation);
            if(!before.build().equals(replacement.before()) || !Objects.equals(catalog.find(replacement.target().id()),replacement.target())
                || !replacement.after().review(catalog,vehicle,physics,garage.livery()).equals(validation)) throw new IllegalArgumentException("Replacement differs from reviewed draft");
        }
    }
    public PartReplacementReview reviewPartReplacement(String id) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return null;
        try {var catalog=partsWorkbench.catalog();var replacement=PartsReplacement.preserving(partsWorkbench.build(),catalog,id);
            return new PartReplacementReview(partsWorkbench.state(),partsWorkbench.revision(),catalog,store.profile.tuning,vehicleSpec.id,vehicleSpec.physicsVersion,
                replacement,replacement.after().review(catalog,vehicleSpec.id,vehicleSpec.physicsVersion,store.profile.tuning.livery()));
        }catch(IllegalArgumentException error) {notifyUser("目标部件暂不可用，请重新查看目录。");return null;}
    }
    public boolean adoptPartReplacement(PartReplacementReview expected) {
        if(expected==null || online() || screen!=Screen.PARTS || partsWorkbench==null || partsWorkbench.catalog()!=expected.catalog()
            || partsWorkbench.revision()!=expected.revision() || !partsWorkbench.state().equals(expected.before())
            || !store.profile.tuning.equals(expected.garage()) || !vehicleSpec.id.equals(expected.vehicle()) || !vehicleSpec.physicsVersion.equals(expected.physics())) {
            notifyUser("部件、草稿、车库或目录已经变化，请重新预览换件。");return false;
        }
        partsWorkbench.adopt(expected.replacement().after());
        notifyUser(!expected.replacement().changed()?"沿用后的数值与当前草稿一致。":expected.validation().valid()?"换件与可用微调已读为草稿；可撤销，尚未安装或应用车库。":"换件已读为待处理草稿；请先处理点数或组合问题，再安装或应用。");return true;
    }
    public record GarageTransferReview(PartsWorkbench.State before,long revision,com.f1car.persistence.FittedPartsStore.State fitting,
                                       String vehicle,String physics,PartsGarageTransfer transfer,PartsBuild.Review validation) {
        public GarageTransferReview {
            Objects.requireNonNull(before);Objects.requireNonNull(fitting);Objects.requireNonNull(transfer);Objects.requireNonNull(validation);
            if(fitting.pending()!=null || !before.build().equals(transfer.before()) || !Objects.equals(fitting.installed(),transfer.installed())
                || !before.build().vehicle().equals(vehicle) || !before.build().physics().equals(physics)
                || !transfer.after().review(transfer.catalog(),vehicle,physics,transfer.garage().livery()).equals(validation)) throw new IllegalArgumentException("Garage transfer differs from reviewed state");
        }
    }
    public GarageTransferReview reviewGarageTransfer() {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return null;
        if(fittedParts.blocked() || fittedParts.pending()) {notifyUser("请先处理装备读取或未完成的安装，再沿用车库微调。");return null;}
        var build=partsWorkbench.build();
        if(!build.vehicle().equals(vehicleSpec.id) || !build.physics().equals(vehicleSpec.physicsVersion)) {notifyUser("草稿属于其他车型或物理版本，请先明确重建并重新查看。");return null;}
        var garage=store.profile.tuning;var fitting=fittedParts.state();
        if(fitting.installed()!=null && (!fitting.installed().compatible(vehicleSpec.id,vehicleSpec.physicsVersion) || !fitting.installed().accepts(garage))) {notifyUser("车库与已装装备不符，请先重新应用装备基准或解除范围。");return null;}
        for(var field:PartsCatalog.Field.values()) if(Float.compare(field.ratio(garage),field.percent(garage)/100f)!=0) {notifyUser("车库的"+field.title+"含非整数百分比，不能无损读为部件草稿；请先逐项确认整数值。");return null;}
        try {var transfer=PartsGarageTransfer.prepare(build,partsWorkbench.catalog(),garage,fitting.installed());return new GarageTransferReview(partsWorkbench.state(),partsWorkbench.revision(),fitting,vehicleSpec.id,vehicleSpec.physicsVersion,transfer,transfer.after().review(partsWorkbench.catalog(),vehicleSpec.id,vehicleSpec.physicsVersion,garage.livery()));}
        catch(IllegalArgumentException error) {notifyUser("目标部件定义缺失或已修订，请先明确重建草稿并查看范围变化。");return null;}
    }
    public boolean adoptGarageTransfer(GarageTransferReview expected) {
        if(expected==null || online() || screen!=Screen.PARTS || partsWorkbench==null || fittedParts.blocked() || fittedParts.pending()
            || !fittedParts.state().equals(expected.fitting()) || partsWorkbench.catalog()!=expected.transfer().catalog()
            || partsWorkbench.revision()!=expected.revision() || !partsWorkbench.state().equals(expected.before())
            || !store.profile.tuning.equals(expected.transfer().garage()) || !vehicleSpec.id.equals(expected.vehicle()) || !vehicleSpec.physicsVersion.equals(expected.physics())) {
            notifyUser("车库、装备、草稿或目录已变化，请重新查看整套微调。");return false;
        }
        partsWorkbench.adopt(expected.transfer().after());
        notifyUser(expected.transfer().changedSettings()==0?"沿用后的数值与草稿一致。":expected.validation().valid()?"当前车库微调已读为整套草稿；可撤销，安装需单独确认。":"微调已读为待处理草稿；先处理组合或点数问题，再安装。");return true;
    }
    public record SetupEffectsReview(PartsWorkbench.State before,long revision,PartsCatalog catalog,Tuning garage,
                                     VehicleSetupPreview.Comparison comparison,PartsBuild.Review validation) {
        public SetupEffectsReview {
            Objects.requireNonNull(before);Objects.requireNonNull(catalog);Objects.requireNonNull(garage);Objects.requireNonNull(comparison);Objects.requireNonNull(validation);
            var source=comparison.a().source();
            if(!garage.equals(comparison.a().tuning()) || !validation.candidate().equals(comparison.b().tuning())
                || !before.build().vehicle().equals(source.vehicle()) || !before.build().physics().equals(source.physics())
                || !before.build().review(catalog,source.vehicle(),source.physics(),garage.livery()).equals(validation)) throw new IllegalArgumentException("Setup effects differ from reviewed state");
        }
    }
    public SetupEffectsReview reviewSetupEffects() {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return null;
        if(!partsWorkbench.build().vehicle().equals(vehicleSpec.id) || !partsWorkbench.build().physics().equals(vehicleSpec.physicsVersion)) {notifyUser("草稿车型或物理版本不同，请先明确重建，再查看本机模型作用。");return null;}
        try {var garage=store.profile.tuning;var validation=partsWorkbench.review(vehicleSpec.id,vehicleSpec.physicsVersion,garage.livery());var comparison=VehicleSetupPreview.compare(vehicleSpec,garage,validation.candidate());comparison.maximumKph();return new SetupEffectsReview(partsWorkbench.state(),partsWorkbench.revision(),partsWorkbench.catalog(),garage,comparison,validation);}
        catch(IllegalArgumentException error) {notifyUser("当前车辆模型无法生成标准作用对照；草稿与车库保留。");return null;}
    }
    /** Render-only comparison of immutable state and scalar source values. */
    public boolean setupEffectsCurrent(SetupEffectsReview expected) {
        return expected!=null && !online() && screen==Screen.PARTS && partsWorkbench!=null && partsWorkbench.catalog()==expected.catalog()
            && partsWorkbench.revision()==expected.revision() && partsWorkbench.build()==expected.before().build()
            && store.profile.tuning.equals(expected.garage()) && expected.comparison().a().source().matches(vehicleSpec);
    }
    public record SetupRangeReview(SetupEffectsReview context,PartsRangePreview ranges) {
        public SetupRangeReview {Objects.requireNonNull(context);Objects.requireNonNull(ranges);if(ranges.catalog()!=context.catalog() || ranges.build()!=context.before().build() || !ranges.source().equals(context.comparison().a().source()) || !ranges.garage().equals(context.garage())) throw new IllegalArgumentException("Range preview differs from frozen setup");}
    }
    public SetupRangeReview reviewSetupRange() {
        var context=reviewSetupEffects();if(context==null) return null;
        try {return new SetupRangeReview(context,new PartsRangePreview(context.comparison().a().source(),context.garage(),context.before().build(),context.catalog()));}
        catch(IllegalArgumentException error) {notifyUser("范围定义缺失、已修订或数值越界；请先检查并重建草稿，再查看装备范围。");return null;}
    }
    public boolean setupRangeCurrent(SetupRangeReview expected) {return expected!=null && setupEffectsCurrent(expected.context());}
    public record RangeTuningReview(SetupRangeReview range,PartsCatalog.Field field,int percent,TuningTrial.Exercise exercise,
                                    TrackLayout track,ControlSettings controls,TuningPurpose.Draft purpose) {
        public RangeTuningReview {Objects.requireNonNull(range);Objects.requireNonNull(field);Objects.requireNonNull(exercise);Objects.requireNonNull(track);Objects.requireNonNull(controls);Objects.requireNonNull(purpose);
            if(!purpose.equals(range.ranges().trialBrief(field,percent))) throw new IllegalArgumentException("Single-field brief differs from range preview");}
    }
    public RangeTuningReview reviewRangeTuning(SetupRangeReview range,PartsCatalog.Field field,int percent,TuningTrial.Exercise exercise) {
        if(!setupRangeCurrent(range) || field==null || exercise==null) {notifyUser("范围背景已变化，请明确刷新后再预览试车。");return null;}
        try {return new RangeTuningReview(range,field,percent,exercise,partsTrialTrack(),controls.settings(),range.ranges().trialBrief(field,percent));}
        catch(IllegalArgumentException | NullPointerException error) {notifyUser("单项试车需有数值变化，且原草稿和候选均通过范围、预算与组合检查。");return null;}
    }
    public boolean beginRangeTuning(RangeTuningReview expected) {
        if(expected==null || !setupRangeCurrent(expected.range())) {notifyUser("范围背景已变化，请重新预览单项试车。");return false;}
        var track=partsTrialTrack();
        if(!expected.track().id.equals(track.id) || !expected.track().geometryKey().equals(track.geometryKey()) || !expected.controls().equals(controls.settings())) {notifyUser("赛道或操作设置已变化，请重新预览单项试车。");return false;}
        if(tuningSavePending()) {notifyUser("请先到试车报告重试保存已有测量。");return false;}
        if(!controlsReady(expected.controls())) return false;
        var purpose=expected.purpose();var source=expected.range().ranges();
        var experiment=new TuningExperiment(track,source.source().physics(),expected.exercise(),purpose.a().build().tuning(source.garage().livery()),purpose.b().build().tuning(source.garage().livery()),new SplittableRandom().nextBoolean(),expected.controls().identity());
        var captured=purpose.bind(experiment.snapshot());
        tuningReturn=Screen.PARTS;tuningControls=expected.controls();tuningTrack=track;tuningExperiment=experiment;tuningPurpose=captured;tuningReport=tuningPlan();startTuningRun(0);return true;
    }
    public boolean adoptSetupRange(SetupRangeReview expected,PartsCatalog.Field field,int percent) {
        if(!setupRangeCurrent(expected) || field==null) {notifyUser("范围背景已变化，请重新查看。");return false;}
        try {expected.ranges().sweep(field,percent);partsWorkbench.setValue(field.slot(),field,percent);notifyUser("预览值已读为一项草稿；车库采用和装备安装分别选择。");return true;}
        catch(IllegalArgumentException error) {notifyUser("预览值超出所选装备范围；本次草稿保留。");return false;}
    }
    public boolean adoptSetupPair(SetupRangeReview expected,PartsCatalog.Field first,int firstPercent,PartsCatalog.Field second,int secondPercent) {
        if(!setupRangeCurrent(expected) || first==null || second==null) {notifyUser("范围背景已变化，请重新查看双参数组合。");return false;}
        try {var candidate=expected.ranges().pair(first,firstPercent,second,secondPercent).candidate();partsWorkbench.adopt(candidate);notifyUser("两项预览已读为一步可撤销草稿；车库采用和装备安装分别选择。");return true;}
        catch(IllegalArgumentException error) {notifyUser("请选择不同参数，并保留在各自装备范围内；原草稿保留。");return false;}
    }
    public record PairTuningReview(SetupRangeReview range,PartsCatalog.Field first,int firstPercent,PartsCatalog.Field second,int secondPercent,
                                   TuningTrial.Exercise exercise,TrackLayout track,ControlSettings controls,TuningPurpose.Draft purpose) {
        public PairTuningReview {Objects.requireNonNull(range);Objects.requireNonNull(exercise);Objects.requireNonNull(track);Objects.requireNonNull(controls);Objects.requireNonNull(purpose);
            if(!purpose.equals(range.ranges().pairBrief(first,firstPercent,second,secondPercent))) throw new IllegalArgumentException("Pair brief differs from range preview");}
    }
    public PairTuningReview reviewPairTuning(SetupRangeReview range,PartsCatalog.Field first,int firstPercent,PartsCatalog.Field second,int secondPercent,TuningTrial.Exercise exercise) {
        if(!setupRangeCurrent(range) || first==null || second==null || exercise==null) {notifyUser("范围背景已变化，请刷新后再预览组合试车。");return null;}
        try {return new PairTuningReview(range,first,firstPercent,second,secondPercent,exercise,partsTrialTrack(),controls.settings(),range.ranges().pairBrief(first,firstPercent,second,secondPercent));}
        catch(IllegalArgumentException | NullPointerException error) {notifyUser("组合试车需两项都改变，且原草稿和候选均通过范围、预算与组合检查。");return null;}
    }
    public boolean beginPairTuning(PairTuningReview expected) {
        if(expected==null || !setupRangeCurrent(expected.range())) {notifyUser("范围背景已变化，请重新预览组合试车。");return false;}
        var track=partsTrialTrack();
        if(!expected.track().id.equals(track.id) || !expected.track().geometryKey().equals(track.geometryKey()) || !expected.controls().equals(controls.settings())) {notifyUser("赛道或操作设置已变化，请重新预览组合试车。");return false;}
        if(tuningSavePending()) {notifyUser("请先到试车报告重试保存已有测量。");return false;}
        if(!controlsReady(expected.controls())) return false;
        var purpose=expected.purpose();var source=expected.range().ranges();
        var experiment=new TuningExperiment(track,source.source().physics(),expected.exercise(),purpose.a().build().tuning(source.garage().livery()),purpose.b().build().tuning(source.garage().livery()),new SplittableRandom().nextBoolean(),expected.controls().identity());
        var captured=purpose.bind(experiment.snapshot());tuningReturn=Screen.PARTS;tuningControls=expected.controls();tuningTrack=track;tuningExperiment=experiment;tuningPurpose=captured;tuningReport=tuningPlan();startTuningRun(0);return true;
    }
    public void stock() { if(!garageCandidateAllowed(Tuning.STOCK)) return;var previous=store.profile.tuning;store.profile.tuning = Tuning.STOCK;if(!store.save()) {store.profile.tuning=previous;notifyUser("车库配置保存失败，原配置保留。");return;}rebuildPreview(); notifyUser("已恢复原厂参数；装备选择保留"); }
    public void savePreset(String slot) {
        if(store.savePreset(slot,store.profile.tuning)) notifyUser("已保存方案 "+slot);
        else notifyUser(store.warning);
    }
    public void loadPreset(String slot) {
        Tuning t = store.profile.presets.get(slot);
        if (t == null) { notifyUser("方案 " + slot + " 尚未保存"); return; }
        if(!garageCandidateAllowed(t)) return;var previous=store.profile.tuning;store.profile.tuning = t;if(!store.save()) {store.profile.tuning=previous;notifyUser("车库配置保存失败，原配置保留。");return;}rebuildPreview(); notifyUser("已载入方案 " + slot);
    }
    private void rebuildPreview() {
        Screen remember = screen;
        preview(); screen = remember;
        if (online()) raceRoom.configure(store.profile.playerName,store.profile.tuning);
    }
    public void notifyUser(String text) { toast = text; toastLeft = 4; }
    public PartsWorkbench partsWorkbench() {return partsWorkbench;}
    private PartsWorkbench createPartsWorkbench() {
        var saved=partsEditing.saved();var bench=saved==null?new PartsWorkbench(partsStore.catalog(),partsStore.last()==null?PartsBuild.stock(partsStore.catalog(),vehicleSpec.id,vehicleSpec.physicsVersion):partsStore.last().build()):PartsWorkbench.restore(partsStore.catalog(),saved);
        observedPartsRevision=bench.revision();return bench;
    }
    private void observePartsEditing() {
        if(partsWorkbench!=null && observedPartsRevision!=partsWorkbench.revision()) {partsEditing.capture(partsWorkbench.state());observedPartsRevision=partsWorkbench.revision();}
    }
    private boolean checkpointPartsEditing() {observePartsEditing();return partsEditing.flush();}
    public PartsEditingReview reviewPartsEditing() {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return null;
        observePartsEditing();var state=partsWorkbench.state();var lines=new ArrayList<String>();lines.add(partsEditing.status());if(!partsEditing.warning().isEmpty()) lines.add(partsEditing.warning());
        lines.add("编辑恢复保存当前部件、最多二十四步草稿撤销和固定的 A 对照；重启后继续工作台，不应用车库。");
        lines.add("当前有 "+state.undo().size()+" 步草稿撤销；"+(state.reference()==null?"A 对照使用当前车库。":"A 对照固定为："+state.reference().name()));
        lines.add("部件定义与指纹保持原值；缺失或改变的目录仍需在工作台处理。手动保存的上次草稿与命名方案分别保留。");
        boolean backup=partsEditing.needsBackup();
        if(backup) lines.add("继续前将保留所查看的磁盘副本，再写入本次编辑；本次草稿或磁盘文件变化后需重新查看。");
        try {return new PartsEditingReview(state,partsStore.catalog(),backup?partsEditing.inspectDisk():null,List.copyOf(lines),backup);}
        catch(java.io.IOException error) {notifyUser("恢复文件暂时无法查看，原文件保留；可先将本次草稿另存命名方案。");return null;}
    }
    public boolean retryPartsEditing() {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return false;observePartsEditing();boolean ok=partsEditing.retry();notifyUser(ok?"本次编辑已保存，可在下次继续工作台。":partsEditing.warning());return ok;
    }
    public boolean recoverPartsEditing(PartsEditingReview expected) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || expected==null || !expected.backup() || expected.catalog()!=partsStore.catalog() || !expected.state().equals(partsWorkbench.state())) {notifyUser("草稿或目录已变化，请重新查看恢复选择。");return false;}
        boolean ok=partsEditing.backupAndSave(expected.disk(),expected.state());observedPartsRevision=partsWorkbench.revision();notifyUser(ok?"磁盘副本已保留，本次编辑已保存。":partsEditing.warning());return ok;
    }
    public boolean partsForActivity() {return partsReturn==Screen.ACTIVITIES;}
    public String partsBackLabel() {return partsReturn==Screen.CREATION?"返回赛事包":partsReturn==Screen.ACTIVITIES?"返回活动卡":partsReturn==Screen.TUNING_LAB?"返回试车报告":"返回车库";}
    public void openPartsWorkbench() {
        if(online() || screen!=Screen.GARAGE) return;
        if(partsWorkbench==null) partsWorkbench=createPartsWorkbench();
        partsReturn=Screen.GARAGE;screen=Screen.PARTS;pressed.clear();helpVisible=false;physics.setSpeed(0);
    }
    public void backFromPartsWorkbench() {if(screen==Screen.PARTS) {checkpointAuthoring();if(!checkpointPartsEditing()) notifyUser(partsEditing.warning());var destination=partsReturn;partsReturn=Screen.GARAGE;if(destination==Screen.ACTIVITIES) openActivities();else if(destination==Screen.CREATION) openCreation();else if(destination==Screen.TUNING_LAB) {var request=partsEvidenceRequest;openTuningLab();if(request!=null) tuningReport=request.report();partsEvidenceRequest=null;}else {open(Screen.GARAGE);physics.setSpeed(1);}}}
    public boolean applyPartsDraft() {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return false;
        var review=partsWorkbench.review(vehicleSpec.id,vehicleSpec.physicsVersion,store.profile.tuning.livery());
        if(!review.valid()) {notifyUser(review.issues().get(0));return false;}
        if(!garageCandidateAllowed(review.candidate())) return false;
        var previous=store.profile.tuning;if(previous.equals(review.candidate())) {notifyUser("草稿参数已与车库一致。");return true;}
        store.profile.tuning=review.candidate();if(!store.save()) {store.profile.tuning=previous;notifyUser("车库配置保存失败，原配置保留；可重试。");return false;}
        rebuildPreview();physics.setSpeed(0);notifyUser("草稿参数已应用到车库；部件方案可另行保存。");return true;
    }
    public boolean savePartsDraft(String name) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return false;
        boolean saved=partsStore.save(name,partsWorkbench.build());notifyUser(saved?"部件草稿已保存；车库参数保持。":partsStore.warning());return saved;
    }
    public void loadPartsDraft() {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || partsStore.last()==null) return;
        partsWorkbench.adopt(partsStore.last().build());notifyUser("已读取上次部件草稿；可撤销，尚未应用到车库。");
    }
    public PartsPlan savePartsPlan(String name,String notes) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return null;var plan=PartsPlan.capture(name,notes,partsWorkbench.build());if(!partsPlans.save(plan)) {notifyUser(partsPlans.warning());return null;}notifyUser("部件方案已另存到方案库；车库参数保持。");return plan;
    }
    public PartsPlan migratePartsPlan(PartsPlanMigration expected,PartsCatalog catalog) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || expected==null || catalog!=partsStore.catalog() || !expected.original().equals(partsPlans.find(expected.original().id()))) {notifyUser("方案或部件目录已改变，请重新预览迁移。");return null;}
        var current=PartsPlanMigration.prepare(expected.original(),catalog,vehicleSpec.id,vehicleSpec.physicsVersion);if(!current.equals(expected)) {notifyUser("迁移条件已变化，请重新预览。");return null;}if(!current.changed()) {notifyUser("此方案已匹配当前目录，无需迁移。");return null;}
        var copy=current.copy();var trail=partsPlans.trail(current.original().id());boolean saved;try {saved=trail.steps().isEmpty()?partsPlans.save(copy,PartsPlanSource.local(current.original())):partsPlans.saveShared(copy,trail.prepend(PartsPlanSource.local(current.original())));}catch(IllegalArgumentException | ArithmeticException error) {notifyUser("来源记录需要核对，旧方案保留；请重新查看来源。");return null;}if(!saved) {notifyUser(partsPlans.warning());return null;}notifyUser(current.issues().isEmpty()?"已另存迁移副本与来源；旧方案、工作台和车库保持。":"已另存待处理迁移草稿；请解决问题后再试车或应用。");return copy;
    }
    public boolean updatePartsPlan(PartsPlan expected,String name,String notes,boolean replaceBuild) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || expected==null) return false;
        if(!expected.equals(partsPlans.find(expected.id()))) {notifyUser("方案已变化，请重新检查后再保存。");return false;}
        var plan=expected.withText(name,notes);if(replaceBuild) plan=plan.withBuild(partsWorkbench.build());boolean saved=partsPlans.save(plan);notifyUser(saved?"命名方案已保存；草稿、车库与快捷槽保持。":partsPlans.warning());return saved;
    }
    public boolean removePartsPlan(PartsPlan expected) {
        if(online() || screen!=Screen.PARTS || expected==null || !expected.equals(partsPlans.find(expected.id()))) return false;boolean saved=partsPlans.remove(expected.id());notifyUser(saved?"命名方案已移除；草稿、已固定的对照 A 和车库保留。":partsPlans.warning());return saved;
    }
    public void loadPartsPlan(String id) {if(online() || screen!=Screen.PARTS || partsWorkbench==null) return;var plan=partsPlans.find(id);if(plan!=null) {partsWorkbench.adopt(plan.build());notifyUser("已将命名方案读为草稿 B；可撤销，尚未应用到车库。");}}
    public void referencePartsPlan(String id) {if(online() || screen!=Screen.PARTS || partsWorkbench==null) return;var plan=partsPlans.find(id);if(plan!=null) {partsWorkbench.reference(new PartsComparison.Reference(plan.name(),plan.build()));notifyUser("对照 A 已固定为这个部件方案的快照。");}}
    public void clearPartsReference() {if(screen==Screen.PARTS && !online() && partsWorkbench!=null) partsWorkbench.reference(null);}
    public void reloadPartsPlans() {if(screen==Screen.PARTS && !online()) {partsPlans.reload();notifyUser(partsPlans.writable()?"方案库已重新读取；草稿与对照快照保留。":partsPlans.warning());}}
    public PartsComparison partsComparison() {return partsWorkbench==null?null:partsWorkbench.comparison(vehicleSpec.id,vehicleSpec.physicsVersion,store.profile.tuning);}
    public TrackLayout partsTrialTrack() {return selectedCircuit();}
    public boolean beginPartsTuning(PartsComparison expected,TuningTrial.Exercise exercise,TrackLayout expectedTrack) {
        return beginPartsTuning(expected,exercise,expectedTrack,null);
    }
    public boolean beginPartsTuning(PartsComparison expected,TuningTrial.Exercise exercise,TrackLayout expectedTrack,TuningPurpose.Draft expectedPurpose) {
        return beginPartsTuning(expected,exercise,expectedTrack,expectedPurpose,controls.settings());
    }
    public boolean beginPartsTuning(PartsComparison expected,TuningTrial.Exercise exercise,TrackLayout expectedTrack,TuningPurpose.Draft expectedPurpose,ControlSettings expectedControls) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || expected==null || exercise==null || expectedTrack==null) return false;
        if(!Objects.equals(expectedControls,controls.settings())) {notifyUser("操作设置已变化，请重新预览试车条件。");return false;}
        var comparison=partsComparison();var track=selectedCircuit();
        if(!comparison.equals(expected) || !expectedTrack.id.equals(track.id) || !expectedTrack.geometryKey().equals(track.geometryKey())) {notifyUser("对照参数或赛道已变化，请重新检查试车条件。");return false;}
        if(!comparison.valid()) {notifyUser(comparison.issues().get(0));return false;}
        if(tuningSavePending()) {notifyUser("请先到试车报告重试保存已有测量。");return false;}if(!controlsReady(controls.settings())) return false;
        try {var purpose=TuningPurpose.Draft.prepare(partsStore.catalog(),partsWorkbench.reference(),partsWorkbench.build(),expectedPurpose==null?TuningPurpose.Intent.defaults():expectedPurpose.intent());if(expectedPurpose!=null && !expectedPurpose.equals(purpose)) {notifyUser("部件用途、定义或来源已改变，请重新预览试车问题。");return false;}
            var experiment=new TuningExperiment(track,vehicleSpec.physicsVersion,exercise,comparison.a(),comparison.b(),new SplittableRandom().nextBoolean(),controls.settings().identity());var captured=purpose.bind(experiment.snapshot());tuningReturn=Screen.PARTS;tuningControls=controls.settings();tuningTrack=track;tuningExperiment=experiment;tuningPurpose=captured;tuningReport=tuningPlan();startTuningRun(0);return true;
        }catch(IllegalArgumentException | NullPointerException error) {notifyUser("部件来源或试车问题不适用，请重新检查草稿。");return false;}
    }
    public void openPartsTuningReport() {if(screen==Screen.PARTS && !online()) {tuningReturn=Screen.PARTS;openTuningLab();}}
    public PartsEvidenceRequest partsEvidenceRequest() {return partsEvidenceRequest;}
    public TuningPurpose purposeForReport(TuningExperiment.Snapshot report) {if(report==null) return null;var value=tuningPurpose!=null && tuningPurpose.matches(report)?tuningPurpose:tuningStore.purpose(report.id());return value!=null && value.matches(report)?value:null;}
    public void openPartsEvidenceFromReport() {
        if(online() || screen!=Screen.TUNING_LAB || tuningReport==null || !tuningReport.complete() || tuningStore.pending()) {notifyUser("请先完成六组并保存报告，再关联命名方案。");return;}
        if(partsWorkbench==null) partsWorkbench=createPartsWorkbench();
        partsEvidenceRequest=new PartsEvidenceRequest(tuningReport,purposeForReport(tuningReport));partsReturn=Screen.TUNING_LAB;screen=Screen.PARTS;pressed.clear();helpVisible=false;physics.setSpeed(0);
    }
    public PartsTrialEvidence capturePartsEvidence(PartsPlan expected,PartsTrialEvidence value,PartsCatalog catalog) {
        if(value==null) return null;var share=value.plan();var report=value.report();var purpose=value.purpose();
        if(online() || screen!=Screen.PARTS || expected==null || share==null || catalog!=partsStore.catalog() || !expected.equals(partsPlans.find(expected.id())) || !expected.equals(share.plan()) || !partsPlans.trail(expected.id()).equals(share.trail()) || tuningStore.pending() || report==null || tuningStore.reports().stream().noneMatch(report::equals) || !Objects.equals(purpose,purposeForReport(report))) {notifyUser("方案、目录、来源或报告已变化，请重新预览关联。");return null;}
        try {if(!share.equals(PartsPlanShare.capture(expected,share.author(),share.trail(),catalog))) throw new IllegalArgumentException("Shared parts changed");if(!partsEvidence.capture(value,expected)) {notifyUser(partsEvidence.warning());return null;}notifyUser("方案与完整试车快照已关联；本机报告、车库与成绩保持。");return value;}catch(IllegalArgumentException error) {notifyUser("所选报告侧与方案参数或部件不对应，不能关联。");return null;}
    }
    public boolean receivePartsEvidence(PartsTrialEvidence value) {if(online() || screen!=Screen.PARTS || value==null) return false;if(!partsEvidence.receive(value)) {notifyUser(partsEvidence.warning());return false;}notifyUser("已收录提供者的方案试车记录；未写本机报告、部件目录、命名卡或车库。");return true;}
    public boolean linkPartsEvidence(PartsTrialEvidence.Entry expected,PartsPlan plan) {if(online() || screen!=Screen.PARTS || plan!=null && !plan.equals(partsPlans.find(plan.id()))) {notifyUser("命名卡已变化，请重新选择关联。");return false;}if(!partsEvidence.link(expected,plan)) {notifyUser(partsEvidence.warning());return false;}notifyUser(plan==null?"已解除记录与本地卡的关联，原始快照保留。":"记录已明确关联这张命名卡，未改参数或本机测量。");return true;}
    public boolean removePartsEvidence(PartsTrialEvidence.Entry expected) {if(online() || screen!=Screen.PARTS) return false;if(!partsEvidence.remove(expected)) {notifyUser(partsEvidence.warning());return false;}notifyUser("已移除此份关联记录，命名卡、本机报告和外部文件保持。");return true;}
    public boolean exportPartsEvidence(PartsTrialEvidence.Entry expected,Path path) {
        return exportPartsEvidence(expected,path,false);
    }
    public boolean exportPartsEvidence(PartsTrialEvidence.Entry expected,Path path,boolean replace) {
        if(online() || screen!=Screen.PARTS || expected==null || !expected.equals(partsEvidence.find(expected.evidence().id()))) {
            notifyUser("记录或关联已变化，请重新预览导出。");
            return false;
        }
        try {
            com.f1car.persistence.PartsTrialEvidenceCodec.write(contentExportTarget(path),expected.evidence(),replace);
            notifyUser("方案与试车快照已导出，驾驶数据仍为提供者声明。");
            return true;
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("试车记录未导出，请检查独立路径，已有资料保留。");
            return false;
        }
    }
    public boolean savePartsDiscussion(PartsDiscussion value,PartsDiscussion expected,PartsPlan local,PartsCatalog catalog) {
        if(online() || screen!=Screen.PARTS || value==null) return false;
        if(expected==null && local!=null && (catalog!=partsStore.catalog() || !local.equals(partsPlans.find(local.id())) || !value.plan().equals(PartsPlanShare.capture(local,value.plan().author(),partsPlans.trail(local.id()),catalog)))) {notifyUser("命名方案、来源或目录已变化，请重新准备话题。");return false;}
        var preview=partsDiscussions.preview(value);if(!Objects.equals(expected,preview.before()) || !preview.valid() || (expected==null?value.messages().size()!=1:preview.added()!=1 || !value.equals(preview.candidate()))) {notifyUser("话题已变化或回复不再对应，请保留草稿并重新查看。");return false;}
        if(!partsDiscussions.accept(preview)) {notifyUser(partsDiscussions.warning());return false;}observeDiscussionReading();notifyUser("方案讨论已保存；原方案、报告、车库与成绩保持。");return true;
    }
    public boolean receivePartsDiscussion(PartsDiscussion.Merge reviewed) {if(online() || screen!=Screen.PARTS || reviewed==null) return false;if(!partsDiscussions.accept(reviewed)) {notifyUser(partsDiscussions.warning());return false;}observeDiscussionReading();notifyUser("讨论已收录，原留言保留；未安装部件或采用参数。");return true;}
    public boolean reloadPartsDiscussions() {
        if(online() || screen!=Screen.PARTS) return false;
        partsDiscussions.reload();
        if(!partsDiscussions.writable()) {
            notifyUser(partsDiscussions.warning());
            return false;
        }
        observeDiscussionReading();
        return true;
    }
    public boolean removePartsDiscussion(PartsDiscussion expected) {if(online() || screen!=Screen.PARTS) return false;if(!partsDiscussions.remove(expected)) {notifyUser(partsDiscussions.warning());return false;}observeDiscussionReading();notifyUser("已移除此话题的本地副本，方案、报告与外部文件保持。");return true;}
    public boolean saveDiscussionLap(PartsDiscussionLap value,PartsDiscussion expected) {
        if(online() || screen!=Screen.PARTS || value==null || expected==null || !expected.equals(value.discussion()) || !expected.equals(partsDiscussions.find(expected.id())) || value.status(expected)!=PartsDiscussionTrace.Status.MATCHED) {notifyUser("话题或留言已变化；请返回并重新预览装备圈引用。");return false;}
        boolean saved=discussionLaps.save(value,PartsDiscussionLap.Origin.LOCAL);notifyUser(saved?"装备圈引用已保存；原留言、记录库和装备保持。":discussionLaps.warning());return saved;
    }
    public boolean receiveDiscussionLap(PartsDiscussionLap value) {
        if(online() || screen!=Screen.PARTS || value==null) return false;boolean saved=discussionLaps.save(value,PartsDiscussionLap.Origin.RECEIVED);notifyUser(saved?"独立引用已收录；所附话题与圈未写入其他记录库。":discussionLaps.warning());return saved;
    }
    public boolean exportDiscussionLap(PartsDiscussionLap.Entry expected,Path target,boolean replace) {
        if(online() || screen!=Screen.PARTS || expected==null || expected!=discussionLaps.find(expected.citation().id())) return false;
        try {var path=target.toAbsolutePath().normalize();var root=store.directory().toAbsolutePath().normalize();
            if(path.startsWith(root) || Files.exists(path) && Files.exists(discussionLaps.file()) && Files.isSameFile(path,discussionLaps.file())) throw new java.io.IOException("Choose an independent export outside player data");
            com.f1car.persistence.PartsDiscussionLapCodec.write(path,expected.citation(),replace);notifyUser("完整装备圈引用已导出，原声明与留言保持。");return true;
        }catch(java.io.IOException | RuntimeException error) {notifyUser("引用未导出；请选择玩家资料目录外的独立文件并检查目标。");return false;}
    }
    public boolean saveDiscussionTrace(PartsDiscussionTrace value,PartsDiscussion expected) {
        if(online() || screen!=Screen.PARTS || value==null || expected==null || !expected.equals(value.discussion())
            || !expected.equals(partsDiscussions.find(expected.id())) || value.status(expected)!=PartsDiscussionTrace.Status.MATCHED) {notifyUser("原话题或留言已变化，请重新查看曲线引用。");return false;}
        boolean ok=discussionTraces.save(value,PartsDiscussionTrace.Origin.LOCAL);notifyUser(ok?"曲线引用已保存；原话题、报告和车库保持。":discussionTraces.warning());return ok;
    }
    public boolean receiveDiscussionTrace(PartsDiscussionTrace value,PartsDiscussionTrace.Entry expected) {
        if(online() || screen!=Screen.PARTS || value==null || !Objects.equals(expected,discussionTraces.find(value.id()))) {notifyUser("本地引用已变化，请重新查看收录。");return false;}
        boolean ok=discussionTraces.save(value,PartsDiscussionTrace.Origin.RECEIVED);notifyUser(ok?"引用已收录；所附话题、部件和本机报告仍分别处理。":discussionTraces.warning());return ok;
    }
    public boolean exportDiscussionTrace(PartsDiscussionTrace.Entry expected,Path path) {
        return exportDiscussionTrace(expected,path,false);
    }
    public boolean exportDiscussionTrace(PartsDiscussionTrace.Entry expected,Path path,boolean replace) {
        if(online() || screen!=Screen.PARTS || expected==null || !expected.equals(discussionTraces.find(expected.attachment().id()))) return false;
        try {var target=contentExportTarget(path);if(target.startsWith(resolvedPathForExport(store.directory().toAbsolutePath().normalize())) && !target.getFileName().toString().startsWith("parts-export-")) throw new java.io.IOException("Player data is reserved");
            com.f1car.persistence.PartsDiscussionTraceCodec.write(target,expected.attachment(),replace);notifyUser("已导出独立曲线引用，携带原留言背景和单组完整记录。");return true;
        }catch(java.io.IOException | RuntimeException error) {notifyUser("曲线引用未导出，请使用独立文件路径。");return false;}
    }
    public boolean exportPartsDiscussion(PartsDiscussion expected,Path path) {
        return exportPartsDiscussion(expected,path,false);
    }
    public boolean exportPartsDiscussion(PartsDiscussion expected,Path path,boolean replace) {
        if(online() || screen!=Screen.PARTS || expected==null || !expected.equals(partsDiscussions.find(expected.id()))) {
            notifyUser("讨论已变化，请重新预览导出。");
            return false;
        }
        try {
            com.f1car.persistence.PartsDiscussionCodec.write(contentExportTarget(path),expected,replace);
            notifyUser("完整讨论已导出，署名、观察与方案来源仍是提供者声明。");
            return true;
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("讨论未导出，请检查独立文件路径，已有资料保留。");
            return false;
        }
    }
    public record RevisionDiscussionReview(com.f1car.persistence.PartsDiscussionRevisionStore.Entry entry,RevisionDiscussionTarget.Review reviewed) {
        public RevisionDiscussionReview {if(entry==null || reviewed==null || !entry.revision().equals(reviewed.target().revision())) throw new IllegalArgumentException("Mixed revision discussion route");}
    }
    public RevisionDiscussionReview reviewRevisionDiscussion(com.f1car.persistence.PartsDiscussionRevisionStore.Entry expected,RevisionDiscussionTarget.Side side) {
        if(online() || screen!=Screen.PARTS || expected==null || side==null || !expected.equals(discussionRevisions.find(expected.revision().id()))) return null;
        var target=new RevisionDiscussionTarget(expected.revision(),side);return new RevisionDiscussionReview(expected,target.review(partsDiscussions.find(target.frozen().id())));
    }
    public PartsDiscussion resolveRevisionDiscussion(RevisionDiscussionReview expected) {
        if(online() || screen!=Screen.PARTS || expected==null) return null;
        var target=expected.reviewed().target();var local=partsDiscussions.find(target.frozen().id());
        if(!expected.entry().equals(discussionRevisions.find(expected.entry().revision().id())) || !expected.reviewed().allowed() || !Objects.equals(expected.reviewed().local(),local)) {
            notifyUser("修订声明或本地原话题已变化／缺失，请明确刷新对应；冻结原文保留。");return null;
        }
        return local;
    }
    public boolean acceptDiscussionRevision(com.f1car.persistence.PartsDiscussionRevisionStore.Review review,boolean localParent,boolean localChild) {
        if(online() || screen!=Screen.PARTS || review==null) return false;
        var value=review.incoming();
        if(review.origin()==com.f1car.persistence.PartsDiscussionRevisionStore.Origin.LOCAL
            && (localParent && !value.parent().equals(partsDiscussions.find(value.parent().id())) || localChild && !value.child().equals(partsDiscussions.find(value.child().id())))) {
            notifyUser("父侧或本地子侧原话题已变化，请重新选择背景与原留言。");return false;
        }
        boolean ok=discussionRevisions.accept(review);notifyUser(ok?"修订声明已保存；话题、留言、方案和测量分别保留。":discussionRevisions.warning());return ok;
    }
    public boolean removeDiscussionRevision(com.f1car.persistence.PartsDiscussionRevisionStore.Entry expected) {
        if(online() || screen!=Screen.PARTS) return false;boolean ok=discussionRevisions.remove(expected);notifyUser(ok?"本地修订声明副本已移除；原话题与方案保留。":discussionRevisions.warning());return ok;
    }
    public boolean exportDiscussionRevision(com.f1car.persistence.PartsDiscussionRevisionStore.Entry expected,Path path) {
        return exportDiscussionRevision(expected,path,false);
    }
    public boolean exportDiscussionRevision(com.f1car.persistence.PartsDiscussionRevisionStore.Entry expected,Path path,boolean replace) {
        if(online() || screen!=Screen.PARTS || expected==null || !expected.equals(discussionRevisions.find(expected.revision().id()))) return false;
        try {var target=contentExportTarget(path);if(target.startsWith(resolvedPathForExport(store.directory().toAbsolutePath().normalize()))) throw new java.io.IOException("Use an independent export path");
            com.f1car.persistence.PartsDiscussionRevisionCodec.write(target,expected.revision(),replace);notifyUser("完整修订关系与两侧冻结原文已导出。");return true;
        }catch(java.io.IOException | RuntimeException error) {notifyUser("修订声明未导出，请使用玩家资料目录外的独立路径。");return false;}
    }
    public boolean installPartsPack(PartsCatalog.Pack pack,boolean replace) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return false;
        boolean saved=partsStore.install(pack,replace);if(saved) partsWorkbench.catalog(partsStore.catalog());notifyUser(saved?"部件包已收录；旧草稿保留，请查看依赖与定义变化。":partsStore.warning());return saved;
    }
    public PartsAuthoring partsAuthoring() {
        if(partsAuthoring==null && partsAuthoringEditing.current()!=null) partsAuthoring=new PartsAuthoring(partsAuthoringEditing.current());
        return partsAuthoring;
    }
    public boolean beginPartsAuthoring(PartsAuthoring.Draft draft) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || draft==null) return false;
        partsAuthoring=new PartsAuthoring(draft);partsAuthoringEditing.capture(partsAuthoring.state());return true;
    }
    public record PartsAuthoringReview(PartsAuthoring editor,PartsAuthoring.State state,PartsCatalog catalog,PartsCatalog.Pack pack,PartsCatalog preview,String installationIssue) {
        public PartsAuthoringReview {if(editor==null || state==null || catalog==null || pack==null || installationIssue==null || !pack.equals(state.draft().pack()) || com.f1car.persistence.PartsPackCodec.packJson(pack).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>com.f1car.persistence.PartsPackCodec.PACK_BYTES) throw new IllegalArgumentException("Authoring review differs from draft");}
    }
    public PartsAuthoringReview reviewPartsAuthoring() {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || partsAuthoring()==null) return null;
        try {
            var pack=partsAuthoring.draft().pack();
            if(com.f1car.persistence.PartsPackCodec.packJson(pack).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>com.f1car.persistence.PartsPackCodec.PACK_BYTES) throw new IllegalArgumentException("部件包超过 65536 字节，请缩短说明或减少部件。");
            PartsCatalog preview=null;String issue="";try {preview=partsStore.previewPacks(List.of(pack));}catch(IllegalArgumentException error) {issue=error.getMessage();}
            return new PartsAuthoringReview(partsAuthoring,partsAuthoring.state(),partsStore.catalog(),pack,preview,issue);
        }catch(IllegalArgumentException error) {notifyUser(error.getMessage());return null;}
    }
    public boolean partsAuthoringCurrent(PartsAuthoringReview expected,boolean catalogue) {
        return expected!=null && !online() && screen==Screen.PARTS && partsWorkbench!=null && partsAuthoring==expected.editor() && partsAuthoring.state()==expected.state() && (!catalogue || partsStore.catalog()==expected.catalog());
    }
    public boolean collectAuthoredParts(PartsAuthoringReview expected) {
        if(!partsAuthoringCurrent(expected,true)) {notifyUser("制作草稿或部件目录已变化，请重新预览。");return false;}
        if(expected.preview()==null) {notifyUser(expected.installationIssue());return false;}
        return installPartsPack(expected.pack(),true);
    }
    public boolean exportAuthoredParts(PartsAuthoringReview expected,Path path) {
        return exportAuthoredParts(expected,path,false);
    }
    public boolean exportAuthoredParts(PartsAuthoringReview expected,Path path,boolean replace) {
        if(!partsAuthoringCurrent(expected,false)) {notifyUser("制作草稿已变化，请重新预览导出。");return false;}
        try {var target=contentExportTarget(path);if(target.startsWith(resolvedPathForExport(store.directory().toAbsolutePath().normalize())) && !target.getFileName().toString().startsWith("parts-export-")) {notifyUser("用户目录内请使用 parts-export- 开头的独立导出文件名。");return false;}partsStore.exportPack(expected.pack(),target,replace);notifyUser("制作的部件包已导出，目录收录和车辆安装可分别选择。");return true;}
        catch(java.io.IOException | RuntimeException error) {notifyUser("制作的部件包未导出，请检查目标路径后重试。");return false;}
    }
    public boolean removePartsPack(String id) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || partsStore.packs().stream().noneMatch(pack->pack.id().equals(id))) return false;
        boolean saved=partsStore.remove(id);if(saved) partsWorkbench.catalog(partsStore.catalog());notifyUser(saved?"部件包已移除；原草稿保留，缺失部件会明确提示。":partsStore.warning());return saved;
    }
    public boolean importPartsBundle(PartsPackCodec.Bundle bundle) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null) return false;
        try {boolean saved=partsStore.importBundle(bundle);if(saved) {partsWorkbench.catalog(partsStore.catalog());partsWorkbench.adopt(bundle.plan().build());}notifyUser(saved?"方案与部件包已收录；尚未应用到车库。":partsStore.warning());return saved;}
        catch(IllegalArgumentException error) {notifyUser(error.getMessage());return false;}
    }
    public void reloadPartsData() {if(screen==Screen.PARTS && !online()) {partsStore.reload();if(partsWorkbench!=null) partsWorkbench.catalog(partsStore.catalog());notifyUser(partsStore.writable()?"部件资料已重新读取；当前草稿保留。":partsStore.warning());}}
    public void exportPartsDraft(Path path) {
        exportPartsDraft(reviewPartsDraftExport(),path,false);
    }
    public PartsPackCodec.Bundle reviewPartsDraftExport() {
        return online() || screen!=Screen.PARTS || partsWorkbench==null?null:
                partsStore.bundle(partsStore.last()==null?"我的部件方案":partsStore.last().name(),partsWorkbench.build());
    }
    public boolean exportPartsDraft(PartsPackCodec.Bundle reviewed,Path path,boolean replace) {
        if(reviewed==null || !reviewed.equals(reviewPartsDraftExport())) {
            notifyUser("部件草稿或目录已变化，请重新选择后导出。");
            return false;
        }
        try {
            partsStore.exportBundle(reviewed,contentExportTarget(path),replace);
            notifyUser("已导出草稿和当前玩家部件包。");
            return true;
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("部件方案导出失败，请检查目标文件。");
            return false;
        }
    }
    public void exportPartsTemplate(Path path,PartsCatalog.Pack pack) {
        exportPartsTemplate(path,pack,false);
    }
    public void exportPartsTemplate(Path path,PartsCatalog.Pack pack,boolean replace) {
        if(online() || screen!=Screen.PARTS) return;
        try {partsStore.exportPack(pack,contentExportTarget(path),replace);notifyUser("已导出部件包，可编辑 JSON 后导入。");}
        catch(java.io.IOException | RuntimeException error) {notifyUser("部件包导出失败，请检查目标文件。");}
    }
    public void exportPartsPlan(PartsPlan plan,Path path) {
        exportPartsPlan(plan,reviewPartsPlanExport(plan),path,false);
    }
    public PartsPackCodec.Bundle reviewPartsPlanExport(PartsPlan plan) {
        return online() || screen!=Screen.PARTS || plan==null || !plan.equals(partsPlans.find(plan.id()))?null:
                partsStore.bundle(plan.name(),plan.build());
    }
    public void exportPartsPlan(PartsPlan plan,PartsPackCodec.Bundle reviewed,Path path,boolean replace) {
        if(reviewed==null || !reviewed.equals(reviewPartsPlanExport(plan))) {
            notifyUser("方案或目录已变化，请重新选择后导出。");
            return;
        }
        try {partsStore.exportBundle(reviewed,contentExportTarget(path),replace);notifyUser("已导出所选方案和当前部件包。");}
        catch(java.io.IOException | RuntimeException error) {notifyUser("命名部件方案导出失败，请检查目标文件。");}
    }
    public PartsPlan receivePortablePartsPlan(PartsPlanShare share,PartsPlan copy) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || share==null || copy==null || copy.id().equals(share.plan().id()) || !copy.withText(share.plan().name(),share.plan().notes()).equals(copy) || !copy.build().equals(share.plan().build())) {notifyUser("收到的方案副本与预览不符，请重新读取。");return null;}
        PartsPlanShare.Trail trail;try {trail=share.receivedTrail();}catch(IllegalArgumentException | ArithmeticException error) {notifyUser("来源记录超过当前界限；原始文件与方案保留，请核对来源。");return null;}var old=partsPlans.find(copy.id());if(old!=null) {if(old.equals(copy) && partsPlans.trail(copy.id()).equals(trail) && partsPlans.writable()) {notifyUser("这份收到的方案已另存，未创建重复卡。");return old;}notifyUser("这个本地副本已变化，请重新读取后另存。");return null;}
        if(!partsPlans.saveShared(copy,trail)) {notifyUser(partsPlans.warning());return null;}notifyUser("已另存独立方案及来源；部件目录、草稿与车库保持。");return copy;
    }
    public boolean installPortableParts(PartsPlanShare share,PartsCatalog expected) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || share==null || expected!=partsStore.catalog()) {notifyUser("部件目录已变化，请重新预览收录。");return false;}boolean saved=partsStore.installPacks(share.packs(),true);if(saved) partsWorkbench.catalog(partsStore.catalog());notifyUser(saved?"相关完整部件包已收录；命名方案、草稿与对照 A 保留，请查看修订影响。":partsStore.warning());return saved;
    }
    public boolean loadPortablePartsDraft(PartsPlanShare share,PartsCatalog expected,PartsBuild previous) {
        if(online() || screen!=Screen.PARTS || partsWorkbench==null || share==null || expected!=partsStore.catalog() || !partsWorkbench.build().equals(previous)) {notifyUser("草稿或目录已变化，请重新预览读取。");return false;}partsWorkbench.adopt(share.plan().build());notifyUser("收到的方案已读为可撤销草稿；未安装部件或应用车库。");return true;
    }
    public boolean exportPortablePartsPlan(PartsPlan expected,PartsPlanShare share,PartsCatalog catalog,Path path) {
        return exportPortablePartsPlan(expected,share,catalog,path,false);
    }
    public boolean exportPortablePartsPlan(PartsPlan expected,PartsPlanShare share,PartsCatalog catalog,Path path,boolean replace) {
        if(online() || screen!=Screen.PARTS || expected==null || share==null || catalog!=partsStore.catalog() || !expected.equals(partsPlans.find(expected.id())) || !expected.equals(share.plan()) || !partsPlans.trail(expected.id()).equals(share.trail())) {notifyUser("所选方案、来源或目录已变化，请重新预览分享。");return false;}
        try {var target=contentExportTarget(path);if(!PartsPlanShare.capture(expected,share.author(),share.trail(),catalog).equals(share)) {notifyUser("携带的部件内容已变化，请重新预览。");return false;}com.f1car.persistence.PartsPlanShareCodec.write(target,share,replace);notifyUser("已导出方案、备注、署名、近期来源与相关部件包，接收者可分别决定收录和采用。");return true;}catch(java.io.IOException | RuntimeException error) {notifyUser("带说明方案未导出，请检查独立文件路径，已有资料保留。");return false;}
    }
    public boolean exportTrialTraces(TrialTraceShare value,Path path) {
        return exportTrialTraces(value,path,false);
    }
    public boolean exportTrialTraces(TrialTraceShare value,Path path,boolean replace) {
        if(online() || screen!=Screen.TUNING_LAB || value==null) return false;
        try {var target=contentExportTarget(path);if(target.startsWith(resolvedPathForExport(store.directory().toAbsolutePath().normalize())) && !target.getFileName().toString().startsWith("parts-export-")) throw new java.io.IOException("Use an independent export path");com.f1car.persistence.TrialTraceCodec.write(target,value,replace);notifyUser("已导出冻结报告与过程曲线，来源为提供者声明。");return true;}
        catch(java.io.IOException | RuntimeException error) {notifyUser("曲线未导出，请使用独立文件路径；已有资料保留。");return false;}
    }
    public void openSetupLibrary() {
        if(online() || screen!=Screen.GARAGE) return;
        screen=Screen.SETUP_LIBRARY;pressed.clear();helpVisible=false;physics.setSpeed(0);
    }
    public void backFromSetupLibrary() { if(screen==Screen.SETUP_LIBRARY) { open(Screen.GARAGE);physics.setSpeed(1); } }
    public SetupCard saveNamedSetup(String name,String notes) {
        if(online() || screen!=Screen.SETUP_LIBRARY) return null;
        return saveNamedSetup(captureNamedSetup(name,notes));
    }
    public SetupCard captureNamedSetup(String name,String notes) {
        return SetupCard.capture(name,notes,vehicleSpec,circuit,controls.settings().identity(),store.profile.tuning);
    }
    public SetupCard saveNamedSetup(SetupCard candidate) {
        if(online() || screen!=Screen.SETUP_LIBRARY) return null;
        var saved=setupLibrary.importCard(candidate);
        notifyUser(saved==null?setupLibrary.warning():"已保存命名配置；当前车库与 A/B 快捷槽保留。");
        return saved;
    }
    public void editNamedSetup(String id,String name,String notes) {
        if(online() || screen!=Screen.SETUP_LIBRARY) return;
        var card=setupLibrary.find(id);
        if(card!=null) editNamedSetup(card,card.withText(name,notes));
    }
    public boolean editNamedSetup(SetupCard reviewed,SetupCard candidate) {
        if(online() || screen!=Screen.SETUP_LIBRARY) return false;
        boolean saved=setupLibrary.editText(reviewed,candidate);
        notifyUser(saved?"配置名称与备注已保存。":setupLibrary.warning());
        return saved;
    }
    public void removeNamedSetup(String id) {
        removeReviewedNamedSetup(setupLibrary.find(id));
    }
    public void removeReviewedNamedSetup(SetupCard reviewed) {
        if(!currentNamedSetups(reviewed)) return;
        notifyUser(setupLibrary.remove(reviewed.id())?"已删除配置；当前车库与已有报告保留。":setupLibrary.warning());
    }
    public boolean applyNamedSetup(String id) {
        return applyReviewedNamedSetup(setupLibrary.find(id));
    }
    public boolean applyReviewedNamedSetup(SetupCard card) {
        if(!currentNamedSetups(card)) return false;
        if(!card.incompatibility(vehicleSpec).isEmpty()) { notifyUser(card.incompatibility(vehicleSpec)+"，请用当前版本重新试车保存。");return false; }
        var previous=store.profile.tuning;var next=card.performanceWithLivery(previous.livery());if(!garageCandidateAllowed(next)) return false;store.profile.tuning=next;
        if(!store.save()) { store.profile.tuning=previous;notifyUser("车库配置保存失败，保留原配置；可重试。");return false; }
        rebuildPreview();physics.setSpeed(0);notifyUser("已应用性能配置；涂装、键位与手柄设置保留。");return true;
    }
    public SetupCard importNamedSetup(SetupCard card) {
        if(online() || screen!=Screen.SETUP_LIBRARY) return null;
        var imported=setupLibrary.importCard(card);notifyUser(imported==null?setupLibrary.warning():"配置已收录；尚未应用到车库。");return imported;
    }
    public SetupCard rebaseNamedSetup(String id) {
        return rebaseReviewedNamedSetup(setupLibrary.find(id));
    }
    public SetupCard rebaseReviewedNamedSetup(SetupCard old) {
        if(!currentNamedSetups(old) || !old.canRebase(vehicleSpec)) return null;
        var copy=old.rebase(vehicleSpec);if(!setupLibrary.save(copy)) {notifyUser(setupLibrary.warning());return null;}
        notifyUser("已另存当前物理副本；新增悬挂倍率为 100%，请先重新试车。");return copy;
    }
    public void exportNamedSetup(String id,Path path) {
        var card=setupLibrary.find(id);
        if(card!=null) exportNamedSetup(card,path,false);
    }
    public void exportNamedSetup(SetupCard reviewed,Path path,boolean replace) {
        if(!currentNamedSetups(reviewed)) return;
        try { setupLibrary.exportCard(reviewed,contentExportTarget(path),replace);notifyUser("配置已导出；分享的是参数与备注，不含成绩或操作绑定。"); }
        catch(java.io.IOException | RuntimeException error) { notifyUser("配置导出失败，请检查目标文件。"); }
    }
    public void beginLibraryTuning(SetupCard baseline,String id,TuningTrial.Exercise exercise) {
        beginReviewedLibraryTuning(baseline,setupLibrary.find(id),store.profile.tuning,exercise);
    }
    public void beginReviewedLibraryTuning(SetupCard baseline,SetupCard b,Tuning reviewedGarage,TuningTrial.Exercise exercise) {
        if(!currentNamedSetups(b,baseline)) return;
        if(baseline==null && !store.profile.tuning.equals(reviewedGarage)) {
            notifyUser("作为对比基准的车库配置已变化，请重新确认。");
            return;
        }
        if(!b.incompatibility(vehicleSpec).isEmpty() || baseline!=null && !baseline.incompatibility(vehicleSpec).isEmpty()) { notifyUser("车型或物理版本不同，不能建立对照试车。");return; }
        var a=baseline==null?store.profile.tuning:baseline.performanceWithLivery(store.profile.tuning.livery());
        var candidate=b.performanceWithLivery(store.profile.tuning.livery());
        if(a.performanceKey().equals(candidate.performanceKey())) { notifyUser("两套性能参数相同，请选择不同配置。");return; }
        if(tuningSavePending()) { notifyUser("请先到试车中心重试保存已有报告。");return; }
        if(!controlsReady(controls.settings())) return;
        tuningReturn=Screen.SETUP_LIBRARY;tuningControls=controls.settings();tuningTrack=selectedCircuit();
        tuningExperiment=new TuningExperiment(tuningTrack,vehicleSpec.physicsVersion,exercise,a,candidate,new SplittableRandom().nextBoolean(),tuningControls.identity());
        tuningReport=tuningPlan();startTuningRun(0);
    }
    private boolean currentNamedSetups(SetupCard selected,SetupCard... others) {
        if(online() || screen!=Screen.SETUP_LIBRARY || selected==null) return false;
        if(setupLibrary.verifySelection(selected,others)) return true;
        notifyUser(setupLibrary.warning());
        return false;
    }
    public ExperienceTrial experience() { return experience; }
    public ExperienceRun experienceReference() { return experienceReference; }
    public ExperiencePlayer experiencePlayer() { return experiencePlayer; }
    public CircuitExperience.Course experienceCourse() { return experienceCourses().get(experiencePart); }
    public List<CircuitExperience.Course> experienceCourses() {
        if(experienceCourseTrack!=circuit || experienceCourses.isEmpty()) {
            var trackId=circuit!=null && TrackCatalog.isBuiltin(circuit)?circuit.id:TrackCatalog.DEFAULT_ID;
            experienceCourses=CircuitExperience.forTrack(trackId);
            experienceCourseTrack=circuit;
            Arrays.fill(experienceKeys,null);
        }
        return experienceCourses;
    }
    public String experienceInputIdentity() {
        var settings=controls.settings();
        if(experienceKeyControls!=settings) {
            experienceInputIdentity=settings.identity();
            experienceKeyControls=settings;
            Arrays.fill(experienceKeys,null);
        }
        return experienceInputIdentity;
    }
    public ExperienceRun experienceBest(int part) {
        if(part<0 || part>=experienceKeys.length) throw new IllegalArgumentException("Invalid experience part");
        var course=experienceCourses().get(part);
        var identity=experienceInputIdentity();
        if(experienceKeys[part]==null) experienceKeys[part]=course.key(vehicleSpec.physicsVersion,identity);
        return experienceStore.savedBest(experienceKeys[part]);
    }
    public void openExperience() {
        if(online()) { notifyUser("分段攻关在单机进行，请先退出房间。");return; }
        var track=TrackCatalog.isBuiltin(circuit)?circuit:TrackCatalog.require(TrackCatalog.DEFAULT_ID).layout();
        preview(track);screen=Screen.EXPERIENCE;physics.setSpeed(0);helpVisible=false;pressed.clear();
        if(!experienceStore.warning.isEmpty()) notifyUser(experienceStore.warning);
    }
    public void backFromExperience() { mainMenu();open(Screen.COURSES); }
    public void cycleExperienceTrack(int direction) {
        if(screen!=Screen.EXPERIENCE) return;
        var tracks=TrackCatalog.all();
        var current=experienceCourse().track().id;
        int index=0;
        for(int i=0;i<tracks.size();i++) if(tracks.get(i).id().equals(current)) index=i;
        preview(tracks.get(Math.floorMod(index+direction,tracks.size())).layout());screen=Screen.EXPERIENCE;physics.setSpeed(0);
    }
    public void selectExperiencePart(int part) {
        if(part<0 || part>=experienceKeys.length) throw new IllegalArgumentException("Invalid experience part");
        if(experiencePart==part) return;
        experiencePart=part;
        experiencePlayer=null;
    }
    public void startExperience() { startExperience(experienceCourse()); }
    private void startExperience(CircuitExperience.Course course) {
        if(online() || !controlsReady(controls.settings())) return;
        var reference=experienceStore.savedBest(course.key(vehicleSpec.physicsVersion,controls.settings().identity()));
        disposeSession();freezeControls(controls.settings());circuit=course.track();experiencePart=course.part();mode=Mode.EXPERIENCE;attemptId=UUID.randomUUID().toString();
        experience=new ExperienceTrial(course,vehicleSpec.physicsVersion,controlsIdentity(),store.profile.tuning.livery());experienceReference=reference;createWorld(false);
        var stock=new Tuning(1,1,1,1,store.profile.tuning.livery());player=addCar("分段攻关",stock,-18,0,false,1,99999);var spawn=course.gate(0);player.recover(spawn.position(),spawn.tangent());racers.get(0).previous=player.position();
        sessionTime=0;countdown=.8f;sessionLaps=1;newBest=false;helpVisible=false;screen=Screen.DRIVING;physics.setSpeed(1);cameraImmediate();
    }
    private ExperienceTrial.Observation experienceObservation() { var forward=player.forward();return ExperienceTrial.Observation.of(player.position(),player.velocity(),(float)Math.atan2(forward.x,forward.z),player.throttle,player.braking,player.steeringDemand()); }
    private void launchExperience() {
        releaseRollingStart(experience.course().gate(0),CircuitExperience.ROLLING_SPEED);
        sessionTime=0;
        experience.begin(experienceObservation());
    }
    private void releaseRollingStart(TrackLayout.Sample spawn,float speedMps) {
        player.recover(spawn.position(),spawn.tangent());
        player.drive(0,0,0,0);
        player.body.setLinearVelocity(spawn.tangent().mult(speedMps));
        racers.get(0).previous=player.position();
    }
    public void viewExperienceBest() { var best=experienceBest(experiencePart);if(best==null) { notifyUser("先完成一次当前条件下的有效尝试，再查看个人最佳回看。");return; }experiencePlayer=new ExperiencePlayer(best); }
    public ExperienceComparison compareRecentExperience() {
        if (screen != Screen.EXPERIENCE || online()) return null;
        var course = experienceCourse();
        var conditions = ExperienceRun.Conditions.of(course, vehicleSpec.physicsVersion, controls.settings().identity());
        var best = experienceStore.savedBest(conditions.key());
        if (best != null && conditions.equals(best.conditions())) {
            for (var run : experienceStore.savedRecent()) {
                if (!run.id().equals(best.id()) && conditions.equals(run.conditions()))
                    return new ExperienceComparison(best, run);
            }
        }
        notifyUser("需要两次已保存的同条件有效成绩。请先完成另一条记录。");
        return null;
    }

    public void viewComparedExperience(ExperienceComparison review) {
        if (review == null || screen != Screen.EXPERIENCE || online()) return;
        var course = experienceCourse();
        var conditions = ExperienceRun.Conditions.of(course, vehicleSpec.physicsVersion, controls.settings().identity());
        if (!conditions.equals(review.recent().conditions()) || !experienceStore.saved(review.recent())) {
            notifyUser("所选区间或原成绩已变化，请重新打开对照。");
            return;
        }
        experiencePlayer = new ExperiencePlayer(review.recent());
    }
    public void viewExperienceResult() {
        if(experience==null || experience.state()!=ExperienceTrial.State.SUCCESS) return;var run=experience.result();int part=experience.course().part();openExperience();experiencePart=part;experiencePlayer=new ExperiencePlayer(run);
    }
    public void retryExperienceSave() {
        if(experienceStore.flush()) {
            experienceSaveFinished();
            return;
        }
        var conflict=experienceStore.reviewConflict();
        if(conflict!=null && hud!=null) hud.reviewExperienceConflict(conflict);
        else notifyUser(experienceStore.warning);
    }
    public void applyExperienceConflict(ExperienceStore.ConflictReview review) {
        if(experienceStore.applyConflict(review)) experienceSaveFinished();
        else notifyUser(experienceStore.warning);
    }
    private void experienceSaveFinished() {
        if(screen==Screen.RESULTS && experience!=null && experience.state()==ExperienceTrial.State.SUCCESS)
            updateExperienceBest(experience.result());
        notifyUser("分段攻关记录已保存。");
    }
    private void updateExperienceBest(ExperienceRun run) {
        var best=experienceStore.savedBest(run.key());
        newBest=best!=null && best.id().equals(run.id())
                && (experienceReference==null || run.duration()<experienceReference.duration());
    }
    public WorkshopSession workshop() { return workshop; }
    public WorkshopReport workshopReport() { return workshopReport; }
    public WorkshopReport workshopBest(WorkshopSession.Kind kind) {
        var settings=controls.settings();
        if(workshopBestTrack!=circuit || workshopBestControls!=settings) {
            String input=settings.identity();
            for(var task:WorkshopSession.Kind.values()) {
                var conditions=WorkshopReport.Conditions.of(task,circuit,vehicleSpec.physicsVersion,input);
                workshopBestKeys.put(task,WorkshopReport.key(task,conditions,Tuning.STOCK));
            }
            workshopBestTrack=circuit;
            workshopBestControls=settings;
        }
        return workshopStore.best(workshopBestKeys.get(kind));
    }
    public void openWorkshop() {
        if(online()) { notifyUser("实验委托在单机进行，请先退出房间。");return; }
        if(workshop!=null) preview();
        if(workshopReport==null && !workshopStore.recent().isEmpty()) workshopReport=workshopStore.recent().get(0);
        screen=Screen.WORKSHOP;pressed.clear();helpVisible=false;physics.setSpeed(0);
        if(!workshopStore.warning.isEmpty()) notifyUser(workshopStore.warning);else if(!journal.warning.isEmpty()) notifyUser(journal.warning);
    }
    public void startWorkshop(WorkshopSession.Kind kind) {
        if(online() || screen!=Screen.WORKSHOP && workshop==null) return;
        if(!controlsReady(controls.settings())) return;
        disposeSession();freezeControls(controls.settings());circuit=selectedCircuit();mode=Mode.WORKSHOP;attemptId=UUID.randomUUID().toString();workshopRecorded=false;workshopReport=null;
        var tuning=new Tuning(1,1,1,1,store.profile.tuning.livery());workshop=new WorkshopSession(kind,circuit,vehicleSpec.physicsVersion,tuning,controlsIdentity());createWorld(kind==WorkshopSession.Kind.PARK);
        player=addCar("委托试车",tuning,-18,0,false,1,99999);var spawn=workshop.spawn();player.recover(spawn.position(),spawn.forward());racers.get(0).previous=player.position();if(workshop.cup()!=null) player.installCup(factory);
        sessionTime=0;countdown=.8f;sessionLaps=1;newBest=false;helpVisible=false;screen=Screen.DRIVING;physics.setSpeed(1);cameraImmediate();
    }
    public void workshopResponseToggle() {
        boolean hadPending=workshopStore.pending();
        if(!workshopStore.responses(!workshopStore.responses())) notifyUser(workshopStore.warning);
        else if(hadPending) finishWorkshopSave();
    }
    public void viewWorkshopReport(String id) { workshopStore.recent().stream().filter(report->report.id().equals(id)).findFirst().ifPresent(report->workshopReport=report); }
    public void retryWorkshopSave() {
        if(!workshopStore.conflictReviewAvailable() && workshopStore.flush()) {
            finishWorkshopSave();
            return;
        }
        var conflict=workshopStore.reviewConflict();
        if(conflict!=null && hud!=null) hud.reviewWorkshopConflict(conflict);
        else notifyUser(workshopStore.warning);
    }
    public void applyWorkshopConflict(WorkshopStore.ConflictReview review) {
        if(workshopStore.applyConflict(review)) finishWorkshopSave();
        else notifyUser(workshopStore.warning);
    }
    private void finishWorkshopSave() {
        boolean history=workshopStore.reconcile(journal);
        notifyUser(history?"实验报告与履历已保存。":journal.warning);
        if(journal.conflictReviewAvailable()) retryJournalSave();
    }
    public TuningTrial tuningTrial() { return tuningTrial; }
    public TuningExperiment.Snapshot tuningPlan() { return tuningExperiment==null?null:tuningExperiment.snapshot(); }
    public TuningExperiment.Snapshot tuningReport() { return tuningReport; }
    public TuningPurpose tuningPurpose() {if(tuningReport==null) return null;return tuningPurpose!=null && tuningPurpose.matches(tuningReport)?tuningPurpose:tuningStore.purpose(tuningReport.id());}
    public record TuningProjectsReview(List<TuningExperiment.Snapshot> stored,TuningExperiment.Snapshot anchor,TuningProjectStudy study) {
        public TuningProjectsReview {stored=List.copyOf(stored);Objects.requireNonNull(anchor);Objects.requireNonNull(study);if(!anchor.id().equals(study.anchor().snapshot().id())) throw new IllegalArgumentException("Project anchor differs");}
    }
    public TuningProjectsReview reviewTuningProjects() {
        if(online() || screen!=Screen.TUNING_LAB || tuningReport==null) return null;
        var current=tuningStore.reports().stream().filter(report->report.id().equals(tuningReport.id())).findFirst().orElse(tuningReport);
        var reports=new ArrayList<TuningProjectStudy.Report>();var anchor=new TuningProjectStudy.Report(current,purposeForReport(current));reports.add(anchor);
        for(var report:tuningStore.reports()) if(!report.id().equals(tuningReport.id())) reports.add(new TuningProjectStudy.Report(report,purposeForReport(report)));
        return new TuningProjectsReview(tuningStore.reports(),tuningReport,new TuningProjectStudy(anchor,reports));
    }
    /** Rendering compares frozen in-memory values only; no digest, JSON or disk read. */
    public boolean tuningProjectsCurrent(TuningProjectsReview review) {
        if(review==null || online() || screen!=Screen.TUNING_LAB || !Objects.equals(tuningReport,review.anchor()) || !tuningStore.reports().equals(review.stored())) return false;
        for(var candidate:review.study().candidates()) {var report=candidate.report();var purpose=tuningPurpose!=null && tuningPurpose.experiment().equals(report.snapshot().id())?tuningPurpose:tuningStore.purpose(report.snapshot().id());if(!Objects.equals(purpose,report.purpose())) return false;}
        return true;
    }
    public int tuningIndex() { return tuningIndex; }
    public void openTuningLab() {
        if(pairRunning()) {openPairLab();return;}
        if(online()) { notifyUser("调校试车在单机进行，请先退出房间。");return; }
        if(screen==Screen.GARAGE || screen==Screen.COURSES) tuningReturn=screen;
        if(tuningTrial!=null) preview();
        tuningReport=tuningPlan();if(tuningReport==null && !tuningStore.reports().isEmpty()) tuningReport=tuningStore.reports().get(0);
        pairLabRequested=false;screen=Screen.TUNING_LAB;pressed.clear();helpVisible=false;physics.setSpeed(0);
    }
    public boolean pairRunning() {return pairRunActive && tuningTrial!=null;}
    public boolean pairLabRequested() {return pairLabRequested;}
    public int pairIndex() {return pairIndex;}
    public PairExperiment.Snapshot pairReport() {return pairReport;}
    public PairExperiment.Snapshot pairPlan() {return pairExperiment==null?null:pairExperiment.snapshot();}
    public void openPairLab() {
        if(online()) {notifyUser("四条件试车仅在单机进行。");return;}if(screen==Screen.GARAGE || screen==Screen.COURSES) tuningReturn=screen;
        if(tuningTrial!=null) preview();if(pairReport==null && !pairTrials.reports().isEmpty()) pairReport=pairTrials.reports().get(0);
        pairLabRequested=true;screen=Screen.TUNING_LAB;pressed.clear();helpVisible=false;physics.setSpeed(0);
    }
    public void closePairLab() {pairLabRequested=false;}
    public void viewPairReport(String id) {if(screen==Screen.TUNING_LAB) {var value=pairTrials.find(id);if(value!=null) pairReport=value;}}
    public void viewCurrentPairReport() {if(screen==Screen.TUNING_LAB && pairExperiment!=null) pairReport=pairExperiment.snapshot();}
    private String pairModelIdentity() {return PartsCatalog.hash(new com.google.gson.Gson().toJson(vehicleSpec));}
    public record PairExperimentReview(SetupRangeReview range,PartsCatalog.Field first,int firstPercent,PartsCatalog.Field second,int secondPercent,
                                       TuningTrial.Exercise exercise,TrackLayout track,ControlSettings controls,PairExperiment.Snapshot initial) {
        public PairExperimentReview {
            Objects.requireNonNull(range);Objects.requireNonNull(track);Objects.requireNonNull(controls);Objects.requireNonNull(initial);
            if(exercise==null || !initial.plan().equals(PairExperiment.Plan.capture(range.ranges(),first,firstPercent,second,secondPercent,initial.plan().modelIdentity())) || initial.conditions().exercise()!=exercise || !initial.conditions().controls().equals(controls.identity()) || !initial.conditions().trackId().equals(track.id) || !initial.conditions().geometry().equals(track.geometryKey()) || !initial.runs().isEmpty() || initial.lastAttempt()!=0) throw new IllegalArgumentException("Four-condition preview differs from frozen source");
        }
    }
    public PairExperimentReview reviewPairExperiment(SetupRangeReview range,PartsCatalog.Field first,int firstPercent,PartsCatalog.Field second,int secondPercent,TuningTrial.Exercise exercise) {
        if(!setupRangeCurrent(range) || exercise==null) return null;
        try {var track=partsTrialTrack();var control=controls.settings();var plan=PairExperiment.Plan.capture(range.ranges(),first,firstPercent,second,secondPercent,pairModelIdentity());var initial=new PairExperiment(track,control.identity(),exercise,plan,new SplittableRandom().nextLong()).snapshot();return new PairExperimentReview(range,first,firstPercent,second,secondPercent,exercise,track,control,initial);}
        catch(IllegalArgumentException | NullPointerException error) {notifyUser("四条件试车需两项都改变，且四个配置均符合原装备范围与组合规则。");return null;}
    }
    public boolean beginPairExperiment(PairExperimentReview expected) {
        if(expected==null || !setupRangeCurrent(expected.range()) || !expected.initial().plan().modelIdentity().equals(pairModelIdentity())) {notifyUser("装备草稿或车型配置已变化，请重新预览四条件试车。");return false;}
        var track=partsTrialTrack();if(!expected.track().id.equals(track.id) || !expected.track().geometryKey().equals(track.geometryKey()) || !expected.controls().equals(controls.settings())) {notifyUser("路线或操作设置已变化，请重新预览。");return false;}
        if(tuningSavePending() || pairTrials.pending()!=null || pairTrials.recoveryReviewNeeded()) {notifyUser("请先处理原试车报告的待存或重开恢复副本，再开始新的四条件试车。");return false;}if(!controlsReady(expected.controls())) return false;
        pairExperiment=PairExperiment.resume(expected.initial());pairReport=expected.initial();tuningReturn=Screen.PARTS;
        boolean saved=pairTrials.save(pairReport);openPairLab();if(!saved) {notifyUser(pairTrials.warning());return false;}startPairRun(0);return pairRunning();
    }
    public record PairRunReview(PairExperiment.Snapshot report,int index,TrackLayout track,ControlSettings controls) {
        public PairRunReview {if(report==null || index<0 || index>=12 || index>report.runs().size() || track==null || controls==null || !report.conditions().trackId().equals(track.id) || !report.conditions().geometry().equals(track.geometryKey()) || !report.conditions().controls().equals(controls.identity())) throw new IllegalArgumentException("Invalid four-condition run review");}
    }
    public PairRunReview reviewPairRun(PairExperiment.Snapshot report,int index) {
        if(online() || screen!=Screen.TUNING_LAB && screen!=Screen.RESULTS && !(pairRunning() && (screen==Screen.DRIVING || screen==Screen.PAUSED)) || report==null || !report.equals(pairReport) || !report.equals(pairTrials.find(report.id())) || index<0 || index>=12 || index>report.runs().size()) return null;
        if(pairTrials.pending()!=null || pairTrials.recoveryReviewNeeded() || tuningSavePending()) {notifyUser("请先处理待存报告或重开恢复副本，再继续实际试车。");return null;}
        var track=partsTrialTrack();var control=controls.settings();
        if(!report.plan().model().matches(vehicleSpec) || !report.plan().modelIdentity().equals(pairModelIdentity()) || !report.conditions().trackId().equals(track.id) || !report.conditions().geometry().equals(track.geometryKey()) || !report.conditions().controls().equals(control.identity())) {notifyUser("当前车型、路线版本或操作与报告不符；原报告仍可只读查看，请恢复相同条件再试车。");return null;}
        return new PairRunReview(report,index,track,control);
    }
    public boolean beginPairRun(PairRunReview expected) {
        if(expected==null) return false;var current=reviewPairRun(expected.report(),expected.index());
        if(current==null || !expected.controls().equals(current.controls()) || !expected.track().id.equals(current.track().id) || !expected.track().geometryKey().equals(current.track().geometryKey())) {notifyUser("四条件报告或背景已变化，请重新预览本组。");return false;}if(!controlsReady(expected.controls())) return false;
        pairExperiment=PairExperiment.resume(expected.report());pairReport=expected.report();var plan=pairReport;
        disposeSession();freezeControls(expected.controls());tuningControls=expected.controls();tuningTrack=expected.track();circuit=expected.track();mode=Mode.TUNING_TRIAL;attemptId=UUID.randomUUID().toString();pairIndex=expected.index();pairRunActive=true;pairLabRequested=true;tuningRecorded=false;
        tuningTrial=new TuningTrial(plan.conditions().exercise(),circuit,plan.tuning(pairIndex));createWorld(tuningTrial.exercise()!=TuningTrial.Exercise.FLOW);player=addCar("四条件 · "+plan.order().get(pairIndex).title,plan.tuning(pairIndex),-18,0,false,1,99999);var spawn=tuningTrial.spawn();player.recover(spawn.position(),spawn.forward());racers.get(0).previous=player.position();
        sessionTime=0;countdown=.8f;sessionLaps=1;newBest=false;helpVisible=false;screen=Screen.DRIVING;physics.setSpeed(1);cameraImmediate();return true;
    }
    public void startPairRun(int index) {if(pairExperiment==null) return;pairReport=pairExperiment.snapshot();var reviewed=reviewPairRun(pairReport,index);if(reviewed!=null) beginPairRun(reviewed);}
    public void nextPairRun() {if(pairPlan()!=null && !pairPlan().complete()) startPairRun(pairPlan().runs().size());else openPairLab();}
    public void retryPairSave() {boolean saved=pairTrials.retry();notifyUser(saved?(pairTrials.recoveryReviewNeeded()?pairTrials.warning():"四条件原报告已保存。"):pairTrials.warning());}
    public void cancelPairSave() {
        var pending=pairTrials.pending();if(pending==null) return;if(!pairTrials.cancelPending()) {notifyUser(pairTrials.warning());return;}if(pairExperiment!=null && pairExperiment.snapshot().id().equals(pending.id())) pairExperiment=null;if(pairReport!=null && pairReport.id().equals(pending.id())) pairReport=pairTrials.find(pending.id());notifyUser("未存候选已明确取消；此前成功保存的四条件报告保持。");
    }
    public boolean restorePairRecovery(PairTrialStore.Recovery expected) {
        if(online() || screen!=Screen.TUNING_LAB || !pairTrials.restoreRecovery(expected)) {notifyUser(pairTrials.warning());return false;}
        pairReport=pairTrials.pending();pairExperiment=PairExperiment.resume(pairReport);notifyUser(pairTrials.warning());return true;
    }
    public boolean discardPairRecovery(PairTrialStore.Recovery expected) {
        if(online() || screen!=Screen.TUNING_LAB || !pairTrials.discardRecovery(expected)) {notifyUser(pairTrials.warning());return false;}
        notifyUser(pairTrials.warning());return true;
    }
    public boolean acknowledgePairRecovery(PairTrialStore.Recovery expected) {
        if(online() || screen!=Screen.TUNING_LAB || !pairTrials.acknowledgeRecovery(expected)) {notifyUser(pairTrials.warning());return false;}
        notifyUser(pairTrials.warning());return true;
    }
    public boolean reloadPairRecovery() {
        if(online() || screen!=Screen.TUNING_LAB) return false;boolean read=pairTrials.reloadRecovery();notifyUser(read?"已明确重读恢复文件；没有自动恢复、发布或开始驾驶。":pairTrials.warning());return read;
    }
    public boolean removePairReport(PairExperiment.Snapshot expected) {
        if(online() || screen!=Screen.TUNING_LAB || !pairTrials.remove(expected)) {notifyUser(pairTrials.warning());return false;}
        if(pairExperiment!=null && pairExperiment.snapshot().id().equals(expected.id())) pairExperiment=null;if(pairReport!=null && pairReport.id().equals(expected.id())) pairReport=pairTrials.reports().isEmpty()?null:pairTrials.reports().get(0);return true;
    }
    public boolean removePairHistory(PairTrialRevision expected) {
        if(online() || screen!=Screen.TUNING_LAB || !pairTrials.removeHistory(expected)) {notifyUser(pairTrials.warning());return false;}
        notifyUser(pairTrials.pending()==null?"所选重测前历史已移除，当前报告保持。":"历史已移除；原待存仍需明确重试。");return true;
    }
    public enum PairShareOrigin {CURRENT,HISTORY,RECEIVED}
    public record PairShareExport(PairTrialShare share,PairShareOrigin origin) {
        public PairShareExport {Objects.requireNonNull(share);Objects.requireNonNull(origin);if(origin==PairShareOrigin.CURRENT && share.history()!=null || origin==PairShareOrigin.HISTORY && share.history()==null) throw new IllegalArgumentException("Export origin differs from frozen share");}
    }
    public boolean pairShareExportCurrent(PairShareExport expected) {
        if(expected==null) return false;var value=expected.share();return switch(expected.origin()) {
            case CURRENT->value.report().equals(pairTrials.find(value.report().id()));
            case HISTORY->pairTrials.history().contains(value.history());
            case RECEIVED->value.equals(receivedPairTrials.find(value.id()));
        };
    }
    public boolean exportPairTrial(PairShareExport expected,Path target,boolean replace) {
        if(online() || screen!=Screen.TUNING_LAB || !pairShareExportCurrent(expected)) {notifyUser("分享来源已变化或移除，请保留原草稿并重新核对来源。");return false;}
        try {
            var path=contentExportTarget(target);var root=store.directory().toAbsolutePath().normalize();
            if(path.startsWith(root) || path.getParent().toRealPath().startsWith(root.toRealPath())) throw new java.io.IOException("Choose an export outside player data");
            PairTrialShareCodec.write(path,expected.share(),replace);notifyUser("完整四条件文件已导出；提供者原声明保持，接收方另存收到库。");return true;
        }catch(java.io.IOException | RuntimeException error) {notifyUser("四条件文件未导出；同份分享草稿保持，请检查玩家资料目录外的独立目标后重试。");return false;}
    }
    public boolean receivePairTrial(PairTrialShare value) {
        if(online() || screen!=Screen.TUNING_LAB) return false;boolean saved=receivedPairTrials.receive(value);notifyUser(saved?"已存入独立收到库；没有写成本机测量、采用装备或开始驾驶。":receivedPairTrials.warning());return saved;
    }
    public boolean removeReceivedPairTrial(PairTrialShare expected) {
        if(online() || screen!=Screen.TUNING_LAB) return false;boolean saved=receivedPairTrials.remove(expected);notifyUser(saved?(receivedPairTrials.pending()==null?"已移除此收到副本；本机报告、装备及外部文件保持。":"已移除收到副本；原收到待存仍须明确重试。"):receivedPairTrials.warning());return saved;
    }
    public void backFromTuningLab() { preview();screen=tuningReturn;if(screen==Screen.SETUP_LIBRARY || screen==Screen.PARTS) physics.setSpeed(0); }
    public void viewTuningReport(String id) { tuningStore.reports().stream().filter(report->report.id().equals(id)).findFirst().ifPresent(report->tuningReport=report); }
    public void viewCurrentTuningPlan() { if(tuningExperiment!=null) tuningReport=tuningExperiment.snapshot(); }
    public void adoptTuningReport(TuningExperiment.Slot slot) {
        if(online() || screen!=Screen.TUNING_LAB || tuningReport==null) return;
        if(!vehicleSpec.physicsVersion.equals(tuningReport.conditions().physics())) {notifyUser("旧物理试车报告仅供查看，请在当前版本重新试车。");return;}
        var previous=store.profile.tuning;var next=(slot==TuningExperiment.Slot.A?tuningReport.a():tuningReport.b()).withLivery(previous.livery());if(!garageCandidateAllowed(next)) return;store.profile.tuning=next;if(!store.save()) {store.profile.tuning=previous;notifyUser(store.warning);return;}preview();screen=Screen.TUNING_LAB;physics.setSpeed(0);
        notifyUser("车库已采用报告中的方案 "+slot+"。");
    }
    public void beginTuningExperiment(TuningTrial.Exercise exercise) {
        if(online() || screen!=Screen.TUNING_LAB) return;
        if(tuningSavePending()) {notifyUser("请先重试保存已有报告，再建立新的对照试车。");return;}
        var a=store.profile.presets.get("A");var b=store.profile.presets.get("B");
        if(a==null || b==null) { notifyUser("请先在车库分别存为 A 和 B，再开始试车。");return; }
        if(!controlsReady(controls.settings())) return;
        tuningControls=controls.settings();tuningTrack=selectedCircuit();tuningExperiment=new TuningExperiment(tuningTrack,vehicleSpec.physicsVersion,exercise,a,b,new SplittableRandom().nextBoolean(),tuningControls.identity());tuningReport=tuningPlan();startTuningRun(0);
    }
    public void openTuningGuide() {
        if(online()) { notifyUser("请在单机车库使用调校向导。");return; }
        screen=Screen.TUNING_GUIDE;pressed.clear();helpVisible=false;physics.setSpeed(0);
    }
    public void applyGuidedTuning(TuningGuide.Goal goal,Tuning baseline) {
        if(!Objects.equals(baseline,store.profile.tuning)) return;
        applyGuidedTuning(reviewGuidedTuning(goal));
    }
    public record GuidedTuningReview(TuningGuide.Goal goal,Tuning baseline,com.f1car.persistence.FittedPartsStore.State fitting,
                                    String vehicle,String physics,String selectedTrack,List<com.f1car.persistence.CircuitStore.Saved> library,
                                    TrackLayout track,ControlSettings controls,TuningGuide.Preview preview) {}
    /** Pure in-memory preview; rendering must not read archives or hash definitions. */
    public GuidedTuningReview reviewGuidedTuning(TuningGuide.Goal goal) {
        if(goal==null || screen!=Screen.TUNING_GUIDE || online() || fittedParts.blocked() || fittedParts.pending()) return null;
        var fitting=fittedParts.state();var installed=fitting.installed();
        if(installed!=null && (!installed.compatible(vehicleSpec.id,vehicleSpec.physicsVersion) || !installed.accepts(store.profile.tuning))) return null;
        return new GuidedTuningReview(goal,store.profile.tuning,fitting,vehicleSpec.id,vehicleSpec.physicsVersion,store.profile.trackId,circuits.all(),selectedCircuit(),controls.settings(),TuningGuide.preview(goal,store.profile.tuning,installed));
    }
    public boolean guidedTuningCurrent(GuidedTuningReview expected) {
        if(expected==null || screen!=Screen.TUNING_GUIDE || online() || fittedParts.blocked() || fittedParts.pending()
            || !Objects.equals(expected.baseline(),store.profile.tuning) || !Objects.equals(expected.fitting(),fittedParts.state())
            || !Objects.equals(expected.vehicle(),vehicleSpec.id) || !Objects.equals(expected.physics(),vehicleSpec.physicsVersion)
            || !Objects.equals(expected.controls(),controls.settings()) || expected.track()==null || expected.goal()==null
            || !Objects.equals(expected.selectedTrack(),store.profile.trackId) || expected.library()!=circuits.all()) return false;
        return true;
    }
    private boolean guidedTuningAllowed(GuidedTuningReview expected) {
        if(!guidedTuningCurrent(expected) || !Objects.equals(expected.preview(),TuningGuide.preview(expected.goal(),expected.baseline(),expected.fitting().installed()))) {notifyUser("车库、装备、车型、赛道或操作已变化，请重新查看向导候选。");return false;}
        if(!expected.preview().available()) {notifyUser("此装备固定或这一方向已到范围边界，请换目标、微调或更换装备。");return false;}
        return garageCandidateAllowed(expected.preview().candidate());
    }
    public boolean applyGuidedTuning(GuidedTuningReview expected) {
        if(!guidedTuningAllowed(expected)) return false;
        var baseline=expected.baseline();var next=expected.preview().candidate();store.profile.tuning=next;
        if(!store.save()) { store.profile.tuning=baseline;notifyUser(store.warning);return false; }
        rebuildPreview();physics.setSpeed(0);notifyUser("已采用候选配置；建议先重复试车，再决定是否保留。");
        return true;
    }
    public void beginGuidedTuning(TuningGuide.Goal goal,Tuning baseline) {
        if(!Objects.equals(baseline,store.profile.tuning)) return;
        beginGuidedTuning(reviewGuidedTuning(goal));
    }
    public boolean beginGuidedTuning(GuidedTuningReview expected) {
        if(!guidedTuningAllowed(expected)) return false;
        if(tuningSavePending()) { notifyUser("请先到试车中心重试保存已有报告。");return false; }
        if(!controlsReady(expected.controls())) return false;
        tuningReturn=Screen.GARAGE;tuningControls=expected.controls();tuningTrack=expected.track();
        tuningExperiment=new TuningExperiment(tuningTrack,expected.physics(),expected.goal().exercise,expected.baseline(),expected.preview().candidate(),new SplittableRandom().nextBoolean(),tuningControls.identity());
        tuningPurpose=expected.fitting().installed()==null?null:TuningPurpose.Draft.guided(expected.goal(),expected.fitting().installed(),expected.baseline(),expected.preview().candidate()).bind(tuningExperiment.snapshot());
        tuningReport=tuningPlan();startTuningRun(0);
        return true;
    }
    public void openDrivingGuide() {
        if(online()) return;
        preview();screen=Screen.DRIVING_GUIDE;physics.setSpeed(0);helpVisible=false;pressed.clear();
    }
    public void chooseGuideTrack(String id) {
        if(screen!=Screen.DRIVING_GUIDE) return;
        preview(TrackCatalog.require(id).layout());screen=Screen.DRIVING_GUIDE;physics.setSpeed(0);
    }
    public void prepareGuideDrive(Mode mode) {
        if(screen!=Screen.DRIVING_GUIDE || mode!=Mode.FREE_CIRCUIT && mode!=Mode.RACE) return;
        String track=TrackCatalog.isBuiltin(circuit)?circuit.id:TrackCatalog.DEFAULT_ID;
        prepareMode(mode,null);chooseLaunchTrack(track);launchSetup.difficulty=0;launchSetup.laps=1;
    }
    public void startTuningRun(int index) {
        if(online() || tuningExperiment==null || index<0 || index>=6 || index>tuningPlan().runs().size()) return;
        if(tuningSavePending()) {notifyUser("请先在试车中心重试保存报告与连续记录，再继续测量。");return;}
        var plan=tuningPlan();
        if(!controlsReady(tuningControls)) return;
        disposeSession();freezeControls(tuningControls);circuit=tuningTrack;mode=Mode.TUNING_TRIAL;attemptId=UUID.randomUUID().toString();tuningIndex=index;tuningRecorded=false;
        tuningTrial=new TuningTrial(plan.conditions().exercise(),circuit,plan.tuning(index));createWorld(tuningTrial.exercise()!=TuningTrial.Exercise.FLOW);
        player=addCar("试车 · "+plan.order().get(index),plan.tuning(index),-18,0,false,1,99999);var spawn=tuningTrial.spawn();player.recover(spawn.position(),spawn.forward());racers.get(0).previous=player.position();
        sessionTime=0;countdown=.8f;sessionLaps=1;newBest=false;helpVisible=false;screen=Screen.DRIVING;physics.setSpeed(1);cameraImmediate();tuningReport=plan;
    }
    public void nextTuningRun() { if(tuningPlan()!=null && !tuningPlan().complete()) startTuningRun(tuningPlan().runs().size());else openTuningLab(); }
    public boolean tuningSavePending() {return tuningStore.pending() || tuningTraceStore.pending() || tuningStore.recoveryNeedsAttention() || tuningTraceStore.recoveryNeedsAttention();}
    public void retryTuningSave() {
        if(tuningStore.recoveryNeedsAttention() && !tuningStore.pending()) {notifyUser("请先在普通报告恢复页核对副本；未自动恢复或清理。" );return;}
        if(tuningTraceStore.recoveryNeedsAttention() && !tuningTraceStore.pending() && !tuningStore.pending()) {notifyUser("请先在原曲线恢复页核对副本，未自动恢复或清理。" );return;}
        if(!tuningTraceStore.checkpoint()) {notifyUser(tuningTraceStore.warning+" "+tuningTraceStore.recoveryWarning());return;}
        boolean report=tuningStore.retry(),trace=report && tuningStore.saveTraces(tuningTraceStore);
        notifyUser(!report?tuningStore.warning+" "+tuningStore.recoveryWarning():!trace?tuningStore.warning+" "+tuningTraceStore.warning+" "+tuningTraceStore.recoveryWarning():tuningTraceStore.recoveryNeedsAttention()?"报告已保存，原曲线副本仍须明确核对处理。":tuningStore.recoveryNeedsAttention()?"报告已保存，但恢复副本仍需明确核对清理。":"试车报告与连续记录已保存。");
    }
    public void reloadTuningRecovery() {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested) return;
        boolean recovery=tuningStore.reloadRecovery(),main=tuningStore.pending() || tuningStore.reload();
        notifyUser(recovery && main?"已明确重读报告恢复内容；未自动恢复、发布或清理。":tuningStore.warning+" "+tuningStore.recoveryWarning());
    }
    public void restoreTuningRecovery(TuningStore.Recovery expected) {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested) return;
        if(tuningStore.restoreRecovery(expected)) {tuningExperiment=null;tuningTrial=null;tuningReport=tuningStore.reports().get(0);tuningPurpose=tuningStore.purpose(tuningReport.id());}
        notifyUser(tuningStore.warning+" "+tuningStore.recoveryWarning());
    }
    public void acknowledgeTuningRecovery(TuningStore.Recovery expected) {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested) return;
        tuningStore.acknowledgeRecovery(expected);notifyUser(tuningStore.warning+" "+tuningStore.recoveryWarning());
    }
    public void discardTuningRecovery(TuningStore.Recovery expected) {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested) return;
        tuningStore.discardRecovery(expected);notifyUser(tuningStore.warning+" "+tuningStore.recoveryWarning());
    }
    public void cancelTuningPending(TuningStore.Archive expected) {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested || !Objects.equals(expected,tuningStore.candidate())) return;
        if(tuningStore.cancelPending()) {tuningExperiment=null;tuningTrial=null;tuningReport=tuningStore.reports().isEmpty()?null:tuningStore.reports().get(0);tuningPurpose=tuningReport==null?null:tuningStore.purpose(tuningReport.id());}
        notifyUser(tuningStore.warning+" "+tuningStore.recoveryWarning());
    }
    public void reloadTuningTraceRecovery() {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested) return;
        boolean source=tuningStore.pending() || tuningStore.reload(),main=tuningTraceStore.pending() || tuningTraceStore.reload(),copy=tuningTraceStore.reloadRecovery();
        notifyUser(source && main && copy?"已明确重读原曲线与报告；没有自动恢复、发布或清理。":tuningStore.warning+" "+tuningTraceStore.warning+" "+tuningTraceStore.recoveryWarning());
    }
    public void restoreTuningTraceRecovery(TuningTraceStore.Recovery expected) {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested) return;
        if(tuningStore.pending() || !tuningStore.reload()) {notifyUser("先处理并保存匹配的报告；原曲线副本保持。" );return;}
        tuningTraceStore.restoreRecovery(expected,tuningStore.accepted().reports());notifyUser(tuningTraceStore.warning+" "+tuningTraceStore.recoveryWarning());
    }
    public void acknowledgeTuningTraceRecovery(TuningTraceStore.Recovery expected) {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested || tuningStore.pending()) return;
        tuningStore.acknowledgeTraceRecovery(tuningTraceStore,expected);
        notifyUser(tuningStore.warning+" "+tuningTraceStore.warning+" "+tuningTraceStore.recoveryWarning());
    }
    public void discardTuningTraceRecovery(TuningTraceStore.Recovery expected,boolean memory) {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested) return;
        if(memory) tuningTraceStore.cancelPending(expected);else tuningTraceStore.discardRecovery(expected);
        notifyUser(tuningTraceStore.warning+" "+tuningTraceStore.recoveryWarning());
    }
    public void stageTuningReportFromTraceRecovery(TuningTraceStore.Recovery expected,TuningStore.Archive original) {
        if(online() || screen!=Screen.TUNING_LAB || pairLabRequested || expected==null || original==null || tuningStore.pending() || !Objects.equals(original,tuningStore.accepted()) || !tuningTraceStore.verifyRecovery(expected)) return;
        if(!tuningStore.reloadRecovery() || tuningStore.recoveryNeedsAttention()) {notifyUser("先处理已有普通报告恢复副本；原曲线与其中报告保持。" );return;}
        if(!tuningStore.reload() || !original.equals(tuningStore.accepted()) || !expected.reportBaseMatches(tuningStore.accepted().reports()) || expected.reportAvailable(tuningStore.accepted().reports())) {notifyUser("已存报告与原基准不符，或原次已存在；保持原文，请明确重读核对。" );return;}
        tuningStore.stage(expected.report(),expected.purpose());tuningExperiment=null;tuningTrial=null;tuningReport=expected.report();tuningPurpose=expected.purpose();
        notifyUser("已从原曲线副本恢复所属报告为待存；尚未发布，请先明确保存报告，再核对恢复原曲线。" );
    }
    public void setHumor() { store.profile.humor = !store.profile.humor; radio = ""; store.save(); }
    public void setSound() { store.profile.sound = !store.profile.sound; saveAudioSettings(); }
    public void setMusic() { store.profile.music=!store.profile.music;saveAudioSettings(); }
    public void changeMusicVolume(float delta) { store.profile.musicVolume=Math.max(0,Math.min(1,store.profile.musicVolume+delta));saveAudioSettings(); }
    private void saveAudioSettings() { if(!store.save()) notifyUser(store.warning); }
    public boolean audioAvailable() { return audio!=null; }
    public void previewMusic() {
        if(!store.profile.music || store.profile.musicVolume<=0) { notifyUser("请先开启背景音乐并调高音乐音量。");return; }
        musicPreviewIndex=(musicPreviewIndex+1)%MusicTrack.values().length;musicPreviewLeft=12;
    }
    public void stopMusicPreview() { musicPreviewLeft=0; }
    public boolean musicPreviewing() { return screen==Screen.SETTINGS && musicPreviewLeft>0; }
    private boolean inPausedSettings() {
        if (previousScreen != Screen.PAUSED) return false;
        Screen page = screen == Screen.KEYBOARD ? keyboardReturn : screen;
        return page == Screen.SETTINGS || page == Screen.CONTROLS && controlsReturn == Screen.SETTINGS;
    }
    public MusicTrack musicTrack() {
        if(musicPreviewing()) return MusicTrack.values()[musicPreviewIndex];
        if(screen==Screen.MINI4WD || screen==Screen.MINI_HOME) return MusicTrack.MINI;
        if(screen==Screen.MAP_EDITOR || screen==Screen.CREATION || screen==Screen.GARAGE || screen==Screen.TUNING_LAB || screen==Screen.TUNING_GUIDE || screen==Screen.SETUP_LIBRARY || screen==Screen.WORKSHOP) return MusicTrack.WORKSHOP;
        if(screen==Screen.DRIVING || screen==Screen.PAUSED || screen==Screen.REPLAY || screen==Screen.TELEMETRY || screen==Screen.GHOSTS
            || inPausedSettings()) return MusicTrack.RACE;
        return MusicTrack.MENU;
    }
    private void updateAudio(float dt) {
        if(screen!=Screen.SETTINGS) musicPreviewLeft=0;else musicPreviewLeft=Math.max(0,musicPreviewLeft-dt);
        var current=audio;
        if(current==null) return;
        boolean driving=screen==Screen.DRIVING && !helpVisible && player!=null;
        boolean duck=screen==Screen.PAUSED || screen==Screen.DRIVING && (helpVisible || countdown>0)
            || !musicPreviewing() && inPausedSettings();
        try {
            current.update(dt,musicTrack(),store.profile.music,store.profile.musicVolume,store.profile.sound,store.profile.volume,
                windowFocused(),driving,player==null?0:player.speed(),player==null?0:player.throttle,duck);
        } catch(RuntimeException error) {
            disableAudio(current);
        }
    }
    private void withAudio(Consumer<GameAudio> operation) {
        var current=audio;
        if(current==null) return;
        try { operation.accept(current); }
        catch(RuntimeException unavailable) { disableAudio(current); }
    }
    private void disableAudio(GameAudio current) {
        if(audio!=current) return;
        audio=null;
        current.stop();
        notifyUser("音频设备暂不可用，其他功能可继续使用。");
    }
    private void playCue(GameAudio.Cue cue) {
        withAudio(current->current.cue(cue,store.profile.sound,store.profile.volume));
    }
    public void toggleGhost() { store.profile.ghostEnabled=!store.profile.ghostEnabled;store.save(); }
    public TelemetryBook telemetryBook() { return telemetryBook; }
    public void openGhosts() {
        if(online() || timeTrial==null || screen!=Screen.DRIVING && screen!=Screen.PAUSED) return;
        screen=Screen.GHOSTS;physics.setSpeed(0);pressed.clear();helpVisible=false;
        String message=refreshGhostChoices();
        if(!message.isEmpty()) notifyUser(message);
    }
    private String refreshGhostChoices() {
        ghostExchange.reload();
        if(!ghostExchange.warning().isEmpty()) return ghostExchange.warning();
        if(selectedGhost!=null && !selectedGhost.entry().equals(ghostExchange.find(selectedGhost.id()))) {
            clearGhostSelection();
            return "原选幽灵已移出幽灵库或索引已改变，下一圈使用个人最佳。";
        }
        return "";
    }
    public void backFromGhosts() { if(screen==Screen.GHOSTS) { screen=Screen.PAUSED;pressed.clear();physics.setSpeed(0); } }
    public void retryTimeTrialBest() {
        if(timeTrial!=null) retryTimeTrialBest(timeTrial,timeTrial.pendingBest());
    }
    public void retryTimeTrialBest(TimeTrialSession viewed,GhostLap displayedBest) {
        if(screen!=Screen.GHOSTS || timeTrial==null || displayedBest==null) return;
        if(timeTrial!=viewed || timeTrial.pendingBest()!=displayedBest) {
            notifyUser("这份待保存成绩已变化，请重新查看。");
            return;
        }
        timeTrial.retryBest();
        newBest=timeTrial.last()!=null && timeTrial.last().saved();
        String notice=timeTrial.consumeNotice();
        if(!notice.isEmpty()) notifyUser(notice);
    }
    public List<GhostStore.PendingBest> pendingTimeTrialBests() {return personalBests.pendingBests();}
    public void retryTimeTrialBest(GhostStore.PendingBest expected) {
        if(screen!=Screen.GHOSTS || timeTrial==null || expected==null) return;
        String key=expected.lap().key();
        if(personalBests.pendingBest(key)!=expected) {
            notifyUser("这份待保存成绩已变化，请重新查看。");
            return;
        }
        if(timeTrial.key().equals(key)) {
            retryTimeTrialBest(timeTrial,expected.lap());
            return;
        }
        var write=personalBests.writeBest(expected.lap());
        notifyUser(switch(write.status()) {
            case SAVED -> "已保存原条件的最佳圈 · "+expected.trackName()+" · "+PracticeSession.format(expected.lap().duration());
            case UNCHANGED -> "已保留原条件下用时更短或相同的个人最佳圈。";
            case FAILED -> personalBests.warning()+" 可再次重试原圈。";
        });
    }
    public void importGhost(Path path) {
        if(screen!=Screen.GHOSTS || timeTrial==null) return;
        try {
            var entry=ghostExchange.importFile(path);String mismatch=GhostChallenge.mismatch(entry.conditions(),timeTrial.conditions(),entry.routeGeometry(),timeTrial.routeGeometry());
            notifyUser(mismatch.isEmpty()?"幽灵已导入，请选择参照；不会修改个人最佳。":"已收入幽灵库，当前不可选："+mismatch);
        } catch(java.io.IOException | RuntimeException error) { notifyUser("幽灵导入失败：文件无效、库已满或无法保存；原纪录保留。"); }
    }
    public void selectGhostReference(String id) {
        if(timeTrial==null || online()) return;
        if(id==null) {
            clearGhostSelection();
            String warning=timeTrial.refreshPersonalBest();
            notifyUser(warning.isEmpty()?"已选择个人最佳；下一圈生效。":"已选择个人最佳；"+warning);
            return;
        }
        var entry=ghostExchange.find(id);
        if(entry==null) {notifyUser("这份幽灵已不在当前库中。");return;}
        String mismatch=GhostChallenge.mismatch(entry.conditions(),timeTrial.conditions(),entry.routeGeometry(),timeTrial.routeGeometry());
        if(!mismatch.isEmpty()) {notifyUser("参照不可用："+mismatch);return;}
        try {
            loadGhostReference(id);
            notifyUser("已选择好友参照；下一圈或重开后生效。");
        } catch(java.io.IOException | RuntimeException error) { notifyUser("好友幽灵无法读取，保留当前参照。"); }
    }
    private void restoreGhostReference() {
        if(selectedGhost==null) return;
        var source=selectedGhost.source();
        if(!GhostChallenge.mismatch(source.conditions(),timeTrial.conditions(),source.routeGeometry(),timeTrial.routeGeometry()).isEmpty())
            return;
        try {
            loadGhostReference(selectedGhost.id());
        } catch(java.io.IOException | RuntimeException error) {
            clearGhostSelection();
            notifyUser("原选好友幽灵无法读取，本次使用个人最佳。");
        }
    }
    private void loadGhostReference(String id) throws java.io.IOException {
        var source=ghostExchange.load(id);
        var entry=Objects.requireNonNull(ghostExchange.find(id));
        var prior=selectedGhost;
        boolean reusable=prior!=null && prior.entry().equals(entry) && prior.source()==source && prior.reference().key().equals(timeTrial.key());
        var reference=reusable?prior.reference():source.referenceFor(timeTrial.conditions(),timeTrial.routeGeometry());
        String label=source.author().isBlank()?"未署名好友":source.author();
        timeTrial.selectReference(reference,label,id);
        if(!reusable) selectedGhost=new SelectedGhost(entry,source,reference);
    }
    private void clearGhostSelection() {
        selectedGhost=null;
        timeTrial.selectReference(null,null,null);
    }
    public void removeGhost(String id) {
        if(screen!=Screen.GHOSTS || timeTrial==null) return;
        try {
            ghostExchange.remove(id);
            if(selectedGhost!=null && id.equals(selectedGhost.id())) clearGhostSelection();
            var warning=ghostExchange.warning();
            notifyUser(warning.isEmpty()?"已从幽灵库移除；参考圈在下一圈更新。":warning);
        }
        catch(java.io.IOException | RuntimeException error) { notifyUser("未能完成移除，请重新打开幽灵库核对当前记录。"); }
    }
    public void exportGhost(Path path,String author,boolean reply) {
        exportGhost(path,author,reply,false);
    }
    public void exportGhost(Path path,String author,boolean reply,boolean replace) {
        if(screen!=Screen.GHOSTS || timeTrial==null) return;
        try {
            exportReviewedGhost(ExportDestination.forWrite(path,replace),author,reply);
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("无法核对幽灵导出文件，请重新选择目标。");
        }
    }
    public void exportReviewedGhost(ExportDestination destination,String author,boolean reply) {
        if(screen!=Screen.GHOSTS || timeTrial==null) return;
        if(!reply) {
            String warning=timeTrial.refreshPersonalBest();
            if(!warning.isEmpty()) {notifyUser("个人最佳未导出。"+warning);return;}
        }
        var lap=reply?timeTrial.completedLap():timeTrial.best();String source=reply?timeTrial.completedReplyTo():null;
        if(lap==null || reply && source==null) { notifyUser(reply?"请先对好友参照完成一个有效圈。":"请先完成一个有效圈并保存个人最佳。");return; }
        try {
            GhostExchangeStore.writeFile(new GhostChallenge(2,timeTrial.conditions(),circuit.name,author,source,lap,timeTrial.routeGeometry()),
                    contentExportTarget(destination.path()),destination);
            notifyUser(reply?"已导出实际完成圈的回传文件；接收方仍只将其作为参照。":"个人最佳幽灵已导出。");
        } catch(java.io.IOException error) {
            notifyUser("幽灵未导出，请重新核对目标文件后再试。");
        } catch(RuntimeException error) {
            notifyUser("幽灵导出失败，请检查作者署名和目标文件。");
        }
    }
    public TelemetryComparison telemetryComparison() { return telemetryComparison; }
    public EquipmentLap latestEquipmentLap() {return timeTrial==null || timeTrial.telemetry()==null?null:timeTrial.telemetry().lastEquipmentLap();}
    public boolean receiveEquipmentLap(EquipmentLapShare value) {
        if(screen!=Screen.TELEMETRY || online()) return false;boolean saved=receivedEquipmentLaps.receive(value);notifyUser(saved?"已收录提供者声明；没有写入本机测量或采用装备。":receivedEquipmentLaps.warning());return saved;
    }
    public boolean acceptTuningStudy(TuningLapStudyStore.Review reviewed) {
        if(screen!=Screen.TUNING_LAB || online() || reviewed==null) return false;
        boolean saved=tuningStudies.accept(reviewed);notifyUser(saved?"完整对照已收录；报告、圈库、车库与成绩分别保持。":tuningStudies.warning());return saved;
    }
    public boolean removeTuningStudy(TuningLapStudyStore.Entry expected) {
        if(screen!=Screen.TUNING_LAB || online()) return false;boolean saved=tuningStudies.remove(expected);notifyUser(saved?"仅移除此份对照副本；原报告、整圈与外部文件保持。":tuningStudies.warning());return saved;
    }
    public boolean exportTuningStudy(TuningLapStudyStore.Entry expected,Path target,boolean replace) {
        if(screen!=Screen.TUNING_LAB || online() || expected==null || !expected.equals(tuningStudies.find(expected.study().id()))) {notifyUser("对照记录已变化，请重新预览导出。");return false;}
        try {
            var path=contentExportTarget(target);var root=store.directory().toAbsolutePath().normalize();
            if(path.startsWith(root) || path.getParent().toRealPath().startsWith(root.toRealPath())) throw new java.io.IOException("Choose an export outside player data");
            TuningLapStudyCodec.write(path,expected.study(),replace);notifyUser("完整对照已导出；原来源声明保留，接收方不会视为本机测量。");return true;
        }catch(java.io.IOException | RuntimeException error) {notifyUser("对照未导出；请选择玩家资料目录外的独立文件，检查大小与路径。");return false;}
    }
    public boolean exportEquipmentLap(EquipmentLapShare value,Path target,boolean replace) {
        try {
            var path=target.toAbsolutePath().normalize();var root=store.directory().toAbsolutePath().normalize();
            // Export is deliberately outside the player's data root, including its ordinary archives.
            if(path.startsWith(root) || Files.exists(path) && (Files.exists(equipmentLaps.file()) && Files.isSameFile(path,equipmentLaps.file()) || Files.exists(receivedEquipmentLaps.file()) && Files.isSameFile(path,receivedEquipmentLaps.file()))) throw new java.io.IOException("Choose an export outside player data");
            EquipmentLapShareCodec.write(path,value,replace);notifyUser("已导出完整装备圈；署名和说明是提供者声明。");return true;
        }catch(java.io.IOException | RuntimeException error) {notifyUser("装备圈未导出；请选择玩家资料目录之外的文件，检查大小和目标路径。");return false;}
    }
    public boolean captureEquipmentLap(EquipmentLap expected) {
        if(screen!=Screen.TELEMETRY || online() || expected==null || expected!=latestEquipmentLap()) {notifyUser("完整圈或当前场次已变化，请重新查看。");return false;}
        boolean saved=equipmentLaps.capture(expected);notifyUser(saved?"已明确收录装备整圈；车库与原遥测保持。":equipmentLaps.warning());return saved;
    }
    public TrackLayout telemetryTrack() { return telemetryTrack; }
    public TrackLayout equipmentLapTrack(TelemetryLap.Conditions source) {
        if(source.matchesTrack(circuit)) return circuit;
        for(var entry:TrackCatalog.all()) {
            if(source.matchesTrack(entry.layout())) return entry.layout();
        }
        return circuits.trackForTelemetry(source);
    }

    private TrackLayout equipmentSectionLaunchTrack(TelemetryLap.Conditions source) {
        return source.trackId().startsWith("user-")?circuits.currentTelemetryTrack(source):equipmentLapTrack(source);
    }
    public float telemetryDistance() { return telemetryDistance; }
    public boolean telemetryHasReference() { return telemetryComparison!=null && !telemetryComparison.a().id().equals(telemetryComparison.b().id()); }
    public void openTelemetry() {
        if(online()) return;
        telemetryReturn=screen==Screen.DRIVING || screen==Screen.PAUSED?screen:Screen.COURSES;telemetryTrack=circuit;
        var conditions=timeTrial!=null && timeTrial.telemetry()!=null?timeTrial.telemetry().conditions():TelemetryLap.Conditions.of(circuit,vehicleSpec.physicsVersion,store.profile.tuning,controls.settings().identity());
        telemetryBook=telemetryStore.load(conditions);telemetryA=telemetryB=null;telemetryCursor(0);rebuildTelemetry();
        physics.setSpeed(0);blockDrivingInput();helpVisible=false;screen=Screen.TELEMETRY;
        if(!telemetryStore.warning().isEmpty()) notifyUser(telemetryStore.warning());
    }
    private void rebuildTelemetry() {
        var laps=telemetryBook.all();if(laps.isEmpty()) { telemetryComparison=null;telemetryBrakeIndex=-1;return; }
        if(laps.stream().noneMatch(lap->lap.id().equals(telemetryA))) telemetryA=laps.get(0).id();
        if(telemetryA.equals(telemetryB) || laps.stream().noneMatch(lap->lap.id().equals(telemetryB)))
            telemetryB=laps.stream().filter(lap->!lap.id().equals(telemetryA)).min(Comparator.comparingDouble(TelemetryLap::duration)).map(TelemetryLap::id).orElse(telemetryA);
        var a=laps.stream().filter(lap->lap.id().equals(telemetryA)).findFirst().orElseThrow();var b=laps.stream().filter(lap->lap.id().equals(telemetryB)).findFirst().orElseThrow();
        if(telemetryComparison!=null && telemetryComparison.a()==a && telemetryComparison.b()==b) return;
        telemetryBrakeIndex=-1;
        telemetryComparison=new TelemetryComparison(a,b);
    }
    public void cycleTelemetry(boolean reference) {
        if(telemetryBook==null) return;
        var laps=telemetryBook.all();
        if(laps.size()<2) return;
        String current=reference?telemetryB:telemetryA;int index=0;
        for(int i=0;i<laps.size();i++) if(laps.get(i).id().equals(current)) index=i;
        do { index=(index+1)%laps.size(); } while(reference && laps.get(index).id().equals(telemetryA));
        if(reference) telemetryB=laps.get(index).id();else telemetryA=laps.get(index).id();rebuildTelemetry();
    }
    public void telemetryCursor(float distance) {
        if(telemetryBook==null || !Float.isFinite(distance)) return;
        telemetryBrakeIndex=-1;
        telemetryDistance=Math.max(0,Math.min(telemetryBook.conditions().length(),distance));
    }
    public TelemetryLap.Sample telemetryBrakeSample() {
        return telemetryBrakeIndex<0?null:telemetryComparison.brakePressesByDistance().get(telemetryBrakeIndex);
    }
    public void nextTelemetryBrake() {
        if(telemetryComparison==null) return;
        var presses=telemetryComparison.brakePressesByDistance();
        if(presses.isEmpty()) return;
        float length=telemetryComparison.a().conditions().length();
        if(telemetryBrakeIndex>=0) telemetryBrakeIndex=(telemetryBrakeIndex+1)%presses.size();
        else {
            int low=0,high=presses.size();
            while(low<high) {
                int middle=(low+high)/2;
                if(Math.min(length,presses.get(middle).distance())<=telemetryDistance) low=middle+1;
                else high=middle;
            }
            telemetryBrakeIndex=low==presses.size()?0:low;
        }
        telemetryDistance=Math.min(length,presses.get(telemetryBrakeIndex).distance());
    }
    public void retryTelemetrySave() {
        if(screen!=Screen.TELEMETRY || telemetryBook==null) return;
        int groups=telemetryStore.pendingCount();
        if(groups==0) {
            reloadTelemetryBook();
            notifyUser(telemetryStore.warning().isEmpty()?"当前没有待存遥测。":telemetryStore.warning());
            return;
        }
        telemetryStore.retry();
        int remaining=telemetryStore.pendingCount();
        String notice="已保存 "+(groups-remaining)+" 组遥测。";
        if(remaining>0) notice+="仍有 "+remaining+" 组待存。"+telemetryStore.warning();
        reloadTelemetryBook();
        notifyUser(notice);
    }
    public void retryTelemetrySave(TelemetryBook displayed) {
        if(screen!=Screen.TELEMETRY || telemetryBook==null || telemetryBook!=displayed) return;
        String notice=telemetryStore.retry(displayed)?"本组遥测已保存。":telemetryStore.warning();
        reloadTelemetryBook();
        notifyUser(notice);
    }
    public void reviewTelemetryMerge() {
        if(screen!=Screen.TELEMETRY || telemetryBook==null || hud==null) return;
        var conditions=telemetryBook.conditions();
        var review=telemetryStore.reviewMerge(conditions);
        if(review==null) {
            notifyUser(telemetryStore.warning());
            return;
        }
        String detail="当前磁盘近期 "+review.saved().recent().size()+" 圈 · 本机待存近期 "+review.local().recent().size()
                +" 圈\n合并保留近期 "+review.merged().recent().size()+" 圈和最快圈 "
                +PracticeSession.format(review.merged().best().duration())+"。"
                +(review.trimmedForSize()>0?"\n因单组档案上限，省略最旧的 "+review.trimmedForSize()+" 圈近期记录。":"");
        hud.confirm("合并这组遥测？",detail,()->{
            if(screen!=Screen.TELEMETRY || telemetryBook==null || !conditions.equals(telemetryBook.conditions())) return;
            if(telemetryStore.commitMerge(review)) {
                reloadTelemetryBook();
                notifyUser("已合并同条件的近期遥测与最快圈。");
            }else notifyUser(telemetryStore.warning());
        });
    }
    private void reloadTelemetryBook() {
        if(telemetryBook==null) return;
        telemetryBook=telemetryStore.load(telemetryBook.conditions());
        rebuildTelemetry();
    }
    public void exportTelemetry(Path target) {
        if(telemetryComparison!=null) exportTelemetry(telemetryComparison.a(),target);
    }
    public void exportTelemetry(TelemetryLap lap,Path target) {
        exportTelemetry(lap,target,false);
    }
    public void exportTelemetry(TelemetryLap lap,Path target,boolean replace) {
        try {
            telemetryStore.exportCsv(lap,telemetryExportTarget(target,replace),replace);
            notifyUser("当前圈操作数据已导出 CSV。");
        }
        catch(java.io.IOException | RuntimeException error) { notifyUser("遥测导出失败，请检查所选目录。"); }
    }
    public void exportReviewedTelemetry(TelemetryLap selected,ExportDestination destination) {
        if(screen!=Screen.TELEMETRY) return;
        if(telemetryComparison==null || !telemetryComparison.a().equals(selected)) {
            notifyUser("A 圈已变化，请重新选择要导出的圈。");
            return;
        }
        try {
            var target=telemetryExportTarget(destination.path(),destination.replacing());
            telemetryStore.exportReviewedCsv(selected,target,destination);
            notifyUser("当前圈操作数据已导出 CSV。");
        } catch(java.io.IOException | RuntimeException error) {
            notifyUser("遥测未导出，请重新核对目标文件和目录。");
        }
    }
    private Path telemetryExportTarget(Path target,boolean replace) throws java.io.IOException {
        com.f1car.persistence.CircuitShowcaseFiles.extension(target,".csv");
        return replace?contentExportTarget(target):target;
    }
    public void backFromTelemetry() {
        telemetryBook=null;
        telemetryComparison=null;
        telemetryTrack=null;
        telemetryA=telemetryB=null;
        telemetryBrakeIndex=-1;
        telemetryDistance=0;
        if(telemetryReturn==Screen.DRIVING) {
            screen=Screen.PAUSED;
            resume();
        } else {
            screen=telemetryReturn;
            pressed.clear();
            physics.setSpeed(screen==Screen.PAUSED?0:1);
        }
    }
    public boolean replayViewing() { return screen==Screen.REPLAY && replayPlayer!=null; }
    public boolean eventReplayViewing() {return replayViewing() && replayClose==Screen.EVENTS;}
    public ReplayPlayer replayPlayer() { return replayPlayer; }
    public boolean hasCurrentReplay() { return replayRecorder!=null; }
    public void openReplays() {
        if(online()) { notifyUser("请先退出房间，再查看本地回看。");return; }
        cancelReplayLoading();
        if(!replayStore.saving()) replayStore.reload();
        replayReturn=screen;screen=Screen.REPLAYS;pressed.clear();helpVisible=false;physics.setSpeed(0);
    }
    public void backFromReplays() { cancelReplayLoading();replayPlayer=null;screen=replayReturn;pressed.clear();physics.setSpeed(screen==Screen.PAUSED || screen==Screen.RESULTS?0:1); }
    public void closeReplay() { cancelReplayLoading();replayPlayer=null;screen=replayClose;replayClose=Screen.REPLAYS;pressed.clear();if(hud!=null) hud.resetCamera(); }
    public void openReplay(String id) {
        if(online()) {notifyUser("请先退出房间，再查看本地回看。");return;}
        openStoredPlayback(id,null);
    }
    public boolean openEventRaceReplay(EventRoundBrief round) {
        if(!currentEventReplayRound(round)) {
            notifyUser("赛事来源已变化，请重新打开对应站次的回顾。");
            return false;
        }
        String id=round.replayId();
        if(id==null || !replayStore.contains(id)) {
            notifyUser(id==null?"本站未关联视觉回看。":"本站回看已不在本机回看库。");
            return false;
        }
        return openStoredPlayback(id,round);
    }

    private boolean openStoredPlayback(String id,EventRoundBrief round) {
        cancelReplayLoading();
        var result=replayStore.loadPlayback(id,round);
        if(result.player()==null) {notifyUser(result.warning());return false;}
        showReplay(result.player(),round);
        return true;
    }

    private boolean currentEventReplayRound(EventRoundBrief round) {
        var event=activeEvent();
        return screen==Screen.EVENTS && !online() && round!=null && event!=null && round.source().equals(event.state());
    }

    public void requestReplay(String id) {
        if(screen==Screen.REPLAYS && !online()) requestReplay(id,null,()->true,false);
    }

    public void requestSavedReplay(ReplayStore.Entry expected) {
        requestLibraryPlayback(expected,true);
    }

    public void requestListedReplay(ReplayStore.Entry expected) {
        requestLibraryPlayback(expected,false);
    }

    private void requestLibraryPlayback(ReplayStore.Entry expected,boolean savedOnly) {
        if(screen!=Screen.REPLAYS || online()) return;
        cancelReplayLoading();
        var request=savedOnly?replayStore.requestSavedPlayback(expected):replayStore.requestListedPlayback(expected);
        if(request==null) {notifyUser("回看条目已变化或暂时无法打开，请重新选择。");return;}
        replayOpen=new ReplayOpen(screen,null,()->true,false,request);
    }

    public void requestEventRaceReplay(EventRoundBrief round,java.util.function.BooleanSupplier stillSelected,boolean showMarkers) {
        if(!currentEventReplayRound(round) || !stillSelected.getAsBoolean()) {
            notifyUser("赛事来源已变化，请重新打开对应站次的回顾。");
            return;
        }
        requestReplay(round.replayId(),round,()->stillSelected.getAsBoolean() && currentEventReplayRound(round),showMarkers);
    }

    public void requestArchivedEventReplay(EventHistoryStore.Entry entry,EventRoundBrief round,java.util.function.BooleanSupplier stillSelected) {
        java.util.function.BooleanSupplier current=()->screen==Screen.EVENTS && !online() && entry!=null && round!=null
                && entry.event().equals(round.source()) && entry.equals(eventHistory.find(entry.id())) && stillSelected.getAsBoolean();
        if(!current.getAsBoolean()) {
            notifyUser("已选赛事档案或站次已变化，请重新打开。");
            return;
        }
        requestReplay(round.replayId(),round,current,false);
    }

    private void requestReplay(String id,EventRoundBrief round,java.util.function.BooleanSupplier stillSelected,boolean showMarkers) {
        cancelReplayLoading();
        if(id==null || !replayStore.contains(id)) {notifyUser("所选回看已不在本机回看库。");return;}
        var request=replayStore.requestPlayback(id,round,showMarkers);
        if(request==null) {notifyUser("当前无法开始读取回看，请重试。");return;}
        replayOpen=new ReplayOpen(screen,round,stillSelected,showMarkers,request);
    }

    public boolean replayLoading(String id) {
        return replayOpen!=null && replayOpen.origin()==screen && replayOpen.request().id().equals(id);
    }

    public void cancelReplayLoading() {
        cancelReplayMarkers();
        if(replayOpen==null) return;
        replayOpen=null;
        replayStore.cancelPlayback();
    }

    public void cancelEventRaceReplay(EventRoundBrief round) {
        if(round!=null && replayOpen!=null && replayOpen.round()==round) cancelReplayLoading();
    }

    private void updateReplayLoading() {
        var opened=replayOpen;
        if(opened==null) return;
        if(screen!=opened.origin() || online() || !opened.stillSelected().getAsBoolean()) {
            cancelReplayLoading();
            return;
        }
        if(helpVisible || (hud!=null && hud.confirming())) return;
        var result=replayStore.pollPlayback(opened.request());
        if(result==null) return;
        replayOpen=null;
        if(result.player()==null) {notifyUser(result.warning());return;}
        showReplay(result.player(),opened.round());
        if(opened.showMarkers() && hud!=null) hud.showReplayMarkers();
    }

    public boolean replayMarkersLoading() {return replayViewing() && replayMarkersFor==replayPlayer;}

    public boolean requestReplayMarkers() {
        if(!replayViewing() || helpVisible || replayMarkersLoading() || replayPlayer.preparedReviewTimeline()!=null) return false;
        if(!replayStore.requestReviewTimeline(replayPlayer.replay())) {
            notifyUser("当前无法整理回看标记，请重试。");
            return false;
        }
        replayMarkersFor=replayPlayer;
        return true;
    }

    public void cancelReplayMarkers() {
        if(replayMarkersFor==null) return;
        replayMarkersFor=null;
        replayStore.cancelReviewTimeline();
    }

    private void updateReplayMarkers() {
        var player=replayMarkersFor;
        if(player==null) return;
        if(!replayMarkersLoading()) {cancelReplayMarkers();return;}
        if(helpVisible || hud!=null && !hud.canPresentReplayMarkers()) return;
        var result=replayStore.pollReviewTimeline();
        if(result==null) return;
        replayMarkersFor=null;
        if(result.timeline()==null) {notifyUser(result.warning());return;}
        player.acceptReviewTimeline(result.timeline());
        if(hud!=null) hud.showReplayMarkers();
    }

    private void viewReplay(RaceReplay replay) {
        ReplayPlayer player;
        try {player=new ReplayPlayer(replay);}
        catch(IllegalArgumentException error) {notifyUser("回看地图无法重建，原文件保留。");return;}
        showReplay(player,null);
    }
    private void showReplay(ReplayPlayer player,EventRoundBrief round) {
        cancelReplayLoading();
        if(round==null) applyLibraryEventResult(player);
        replayPlayer=player;
        replayClose=round==null?Screen.REPLAYS:Screen.EVENTS;
        screen=Screen.REPLAY;physics.setSpeed(0);pressed.clear();helpVisible=false;if(hud!=null) hud.resetCamera();
    }
    private void applyLibraryEventResult(ReplayPlayer player) {
        var replay=player.replay();
        if(replay.source()!=RaceReplay.Source.LOCAL || replay.end()!=RaceReplay.End.COMPLETED || replay.qualifying()) return;
        if(pendingEvent!=null && player.useLinkedEventResult(pendingEvent.state())) return;
        var saved=events.current();
        if(saved!=null && player.useLinkedEventResult(saved.state())) return;
        for(var entry:eventHistory.entries()) if(player.useLinkedEventResult(entry.event())) return;
    }
    public void viewCurrentReplay() { if(replayRecorder!=null) { var snapshot=replayRecorder.snapshot(RaceReplay.End.SNAPSHOT);if(snapshot!=null) viewReplay(snapshot);else notifyUser("录制时间还太短，请继续驾驶。"); } }
    public void saveCurrentReplay() {
        if(replayRecorder==null) return;
        var snapshot=replayRecorder.snapshot(RaceReplay.End.SNAPSHOT);
        if(snapshot==null) return;
        notifyUser(replayStore.queueManualSave(snapshot)?"当前片段已加入保存队列。":replayStore.warning);
    }
    public void retryReplaySave() {
        notifyUser(replayStore.queueRetry()?"已开始重试待存回看。":replayStore.warning);
    }
    public void reloadReplayLibrary() {
        if(replayStore.reload()) notifyUser(replayStore.pendingCount()==0?"已重读回看目录。":"已重读回看目录，待存片段保留。");
        else notifyUser(replayStore.warning);
    }
    public void discardPendingReplay(String id) {
        if(replayStore.discardPending(id)) notifyUser("已放弃待存片段，已存回看和成绩保留。");
        else if(replayStore.isSaving(id)) notifyUser(replayStore.warning);
    }
    public void deleteReplay(String id) { notifyUser(replayStore.delete(id)?"回放已删除，比赛成绩保留。"+replayStore.warning:replayStore.warning); }
    public void clearReplays() { notifyUser(replayStore.clear()?"已保存及待存回放已清空，比赛成绩保留。"+replayStore.warning:replayStore.warning); }
    public void cleanupReplayFiles() { int count=replayStore.cleanupUnused();notifyUser(count<0?replayStore.warning:"已清理 "+count+" 个未引用的旧回放文件。"); }
    private void beginReplay(boolean online,RaceSession.Frame frame) {
        try {
        replayOnline=online;replayClock=0;replaySequence=-1;replayOriginTick=online?frame.sequence():0;
        replayRecoverySearchesObserved=false;
        replayLapCrossings=online?null:new double[racers.size()];
        List<RaceReplay.Driver> drivers;
        if(online) drivers=frame.cars().stream().map(car->new RaceReplay.Driver(car.id(),car.name(),car.tuning(),car.id()==raceRoom.localId()?journal.state().look():null)).toList();
        else { var list=new ArrayList<RaceReplay.Driver>();var design=activeLivery();for(int i=0;i<racers.size();i++) {boolean local=racers.get(i).car==player;var appearance=local && design!=null?new RaceReplay.Appearance(vehicleSpec.id,hud==null?"":hud.carAtlasIdentity(),design,design.version()==2 && hud!=null?hud.paintRegions():null):null;list.add(new RaceReplay.Driver(i,racers.get(i).car.name,racers.get(i).car.tuning,local?journal.state().look():null,appearance));}drivers=list; }
        replayRecorder=new ReplayRecorder(modeName(),vehicleSpec.physicsVersion,online?(raceRoom.host()?RaceReplay.Source.HOST:RaceReplay.Source.RECEIVED):RaceReplay.Source.LOCAL,
                circuit,sessionLaps,mode==Mode.QUALIFYING,drivers,!online && mode==Mode.EVENT_RACE);
        if(online) recordOnlineReplay(frame);else recordLocalReplay();
    
        } catch(RuntimeException error) { replayRecorder=null;replayLapCrossings=null;notifyUser("本次回看无法开始，驾驶继续。"); }
    }
    private void recordLocalReplay() {
        recordLocalReplay(null);
    }
    private record ReplayFinishSample(Vector3f position,float distance) {}

    private void recordLocalReplay(ReplayFinishSample[] finishSamples) {
        recordLocalReplay(finishSamples,null);
    }

    private void recordLocalReplay(ReplayFinishSample[] finishSamples,Vector3f[] positions) {
        if(replayRecorder==null || replayOnline || replayRecorder.isStaleObservation(replayClock)) return;
        try {
            for(int i=0;i<racers.size();i++) {
                var racer=racers.get(i);
                var sample=finishSamples==null?null:finishSamples[i];
                var position=sample!=null?sample.position():positions!=null?positions[i]:racer.car.position(replayCaptureVector);
                float x=position.x,z=position.z;
                var progress=racer.progress;
                float distance=sample==null?progress.raceDistance(position):sample.distance();
                var forward=racer.car.forward(replayCaptureRotation,replayCaptureVector);
                float yaw=(float)Math.atan2(forward.x,forward.z);
                float speed=racer.car.velocity(replayCaptureVector).length();
                replayCaptureCars.add(new RaceReplay.Car(i,x,z,yaw,speed,distance,progress.completedLaps(),progress.validLap(),
                        progress.lastLapValid(),progress.finished(),racer.retired,racer.connected,racer.recovery,racer.penalty,
                        progress.finishTime(),progress.lastLap()));
            }
            var frame=new RaceReplay.Frame(replayClock,sessionTime,Math.max(0,countdown),replayCaptureCars);
            replayRecorder.observeLocal(frame,replayRecoverySearchesObserved?replayRecoverySearches:null,replayLapCrossings);
            if(replayRecorder.limited()) finishReplay(RaceReplay.End.LIMIT);
        } catch(RuntimeException error) { finishReplay(RaceReplay.End.STOPPED);notifyUser("回看录制中断；已保存片段保留，驾驶继续。"); }
        finally {replayCaptureCars.clear();}
    }
    private void recordOnlineReplay(RaceSession.Frame frame) {
        if (replayRecorder == null || !replayOnline || frame.sequence() <= replaySequence) return;
        try {
            replaySequence = frame.sequence();
            for (var car : frame.cars()) {
                var p = car.pose();
                var forward = replayCaptureRotation.set(p.qx(), p.qy(), p.qz(), p.qw()).mult(Vector3f.UNIT_Z, replayCaptureVector);
                float yaw = (float) Math.atan2(forward.x, forward.z);
                float speed = replayCaptureVector.set(p.vx(), p.vy(), p.vz()).length();
                var progress = car.progress();
                replayCaptureCars.add(new RaceReplay.Car(car.id(), p.x(), p.z(), yaw, speed, car.distance(),
                        progress.completedLaps(), progress.validLap(), progress.lastLapValid(), progress.finished(),
                        car.retired(), car.connected(), car.recovery(), car.penalty(), progress.finishTime(), progress.lastLap()));
            }
            double time = (double) (replaySequence - replayOriginTick) / RaceSession.TICKS_PER_SECOND;
            replayRecorder.observe(new RaceReplay.Frame(time, frame.elapsed(), frame.countdown(), replayCaptureCars));
            if (frame.complete() || replayRecorder.limited()) finishReplay(frame.complete() ? RaceReplay.End.COMPLETED : RaceReplay.End.LIMIT);
        } catch(RuntimeException error) { finishReplay(RaceReplay.End.STOPPED);notifyUser("回看录制中断；已保存片段保留，驾驶继续。"); }
        finally { replayCaptureCars.clear(); }
    }
    private void finishReplay(RaceReplay.End end) {
        saveCapturedReplay(detachReplay(end));
    }
    private RaceReplay detachReplay(RaceReplay.End end) {
        if(replayRecorder==null) return null;
        var recorder=replayRecorder;replayRecorder=null;replayLapCrossings=null;
        try { return recorder.snapshot(end); }
        catch(IllegalArgumentException error) { notifyUser("本次回看未能生成，驾驶记录与赛事成绩仍按原流程保存。");return null; }
    }
    private void saveCapturedReplay(RaceReplay replay) {
        if(replay!=null && !replayStore.queueSave(replay)) notifyUser(replayStore.warning);
    }
    public void cycleGhostOpacity() { store.profile.ghostOpacity=store.profile.ghostOpacity<.33f ? .4f : store.profile.ghostOpacity<.5f ? .6f : .25f;store.save(); }
    public GraphicsQuality graphicsQuality() { return store.profile.graphicsQuality==null?GraphicsQuality.BALANCED:store.profile.graphicsQuality; }
    public void cycleGraphicsQuality() {
        var levels=GraphicsQuality.values();store.profile.graphicsQuality=levels[(graphicsQuality().ordinal()+1)%levels.length];
        if(context!=null && !smoke) context.getSettings().setFrameRate(graphicsQuality().frameRate);
        notifyUser(store.save()?"画面已切换为"+graphicsQuality().title+"；驾驶规则和纪录条件保持一致。":"画面已切换，但设置暂未保存。");
    }
    public void changeVolume(float delta) { store.profile.volume = Math.max(0, Math.min(1, store.profile.volume + delta)); saveAudioSettings(); }
    public void cycleDifficulty() { store.profile.difficulty = (store.profile.difficulty + 1) % 3; store.save(); }
    public void cycleLaps() { store.profile.raceLaps = store.profile.raceLaps == 1 ? 3 : store.profile.raceLaps == 3 ? 5 : 1; store.save(); }
    public void exitGame() { store.save(); stop(); }

    public ControlSettings driveControls() { return driveControls; }
    public KeyboardSettings.Command capturingKey() { return capturingKey; }
    public void openKeyboard() { cancelCalibration();keyboardReturn=screen;screen=Screen.KEYBOARD;blockDrivingInput();capturingKey=null;keyboardMessage=keyboard.warning; }
    public void backFromKeyboard() { capturingKey=null;blockDrivingInput();screen=keyboardReturn; }
    public void captureKeyboardKey(KeyboardSettings.Command command,boolean alternate) { if(screen!=Screen.KEYBOARD) return;capturingKey=command;capturingAlternate=alternate;clearDrivingKeys();keyboardMessage="请按下新的"+(alternate?"备用":"主要")+"按键；ESC 取消。"; }
    public void cancelKeyboardCapture() { capturingKey=null;keyboardMessage="已取消绑定。"; }
    public void acceptKeyboardKey(int key) {
        if(capturingKey==null) return;
        if(key==KeyInput.KEY_ESCAPE) { cancelKeyboardCapture();return; }
        try { var next=keyboard.settings().bind(capturingKey,capturingAlternate,key);capturingKey=null;setKeyboard(next); }
        catch(IllegalArgumentException error) { keyboardMessage=error.getMessage()+"；可重选或按 ESC 取消。"; }
    }
    public void setKeyboard(KeyboardSettings settings) { keyboard.set(settings);capturingKey=null;bindKeyboard();keyboardMessage=keyboard.dirty()?keyboard.warning:"键位已保存并生效。"; }
    public String keyLabel(KeyboardSettings.Command command) { return keyboard.settings().label(command); }
    public String drivingHint(String text) {
        return keyboard.settings().drivingHint(text);
    }
    public String controlsIdentity() { return driveControls.identity(); }
    private String recordControlsIdentity() {
        // The room can deliver a completed lap while the driver edits settings for the next session.
        if(onlineSession>=0 && online()) return controlsIdentity();
        return screen==Screen.DRIVING || screen==Screen.PAUSED || screen==Screen.RESULTS?controlsIdentity():controls.settings().identity();
    }
    public AxisCalibration calibration() { return calibration; }
    public ControlSettings.Command capturingButton() { return capturingButton; }
    public void openControls() { controlsReturn=screen;screen=Screen.CONTROLS;pressed.clear();controllerInput.block();cancelCalibration(); }
    public void backFromControls() { cancelCalibration();screen=controlsReturn;controllerInput.block(); }
    public void controlSettings(ControlSettings value) {
        cancelCalibration();
        controllerInput.block();
        if (mini4wd != null) mini4wd.controllerBoost(false);
        if (!controls.set(value)) controlMessage=controls.warning;
        else controlMessage="设置已保存；驾驶设置在下一次尝试或新赛事阶段生效。";
    }
    public void selectController() {
        var devices=controllerInput.devices();if(devices.isEmpty()) { controlMessage="没有检测到手柄，连接后会自动刷新。";return; }
        int current=-1;for(int i=0;i<devices.size();i++) if(devices.get(i).key().equals(controls.settings().device())) current=i;
        var defaults=ControlSettings.defaults();var device=devices.get((current+1)%devices.size());
        if(device.key().equals(controls.settings().device())) return;
        controlSettings(new ControlSettings(1,controls.settings().enabled(),device.key(),defaults.steering(),defaults.throttle(),defaults.brake(),0,1,defaults.buttons()));
        if(!controls.dirty()) controlMessage="已选择 "+device.displayName()+"；请重新校准三个驾驶轴。";
    }
    private boolean beginControllerCapture() {
        cancelCalibration();
        captureGeneration=controllerInput.observationGeneration(controls.settings());
        if(captureGeneration!=0) return true;
        controlMessage="请先选择已连接的手柄。";
        return false;
    }
    public void beginCalibration(ControlSettings.Channel channel) {
        if(!beginControllerCapture()) return;
        calibration=new AxisCalibration(channel);
        controlMessage="";
        controllerInput.block();
    }
    public void confirmCalibration() {
        if(calibration==null || invalidateInterruptedCapture()) return;
        try {
            var result=calibration.confirm(controllerInput.raw(controls.settings()),controls.settings().axis(calibration.channel()));
            if(result.isEmpty()) {
                controlMessage="";
                return;
            }
            var channel=calibration.channel();
            var candidate=controls.settings().axis(channel,result.get());
            var conflict=candidate.calibrationConflict(channel);
            if(!conflict.isEmpty()) {
                calibration=new AxisCalibration(channel);
                controlMessage=conflict;
                return;
            }
            controlSettings(candidate);
        } catch(IllegalArgumentException error) { controlMessage=error.getMessage(); }
    }
    public void cancelCalibration() { calibration=null;capturingButton=null;captureGeneration=0; }
    public void captureControllerButton(ControlSettings.Command command) {
        if(!beginControllerCapture()) return;
        capturingButton=command;
        controlMessage="按下要绑定的手柄按钮；ESC 或鼠标取消。";
    }
    private boolean invalidateInterruptedCapture() {
        if(captureGeneration==0 || captureGeneration==controllerInput.observationGeneration(controls.settings())) return false;
        cancelCalibration();
        controlMessage="所选手柄的输入已中断，请重新开始校准或按键绑定。";
        return true;
    }
    private void resetFocusInput() {
        controllerInput.focusLost();
        invalidateInterruptedCapture();
        pressed.clear();
        keyboardHeld.clear();
        if(hud!=null) hud.clearNavigationKeys();
    }
    private boolean controlsReady(ControlSettings settings) {
        var problem=controllerInput.drivingProblem(settings);
        if(problem.isEmpty()) return true;
        notifyUser(problem);
        return false;
    }
    private void freezeControls(ControlSettings settings) { driveControls=settings;blockDrivingInput(); }
    private ControllerInput.Drive drivingInput() {
        if(!windowFocused()) return ControllerInput.Drive.STOP;
        if(driveControls.enabled()) return controllerInput.drive(driveControls);
        float throttle=pressed.contains("throttle")?1:0,brake=pressed.contains("handbrake")?1:0;
        float steer=(pressed.contains("right")?1:0)-(pressed.contains("left")?1:0);
        boolean reverse=pressed.contains("brake");
        if(keyboardDrive.throttle()!=throttle || keyboardDrive.brake()!=brake || keyboardDrive.steer()!=steer
                || keyboardDrive.reverse()!=reverse)
            keyboardDrive=new ControllerInput.Drive(throttle,brake,steer,reverse);
        return keyboardDrive;
    }
    private void controllerConnectionChanged() {
        invalidateInterruptedCapture();
        if (mini4wd != null && mini4wd.controllerBoostHeld()
                && !controllerInput.pressed(controls.settings(), ControlSettings.Command.BOOST)) mini4wd.controllerBoost(false);
        checkDrivingController();
    }
    private void checkDrivingController() {
        if(screen==Screen.DRIVING && driveControls.enabled()) {
            var problem=controllerInput.drivingProblem(driveControls);
            if(!problem.isEmpty()) {pause();notifyUser(problem);}
        }
    }
    private void controllerButton(com.jme3.input.event.JoyButtonEvent event) {
        if(!windowFocused()) return;
        var settings=screen==Screen.DRIVING?driveControls:controls.settings();
        if(controllerInput.deviceId(settings)!=event.getJoyIndex()) return;
        if(screen==Screen.CONTROLS && invalidateInterruptedCapture()) return;
        if(screen==Screen.CONTROLS && capturingButton!=null) { if(event.isPressed()) controlSettings(settings.button(capturingButton,event.getButtonIndex()));return; }
        if(!settings.enabled()) return;
        ControlSettings.Command command=null;for(var entry:settings.buttons().entrySet()) if(entry.getValue()==event.getButtonIndex()) command=entry.getKey();
        if(command==null) return;
        if(command==ControlSettings.Command.BOOST && screen==Screen.MINI4WD && mini4wd!=null) {
            if (!event.isPressed() || hud == null || (!hud.typing() && !hud.transitioning()))
                mini4wd.controllerBoost(event.isPressed());
            return;
        }
        if(!event.isPressed()) return;
        if(screen==Screen.CONTROLS && calibration!=null) { if(command==ControlSettings.Command.CONFIRM) confirmCalibration();else if(command==ControlSettings.Command.BACK) cancelCalibration();return; }
        switch(command) {
            case CONFIRM -> { if((screen!=Screen.DRIVING || helpVisible) && hud!=null) hud.controllerConfirm(); }
            case BACK -> { if(hud==null || !hud.cancelPrompt()) action("escape",true,0); }
            case PAUSE -> { if(screen==Screen.DRIVING || screen==Screen.PAUSED) action("escape",true,0); }
            case RESET -> { if(screen==Screen.DRIVING || screen==Screen.RESULTS) action("reset",true,0); }
            case CAMERA -> { if(screen==Screen.DRIVING || screen==Screen.REPLAY) action("camera",true,0); }
            case HELP -> { if(screen!=Screen.CONTROLS) action("help",true,0); }
            default -> { }
        }
    }
    private void updateController(float frameSeconds) {
        if(controllerAdapter!=null) controllerAdapter.refresh();
        if(inputScreen!=screen || inputHelp!=helpVisible) { controllerInput.block();inputScreen=screen;inputHelp=helpVisible; }
        if(!windowFocused()) return;
        checkDrivingController();
        if(screen==Screen.DRIVING && driveControls.enabled() && !helpVisible && countdown>0) controllerInput.drive(driveControls);
        if((screen!=Screen.DRIVING || helpVisible) && calibration==null && capturingButton==null && hud!=null) {
            int direction=controllerInput.menu(controls.settings(),frameSeconds);if(direction!=0) hud.controllerNavigate(direction);
        }
    }

    // Bound catch-up work consistently: Bullet stores this budget until render().
    // Under sustained <20 fps, both the session and physics slow down together.
    private static float simulationDelta(float tpf) {
        return Float.isFinite(tpf)?Math.min(.05f,Math.max(0,tpf)):0;
    }

    @Override public void simpleUpdate(float tpf) {
        float dt = simulationDelta(tpf);
        if (smoke && scenario.startsWith("formula-")) dt = Math.min(.05f,dt * 4);
        if(replayStore.pollSave()) {
            String notice=replayStore.takeSaveNotice();
            String message=replayStore.warning.isEmpty()?notice:replayStore.warning;
            if(!message.isEmpty()) notifyUser(message);
        }
        updateReplayLoading();
        updateReplayMarkers();
        updateController(tpf);
        observeDiscussionReading();
        observePartsEditing();partsEditing.tick(Float.isFinite(tpf)?Math.max(0,tpf):0);
        captureAuthoringDrafts();double draftDelta=Float.isFinite(tpf)?Math.max(0,tpf):0;
        liveryEditing.tick(draftDelta);purposeEditing.tick(draftDelta);discussionEditing.tick(draftDelta);revisionEditing.tick(draftDelta);feedbackEditing.tick(draftDelta);feedbackDiscussionEditing.tick(draftDelta);partsAuthoringEditing.tick(draftDelta);circuitPackEditing.tick(draftDelta);
        if(screen==Screen.EXPERIENCE && experiencePlayer!=null && !helpVisible
                && (hud==null || !hud.typing() && !hud.transitioning())) experiencePlayer.update(tpf);
        sceneClock += dt; uiClock += dt;
        toastLeft = Math.max(0, toastLeft - dt); radioLeft = Math.max(0, radioLeft - dt);
        updateCaptureNotices();
        if(replayViewing()) {
            physics.setSpeed(0);
            if(!helpVisible && (hud==null || !hud.typing() && !hud.transitioning())) replayPlayer.update(tpf);
        }
        else if (raceRoom != null) {
            setPauseOnLostFocus(false); updateOnline(dt); updateCamera(dt);
        } else if (screen == Screen.MINI4WD) {
            setPauseOnLostFocus(!smoke && !mini4wd.roomActive());
            // The raw keyboard state still sees releases consumed by the HUD.
            if (mini4wd.keyboardBoostHeld() && !keyboardBindingHeld(KeyboardSettings.Command.HANDBRAKE))
                mini4wd.keyboardBoost(false);
            if (hud != null) hud.updateMiniPointer(inputManager.getCursorPosition());
            mini4wd.update(dt);
        }
        else if(screen==Screen.MAP_EDITOR) { physics.setSpeed(0);if(hud!=null) hud.updateCircuitPointer(inputManager.getCursorPosition()); }
        else {
            if (screen == Screen.DRIVING && !helpVisible) updateDriving(dt);
            else if(screen!=Screen.PAUSED && screen!=Screen.RESULTS && screen!=Screen.TELEMETRY && screen!=Screen.GHOSTS && screen!=Screen.REPLAYS && player!=null
                && !inPausedSettings()) player.halt();
            if (screen == Screen.DRIVING) physics.setSpeed(helpVisible ? 0 : 1);
            updateCamera(dt);
        }
        updateAudio(dt);
        if (hud != null) {
            hud.update(dt);
            if (uiClock >= 1f/graphicsQuality().frameRate-.001f) { hud.render(); uiClock = 0; }
        }
        if (smoke) smokeUpdate(dt);
    }
    private void observeOnlineFrame(RaceSession.Frame frame) {
        if (frame.session() != onlineSession) {
            disposeSession();circuit=raceRoom.track();createWorld(false);physics.setSpeed(0);
            mode=switch(frame.settings().activity()) { case RACE->Mode.RACE;case QUALIFYING->Mode.QUALIFYING;case PRACTICE->Mode.FREE_CIRCUIT; };
            sessionLaps = frame.settings().laps();
            List<RaceSession.CarState> ordered = new ArrayList<>(frame.cars());
            ordered.sort(Comparator.comparingInt(c -> c.id() == raceRoom.localId() ? -1 : c.id()));
            for (var state : ordered) {
                FormulaCar car=addCar(state.name(),state.tuning(),-18,0,false,1,mode==Mode.QUALIFYING?1:mode==Mode.RACE?sessionLaps:99999);
                onlineCars.put(state.id(),racers.get(racers.size()-1));
                if (state.id() == raceRoom.localId()) player = car;
            }
            freezeControls(controls.settings());
            onlineSession = frame.session(); dismissedOnlineResult = -1; onlineLapsRecorded = 0; newBest = false;
            onlineDriver = new DriverAI(circuit,.84f + raceRoom.localId() * .01f,raceRoom.localId() % 2 == 0 ? -1 : 1);
            if(mode==Mode.RACE || mode==Mode.QUALIFYING) beginReplay(true,frame);
            screen = Screen.DRIVING; helpVisible = false; pressed.clear(); cameraImmediate();
        } else if (replayRecorder != null && replayOnline) recordOnlineReplay(frame);
        for (var car : frame.cars()) {
            if (car.id() != raceRoom.localId()) continue;
            var progress = car.progress();
            if (progress.completedLaps() > onlineLapsRecorded) {
                onlineLapsRecorded = progress.completedLaps();
                if (progress.lastLapValid()) recordLapBest(progress.lastLap());
            }
            break;
        }
    }
    private void updateOnline(float dt) {
        RaceSession.Input input = RaceSession.Input.STOP;
        if (screen == Screen.DRIVING && !helpVisible) {
            if (smoke && scenario.startsWith("formula-") && onlineDriver != null && player != null) {
                onlineDriver.update(dt,player,racers.stream().filter(r -> !r.retired && !r.progress.finished()).map(r -> r.car).toList());
                input = new RaceSession.Input(player.throttle,player.braking,player.steeringDemand(),false);
            } else { var drive=drivingInput();input=new RaceSession.Input(drive.throttle(),drive.brake(),drive.steer(),drive.reverse()); }
        }
        raceRoom.control(input);
        var initial = raceRoom.rawFrame();
        if (!raceRoom.closed() && initial != null && initial.session() != onlineSession) observeOnlineFrame(initial);
        raceRoom.poll(dt, this::observeOnlineFrame);
        String roomNotice=raceRoom.takeNotice();if(roomNotice!=null) notifyUser(roomNotice);
        if (raceRoom.closed()) {
            String reason = raceRoom.status(); closeRaceRoom(); preview(); openRaceRoom(); notifyUser(reason); return;
        }
        var frame = raceRoom.frame();
        if (frame == null) {
            if (onlineSession >= 0) { onlineSession = -1; preview(); openRaceRoom(); }
            else if(circuit!=raceRoom.track()) { Screen remember=screen;preview();screen=remember; }
            if (player != null) player.halt();
            return;
        }
        sessionTime = frame.elapsed(); countdown = frame.countdown();
        for (var state : frame.cars()) {
            Racer r = onlineCars.get(state.id()); if (r == null) continue;
            var p = state.pose();
            var position = p.position();
            var rotation = p.rotation();
            r.car.body.setPhysicsLocation(position); r.car.body.setPhysicsRotation(rotation); r.car.body.setLinearVelocity(p.velocity());
            r.car.node.setLocalTranslation(position); r.car.node.setLocalRotation(rotation);
            r.car.throttle = state.throttle(); r.car.braking = state.braking(); r.car.steering = state.steering();
            r.progress.applySnapshot(state.progress()); r.penalty = state.penalty(); r.connected = state.connected(); r.retired = state.retired();
            r.recovery=state.recovery();
            r.previous = position;
        }
        if (frame.complete() && dismissedOnlineResult != frame.session()) {
            screen=Screen.RESULTS;pressed.clear();resultTitle=mode==Mode.QUALIFYING?"联机单圈排位完成":"方程式联机正赛完成";
            resultDescription=mode==Mode.QUALIFYING?"有效圈速决定本站发车顺序；无有效成绩排在后方。房主返回房间后进入正赛。":"房主已核算本场成绩与积分。返回房间后可查看排名并继续下一阶段。";
        }
    }
    private void updateDriving(float dt) {
        replayRecoverySearchesObserved=false;
        if(replayLapCrossings!=null) Arrays.fill(replayLapCrossings,0);
        boolean recordingLocal=replayRecorder!=null && !replayOnline;
        double replayFrameStart=replayClock;
        if(recordingLocal) replayClock+=dt;
        if (countdown > 0) {
            int old = (int) Math.ceil(countdown);
            countdown -= dt;
            if(countdown<=0 && circuitSectionPractice!=null && circuitSectionPractice.start().speedKph()>0) {
                releaseRollingStart(circuit.sample(circuitSectionPractice.spawnDistance()),circuitSectionPractice.start().speedMps());
            } else {
                for (Racer r : racers) { r.car.drive(dt, 0, 1, 0); r.previous = r.car.position(); }
            }
            if(circuitSectionPractice!=null) previousSectionSpeedMps=sectionSpeed(player);
            if (old != (int) Math.ceil(countdown)) playCue(countdown<=0?GameAudio.Cue.GO:GameAudio.Cue.COUNTDOWN);
            recordLocalReplay();
            return;
        }
        if(experience!=null && experience.state()==ExperienceTrial.State.READY) { launchExperience();return; }
        double frameStart=sessionTime,observedAt=frameStart+dt;
        boolean eventRace=mode==Mode.EVENT_RACE && runningEvent!=null;
        boolean localRace=mode==Mode.RACE;
        boolean cleanLaps=bookAttempt!=null && bookAttempt.entry().kind()==ChallengeBook.Kind.CLEAN_LAPS;
        Racer you=racers.get(0);
        double deadline=qualifying!=null?QualifyingSession.LIMIT
                :eventRace?EventRaceClock.deadlineSeconds(sessionLaps,firstEventFinish())
                :activityTrial!=null?activityTrial.deadline()
                :circuitTrial!=null?CircuitTrial.LIMIT
                :challenge!=null?RaceChallenge.LIMIT
                :cup!=null?CupChallenge.TIME_LIMIT
                :localRace && you.progress.finished()?you.progress.finishTime()+LOCAL_RACE_FINISH_WAIT_SECONDS
                :Double.POSITIVE_INFINITY;
        double lapDeadline=cleanLaps?ChallengeAttempt.CLEAN_LAP_LIMIT:cup==null?deadline:CupChallenge.FINISH_DEADLINE;
        if(observedAt>=deadline) {
            if(recordingLocal) replayClock=replayFrameStart+Math.max(0,Math.min(dt,deadline-frameStart));
            sessionTime=deadline;
            var cutoffPositions=localFramePositions==null?new Vector3f[racers.size()]:localFramePositions;
            advanceLocalLapProgress(frameStart,observedAt,lapDeadline,cutoffPositions,null,null);
            if(qualifying!=null) updateLocalQualifying(cutoffPositions);
            else if(activityTrial!=null) {
                // A native contact at the later sample cannot be assigned to the cutoff position.
                updateLocalActivity(observedAt==deadline && checksActivityContact() && contactWithOpponent());
            }
            else if(circuitTrial!=null) {
                // Interpolated crossings settle at the deadline. Review samples require a real observation.
                updateCircuitTrial(observedAt==deadline?cutoffPositions:null);
            }
            else if(challenge!=null) updateRaceChallenge(cutoffPositions,observedAt==deadline && contactWithOpponent());
            else if(cup!=null) updateCupChallenge(dt);
            else if(localRace) finishLocalRace(cutoffPositions);
            else updateEventRace(cutoffPositions);
            return;
        }
        sessionTime=cleanLaps?Math.min(observedAt,lapDeadline):observedAt;
        List<FormulaCar> traffic = trafficCars;
        var trafficFrame = traffic.size() > 1 ? trafficSampler.capture(traffic) : null;
        DriverAI playerTrafficDriver = null;
        if(smoke && scenario.equals("axle-suspension") && tuningTrial!=null && suspensionSmokeDriver!=null) {
            suspensionSmokeDriver.update(dt,player,traffic);
        } else if(smoke && scenario.equals("vehicle-observation") && tuningTrial!=null) {
            player.drive(dt,1,0,0);
        } else if (smoke && (scenario.equals("verify") || scenario.equals("comparison")) && practice != null) {
            verificationDriver.update(dt, player,traffic);
        } else if ((smoke && (scenario.equals("race") || scenario.equals("cup") || scenario.equals("drive") || scenario.equals("verify") || scenario.equals("pixel") || scenario.equals("time-trial") || scenario.equals("vehicle-observation"))) || ((mode == Mode.RACE || mode==Mode.EVENT_RACE || mode==Mode.QUALIFYING || mode==Mode.MAP_TRIAL) && you.progress.finished())) {
            if (trafficFrame == null) autoDriver.update(dt, player, traffic);
            else autoDriver.updateFromFrame(dt, player, trafficFrame);
            playerTrafficDriver = autoDriver;
        } else {
            var input=drivingInput();
            float throttle=input.throttle(),brake=input.brake();
            if (input.reverse()) {
                if (player.velocity().dot(player.forward()) > 1) brake = 1;
                else throttle = -.55f;
            }
            player.drive(dt, throttle, brake, input.steer());
        }
        // The auto-driven player joins the same recovery queue once their result is recorded by lap progress.
        var finishedPlayerDriver=you.progress.finished()?playerTrafficDriver:null;
        if(finishedPlayerDriver!=null && finishedPlayerDriver.needsRecovery) pendingAiRecoveries.add(0);
        else pendingAiRecoveries.remove(0);
        for (int i = 1; i < racers.size(); i++) {
            Racer racer = racers.get(i);
            racer.ai.updateFromFrame(dt, racer.car, trafficFrame);
            if (racer.ai.needsRecovery) pendingAiRecoveries.add(i);
            else pendingAiRecoveries.remove(i);
        }
        if (eventRaceTrafficReview != null && dt > 0) sampleEventRaceTraffic(dt, playerTrafficDriver);
        // Let each driver finish its traffic checks before any recovery changes a car's position.
        if(mode==Mode.EVENT_RACE && replayRecorder!=null && !replayOnline && dt>0) {
            Arrays.fill(replayRecoverySearches,null);
            replayRecoverySearchesObserved=true;
        }
        boolean recovered=!pendingAiRecoveries.isEmpty() && recoverLocalAi(dt,traffic,finishedPlayerDriver);
        GhostLap.Sample timedPose=null;
        TelemetryLap.Driving timedDriving=null;
        Vector3f timedPosition=null;
        if(timeTrial!=null) {
            timedPosition=player.position(you.observedPosition);
            var forward=player.forward(timeTrialRotation,timeTrialForward);
            float speed=player.velocity(timeTrialVelocity).length();
            timedDriving=TelemetryLap.Driving.capture(speed,player.throttle,player.braking,player.steeringDemand());
            if(timedDriving!=null && Vector3f.isValidVector(timedPosition) && Vector3f.isValidVector(forward))
                timedPose=GhostLap.Sample.capture(timedPosition.x,timedPosition.z,forward.x,forward.z);
            if(timedPose==null) you.progress.invalidate();
        }
        if (mode != Mode.FREE_PAD && mode != Mode.LESSON && mode!=Mode.TUNING_TRIAL && mode!=Mode.WORKSHOP && mode!=Mode.EXPERIENCE && mode!=Mode.MAP_SECTION) {
            // Control commands can limit velocity, but only recovery changes positions within this sequential update.
            var progressFrame=!recovered && physics.getThreadingType()==BulletAppState.ThreadingType.SEQUENTIAL?trafficFrame:null;
            advanceLocalLapProgress(frameStart,observedAt,lapDeadline,localFramePositions,timedPosition,progressFrame);
        } else if(practice!=null && practice.lesson.traffic()) {
            for(int i=1;i<racers.size();i++) {
                var racer=racers.get(i);
                if(!racer.ai.needsRecovery) racer.progress.update(racer.previous,racer.car.position(),sessionTime);
            }
        }
        if(bookAttempt!=null) bookAttempt.clock(sessionTime);
        boolean contact=(challenge!=null || activityTrial!=null && checksActivityContact() || practice!=null && practice.trafficPractice()!=null)
                && contactWithOpponent();
        if(activityTrial!=null) updateLocalActivity(contact);
        if(timeTrial!=null) {
            String referenceNotice=selectedGhost!=null && timeTrial.selectedSource()!=null && timeTrial.startsLapWith(you.progress)
                    ?refreshGhostChoices():"";
            // The pose belongs to the frame endpoint, even when the challenge clock stopped mid-frame.
            timeTrial.update(you.progress,observedAt,timedPose,timedDriving,player.observation());
            String message=timeTrial.consumeNotice();
            if(!referenceNotice.isEmpty()) message=message.isEmpty()?referenceNotice:referenceNotice+" "+message;
            if(!message.isEmpty()) notifyUser(message);
            newBest=timeTrial.last()!=null && timeTrial.last().saved();
        }
        if(experience!=null) {
            experience.update(dt,experienceObservation());
            if(experience.complete() && !experienceRecorded) {
                experienceRecorded=true;
                if(experience.state()==ExperienceTrial.State.SUCCESS) {
                    var run=experience.result();
                    if(!experienceStore.record(run)) notifyUser(experienceStore.warning);
                    updateExperienceBest(run);
                }
                finish(experience.state()==ExperienceTrial.State.SUCCESS?"攻关完成 · "+experience.result().medal().title:"本次需要重试",experience.feedback());
            }
        }
        if(tuningTrial!=null) {
            tuningTrial.update(dt,you.previous,player.position(),player.speed(),player.throttle,player.braking,player.steeringDemand(),player.observation());
            if(tuningTrial.complete() && !tuningRecorded) {
                tuningRecorded=true;
                if(pairRunActive) {pairExperiment.record(pairIndex,tuningTrial);pairReport=pairExperiment.snapshot();if(!pairTrials.save(pairReport)) notifyUser(pairTrials.warning());}
                else {tuningExperiment.record(tuningIndex,tuningTrial);tuningReport=tuningPlan();var purpose=tuningPurpose();var base=tuningStore.accepted().reports().stream().filter(value->value.id().equals(tuningReport.id())).findFirst().orElse(null);
                    var trace=tuningTrial.state()==TuningTrial.State.SUCCESS?TuningTrace.captureExact(tuningReport,tuningIndex,tuningTrial.trace(),tuningTrial.tracePositions()):null;
                    tuningStore.stage(tuningReport,purpose);
                    if(!tuningTraceStore.stage(tuningStore.reports(),trace,purpose,base)) notifyUser(tuningTraceStore.warning+" "+tuningTraceStore.recoveryWarning());
                    else if(!tuningStore.retry()) notifyUser(tuningStore.warning+" "+tuningStore.recoveryWarning());
                    else if(!tuningStore.saveTraces(tuningTraceStore)) notifyUser(tuningStore.warning+" "+tuningTraceStore.warning+" "+tuningTraceStore.recoveryWarning());
                }
                finish(tuningTrial.state()==TuningTrial.State.SUCCESS?"试车数据已收录":"本组需要重试",tuningTrial.feedback());
            }
        }
        if(workshop!=null) {
            workshop.update(dt,you.previous,player.position(),player.velocity());if(workshop.cup()!=null) player.setCupSpill(workshop.cup().spill);
            if(workshop.complete() && !workshopRecorded) {
                workshopRecorded=true;
                workshopReport=workshop.report();
                boolean saved=workshopStore.record(workshopReport);
                boolean history=saved && workshopStore.reconcile(journal);
                if(!saved || !history) notifyUser(!saved?workshopStore.warning:journal.warning);
                finish(workshopReport.success()?"委托完成 · "+workshopReport.grade().title:"本次委托待重试",workshopReport.reason());
            }
        }
        if (practice != null) {
            practice.controls(player.throttle);
            var position=player.position();
            float speed=player.speed();
            practice.update(dt, you.previous, position, speed, player.braking);
            if(practice.trafficPractice()!=null && !practice.complete() && racers.size()>1) {
                var opponent=racers.get(1).car;
                float alignment=practice.lesson==PracticeSession.Lesson.FOLLOW?1:circuit.tangentAlignment(circuit.project(position),player.forward());
                practice.observeTraffic(dt,new TrafficPractice.Observation(position,opponent.position(),speed,opponent.speed(),alignment,contact));
            }
            if (practice.complete() && !practiceRecorded) {
                practiceRecorded = true;
                if (practice.state == PracticeSession.State.SUCCESS) {
                    settleCourse();
                }
                finish(practice.state == PracticeSession.State.SUCCESS ? "练习完成" : "再试一次", practice.feedback);
            }
        }
        if(challenge!=null) updateRaceChallenge(localFramePositions,contact);
        if(cup!=null) updateCupChallenge(dt);
        if(localRace && you.progress.finished() && racers.stream().allMatch(r->r.progress.finished())) {
            finishLocalRace(localFramePositions);
            return;
        }
        if(qualifying!=null) updateLocalQualifying(null);
        else if(eventRace) {
            updateEventRace(localFramePositions);
        }
        if(screen==Screen.DRIVING && bookAttempt!=null && bookAttempt.complete()) finish("挑战结束",bookAttempt.detail());
        if(circuitSectionPractice!=null) {
            var trial=circuitSectionPractice;
            float speed=sectionSpeed(player);
            trial.update(you.previous,player.position(),sessionTime,previousSectionSpeedMps,speed);
            previousSectionSpeedMps=speed;
            String subject=trial.focusedSplit()==0?"区间":"分段";
            if(trial.state()==CircuitSectionPractice.State.FINISHED) {
                if(originalSectionResult!=null) originalSectionResult.splitHistory().rememberFinish(trial,controlsIdentity());
                if(equipmentSectionReturn!=null)
                    equipmentSectionReturn.session().rememberFinish(equipmentSectionReturn.car(),trial,controlsIdentity());
                finish(subject+"试驾完成","本次"+subject+"用时 "+PracticeSession.format(trial.elapsed())+"。");
            } else if(trial.state()==CircuitSectionPractice.State.INVALID) {
                if(equipmentSectionReturn!=null)
                    equipmentSectionReturn.session().rememberInterruption(equipmentSectionReturn.car(),trial,controlsIdentity());
                finish(subject+"试驾中止",trial.failure());
            }
        }
        if(circuitTrial!=null) updateCircuitTrial(localFramePositions);
        if(screen==Screen.DRIVING) {
            var position=localFramePositions==null?player.position():localFramePositions[0];
            if(!world.training) player.applyOffRoadDamping(circuit,position);
            var up=player.up();
            if(!Vector3f.isValidVector(position) || !Vector3f.isValidVector(up) || position.y< -4 || up.y<.05f)
                notifyUser("车辆需要恢复 · R 返回合法起点");
        }
        if (store.profile.humor && mode!=Mode.TUNING_TRIAL && mode!=Mode.WORKSHOP && mode!=Mode.EXPERIENCE && mode!=Mode.ACTIVITY && sessionTime > 12 && radioLeft <= 0 && sessionTime < 12 + dt) {
            radio = mode == Mode.CUP ? "工程师：今天先保住杯架，再考虑领奖台。" : "工程师：赛车状态正常，杯架也通过了静态测试。";
            radioLeft = 5;
        }
        for(int i=0;i<racers.size();i++) {
            var racer=racers.get(i);
            // The next physics read must leave the previous endpoint available for gate interpolation.
            racer.previous=localFramePositions==null?racer.car.position():racer.previousObservedPosition.set(localFramePositions[i]);
        }
        recordLocalReplay(null,localFramePositions);
    }

    private void updateCircuitTrial(Vector3f[] observedPositions) {
        if(observedPositions!=null && !circuitTrial.complete()) {
            if(!observeCircuitReview(observedPositions)) circuitTrial.abort("试跑观测异常，请调整地图或重试。");
            if(!circuitTrial.complete() && racers.size()>1 && sessionTime>=nextCircuitContactReview) {
                nextCircuitContactReview=sessionTime+.2;
                var review=circuitTrial.review();
                var space=physics.getPhysicsSpace();
                for(int first=0;first<racers.size();first++) {
                    for(int second=first+1;second<racers.size();second++) {
                        review.observeContact(first,second,vehicleContact.touching(space,racers.get(first).car,racers.get(second).car));
                    }
                }
            }
            for(int i=0;i<racers.size() && !circuitTrial.complete();i++) {
                var point=observedPositions[i];
                if(!racers.get(i).progress.finished() && (!Float.isFinite(point.y) || point.y < -3 || point.y>8)) {
                    circuitTrial.review().note(CircuitDriveReview.Event.HEIGHT,i,point.x,point.z,"车辆在这里离开有效高度范围。");
                    circuitTrial.abort("车辆离开试跑范围，请调整地图或重试。");
                }
            }
        }
        circuitTrial.update(sessionTime,circuitTrialProgress);
        if(!circuitTrial.complete()) return;

        String message=circuitTrial.feedback();
        boolean success=circuitTrial.state()==CircuitTrial.State.SUCCESS;
        boolean fourCars=circuitTrial.kind()==CircuitTrial.Kind.FOUR_CARS;
        if(fourCars && success) {
            circuitEditor.validated(circuitTrial.proof(vehicleSpec.physicsVersion,System.currentTimeMillis()));
            recordCreationResult();
            if(persistCircuitDraft()) {store.profile.trackId=circuit.id;if(!store.save()) message+=" 赛道已保存，但默认选择尚未保存。";}
            else message="四车验证已通过，但地图尚未保存；返回编辑器可重试保存。";
        }
        rememberCircuitReview();
        finish(success?(fourCars?"地图试跑通过":"单车试驾完成"):"地图需要调整",message);
    }

    private static float sectionSpeed(FormulaCar car) {
        var velocity=car.velocity();
        return (float)Math.hypot(velocity.x,velocity.z);
    }

    private void advanceLocalLapProgress(double frameStart,double observedAt,double deadline,Vector3f[] positions,
                                         Vector3f timedPosition,DriverAI.TrafficFrame trafficFrame) {
        // An exact map cutoff shares its raw endpoint with the contact query and the review.
        boolean cutoff=observedAt>deadline || observedAt==deadline && circuitTrial==null;
        for(int i=0;i<racers.size();i++) {
            var racer=racers.get(i);
            var current=racer.observedPosition;
            TrackLayout.Projection projection=null;
            if(i==0 && timedPosition!=null) current=timedPosition;
            else if(trafficFrame!=null && trafficFrame.copyPosition(i,racer.car,current)) {
                if(!cutoff) projection=trafficFrame.roadProjection(circuit,i,racer.car);
            } else current=racer.car.position(current);
            var observedEnd=cutoff?LapProgress.positionAtDeadline(racer.previous,current,frameStart,observedAt,deadline):current;
            if(positions!=null) positions[i]=observedEnd;
            if(racer.ai!=null && racer.ai.needsRecovery) continue;
            var progress=racer.progress;
            var previousCrossings=progress.latestCrossings();
            boolean lap=progress.updateTimed(racer.previous,current,observedEnd,frameStart,observedAt,deadline);
            if(projection!=null) progress.rememberRaceProjection(circuit,current,projection);
            var crossings=progress.latestCrossings();
            if(eventRaceTrafficReview!=null && crossings!=previousCrossings) eventRaceTrafficReview.crossedGates(i,crossings.size());
            if(lap && replayLapCrossings!=null) for(var crossing:crossings) {
                if(crossing.gate()==0 && !crossing.firstStart()) replayLapCrossings[i]=crossing.time();
            }
            if(!lap || i!=0) continue;

            if(bookAttempt!=null && bookAttempt.entry().kind()==ChallengeBook.Kind.CLEAN_LAPS)
                bookAttempt.lapAt(progress.lapStartTime(),progress.lastLap(),progress.lastLapValid());
            if(!progress.lastLapValid() || circuitTrial!=null || activityTrial!=null) continue;
            recordJournal(attemptId+":lap:"+progress.completedLaps(),DriverJournal.Activity.LAP,true,progress.lastLap(),"有效圈",0,null);
            if(mode!=Mode.CUP && mode!=Mode.TIME_TRIAL && mode!=Mode.CHALLENGE) {
                recordLapBest(progress.lastLap());
                if(newBest) notifyUser("新的个人最佳 · "+PracticeSession.format(progress.lastLap()));
            }
        }
    }

    private double firstEventFinish() {
        double firstFinish=Double.POSITIVE_INFINITY;
        for(var racer:racers) if(racer.progress.finished()) firstFinish=Math.min(firstFinish,racer.progress.finishTime());
        return firstFinish;
    }

    private boolean checksActivityContact() {
        return activityTrial.stage().kind()==ActivityCard.Kind.CHASE || activityTrial.observesContacts();
    }

    private boolean contactWithOpponent() {
        var space=physics.getPhysicsSpace();
        for(int i=1;i<racers.size();i++) {
            if(vehicleContact.touching(space,player,racers.get(i).car)) return true;
        }
        return false;
    }

    private void updateLocalActivity(boolean contact) {
        boolean rank=activityTrial.stage().kind()==ActivityCard.Kind.RACE && racers.get(0).progress.finished();
        var observations=new ArrayList<ActivityTrial.Observation>(racers.size());
        for(int i=0;i<racers.size();i++) {
            var racer=racers.get(i);
            var position=localFramePositions[i];
            if(!Float.isFinite(position.y) || position.y < -3 || position.y>8) {
                activityTrial.abort("车辆离开有效高度范围，请重试本段。");
                break;
            }
            float distance=rank?Math.max(0,racer.progress.raceDistance(position)):0;
            observations.add(new ActivityTrial.Observation(racer.progress,distance,racer.recovery,racer.penalty));
        }
        activityTrial.update(sessionTime,observations,contact);
        if(!activityTrial.complete() || activityRecorded) return;
        activityRecorded=true;
        var result=activityTrial.result();
        var run=activityTrial.run();
        var old=activityStore.best(run.key());
        if(!activityStore.record(run.id(),result)) notifyUser(activityStore.warning);
        var accepted=activityStore.active();
        newBest=accepted.complete() && (old==null || accepted.total()<old.total());
        finish(result.success()?"活动完成一项":"本项需要重试",result.detail());
    }

    private void updateLocalQualifying(Vector3f[] cutoffPositions) {
        qualifying.update(sessionTime,qualifyingProgress);
        if(!qualifying.complete()) return;
        var samples=cutoffPositions!=null && replayRecorder!=null && !replayOnline?raceFinishSamples(cutoffPositions):null;
        commitEvent(runningEvent.qualify(qualifying.results()));
        finish("排位完成","有效圈按毫秒排序；同成绩先做出者在前，无有效圈排在后方。阶段已结算，返回赛事中心继续正赛。",samples);
    }

    private void updateEventRace(Vector3f[] cutoffPositions) {
        int finishers=0;
        double firstFinish=Double.POSITIVE_INFINITY;
        for(int driver=0;driver<racers.size();driver++) {
            var progress=racers.get(driver).progress;
            if(eventRaceTiming!=null) eventRaceTiming.observeProgress(driver,progress);
            if(progress.finished()) {
                finishers++;
                firstFinish=Math.min(firstFinish,progress.finishTime());
            }
        }
        eventRaceClock=EventRaceClock.read(sessionLaps,sessionTime,finishers,firstFinish);
        if(!eventRaceClock.readyToSettle()) return;

        var results=new ArrayList<EventSeries.Finish>(racers.size());
        var samples=replayRecorder==null || replayOnline?null:new ReplayFinishSample[racers.size()];
        for(int driver=0;driver<racers.size();driver++) {
            var racer=racers.get(driver);
            var progress=racer.progress;
            var position=cutoffPositions==null?racer.car.position():cutoffPositions[driver];
            float distance=progress.raceDistance(position);
            if(samples!=null) samples[driver]=new ReplayFinishSample(position,distance);
            results.add(new EventSeries.Finish(driver,progress.finished(),progress.finished()?progress.finishTime():-1,
                    racer.penalty,Math.max(0,distance)));
        }
        recordLocalReplay(samples);
        var replay=detachReplay(RaceReplay.End.COMPLETED);
        var state=runningEvent.state();
        String replayId=EventSeries.matchesCompletedReplay(replay,state,state.round(),sessionTime,results)?replay.id():null;
        var settlement=new EventSeries.Settlement(eventRaceClock.limit(),sessionTime,replayId);
        var completed=runningEvent.finishRace(results,controlsIdentity(),settlement);
        if(eventRaceTiming!=null) eventRaceTiming.settle(completed.state().results().get(state.round()));
        commitEvent(completed);
        if(eventRaceTrafficReview!=null) eventRaceTrafficReview.end(EventRaceTrafficReview.End.SETTLED);
        saveCapturedReplay(replay);
        finish("本站正赛完成",settlement.limit().description()+"成绩含复位罚时。未完赛按进度排列且不得分。");
    }

    private ReplayFinishSample[] raceFinishSamples(Vector3f[] positions) {
        var samples=new ReplayFinishSample[racers.size()];
        for(int driver=0;driver<racers.size();driver++) {
            var racer=racers.get(driver);
            var position=positions==null?racer.car.position():positions[driver];
            samples[driver]=new ReplayFinishSample(position,racer.progress.raceDistance(position));
        }
        return samples;
    }

    private void finishLocalRace(Vector3f[] positions) {
        var samples=raceFinishSamples(positions);
        localResultOrder=List.copyOf(localStandings(samples));
        finish("比赛完成","最终成绩包含复位罚时。未完赛车辆按完成进度排列。",samples);
    }

    private void updateRaceChallenge(Vector3f[] positions,boolean contact) {
        challenge.update(sessionTime,racers.get(0).progress,racers.get(1).progress,contact);
        if(!challenge.complete() || finishRecorded) return;
        boolean success=challenge.state()==RaceChallenge.State.SUCCESS;
        if(success) settleCompletion(ProfileStore.SessionCompletion.challenge(attemptId,
                recordKey("challenge:"+challenge.kind().id),challenge.finishTime()),challenge.kind().title,circuit.name);
        var samples=raceFinishSamples(positions);
        localResultOrder=List.copyOf(localStandings(samples));
        finish(success?"挑战完成":"再试一次",challenge.feedback(),samples);
    }

    private void updateCupChallenge(float dt) {
        var progress=racers.get(0).progress;
        float previousSpill=cup.spill;
        double stop=progress.finished()?progress.finishTime():sessionTime;
        cup.updateThrough(dt,player.velocity(),stop);
        player.setCupSpill(cup.spill);
        if((previousSpill<25 && cup.spill>=25) || (previousSpill<60 && cup.spill>=60)) {
            notifyUser("杯架测试：已洒水 "+Math.round(cup.spill)+"%，试着平顺操作。");
            if(store.profile.humor) {radio="工程师：杯架没有故障，它只是在表达意见。";radioLeft=5;}
        }
        if(cup.observationsComplete() && !progress.finished() && !cup.timedOut()) return;
        sessionTime=cup.time;
        if(!cup.observationsComplete()) {
            finish("测试中止","车辆观测中断，按 R 重新开始杯架测试。");
        } else if(progress.finished()) {
            boolean valid=progress.lastLapValid() && !cup.timedOut();
            if(valid) store.recordScore(recordKey("cup"),cup.score());
            finish(valid?cup.medal():"测试未通过",valid?"有效完成一圈 · 洒水 "+Math.round(cup.spill)+"% · 得分 "+cup.score()
                    :"需要在 110 秒内合法完成一圈，越界或复位后请重试。");
        } else finish("测试时间已到","需要在 110 秒内完成合法整圈。平稳和节奏都要兼顾。");
    }

    private boolean recoverLocalAi(float dt,List<FormulaCar> traffic,DriverAI finishedPlayerDriver) {
        var occupied=recoveryOccupied;
        boolean recovered=false;
        RecoveryPlanner.LocalTraffic[] readings=null;
        if(pendingAiRecoveries.size()>1) {
            if(recoveryReadings.length<traffic.size()) recoveryReadings=new RecoveryPlanner.LocalTraffic[traffic.size()];
            readings=recoveryReadings;
            Arrays.fill(readings,0,traffic.size(),null);
        }
        float gravityY=physics.getPhysicsSpace().getGravity(recoveryGravityReading).y;
        boolean observing=eventRaceTrafficReview!=null && dt>0;
        for (var pending=pendingAiRecoveries.iterator();pending.hasNext();) {
            int index=pending.next();
            var racer=racers.get(index);
            if(circuitTrial!=null && !racer.progress.finished()) {
                var point=racer.car.position();
                circuitTrial.review().note(CircuitDriveReview.Event.RECOVERY,index,point.x,point.z,"AI 在这里需要恢复，请检查附近路线、场景和交通。");
                circuitTrial.abort("AI 需要恢复，请调整路线或重新试跑。");
            }
            long start=observing?System.nanoTime():0;
            occupied.clear();
            boolean incompleteTraffic=false;
            // Traffic order is the same as the frozen local replay roster.
            for(int driver=0;driver<traffic.size();driver++) {
                var car=traffic.get(driver);
                if(car==racer.car) continue;
                var reading=readings==null?null:readings[driver];
                if(reading==null) {
                    reading=readLocalRecoveryTraffic(car,driver);
                    if(readings!=null) readings[driver]=reading;
                }
                occupied.add(reading);
                if(observing && !reading.complete()) incompleteTraffic=true;
            }
            var lookup=findLocalRecovery(racer,occupied,gravityY,replayRecoverySearchesObserved);
            var recovery=lookup.spawn();
            if(observing) {
                long searchNanos=System.nanoTime()-start;
                eventRaceTrafficReview.checkedRecovery(index,dt,searchNanos,recovery.isPresent(),incompleteTraffic,lookup.reusedCandidates());
            }
            if(recovery.isEmpty()) {
                if(replayRecoverySearchesObserved) replayRecoverySearches[index]=lookup;
                continue;
            }

            var spawn=recovery.get();
            racer.car.recover(spawn.position(),spawn.tangent());
            recovered=true;
            pending.remove();
            racer.previous=racer.car.position();
            // Reuse the post-placement position and replace any pre-placement sweep before the next search.
            if(readings!=null && pending.hasNext())
                readings[index]=new RecoveryPlanner.LocalTraffic(racer.previous,racer.car.body.getLinearVelocity(recoveryVelocityReading),index);
            racer.recovery++;
            if(replayRecoverySearchesObserved) replayRecoverySearches[index]=lookup;
            if(eventRaceTrafficReview!=null) eventRaceTrafficReview.recovered(index);
            if(!racer.progress.finished()) {
                racer.progress.invalidate();
                racer.penalty+=5;
                if(qualifying!=null) qualifying.retire(index);
            }
            if(index==0) {
                finishedPlayerDriver.recovered();
            } else {
                racer.ai.recovered();
                if(practice!=null) practice.opponentRecovered();
                if(challenge!=null) challenge.abort("对手需要恢复，按 R 重新开始本次挑战。");
            }
        }
        occupied.clear();
        if(readings!=null) Arrays.fill(readings,0,traffic.size(),null);
        return recovered;
    }

    private void sampleEventRaceTraffic(float dt, DriverAI playerDriver) {
        try {
            for (var racer : racers) {
                var driver = racer.car == player ? playerDriver : racer.ai;
                var release = driver == null ? null : driver.restartDecision();
                // The driving command can clamp native velocity after the shared AI frame was captured.
                float speed=racer.car.velocity(eventTrafficVelocity).length();
                eventTrafficObservations.add(new EventRaceTrafficReview.DriverFrame(speed, racer.progress.finished(),
                        driver != null && driver.needsRecovery,
                        driver == null ? null : driver.proximityDecision(),
                        driver == null ? null : driver.restartProbeDecision(), release == null ? null : release.phase()));
            }
            var space = physics.getPhysicsSpace();
            int touching = 0, pair = 0;
            long start = System.nanoTime();
            for (int first = 0; first < racers.size(); first++) {
                for (int second = first + 1; second < racers.size(); second++, pair++) {
                    if (vehicleContact.touching(space, racers.get(first).car, racers.get(second).car)) touching |= 1 << pair;
                }
            }
            eventRaceTrafficReview.sample(dt, eventTrafficObservations, touching, System.nanoTime() - start);
        } finally {
            eventTrafficObservations.clear();
        }
    }
    private void finish(String title, String description) {
        finish(title,description,null);
    }
    private void finish(String title,String description,ReplayFinishSample[] samples) {
        if(finishRecorded) return;finishRecorded=true;
        rememberAuthorTrial();
        recordLocalReplay(samples);finishReplay(RaceReplay.End.COMPLETED);
        if(bookAttempt!=null && !bookAttempt.complete()) {
            switch(bookAttempt.entry().kind()) {
                case CONSISTENCY,START -> bookAttempt.course(practice.state==PracticeSession.State.SUCCESS,practice.elapsed,practice.consistency().laps(),practice.startPractice()!=null?practice.startPractice().reaction():-1);
                case CHASE -> {
                    var measured=samples!=null?samples:raceFinishSamples(null);
                    bookAttempt.race(challenge.state()==RaceChallenge.State.SUCCESS,challenge.finishTime(),
                            measured[0].distance()-measured[1].distance());
                }
                case SMOOTH -> bookAttempt.cup(cup.observationsComplete() && racers.get(0).progress.finished()
                    && racers.get(0).progress.lastLapValid() && !cup.timedOut(),cup.time,cup.spill);
                case CLEAN_LAPS -> bookAttempt.claim();
            }
        }
        if(runningEvent==null && circuitTrial==null && circuitSectionPractice==null && mode!=Mode.TUNING_TRIAL && mode!=Mode.WORKSHOP && mode!=Mode.EXPERIENCE && activityTrial==null) {
            boolean success=practice!=null?practice.state==PracticeSession.State.SUCCESS:challenge!=null?challenge.state()==RaceChallenge.State.SUCCESS:
                cup!=null?racers.get(0).progress.finished() && racers.get(0).progress.lastLapValid() && !cup.timedOut():racers.get(0).progress.finished();
            var activity=practice!=null?DriverJournal.Activity.COURSE:challenge!=null?DriverJournal.Activity.CHALLENGE:cup!=null?DriverJournal.Activity.CUP:bookAttempt!=null?DriverJournal.Activity.BOOK:DriverJournal.Activity.RACE;
            if(bookAttempt!=null) { success=bookAttempt.medal()!=ChallengeBook.Medal.NONE;title=success?"挑战获得"+bookAttempt.medal().title:"再试一次";description=bookAttempt.detail(); }
            var notices=new ArrayList<DriverJournal.Notice>();
            if(practice!=null && practice.lesson==PracticeSession.Lesson.CONSISTENCY && success)
                for(int i=0;i<practice.consistency().laps().size();i++) notices.add(journalNotice(attemptId+":course-lap:"+i,
                        DriverJournal.Activity.LAP,true,practice.consistency().laps().get(i),"稳定课程有效圈",0,null));
            notices.add(journalNotice(attemptId+":finish",activity,success,bookAttempt!=null?bookAttempt.elapsed():practice!=null?practice.elapsed:sessionTime,
                bookAttempt!=null?bookAttempt.entry().kind().title+" · "+bookAttempt.medal().title:modeName()+" · "+title,activity==DriverJournal.Activity.RACE?position():0,bookAttempt));
            if(!journal.recordAll(notices)) notifyUser(journal.warning);
        }
        resultTitle = title; resultDescription = description;
        screen = Screen.RESULTS; pressed.clear(); physics.setSpeed(0);
        playCue(GameAudio.Cue.COMPLETE);
        smokeReport.put("result", title);
    }
    private void recordJournal(String receipt,DriverJournal.Activity activity,boolean success,double elapsed,String outcome,int place,ChallengeAttempt award) {
        if(!journal.record(journalNotice(receipt,activity,success,elapsed,outcome,place,award))) notifyUser(journal.warning);
    }
    private DriverJournal.Notice journalNotice(String receipt,DriverJournal.Activity activity,boolean success,double elapsed,String outcome,int place,ChallengeAttempt award) {
        String trackKey=circuit.id+":"+circuit.geometryKey()+":"+vehicleSpec.physicsVersion,name=circuit.name;
        if(practice!=null && !practice.lesson.onCircuit()) { trackKey="training-pad:"+trainingGeometry(practice.lesson)+":"+vehicleSpec.physicsVersion;name="综合训练场"; }
        return new DriverJournal.Notice(receipt,System.currentTimeMillis(),activity,trackKey,name,outcome,success,Double.isFinite(elapsed)?Math.max(0,Math.min(86400,elapsed)):0,
            award!=null?award.entry().key(vehicleSpec.physicsVersion):"",award!=null?award.medal():ChallengeBook.Medal.NONE,place);
    }
    public String recordKey(String activity) {
        String trackId=circuit.id,geometry=circuit.geometryKey();
        if(practice!=null && !practice.lesson.onCircuit()) { trackId="training-pad";geometry=trainingGeometry(practice.lesson); }
        Tuning tuning=player!=null?player.tuning:store.profile.tuning;
        return activity + ":" + trackId + ":" + vehicleSpec.physicsVersion + ":" + tuning.performanceKey()
            + ":"+(online()?ControlIdentity.online(recordControlsIdentity()):recordControlsIdentity())+":geometry:"+geometry;
    }
    public String courseReportKey(PracticeSession.Lesson lesson,Tuning tuning) {
        String trackId=lesson.onCircuit()?circuit.id:"training-pad";
        String geometry=lesson.onCircuit()?circuit.geometryKey():trainingGeometry(lesson);
        return lesson.id+":"+trackId+":"+geometry+":"+vehicleSpec.physicsVersion+":"+tuning.performanceKey()+":"+recordControlsIdentity();
    }
    private String trainingGeometry(PracticeSession.Lesson lesson) {
        return lesson.circle()?TrackLayout.circle(lesson==PracticeSession.Lesson.CIRCLE_RIGHT).geometryKey():"training-layout-v1";
    }
    public String comparisonKey(Tuning t) { String key=vehicleSpec.physicsVersion+":"+t.performanceKey(),controls=recordControlsIdentity();return ControlIdentity.KEYBOARD.equals(controls)?key:key+":"+controls; }
    public double bestTime(String activity) { return store.profile.bestTimes.getOrDefault(recordKey(activity), -1d); }

    private void recordLapBest(double time) {
        newBest=false;
        if(!Double.isFinite(time) || time<=0) return;
        String key=recordKey("lap");
        var pending=pendingLapBests.get(key);
        if(pending!=null && pending.seconds()<=time) return;
        var outcome=store.recordLapTime(key,time);
        newBest=outcome==ProfileStore.BestTimeSave.SAVED;
        if(outcome==ProfileStore.BestTimeSave.FAILED) {
            pendingLapBests.put(key,new PendingLapBest(key,time,circuit.name,player.tuning,recordControlsIdentity(),online()));
            notifyUser("圈速待保存，可在成绩页或练习中心重试。");
        } else if(pending!=null) pendingLapBests.remove(key);
    }

    public int pendingLapBestCount() { return pendingLapBests.size(); }
    public List<PendingLapBest> pendingLapBests() { return List.copyOf(pendingLapBests.values()); }

    public boolean retryLapBest(PendingLapBest expected) {
        if(expected==null || screen==Screen.DRIVING || pendingLapBests.get(expected.key())!=expected) {
            notifyUser("这份待存圈速已变化，请重新查看。");
            return false;
        }
        var outcome=store.recordLapTime(expected.key(),expected.seconds());
        if(outcome==ProfileStore.BestTimeSave.FAILED) {
            notifyUser(store.warning);
            return false;
        }
        pendingLapBests.remove(expected.key());
        notifyUser(outcome==ProfileStore.BestTimeSave.SAVED?"原圈速已保存。":"已保存的同条件圈速更快或相同。");
        return true;
    }

    private void settleCourse() {
        var lesson=practice.lesson;
        Tuning tuning=player.tuning;
        try {
            var result=ProfileStore.SessionCompletion.course(attemptId,recordKey(lesson.id),courseReportKey(lesson,tuning),
                    lesson.braking()?comparisonKey(tuning):null,practice,System.currentTimeMillis());
            settleCompletion(result,lesson.title,lesson.onCircuit()?circuit.name:"综合训练场");
        } catch(IllegalArgumentException invalid) {
            notifyUser("课程已完成，但本次计时观测无法保存。");
        }
    }

    private void settleCompletion(ProfileStore.SessionCompletion result,String title,String trackName) {
        var saved=store.saveCompletion(result);
        newBest=saved==ProfileStore.CompletionSave.BEST;
        if(saved!=ProfileStore.CompletionSave.FAILED) return;
        var pending=new ArrayList<>(pendingCompletions);
        pending.add(0,new PendingCompletion(result,title,trackName,player.tuning,controlsIdentity()));
        pendingCompletions=List.copyOf(pending);
        notifyUser(store.warning);
    }

    public List<PendingCompletion> pendingCompletions() {return pendingCompletions;}

    public boolean retryCompletion(PendingCompletion expected) {
        if(online() || screen==Screen.DRIVING || pendingCompletions.stream().noneMatch(item->item==expected)) {
            notifyUser("这份待存成绩已变化，请重新查看。");
            return false;
        }
        var saved=store.saveCompletion(expected.result());
        if(saved==ProfileStore.CompletionSave.FAILED) {
            notifyUser(store.warning);
            return false;
        }
        pendingCompletions=pendingCompletions.stream().filter(item->item!=expected).toList();
        if(screen==Screen.RESULTS && attemptId.equals(expected.result().id())) newBest=saved==ProfileStore.CompletionSave.BEST;
        notifyUser("原成绩已保存。");
        return true;
    }

    public List<Racer> standings() {
        if (online() && !onlineCars.isEmpty()) {
            var frame=raceRoom.frame();
            if(frame!=null) return frame.standings().stream().map(state -> onlineCars.get(state.id())).filter(Objects::nonNull).toList();
        }
        if(mode==Mode.EVENT_RACE && eventRaceTiming!=null && racers.size()==EventSeries.DRIVERS)
            return eventRaceStandings().stream().map(row->racers.get(row.car().driver())).toList();
        if(mode==Mode.QUALIFYING && qualifying!=null)
            return qualifying.gridOrder().stream().map(racers::get).toList();
        if(localResultOrder!=null) return localResultOrder;
        return localStandings(null);
    }

    private List<Racer> localStandings(ReplayFinishSample[] samples) {
        if(racers.size()<2) return new ArrayList<>(racers);
        boolean rankToMilliseconds=mode==Mode.RACE && !online();
        record RankedRacer(int driver,Racer racer,boolean finished,boolean retired,double result,float distance) {}
        var ranked=new ArrayList<RankedRacer>(racers.size());
        for(int driver=0;driver<racers.size();driver++) {
            var racer=racers.get(driver);
            boolean finished=racer.progress.finished();
            float distance=finished?0:samples!=null?samples[driver].distance():racer.progress.raceDistance(racer.car.position());
            ranked.add(new RankedRacer(driver,racer,finished,racer.retired,racer.result(),distance));
        }
        ranked.sort((a,b)->{
            if(rankToMilliseconds && a.finished() && b.finished())
                return EventSeries.compareFinishers(a.result(),a.driver(),b.result(),b.driver());
            if(a.finished() || b.finished()) return Double.compare(a.result(),b.result());
            if(a.retired()!=b.retired()) return a.retired()?1:-1;
            return Float.compare(b.distance(),a.distance());
        });
        var ordered=new ArrayList<Racer>(ranked.size());
        for(var row:ranked) ordered.add(row.racer());
        return ordered;
    }
    public int position() { return standings().indexOf(racers.get(0)) + 1; }
    public String difficulty() { return new String[]{"轻松", "标准", "挑战"}[store.profile.difficulty]; }
    public String modeName() {
        if(online()) return mode==Mode.QUALIFYING?"联机单圈排位":mode==Mode.RACE?"方程式联机正赛":"方程式共同练习";
        return switch (mode) {
            case RACE -> "方程式短赛";
            case FREE_CIRCUIT -> "赛道自由练习";
            case FREE_PAD -> "训练场自由驾驶";
            case LESSON -> practice != null ? practice.lesson.title : "专项练习";
            case CUP -> "杯架稳定测试";
            case TIME_TRIAL -> "计时挑战";
            case CHALLENGE -> challenge==null?"短局挑战":challenge.kind().title;
            case EVENT_PRACTICE -> "赛事自由练习";
            case QUALIFYING -> "单圈排位";
            case EVENT_RACE -> "赛事正赛";
            case MAP_TRIAL -> "自制地图四车验证";
            case TUNING_TRIAL -> "A／B 调校试车";
            case WORKSHOP -> workshop==null?"实验委托":workshop.kind().title;
            case EXPERIENCE -> experience==null?"赛道分段攻关":experience.course().title();
            case MAP_SOLO -> "作者单车试驾";
            case MAP_SECTION -> equipmentSectionActive()?"整圈区间练习":"作者区间试驾";
            case ACTIVITY -> "活动卡 · "+(activityTrial==null?"原厂组合":activityTrial.stage().kind().title);
        };
    }
    private void updateCamera(float dt) {
        // The pixel scene owns its camera. Only an enabled 3D viewport needs this pose.
        if (player == null || !viewPort.isEnabled()) return;
        Vector3f p = player.position(), forward = player.forward(); forward.y = 0; forward.normalizeLocal();
        Vector3f desired, aim;
        if (screen == Screen.MENU || screen == Screen.GARAGE || screen == Screen.COURSES || screen == Screen.CHALLENGES || screen==Screen.BOOK || screen==Screen.JOURNAL || screen==Screen.EVENTS || screen == Screen.TRACKS || screen == Screen.SETTINGS) {
            float a = sceneClock * .10f + (screen == Screen.GARAGE ? 1.1f : 2.2f);
            desired = p.add((float) Math.cos(a) * 9, 3.7f, (float) Math.sin(a) * 9);
            aim = p.add(0, .5f, 0);
        } else if (store.profile.camera == 1) {
            desired = p.add(forward.mult(.15f)).addLocal(0, 1.05f, 0);
            aim = p.add(forward.mult(30)).addLocal(0, .9f, 0);
        } else if (store.profile.camera == 2) {
            desired = p.subtract(forward.mult(4)).addLocal(0, 24, .5f);
            aim = p.add(forward.mult(5));
        } else {
            desired = p.subtract(forward.mult(10 + Math.min(4, player.speed() * .04f))).addLocal(0, 4.5f, 0);
            aim = p.add(forward.mult(5)).addLocal(0, .8f, 0);
        }
        cam.getLocation().interpolateLocal(desired, 1 - (float) Math.exp(-dt * (store.profile.camera == 1 ? 15 : 5)));
        cam.lookAt(aim, Vector3f.UNIT_Y);
    }
    private void cameraImmediate() {
        if (hud != null) hud.resetCamera();
        if (player != null && viewPort.isEnabled()) {
            cam.setLocation(player.position().add(0, 6, -12));
            updateCamera(1);
        }
    }
    public boolean isTraining() { return world != null && world.training; }
    public void capture(String name) {
        if (screenshots == null) {if(!smoke) notifyUser("当前画面暂不可截图。");return;}
        updateCaptureNotices();
        String prefix="manual-".equals(name)?"manual-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+"-":name;
        var ticket=screenshots.submit(prefix);
        if(ticket.outcome().phase()==ScreenCapture.Phase.REJECTED) {
            if(!smoke) notifyUser(ticket.outcome().reason()==ScreenCapture.Reason.QUEUE_FULL?"截图队列已满，请稍后再试。":"截图未排队，请检查画面或截图名称。");
            return;
        }
        if (smoke) toastLeft = 0;
        if (hud != null) hud.render();
        if(!smoke) {captureNotices.addLast(ticket);notifyUser("截图已排队，等待截取画面。");}
    }
    public ScreenCapture.Status captureStatus() {return screenshots==null?null:screenshots.status();}
    private void updateCaptureNotices() {
        if(captureNotices.isEmpty()) return;
        int saved=0,failed=0,cancelled=0;
        ScreenCapture.Outcome last=null;
        for(var iterator=captureNotices.iterator();iterator.hasNext();) {
            var outcome=iterator.next().outcome();
            switch(outcome.phase()) {
                case PENDING -> {continue;}
                case SAVED -> saved++;
                case CANCELLED -> cancelled++;
                default -> failed++;
            }
            last=outcome;
            iterator.remove();
        }
        if(last==null) return;
        if(saved+failed+cancelled>1) {
            var results=new ArrayList<String>(3);
            if(saved>0) results.add("已保存 "+saved+" 张");
            if(failed>0) results.add("失败 "+failed+" 张");
            if(cancelled>0) results.add("取消 "+cancelled+" 张");
            notifyUser("截图"+String.join("，",results)+"。");
            return;
        }
        notifyUser(switch(last.phase()) {case SAVED->"截图已保存到截图目录。";case CANCELLED->"截图已取消：渲染画面已关闭。";case FAILED->last.reason()==ScreenCapture.Reason.SURFACE_TOO_LARGE?"画面尺寸过大，截图未保存。":"截图未保存，请检查截图目录或稍后重试。";default->"截图未保存，请稍后重试。";});
    }

    private void smokeUpdate(float dt) {
        if(screenshots!=null && screenshots.pending()>0) {
            long now=System.nanoTime();
            if(smokeCaptureWaitStarted==0) smokeCaptureWaitStarted=now;
            if(now-smokeCaptureWaitStarted<java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) return;
            smokeReport.put("screenshotsFlushed",false);
            smokeReport.put("passed",false);
            writeSmokeReportAndStop();
            return;
        }
        smokeCaptureWaitStarted=0;
        if(smokeFinishPending) {writeSmokeReportAndStop();return;}
        smokeClock += dt;
        if(scenario.equals("axle-suspension")) {
            verifyAxleSuspension();
        } else if(scenario.equals("vehicle-observation")) {
            verifyVehicleObservation();
        } else if(scenario.equals("brake-balance")) {
            verifyBrakeBalance();
        } else if(scenario.equals("setup-library")) {
            verifySetupLibrary();
        } else if(scenario.equals("setup-guide")) {
            verifyGuidedSetup();
        } else if(scenario.equals("audio")) {
            verifyAudioContent();
        } else if (scenario.equals("pack")) {
            verifyMultiPack();
        } else if(scenario.equals("editor-crud")) {
            verifyEditorCrud();
        } else if(scenario.equals("navigation")) {
            verifyNavigation();
        } else if (scenario.equals("editor")) {
            verifyCircuitEditing();
        } else if (scenario.equals("time-trial")) {
            verifyTimeTrial(dt);
        } else if (scenario.startsWith("formula-")) {
            verifyFormulaNetwork(dt);
        } else if (scenario.equals("pixel")) {
            verifyPixelPresentation();
        } else if (scenario.equals("comparison")) {
            verifyComparison(dt);
        } else if (scenario.equals("verify")) {
            verifyFullFlow(dt);
        } else if (scenario.equals("mini4wd")) {
            verifyMini4wd();
        } else if (scenario.startsWith("network-")) {
            verifyNetwork(dt);
        } else if (scenario.equals("tour")) {
            float[] times = {2, 3, 4, 5, 7, 9, 11, 13, 15, 17, 20};
            if (smokeStep < times.length && smokeClock >= times[smokeStep]) {
                switch (smokeStep) {
                    case 0 -> capture("01-menu-");
                    case 1 -> open(Screen.COURSES);
                    case 2 -> capture("02-practice-menu-");
                    case 3 -> { mainMenu(); open(Screen.GARAGE); changeTuning("brakes", .1f); savePreset("A"); }
                    case 4 -> capture("03-garage-");
                    case 5 -> start(Mode.LESSON, PracticeSession.Lesson.SLALOM);
                    case 6 -> capture("04-training-");
                    case 7 -> start(Mode.RACE, null);
                    case 8 -> capture("05-race-");
                    case 9 -> { start(Mode.CUP, null); capture("06-cup-"); }
                    case 10 -> smokeFinish();
                }
                smokeStep++;
            }
        } else if (scenario.equals("race") || scenario.equals("cup")) {
            if (smokeStep == 0 && smokeClock > 12) { capture("driving-"); smokeStep++; }
            if (screen == Screen.RESULTS && smokeStep < 3) {
                capture("result-"); smokeStep = 3; smokeClock = 0;
                smokeReport.put("allFinished", racers.stream().allMatch(r -> r.progress.finished()));
                smokeReport.put("playerLaps", racers.get(0).progress.completedLaps());
                smokeReport.put("physicsObjects", physics.getPhysicsSpace().getRigidBodyList().size());
            }
            if (smokeStep == 3 && smokeClock > 2) smokeFinish();
            if (smokeClock > 260) { smokeReport.put("timeout", true); smokeFinish(); }
        } else {
            if (smokeStep == 0 && smokeClock > 3) { capture("scene-"); smokeStep++; }
            if (smokeClock > 6) smokeFinish();
        }
    }

    private void smokeInputClick(int x,int y) {
        if(hud==null) hud=new HudRenderer(this);hud.render();inputManager.getCursorPosition().set(x*getCamera().getWidth()/1280f,getCamera().getHeight()-y*getCamera().getHeight()/800f);action("click",true,0);action("click",false,0);hud.render();
    }
    private String brakeSmokeLegacy;
    private DriverAI suspensionSmokeDriver;
    private float suspensionSmokeStepAt;
    private String suspensionSmokeLegacy,suspensionSmokeSaved;
    private Tuning suspensionSmokeTuning;
    private void verifyAxleSuspension() {
        if(smokeClock>100) {smokeReport.put("passed",false);smokeReport.put("timeout",true);smokeFinish();return;}
        if(smokeClock<1 || smokeClock-suspensionSmokeStepAt<1) return;
        switch(smokeStep) {
            case 0 -> {open(Screen.GARAGE);capture("suspension-01-garage-base-");smokeStep++;}
            case 1 -> {
                smokeInputClick(380,90);smokeInputClick(470,246);smokeInputClick(320,322);smokeInputClick(470,398);smokeInputClick(320,474);suspensionSmokeTuning=store.profile.tuning;
                smokeReport.put("fourGarageControls",suspensionSmokeTuning.frontSpring()==1.01f && suspensionSmokeTuning.rearSpring()==.99f && suspensionSmokeTuning.frontDamping()==1.01f && suspensionSmokeTuning.rearDamping()==.99f);capture("suspension-02-garage-axes-");smokeStep++;
            }
            case 2 -> {
                smokeInputClick(90,620);smokeInputClick(470,246);smokeInputClick(200,620);smokeReport.put("presetAndRestart",suspensionSmokeTuning.equals(new ProfileStore(store.directory()).profile.tuning));
                openTuningGuide();smokeInputClick(200,569);smokeInputClick(200,317);capture("suspension-03-guide-");smokeStep++;
            }
            case 3 -> {smokeInputClick(600,684);capture("suspension-04-trial-confirm-");smokeInputClick(450,537);smokeReport.put("trialCancel",tuningPlan()==null);smokeInputClick(600,684);smokeStep++;}
            case 4 -> {
                smokeInputClick(800,537);suspensionSmokeDriver=new DriverAI(circuit,.78f,0);var plan=tuningPlan();
                smokeReport.put("oneFrozenChange",plan!=null && plan.changedSettings()==1 && plan.a().equals(suspensionSmokeTuning) && plan.b().equals(suspensionSmokeTuning.change("frontSpring",.1f)));
                smokeReport.put("actualWheelParameters",Math.abs(player.body.getWheel(0).getSuspensionStiffness()-vehicleSpec.suspensionStiffness*player.tuning.springRatio(true))<.0001f);smokeStep++;
            }
            case 5 -> {if(countdown<=0 && sessionTime>.5) {capture("suspension-05-live-");smokeStep++;}}
            case 6 -> {
                if(screen==Screen.RESULTS) {
                    smokeReport.put("actualCornerRun",tuningTrial.state()==TuningTrial.State.SUCCESS && !suspensionSmokeDriver.needsRecovery);
                    smokeReport.put("reportPersisted",!tuningStore.reports().isEmpty() && !tuningStore.reports().get(0).runs().isEmpty() && tuningStore.reports().get(0).runs().get(0).measurement().observation()!=null);
                    openTuningLab();capture("suspension-06-report-");smokeInputClick(1040,256);capture("suspension-07-response-");smokeStep++;
                }
            }
            case 7 -> {
                mainMenu();open(Screen.GARAGE);openSetupLibrary();var old=new SetupCard(UUID.randomUUID().toString(),"旧版前制动60%","原档保留，按新物理另存。",vehicleSpec.id,"fw-physics-3-brake-balance",circuit.id,circuit.name,circuit.geometryKey(),ControlIdentity.KEYBOARD,new Tuning(1,1,1,1,0,.6f));suspensionSmokeLegacy=old.id();importNamedSetup(old);
                smokeReport.put("oldPhysicsBlocked",!applyNamedSetup(old.id()));capture("suspension-08-legacy-");smokeStep++;
            }
            case 8 -> {smokeInputClick(1100,568);capture("suspension-09-migration-");smokeInputClick(450,537);smokeReport.put("migrationCancel",setupLibrary.entries().size()==1);smokeInputClick(1100,568);smokeStep++;}
            case 9 -> {
                smokeInputClick(800,537);var copy=setupLibrary.entries().get(0);smokeReport.put("legacyRetained",setupLibrary.find(suspensionSmokeLegacy)!=null && setupLibrary.entries().size()==2);
                smokeReport.put("migrationKeepsBiasAndNeutralAxes",copy.physics().equals(vehicleSpec.physicsVersion) && copy.tuning().brakeBias()==.6f && copy.tuning().frontSpring()==1 && copy.tuning().rearSpring()==1 && copy.tuning().frontDamping()==1 && copy.tuning().rearDamping()==1);smokeStep++;
            }
            case 10 -> {
                var saved=saveNamedSetup("前后悬挂试车","只改变前弹簧的连续弯对照；先看观测。");suspensionSmokeSaved=saved.id();smokeInputClick(220,290);smokeInputClick(1100,287);capture("suspension-10-library-axes-");
                Path file=store.directory().resolve("setup-export-suspension-smoke.json");exportNamedSetup(saved.id(),file);
                try {smokeReport.put("versionThreeExchange",SetupLibraryStore.readShare(file).equals(saved));}catch(java.io.IOException error) {smokeReport.put("versionThreeExchange",false);}smokeStep++;
            }
            case 11 -> {
                store.profile.tuning=Tuning.STOCK.withLivery(4);smokeReport.put("applyKeepsAllFactors",applyNamedSetup(suspensionSmokeSaved) && store.profile.tuning.equals(suspensionSmokeTuning.withLivery(4)));
                smokeReport.put("noLapAwards",store.profile.bestTimes.isEmpty());smokeReport.put("libraryAfterReload",new SetupLibraryStore(store.directory()).entries().equals(setupLibrary.entries()));smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish();
            }
        }
        suspensionSmokeStepAt=smokeClock;
    }
    private float observationSmokeStepAt;
    private void verifyVehicleObservation() {
        if(smokeClock>110) {smokeReport.put("passed",false);smokeReport.put("timeout",true);smokeFinish();return;}
        if(smokeClock<1 || smokeClock-observationSmokeStepAt<1) return;
        switch(smokeStep) {
            case 0 -> {store.profile.presets.put("A",Tuning.STOCK);store.profile.presets.put("B",Tuning.STOCK.change("suspension",.1f));openTuningLab();beginTuningExperiment(TuningTrial.Exercise.ACCELERATION);smokeStep++;}
            case 1 -> {if(countdown<=0 && player.observation()!=null) {capture("observation-01-live-");smokeReport.put("liveNativeReadings",player.observation()!=null);smokeStep++;}}
            case 2 -> {if(screen==Screen.RESULTS) {smokeReport.put("actualTrialSucceeded",tuningTrial.state()==TuningTrial.State.SUCCESS);smokeReport.put("trialSummarySaved",!tuningStore.reports().isEmpty() && tuningStore.reports().get(0).runs().get(0).measurement().observation()!=null);openTuningLab();capture("observation-02-report-controls-");smokeStep++;}}
            case 3 -> {smokeInputClick(1040,256);capture("observation-03-report-response-");smokeReport.put("reportTogglePreservesRun",tuningReport().runs().size()==1);smokeStep++;}
            case 4 -> {mainMenu();start(Mode.TIME_TRIAL,null);smokeStep++;}
            case 5 -> {
                if(timeTrial.best()!=null) {
                    var book=telemetryStore.load(timeTrial.telemetry().conditions());
                    smokeReport.put("actualLapObserved",!book.recent().isEmpty() && book.recent().get(0).samples().stream().allMatch(sample->sample.observation()!=null));
                    smokeReport.put("actualLapSamples",book.recent().get(0).samples().size());pause();openTelemetry();capture("observation-04-lap-controls-");smokeStep++;
                }
            }
            case 6 -> {smokeInputClick(1060,722);capture("observation-05-lap-response-");smokeStep++;}
            case 7 -> {
                smokeInputClick(500,350);capture("observation-06-linked-position-");smokeReport.put("linkedCursor",telemetryDistance()>0);
                var p=player.position();double clock=sessionTime;var id=telemetryComparison().a().id();exportTelemetry(store.directory().resolve("observed-lap.csv"));
                smokeReport.put("csvExported",Files.isRegularFile(store.directory().resolve("observed-lap.csv")));
                smokeReport.put("analysisRemainsPassive",sessionTime==clock && p.distance(player.position())==0 && id.equals(telemetryComparison().a().id()));smokeStep++;
            }
            case 8 -> {
                // Compatibility fixture: strip only the optional readings from the actual completed lap.
                var lap=telemetryComparison().a();var legacy=new TelemetryLap(1,UUID.randomUUID().toString(),System.currentTimeMillis(),lap.conditions(),lap.livery(),lap.duration(),lap.sectors(),lap.samples().stream().map(s->new TelemetryLap.Sample(s.time(),s.distance(),s.x(),s.z(),s.yaw(),s.driving())).toList());
                telemetryStore.record(legacy);backFromTelemetry();openTelemetry();capture("observation-07-legacy-mixed-");smokeReport.put("legacyFixtureHasNoReadings",telemetryComparison().a().samples().stream().allMatch(s->s.observation()==null));smokeStep++;
            }
            case 9 -> {smokeInputClick(1060,722);capture("observation-08-legacy-controls-");backFromTelemetry();smokeReport.put("returnsPaused",screen==Screen.PAUSED);smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish();}
        }
        observationSmokeStepAt=smokeClock;
    }
    private void verifyBrakeBalance() {
        if(smokeClock<1+smokeStep) return;
        switch(smokeStep++) {
            case 0 -> {open(Screen.GARAGE);capture("brakes-01-garage-");}
            case 1 -> {smokeInputClick(470,322);smokeReport.put("garageBiasButton",Math.abs(store.profile.tuning.brakeBias()-.51f)<.001f);changeTuning("brakeBias",.09f);capture("brakes-02-front-60-");}
            case 2 -> {smokeReport.put("biasPersisted",Math.abs(new ProfileStore(store.directory()).profile.tuning.brakeBias()-.6f)<.001f);openTuningGuide();smokeInputClick(200,569);capture("brakes-03-guide-front-");}
            case 3 -> {smokeInputClick(200,250);capture("brakes-04-guide-rear-");smokeInputClick(600,684);capture("brakes-05-trial-confirm-");}
            case 4 -> {smokeInputClick(450,537);smokeReport.put("trialCancel",screen==Screen.TUNING_GUIDE && tuningPlan()==null);smokeInputClick(600,684);}
            case 5 -> {
                smokeInputClick(800,537);var plan=tuningPlan();smokeReport.put("biasTrialFrozen",mode==Mode.TUNING_TRIAL && plan!=null && plan.changedSettings()==1 && Math.abs(plan.a().brakeBias()-.6f)<.001f && Math.abs(plan.b().brakeBias()-.55f)<.001f);
                player.drive(1/120f,0,1,0);float q=vehicleSpec.brakeForce*player.tuning.brakes();smokeReport.put("actualWheelInputs",Math.abs(player.body.getWheel(0).getBrake()-2*q*player.tuning.brakeBias())<.001f && Math.abs(player.body.getWheel(2).getBrake()-2*q*(1-player.tuning.brakeBias()))<.001f);capture("brakes-06-driving-");
            }
            case 6 -> {openTuningLab();capture("brakes-07-report-");backFromTuningLab();openSetupLibrary();var old=new SetupCard(UUID.randomUUID().toString(),"旧物理配置","保留原档；按当前物理另存后重新试车。",vehicleSpec.id,"fw-physics-2-speed-limits",circuit.id,circuit.name,circuit.geometryKey(),ControlIdentity.KEYBOARD,Tuning.STOCK);brakeSmokeLegacy=old.id();importNamedSetup(old);capture("brakes-08-legacy-");}
            case 7 -> {smokeReport.put("oldSetupBlocked",!applyNamedSetup(brakeSmokeLegacy));smokeInputClick(1100,568);capture("brakes-09-migration-confirm-");}
            case 8 -> {smokeInputClick(450,537);smokeReport.put("migrationCancel",setupLibrary.entries().size()==1);smokeInputClick(1100,568);}
            case 9 -> {smokeInputClick(800,537);smokeReport.put("legacyRetained",setupLibrary.entries().size()==2 && setupLibrary.find(brakeSmokeLegacy)!=null);var copy=setupLibrary.entries().get(0);smokeReport.put("newCopyNeutral",copy.physics().equals(vehicleSpec.physicsVersion) && copy.tuning().brakeBias()==.5f);capture("brakes-10-current-copy-");}
            case 10 -> {smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish();}
        }
    }
    private void verifySetupLibrary() {
        if(smokeClock<1+smokeStep) return;
        switch(smokeStep++) {
            case 0 -> { open(Screen.GARAGE);capture("library-01-garage-"); }
            case 1 -> { smokeInputClick(990,293);smokeReport.put("libraryEntry",screen==Screen.SETUP_LIBRARY);capture("library-02-empty-"); }
            case 2 -> {
                var original=store.profile.tuning;var a=saveNamedSetup("原厂基准","先熟悉路线，保持相同操作条件。");
                store.profile.tuning=new Tuning(1.05f,1.15f,1,.9f,3);var b=saveNamedSetup("林地制动与直线对照","观察同速停车距离，再比较连续弯；不凭一次圈速决定。");store.profile.tuning=original;
                smokeReport.put("namedSaved",a!=null && b!=null);capture("library-03-compare-");
            }
            case 3 -> { smokeInputClick(670,618);capture("library-04-apply-confirm-"); }
            case 4 -> { smokeInputClick(450,537);smokeReport.put("applyCancel",store.profile.tuning.equals(Tuning.STOCK));smokeInputClick(670,618); }
            case 5 -> { smokeInputClick(800,537);smokeReport.put("performanceApplied",store.profile.tuning.brakes()>1.1f && store.profile.tuning.livery()==0); }
            case 6 -> {
                var b=setupLibrary.entries().get(0);Path exchange=store.directory().resolve("setup-export-smoke.json");exportNamedSetup(b.id(),exchange);
                try { var incoming=SetupLibraryStore.readShare(exchange);smokeReport.put("exchangeRoundTrip",incoming.equals(b));importNamedSetup(incoming);smokeReport.put("repeatImportNoDuplicate",setupLibrary.entries().size()==2); }catch(java.io.IOException error) {smokeReport.put("exchangeRoundTrip",false);}
                smokeReport.put("libraryAfterReload",new SetupLibraryStore(store.directory()).entries().equals(setupLibrary.entries()));
                smokeInputClick(880,618);smokeInputClick(1100,618);capture("library-05-pinned-");
            }
            case 7 -> {
                var a=setupLibrary.entries().get(1);var b=setupLibrary.entries().get(0);var original=store.profile.tuning;var presets=new LinkedHashMap<>(store.profile.presets);
                beginLibraryTuning(a,b.id(),TuningTrial.Exercise.BRAKING);smokeReport.put("sixRunPlan",mode==Mode.TUNING_TRIAL && tuningPlan()!=null && tuningPlan().a().brakes()==1 && tuningPlan().b().brakes()>1.1f);
                smokeReport.put("presetsPreserved",presets.equals(store.profile.presets) && original.equals(store.profile.tuning));capture("library-06-trial-");
            }
            case 8 -> { openTuningLab();backFromTuningLab();smokeReport.put("returnsToLibrary",screen==Screen.SETUP_LIBRARY);capture("library-07-return-"); }
            case 9 -> { smokeReport.put("passed",List.of("libraryEntry","namedSaved","applyCancel","performanceApplied","exchangeRoundTrip","repeatImportNoDuplicate","libraryAfterReload","sixRunPlan","presetsPreserved","returnsToLibrary").stream().allMatch(key->Boolean.TRUE.equals(smokeReport.get(key))));smokeFinish(); }
        }
    }
    private void verifyGuidedSetup() {
        if(smokeClock<1+smokeStep) return;
        switch(smokeStep++) {
            case 0 -> { if(hud!=null) smokeInputClick(1000,497);else openDrivingGuide();smokeReport.put("guideEntry",screen==Screen.DRIVING_GUIDE); }
            case 1 -> { chooseGuideTrack("woodland-technical");capture("setup-01-forest-guide-"); }
            case 2 -> { chooseGuideTrack("park-highspeed");capture("setup-02-park-guide-"); }
            case 3 -> {
                chooseGuideTrack("dockside-street");prepareGuideDrive(Mode.RACE);
                smokeReport.put("easyOneLap",launchSetup!=null && launchSetup.preview.track().id.equals("dockside-street") && launchSetup.difficulty==0 && launchSetup.laps==1);
                cancelLaunch();capture("setup-03-street-guide-");
            }
            case 4 -> { if(hud!=null) smokeInputClick(940,77);else openKeyboard();smokeReport.put("keyboardEntry",screen==Screen.KEYBOARD);capture("setup-04-keyboard-"); }
            case 5 -> { if(hud!=null) smokeInputClick(300,215);else captureKeyboardKey(KeyboardSettings.Command.THROTTLE,false);capture("setup-05-key-capture-"); }
            case 6 -> {
                acceptKeyboardKey(KeyInput.KEY_I);smokeReport.put("keySaved",new KeyboardStore(store.directory()).settings().bindings().get(KeyboardSettings.Command.THROTTLE).primary()==KeyInput.KEY_I);
                capture("setup-06-key-changed-");backFromKeyboard();mainMenu();open(Screen.GARAGE);
            }
            case 7 -> { if(hud!=null) smokeInputClick(1000,345);else openTuningGuide();smokeReport.put("tuningGuideEntry",screen==Screen.TUNING_GUIDE);capture("setup-07-tuning-guide-"); }
            case 8 -> { if(hud!=null) smokeInputClick(200,315);capture("setup-08-low-drag-"); }
            case 9 -> { if(hud!=null) smokeInputClick(600,684);capture("setup-09-trial-confirm-"); }
            case 10 -> { if(hud!=null) { smokeInputClick(450,537);smokeReport.put("trialCancel",screen==Screen.TUNING_GUIDE && tuningPlan()==null);smokeInputClick(600,684); } }
            case 11 -> {
                if(hud!=null) smokeInputClick(800,537);else beginGuidedTuning(TuningGuide.Goal.LOW_DRAG,store.profile.tuning);
                smokeReport.put("singleVariableTrial",tuningPlan()!=null && tuningPlan().changedSettings()==1 && tuningPlan().conditions().exercise()==TuningTrial.Exercise.FLOW);
                smokeReport.put("presetsPreserved",store.profile.presets.isEmpty());capture("setup-10-trial-driving-");
            }
            case 12 -> { openTuningLab();capture("setup-11-trial-report-"); }
            case 13 -> {
                smokeReport.put("keyboardAfterRestart",new KeyboardStore(store.directory()).settings().equals(keyboard.settings()));
                if(!headless) smokeReport.put("screenshotsSaved",screenshots!=null && screenshots.saved>=11 && screenshots.errors==0);
                smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish();
            }
        }
    }
    private void smokeText(String value) { hud.textKey(new KeyInputEvent(KeyInput.KEY_A,'a',true,false),true);for(char c:value.toCharArray()) hud.textKey(new KeyInputEvent(KeyInput.KEY_UNKNOWN,c,true,false),false); }
    private void verifyAudioContent() {
        if(smokeClock<(smokeStep+1)*1.2f) return;
        switch(smokeStep) {
            case 0 -> { gainFocus();mainMenu(); }
            case 1 -> { smokeReport.put("menuMusicPlaying",headless || audio!=null && audio.musicPlaying() && audio.track()==MusicTrack.MENU);open(Screen.SETTINGS);capture("audio-01-settings-"); }
            case 2 -> { smokeInputClick(850,235);smokeReport.put("musicToggleSaved",!new ProfileStore(store.directory()).profile.music); }
            case 3 -> { smokeReport.put("musicMuted",headless || audio!=null && !audio.musicPlaying());smokeInputClick(850,235);smokeInputClick(850,420);smokeInputClick(960,316);smokeInputClick(400,368); }
            case 4 -> { smokeReport.put("menuPreview",musicPreviewing() && musicTrack()==MusicTrack.MENU);smokeReport.put("independentEffectsMute",!store.profile.sound && (headless || audio!=null && audio.musicPlaying()));smokeInputClick(400,368); }
            case 5 -> { smokeReport.put("workshopPreview",musicTrack()==MusicTrack.WORKSHOP && (headless || audio!=null && audio.track()==MusicTrack.WORKSHOP && audio.musicPlaying()));capture("audio-02-workshop-preview-");smokeInputClick(400,368); }
            case 6 -> { smokeReport.put("racePreview",musicTrack()==MusicTrack.RACE && (headless || audio!=null && audio.track()==MusicTrack.RACE && audio.musicPlaying()));smokeInputClick(400,368); }
            case 7 -> { smokeReport.put("miniPreview",musicTrack()==MusicTrack.MINI && (headless || audio!=null && audio.track()==MusicTrack.MINI && audio.musicPlaying()));smokeInputClick(850,368);smokeInputClick(850,420);start(Mode.RACE,null); }
            case 8 -> { smokeReport.put("engineAndRaceMusic",headless || audio!=null && audio.track()==MusicTrack.RACE && audio.musicPlaying() && audio.enginePlaying());capture("audio-03-racing-");pause(); }
            case 9 -> { smokeReport.put("pauseStopsEngine",headless || audio!=null && !audio.enginePlaying());mainMenu();openCircuitEditor(); }
            case 10 -> { smokeReport.put("editorSceneMusic",musicTrack()==MusicTrack.WORKSHOP && (headless || audio!=null && audio.musicPlaying() && !audio.enginePlaying()));openMiniHome(); }
            case 11 -> { smokeReport.put("miniSceneMusic",musicTrack()==MusicTrack.MINI && (headless || audio!=null && audio.musicPlaying()));loseFocus(); }
            case 12 -> { smokeReport.put("backgroundSilent",headless || audio!=null && !audio.musicPlaying() && !audio.enginePlaying());gainFocus(); }
            case 13 -> { smokeReport.put("focusResumesMusic",headless || audio!=null && audio.musicPlaying());openActivities(); }
            case 14 -> { for(int i=0;i<3;i++) smokeInputClick(630,190);capture("audio-04-technical-template-");smokeInputClick(750,190); }
            case 15 -> { capture("audio-05-activity-editor-");smokeInputClick(1000,250);smokeReport.put("technicalTemplateSaved",activityStore.cards().size()==1 && activityStore.cards().get(0).name().equals("林地技术练习")); }
            case 16 -> { smokeInputClick(630,190);capture("audio-06-highspeed-template-"); }
            case 17 -> { smokeInputClick(630,190);capture("audio-07-street-template-"); }
            case 18 -> { smokeInputClick(630,190);capture("audio-08-advanced-template-");smokeInputClick(750,190); }
            case 19 -> { smokeInputClick(1000,250);smokeReport.put("advancedTemplateSaved",activityStore.cards().size()==2 && activityStore.cards().get(1).stages().stream().allMatch(s->s.laps()==3 && s.difficulty()==2)); }
            case 20 -> { if(!headless) smokeReport.put("screenshots",screenshots!=null && screenshots.saved>=8 && screenshots.errors==0);smokeReport.put("audioRenderer",headless?"headless exclusion":getAudioRenderer().getClass().getName());smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish(); }
        }
        smokeStep++;
    }
    private void verifyEditorCrud() {
        if(smokeClock<smokeStep+1) return;
        switch(smokeStep) {
            case 0 -> { smokeReport.put("startsEmpty",circuitEditor==null && circuits.all().isEmpty());capture("crud-01-empty-"); }
            case 1 -> { smokeInputClick(200,400);circuitEditor.rename("第一张草稿");saveCircuitDraft(); }
            case 2 -> { newCircuit(null);circuitEditor.rename("第二张草稿");circuitEditor.width(4);saveCircuitDraft();hud.showCircuitHistory(); }
            case 3 -> smokeInputClick(1050,706);
            case 4 -> { smokeReport.put("deleteDialogOpened",hud.confirming());capture("crud-02-delete-"); }
            case 5 -> { smokeInputClick(450,537);smokeReport.put("mouseCancelWorks",!hud.typing() && circuitEditor.document().name().equals("第二张草稿"));smokeInputClick(1050,706);smokeInputClick(800,537); }
            case 6 -> { smokeReport.put("previousDraftAndProperties",circuitEditor.document().name().equals("第一张草稿") && circuitEditor.document().width()==16);capture("crud-03-previous-"); }
            case 7 -> { hud.showCircuitLibrary();smokeInputClick(530,714);smokeInputClick(1100,714);smokeInputClick(800,537);smokeReport.put("unsavedDraftDiscarded",circuits.all().size()==1 && circuitEditor.document().name().equals("第一张草稿")); }
            case 8 -> { smokeInputClick(1100,714);smokeInputClick(800,537);smokeReport.put("lastDeletionEmpty",circuitEditor==null && circuits.all().isEmpty());capture("crud-04-empty-library-"); }
            case 9 -> { smokeInputClick(530,714);smokeInputClick(730,714);smokeText("名".repeat(80));capture("crud-05-name-limit-"); }
            case 10 -> { smokeInputClick(450,474);smokeReport.put("mapNameLimit",circuitEditor.document().name().length()==60);smokeInputClick(730,714);smokeInputClick(880,410);smokeInputClick(450,474);smokeReport.put("defaultNameRestored",circuitEditor.document().name().equals("我的环线"));saveCircuitDraft(); }
            case 11 -> {
                var bad=CircuitDocument.create().points(List.of(new CircuitDocument.Point(-30,-30),new CircuitDocument.Point(30,30),new CircuitDocument.Point(-30,30),new CircuitDocument.Point(30,-30)));circuits.save(bad,null);editCircuitDraft(bad.id());hud.showCircuitDiagnostics();capture("crud-06-issues-");
            }
            case 12 -> { inputManager.getCursorPosition().set(1070*getCamera().getWidth()/1280f,400*getCamera().getHeight()/800f);hud.zoomEditing(false);smokeInputClick(1070,600);smokeReport.put("wheelSelectsNextIssue",circuitEditor.inspection().issues().size()>1 && circuitEditor.selectedAnchor()==circuitEditor.inspection().issues().get(1).anchor());capture("crud-07-next-issue-"); }
            case 13 -> { openCreation();smokeInputClick(600,192);smokeInputClick(200,251);smokeText("新".repeat(60));capture("crud-08-creation-text-"); }
            case 14 -> { smokeInputClick(880,410);smokeInputClick(450,474);smokeInputClick(200,303);smokeText("备".repeat(400));capture("crud-09-notes-limit-"); }
            case 15 -> { smokeInputClick(880,410);smokeInputClick(450,474);openActivities();smokeInputClick(200,190);smokeInputClick(200,250);smokeText("活动名称");smokeInputClick(450,474);smokeInputClick(200,718);smokeText("注".repeat(300));capture("crud-10-activity-notes-"); }
            case 16 -> { smokeInputClick(450,474);smokeInputClick(1000,250);smokeReport.put("activitySaved",activityStore.cards().size()==1 && !activityStore.pending());capture("crud-11-activity-library-"); }
            case 17 -> { smokeInputClick(1050,593);smokeInputClick(450,537);smokeReport.put("activityDeleteCancel",activityStore.cards().size()==1);smokeInputClick(1050,593);smokeInputClick(800,537);smokeReport.put("activityDeleteConfirm",activityStore.cards().isEmpty()); }
            case 18 -> { openMiniHome();smokeInputClick(200,475); }
            case 19 -> { smokeReport.put("miniEditor",screen==Screen.MINI4WD && mini4wd.view()==Mini4wdGame.View.EDITOR);smokeInputClick(830,50);smokeText("车".repeat(40));capture("crud-12-mini-name-"); }
            case 20 -> { smokeInputClick(880,410);smokeInputClick(450,474);smokeReport.put("miniNameBounded",mini4wd.activeName().length()<=24); }
            case 21 -> { if(!headless) smokeReport.put("screenshots",screenshots!=null && screenshots.saved>=12 && screenshots.errors==0);smokeReport.put("inputPath","FormulaGame.action(click), not direct HUD click");smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish(); }
        }
        smokeStep++;
    }
    private void verifyNavigation() {
        float[] times={1,2,2.08f,2.23f,3,4,5,6,7,8,9,10,11,12,13,14,15,16,18,19,20,21,22,23,24,25,26};
        if(smokeStep>=times.length || smokeClock<times[smokeStep]) return;
        switch(smokeStep) {
            case 0 -> { mainMenu();capture("navigation-01-main-"); }
            case 1 -> smokeClick(200,340);
            case 2 -> { smokeReport.put("outgoingInputBlocked",hud.transitioning());capture("navigation-02-slide-left-"); }
            case 3 -> { smokeReport.put("menuChangedWhileMoving",screen==Screen.START_MENU && hud.transitioning());capture("navigation-03-slide-right-"); }
            case 4 -> { smokeReport.put("modeMenuReached",screen==Screen.START_MENU && !hud.transitioning());capture("navigation-04-modes-"); }
            case 5 -> smokeClick(200,310);
            case 6 -> { capture("navigation-05-single-");smokeClick(200,310); }
            case 7 -> { smokeClick(200,320);smokeReport.put("previewDoesNotCommitMap",screen==Screen.GAME_SETUP && launchSetup.preview.track().id.equals("woodland-technical") && !store.profile.trackId.equals("woodland-technical"));capture("navigation-06-preview-"); }
            case 8 -> { smokeClick(700,704);launchSetup.preview.pan(60,30);capture("navigation-07-moved-preview-"); }
            case 9 -> { smokeClick(550,704);smokeClick(200,700); }
            case 10 -> { smokeReport.put("startNeedsSecondConfirmation",screen==Screen.GAME_SETUP && hud.confirming());capture("navigation-08-confirm-"); }
            case 11 -> { smokeClick(400,537);smokeReport.put("cancelKeepsSetup",screen==Screen.GAME_SETUP && !hud.confirming()); }
            case 12 -> smokeClick(200,700);
            case 13 -> smokeClick(800,537);
            case 14 -> { smokeReport.put("raceStartedOnChosenMap",screen==Screen.DRIVING && mode==Mode.RACE && circuit.id.equals("woodland-technical") && racers.size()==4);capture("navigation-09-race-"); }
            case 15 -> { mainMenu();openCreation();capture("navigation-10-creation-"); }
            case 16 -> { smokeClick(930,88);smokeReport.put("creationDirectMain",screen==Screen.MENU);openCreation();smokeClick(1110,88);if(circuitEditor==null) newCircuit(null);saveCircuitDraft();startCircuitSolo(); }
            case 17 -> { returnToCircuitEditor();hud.showCircuitReview();capture("navigation-11-editor-review-"); }
            case 18 -> { smokeClick(1060,653);capture("navigation-12-records-"); }
            case 19 -> { smokeReport.put("trialDefaultTwelve",circuitReviewCapacity()==12 && circuitReviews().size()==1);smokeClick(1060,420);smokeReport.put("trialCapacityFourSaved",circuitReviewCapacity()==4);smokeClick(1060,475);capture("navigation-13-delete-confirm-"); }
            case 20 -> { smokeClick(800,537);smokeReport.put("trialDeletedMapPreserved",circuitReviews().isEmpty() && circuits.find(circuitEditor.document().id()).isPresent() && !circuitHistory().isEmpty());capture("navigation-14-cleaned-"); }
            case 21 -> { openMiniHome();capture("navigation-15-mini-home-"); }
            case 22 -> smokeClick(200,475);
            case 23 -> { smokeReport.put("miniEditorReached",screen==Screen.MINI4WD && mini4wd.view()==Mini4wdGame.View.EDITOR);capture("navigation-16-mini-editor-"); }
            case 24 -> { mainMenu();smokeClick(200,410); }
            case 25 -> { smokeClick(670,714);smokeReport.put("replaysUnderPractice",screen==Screen.REPLAYS);capture("navigation-17-replays-"); }
            case 26 -> {
                if(!headless) smokeReport.put("screenshotsSaved",screenshots!=null && screenshots.saved>=17 && screenshots.errors==0);
                smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish();
            }
        }
        smokeStep++;
    }

    private void verifyCircuitEditing() {
        float[] times={1,2,3,4,5,6,8,9,11,12};
        if(smokeStep>=times.length || smokeClock<times[smokeStep]) return;
        switch(smokeStep) {
            case 0 -> { newCircuit(null);saveCircuitDraft(); }
            case 1 -> {
                var original=circuitEditor.document();circuitEditor.selectAllAnchors();var active=original.anchors().get(circuitEditor.selectedAnchor());circuitEditor.moveSelected(active.x()+4,active.z()+2);saveCircuitDraft();
                boolean moved=true;for(int i=0;i<original.anchors().size();i++) { var before=original.anchors().get(i);moved &= circuitEditor.document().anchors().get(i).equals(new CircuitDocument.Point(before.x()+4,before.z()+2)); }
                smokeReport.put("routeGroupMoved",moved);if(hud!=null) hud.showCircuitGroups();
            }
            case 2 -> capture("editor-01-route-group-");
            case 3 -> { if(hud!=null) hud.showCircuitHistory(); }
            case 4 -> {
                capture("editor-02-history-");var original=circuitHistory().get(0);restoreCircuitRevision(original.id());smokeReport.put("restoredAsDraft",circuitEditor.dirty() && circuitEditor.document().equals(original.document()) && circuitEditor.proof()==null);saveCircuitDraft();
            }
            case 5 -> startCircuitSolo();
            case 6 -> { returnToCircuitEditor();if(hud!=null) hud.showCircuitReview(); }
            case 7 -> capture("editor-03-review-");
            case 8 -> {
                var saved=new CircuitStore(store.directory()).find(circuitEditor.document().id()).orElseThrow();
                smokeReport.put("threeVersionsSaved",saved.history().size()==3);smokeReport.put("reviewSaved",saved.reviews().size()==1 && saved.reviews().get(0).snapshot().equals(circuitReview()));
                smokeReport.put("observedActualCar",circuitReview()!=null && circuitReview().cars().size()==1);smokeReport.put("soloDoesNotQualify",saved.proof()==null && circuits.playable(vehicleSpec.physicsVersion).isEmpty());
                smokeReport.put("reviewMatchesRestoredDraft",circuitReview()!=null && circuitReview().matches(circuitEditor.document()));
            }
            case 9 -> {
                if(!headless) smokeReport.put("screenshotsSaved",screenshots!=null && screenshots.saved>=3 && screenshots.errors==0);
                smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish();
            }
        }
        smokeStep++;
    }

    private void smokeClick(int x,int y) {
        if(hud==null) hud=new HudRenderer(this);hud.render();hud.click(new Vector2f(x*getCamera().getWidth()/1280f,getCamera().getHeight()-y*getCamera().getHeight()/800f));
    }
    private void verifyMultiPack() {
        float[] times={1,2,3,4,5,6,7,8,9,10,11,12,13,14};
        if(smokeStep>=times.length || smokeClock<times[smokeStep]) return;
        switch(smokeStep) {
            case 0 -> { openCreation();smokeClick(600,192); }
            case 1 -> { smokeClick(200,432);smokeClick(1060,410); }
            case 2 -> capture("pack-01-author-");
            case 3 -> {
                smokeClick(1000,680);var entry=creationStore.packs().get(creationStore.packs().size()-1);smokeReport.put("authoredTwoMaps",entry.localCircuits().size()==2 && entry.original().version()==2);
            }
            case 4 -> {
                var entry=creationStore.packs().get(creationStore.packs().size()-1);
                Path shared=store.directory().resolve("circuit-export-shared-event-pack.json");
                if (!exportCircuitPack(entry.id(), shared) || !creationStore.remove(entry.id())) {
                    smokeReport.put("failure", "Could not prepare the exported event pack for import");
                    smokeReport.put("passed", false);
                    smokeFinish();
                    return;
                }
                importCircuitPack(shared);
                var imported=creationStore.packs().get(creationStore.packs().size()-1);smokeReport.put("importedWithoutProof",imported.imported() && imported.localCircuits().size()==2 && imported.localCircuits().stream().allMatch(map->circuits.find(map.id()).orElseThrow().proof()==null));
                var stages=imported.activity().stages();smokeReport.put("repeatedMapReference",stages.get(0).trackId().equals(stages.get(2).trackId()) && !stages.get(0).trackId().equals(stages.get(1).trackId()));
            }
            case 5 -> { smokeClick(1000,192);smokeClick(200,372); }
            case 6 -> capture("pack-02-imported-");
            case 7 -> { var entry=creationStore.packs().get(creationStore.packs().size()-1);activateCircuitPack(entry.id());smokeReport.put("unqualifiedActivationBlocked",screen==Screen.CREATION && activityStore.cards().isEmpty()); }
            case 8 -> {
                int count=circuits.all().size();var entry=creationStore.packs().get(creationStore.packs().size()-1);importCircuitPack(store.directory().resolve("circuit-export-shared-event-pack.json"));smokeReport.put("idempotentImport",count==circuits.all().size());
                smokeReport.put("mappingReloaded",entry.equals(new CreationStore(store.directory()).pack(entry.id())));smokeClick(1100,545);
            }
            case 9 -> capture("pack-03-second-map-");
            case 10 -> { smokeClick(650,585);var entry=creationStore.packs().get(creationStore.packs().size()-1);smokeReport.put("secondMapEditor",screen==Screen.MAP_EDITOR && circuitEditor.document().id().equals(entry.localCircuits().get(1).id())); }
            case 11 -> capture("pack-04-editor-");
            case 12 -> { if(!headless) smokeReport.put("screenshotsSaved",screenshots!=null && screenshots.saved>=4 && screenshots.errors==0); }
            case 13 -> { smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish(); }
        }
        smokeStep++;
    }

    private void verifyTimeTrial(float dt) {
        verificationStageClock+=dt;
        if(smokeStep==0 && timeTrial.best()!=null && timeTrial.elapsed()>2) {
            smokeReport.put("bestDuration",timeTrial.best().duration());
            smokeReport.put("samples",timeTrial.best().samples().size());
            smokeReport.put("sectors",timeTrial.best().sectors());
            smokeReport.put("ghostPlaying",timeTrial.ghost()!=null);
            capture("time-trial-ghost-");
            verificationPauseTime=sessionTime;verificationPausePosition=player.position();
            smokeReport.put("ghostClockBeforePause",timeTrial.elapsed());pause();verificationStageClock=0;smokeStep=1;
        } else if(smokeStep==1 && verificationStageClock>.5f) {
            boolean frozen=Math.abs(sessionTime-verificationPauseTime)<.00001 && player.position().distance(verificationPausePosition)<.002f
                && Math.abs(timeTrial.elapsed()-((Number)smokeReport.get("ghostClockBeforePause")).doubleValue())<.00001;
            smokeReport.put("pauseFreezesGhostAndCar",frozen);
            resume();resetAttempt();
            boolean reloaded=timeTrial.best()!=null && Math.abs(timeTrial.best().duration()-((Number)smokeReport.get("bestDuration")).doubleValue())<.00001 && !timeTrial.started();
            smokeReport.put("recordReloadedAfterRestart",reloaded);
            boolean original=store.profile.ghostEnabled;toggleGhost();toggleGhost();
            smokeReport.put("ghostTogglePersisted",new ProfileStore(store.directory()).profile.ghostEnabled==original);
            smokeReport.put("passed",frozen && reloaded && Boolean.TRUE.equals(smokeReport.get("ghostPlaying"))
                && Boolean.TRUE.equals(smokeReport.get("ghostTogglePersisted")) && ((Number)smokeReport.get("samples")).intValue()>100);
            smokeFinish();
        }
        if(smokeClock>100) { smokeReport.put("passed",false);smokeReport.put("timeout",true);smokeFinish(); }
    }
    private void verifyFormulaNetwork(float dt) {
        if (smokeStep == 0) {
            raceRoomSettings = new RaceSession.Settings(RaceSession.Activity.RACE,1);
            plannedRoomRules=null;
            store.profile.playerName = scenario.equals("formula-host") ? "方程式房主" : "方程式车手";
            if (scenario.equals("formula-client")) store.profile.tuning = new Tuning(1,1,1,1,2);
            int port = Integer.getInteger("f1car.formula.port",26951);
            if (scenario.equals("formula-host")) hostRaceRoom(port); else joinRaceRoom("127.0.0.1:" + port);
            smokeStep = 1; return;
        }
        if (!online()) { smokeReport.put("passed",false); smokeReport.put("error",toast); smokeFinish(); return; }
        if (raceRoom.frame() == null) {
            if (!raceRoom.host() && !raceRoom.ready()) raceRoom.ready(true);
            if (raceRoom.host() && raceRoom.canStart()) raceRoom.startSession();
        } else {
            var frame = raceRoom.frame();
            smokeReport.put("cars",frame.cars().size()); smokeReport.put("activity",frame.settings().activity().name());
            if (frame.cars().stream().anyMatch(c -> c.id() != raceRoom.localId() && c.pose().velocity().length() > 2)) smokeReport.put("remoteVehicleMoved",true);
            if (frame.complete()) {
                onlineFinishWait += dt;
                smokeReport.put("allFinished",frame.cars().stream().allMatch(c -> c.progress().finished()));
                smokeReport.put("allLapsValid",frame.cars().stream().allMatch(c -> c.progress().lastLapValid()));
                smokeReport.put("onlineRecordSaved",bestTime("lap") > 0);
                smokeReport.put("winner",frame.standings().get(0).id());
                smokeReport.put("finishTimes",frame.cars().stream().map(c -> c.progress().finishTime()).toList());
                smokeReport.put("physics","host-authoritative Bullet 120Hz");
                smokeReport.put("passed",Boolean.TRUE.equals(smokeReport.get("remoteVehicleMoved")) && Boolean.TRUE.equals(smokeReport.get("allFinished"))
                    && Boolean.TRUE.equals(smokeReport.get("allLapsValid")) && Boolean.TRUE.equals(smokeReport.get("onlineRecordSaved")));
                if (onlineFinishWait > (raceRoom.host() ? 4 : .5)) smokeFinish();
            }
        }
        if (smokeClock > 150) { smokeReport.put("passed",false); smokeReport.put("timeout",true); smokeFinish(); }
    }
    private void verifyMini4wd() {
        if (smokeStep == 0) {
            mini4wd = new Mini4wdGame(new ProfileStore(store.directory().resolve("mini4wd-smoke")));
            mini4wd.newKit();
            for (var slot : com.f1car.mini4wd.MiniAssembly.Slot.values()) {
                if (slot == com.f1car.mini4wd.MiniAssembly.Slot.DAMPER) continue;
                mini4wd.selectAssemblySlot(slot); mini4wd.installAssemblyPart();
            }
            smokeReport.put("assembledFromEmptyKit", mini4wd.setup().ready());
            openMini4wd();
            capture("mini4wd-maps-");
            smokeStep++;
        } else if (smokeStep == 1 && smokeClock > 1) {
            mini4wd.select(Mini4wdGame.BUILT_INS);
            mini4wd.edit();
            mini4wd.template(0);
            int cell = MiniTrack.cell(9, 2);
            mini4wd.place(cell, true);
            smokeReport.put("openTrackRejected", !mini4wd.inspection().ready());
            action("miniUndo", true, 0);
            mini4wd.chooseTool(MiniTrack.Kind.RAMP);
            mini4wd.place(cell, false);
            mini4wd.save();
            smokeReport.put("customTrackSaved", !mini4wd.dirty() && mini4wd.inspection().ready());
            capture("mini4wd-editor-");
            smokeStep++;
        } else if (smokeStep == 2 && smokeClock > 2) {
            action("enter", true, 0);
            smokeStep++;
        } else if (smokeStep == 3 && mini4wd.trial() != null && mini4wd.trial().elapsed() > 2) {
            capture("mini4wd-trial-");
            smokeStep++;
        } else if (smokeStep == 4 && mini4wd.trial().result() != MiniTrial.Result.RUNNING) {
            capture("mini4wd-result-");
            smokeStep++;
        } else if (smokeStep == 5) {
            var reloaded = new Mini4wdGame(new ProfileStore(store.directory().resolve("mini4wd-smoke")));
            reloaded.select(Mini4wdGame.BUILT_INS);
            boolean saved = reloaded.track().sameLayout(mini4wd.track()) && reloaded.best() > 0 && reloaded.setup().equals(mini4wd.setup());
            smokeReport.put("laps", mini4wd.trial().laps());
            smokeReport.put("trackAndTimeReloaded", saved);
            action("escape", true, 0);
            smokeReport.put("returnedToEditor", mini4wd.view() == Mini4wdGame.View.EDITOR);
            action("escape", true, 0);
            action("escape", true, 0);
            smokeReport.put("miniHomeRestored", screen == Screen.MINI_HOME);
            action("escape", true, 0);
            boolean restored = screen == Screen.MENU && physics.getPhysicsSpace().getRigidBodyList().size() == baselinePhysicsCount;
            smokeReport.put("formulaMenuRestored", restored);
            smokeReport.put("passed", saved && restored && Boolean.TRUE.equals(smokeReport.get("assembledFromEmptyKit")) && Boolean.TRUE.equals(smokeReport.get("returnedToEditor")) &&
                Boolean.TRUE.equals(smokeReport.get("openTrackRejected")) && Boolean.TRUE.equals(smokeReport.get("customTrackSaved")) &&
                Boolean.TRUE.equals(smokeReport.get("miniHomeRestored")) && Integer.valueOf(MiniTrial.LAPS).equals(smokeReport.get("laps")));
            smokeFinish();
        } else if (smokeClock > 65) {
            smokeReport.put("passed", false);
            smokeReport.put("timeout", true);
            smokeFinish();
        }
    }

    private void verifyNetwork(float dt) {
        if (smokeStep == 0) {
            openMini4wd();
            mini4wd.showRooms();
            mini4wd.stockSetup();
            int port = Integer.getInteger("f1car.room.port", 26950);
            if (scenario.equals("network-host")) mini4wd.hostRoom(port);
            else { mini4wd.select(4); mini4wd.joinRoom("127.0.0.1:" + port); }
            smokeReport.put("initialMapKey", mini4wd.track().recordKey());
            smokeStep = 1;
        }
        var room = mini4wd.room();
        if (room == null || room.closed()) {
            smokeReport.put("passed", false); smokeReport.put("error", mini4wd.notice()); smokeFinish(); return;
        }
        if (smokeStep == 1 && room.members().size() >= 2) {
            smokeReport.put("mapSynchronized", room.map() != null && room.map().track().inspect().ready());
            smokeReport.put("synchronizedMapKey", room.map().track().recordKey());
            capture("network-lobby-");
            if (!room.host()) room.ready(true);
            smokeStep = 2;
        }
        if (room.host() && room.canStart()) room.startRace();
        var frame = room.frame();
        if (frame != null) {
            if (!room.host()) room.boost(frame.elapsed() > 1 && frame.elapsed() < 1.25);
            if (room.host() && frame.cars().stream().anyMatch(c -> c.id() != 0 && c.boosting())) smokeReport.put("remoteBoostReceived", true);
            if (smokeStep == 2 && frame.elapsed() > 2) { capture("network-race-"); smokeStep = 3; }
            if (frame.complete()) {
                verificationStageClock += dt;
                if (verificationStageClock > (room.host() ? 2.5 : 1)) {
                    smokeReport.put("cars", frame.cars().size());
                    smokeReport.put("allFinished", frame.cars().stream().allMatch(c -> c.result() == MiniTrial.Result.FINISHED));
                    boolean success = frame.cars().size() == 2 && frame.cars().stream().allMatch(c -> c.result() == MiniTrial.Result.FINISHED)
                        && Boolean.TRUE.equals(smokeReport.get("mapSynchronized"));
                    if (room.host()) success &= Boolean.TRUE.equals(smokeReport.get("remoteBoostReceived"));
                    smokeReport.put("passed", success);
                    capture("network-results-"); smokeFinish();
                }
            }
        }
        if (smokeClock > 90) { smokeReport.put("passed", false); smokeReport.put("timeout", true); smokeFinish(); }
    }

    private int offscreenPixelCaptures;

    private void capturePixelView(String prefix) {
        if (!headless) {
            capture(prefix);
            return;
        }
        if (hud == null) hud = new HudRenderer(this);
        hud.render();
        Path directory = captures != null ? captures : store.directory().resolve("screenshots");
        try {
            hud.writeCanvasPng(directory.resolve(prefix + "1.png"));
            offscreenPixelCaptures++;
        } catch (Exception failure) {
            smokeReport.put("canvasWriteError", failure.getClass().getName());
        }
    }

    private void verifyPixelPresentation() {
        float[] times={2,3,5,6,8,9,11,12,14,16};
        if(smokeStep>=times.length || smokeClock<times[smokeStep]) return;
        switch(smokeStep) {
            case 0 -> {store.profile.camera=0;capturePixelView("pixel-01-topdown-");}
            case 1 -> store.profile.camera=1;
            case 2 -> capturePixelView("pixel-02-oblique-");
            case 3 -> store.profile.camera=2;
            case 4 -> capturePixelView("pixel-03-overview-");
            case 5 -> {
                pause();Vector3f before=player.position();Tuning configuration=store.profile.tuning;double time=sessionTime;
                boolean loaded=hud!=null && hud.reloadAssets();
                smokeReport.put("assetReload",loaded);
                smokeReport.put("reloadPreservesPosition",player.position().distance(before)<.0001f);
                smokeReport.put("reloadPreservesConfiguration",configuration.equals(store.profile.tuning));
                smokeReport.put("reloadPreservesTime",sessionTime==time);
                smokeReport.put("externalAssets",hud!=null && hud.assetSource().equals(assetDirectory.toAbsolutePath().toString()));
                smokeReport.put("twoDimensional",!viewPort.isEnabled());
            }
            case 6 -> {mainMenu();open(Screen.GARAGE);capturePixelView("pixel-04-garage-");}
            case 7 -> mainMenu();
            case 8 -> capturePixelView("pixel-05-menu-");
            case 9 -> {
                int saved = headless ? offscreenPixelCaptures : screenshots == null ? 0 : screenshots.saved;
                smokeReport.put("captureSource", headless ? "Java2D canvas" : "game viewport");
                smokeReport.put("screenshotsSaved", saved);
                smokeReport.put("allFiveViewsSaved", saved == 5);
                smokeReport.put("screenshotsWithoutErrors", headless ? !smokeReport.containsKey("canvasWriteError")
                        : screenshots != null && screenshots.errors == 0);
                smokeReport.put("passed",!smokeReport.containsValue(false));smokeFinish();
            }
        }
        smokeStep++;
    }
    private void verifyComparison(float dt) {
        verificationStageClock += dt;
        if (smokeClock > 80) { smokeReport.put("timeout", true); smokeFinish(); return; }
        if (smokeStep == 0 && verificationStageClock > 1) {
            stock(); start(Mode.LESSON, PracticeSession.Lesson.COMPARISON); smokeStep = 1;
        } else if ((smokeStep == 1 || smokeStep == 2) && screen == Screen.RESULTS) {
            if (practice.state != PracticeSession.State.SUCCESS) { smokeReport.put("failure", practice.feedback); smokeFinish(); return; }
            if (smokeStep == 1) {
                smokeReport.put("stockTestPassed", true);
                mainMenu(); open(Screen.GARAGE); changeTuning("brakes", .15f);
                start(Mode.LESSON, PracticeSession.Lesson.COMPARISON); smokeStep = 2;
            } else {
                smokeReport.put("modifiedTestPassed", true);
                smokeReport.put("twoConfigRecords", store.profile.brakingDistances.size() >= 2);
                var restored = new ProfileStore(store.directory());
                smokeReport.put("comparisonPersists", restored.profile.brakingDistances.size() >= 2);
                mainMenu(); open(Screen.GARAGE); capture("comparison-garage-");
                smokeStep = 3; verificationStageClock = 0;
            }
        } else if (smokeStep == 3 && verificationStageClock > 1) {
            if (screenshots != null) {
                smokeReport.put("screenshotSaved", screenshots.saved > 0 && screenshots.errors == 0);
            }
            smokeReport.put("passed", !smokeReport.containsValue(false)); smokeFinish();
        }
    }
    private void verifyFullFlow(float dt) {
        verificationStageClock += dt;
        if (smokeClock > 600) { smokeReport.put("timeout", true); smokeFinish(); return; }
        switch (smokeStep) {
            case 0 -> {
                if (verificationStageClock < 2) return;
                capture("verify-01-menu-");
                if (hud != null) {
                    hud.click(new Vector2f(200 * getCamera().getWidth() / 1280f, getCamera().getHeight() - 410 * getCamera().getHeight() / 800f));
                } else open(Screen.COURSES);
                smokeStep = 1; verificationStageClock = 0;
            }
            case 1 -> {
                if (verificationStageClock < 1) return;
                // The menu click queues the normal fade/navigation transition.
                // Observe its destination after that transition, not in the click's frame.
                smokeReport.put("practiceMenuClick", screen == Screen.COURSES);
                capture("verify-02-courses-");
                start(Mode.LESSON, PracticeSession.Lesson.values()[0]);
                smokeStep = 2; verificationStageClock = 0;
            }
            case 2 -> {
                if (screen != Screen.RESULTS) { verificationResultWait = -1; return; }
                if (verificationResultWait < 0) verificationResultWait = 0;
                verificationResultWait += dt;
                if (verificationResultWait < .8f) return;
                boolean success = practice.state == PracticeSession.State.SUCCESS;
                smokeReport.put("course-" + practice.lesson.id, success);
                capture("verify-course-" + practice.lesson.id + "-");
                if (!success) { smokeReport.put("failure", practice.feedback); smokeFinish(); return; }
                verificationLesson++;
                if (verificationLesson < PracticeSession.Lesson.values().length) {
                    start(Mode.LESSON, PracticeSession.Lesson.values()[verificationLesson]);
                    verificationResultWait = -1;
                } else {
                    mainMenu(); open(Screen.GARAGE);
                    changeTuning("brakes", .1f); savePreset("A");
                    Tuning saved = store.profile.tuning;
                    stock(); loadPreset("A");
                    smokeReport.put("presetRoundTrip", saved.equals(store.profile.tuning) && saved.equals(new ProfileStore(store.directory()).profile.tuning));
                    capture("verify-03-garage-");
                    smokeStep = 3; verificationStageClock = 0;
                }
            }
            case 3 -> {
                if (verificationStageClock < 1) return;
                stock(); store.profile.raceLaps = 1; start(Mode.RACE, null);
                smokeStep = 4; verificationStageClock = 0;
            }
            case 4 -> {
                if (verificationStageClock > 12 && !smokeReport.containsKey("raceCapture")) { capture("verify-04-racing-"); smokeReport.put("raceCapture", true); }
                if (screen != Screen.RESULTS) return;
                smokeReport.put("raceAllFinished", racers.stream().allMatch(r -> r.progress.finished()));
                smokeReport.put("racePlayerLaps", racers.get(0).progress.completedLaps());
                capture("verify-05-race-results-");
                smokeStep = 5; verificationStageClock = 0;
            }
            case 5 -> {
                if (verificationStageClock < 1) return;
                start(Mode.CUP, null); smokeReport.put("experimentalPartInstalled", player.hasCup()); smokeStep = 6; verificationStageClock = 0;
            }
            case 6 -> {
                if (verificationStageClock > 13 && !smokeReport.containsKey("cupCapture")) { capture("verify-06-cup-driving-"); smokeReport.put("cupCapture", true); }
                if (screen != Screen.RESULTS) return;
                smokeReport.put("cupCompleted", racers.get(0).progress.finished() && racers.get(0).progress.lastLapValid() && !cup.timedOut());
                smokeReport.put("cupScore", cup.score());
                capture("verify-07-cup-results-");
                smokeStep = 7; verificationStageClock = 0;
            }
            case 7 -> {
                if (verificationStageClock < 1) return;
                start(Mode.FREE_CIRCUIT, null); smokeReport.put("experimentalPartCleaned", !player.hasCup() && cup == null); smokeStep = 8; verificationStageClock = 0;
            }
            case 8 -> {
                if (verificationStageClock < 4) return;
                pause(); verificationPausePosition = player.position(); verificationPauseTime = sessionTime;
                capture("verify-08-paused-"); smokeStep = 9; verificationStageClock = 0;
            }
            case 9 -> {
                if (verificationStageClock < 1.5f) return;
                smokeReport.put("pauseFreezesClock", sessionTime == verificationPauseTime);
                smokeReport.put("pauseFreezesPhysics", player.position().distance(verificationPausePosition) < .05f);
                resume(); resetAttempt();
                smokeReport.put("resetClearsVelocity", player.speed() < .001f);
                mainMenu(); open(Screen.SETTINGS); setHumor(); setHumor(); changeVolume(-.1f); changeVolume(.1f);
                smokeReport.put("settingsRoundTrip", new ProfileStore(store.directory()).profile.humor == store.profile.humor);
                capture("verify-09-settings-");
                smokeStep = 10; verificationStageClock = 0;
            }
            case 10 -> {
                if (verificationStageClock < 1) return;
                mainMenu();
                smokeReport.put("noPhysicsLeaks", baselinePhysicsCount == physics.getPhysicsSpace().getRigidBodyList().size());
                smokeReport.put("brakingComparisonSaved", !store.profile.brakingDistances.isEmpty());
                if (screenshots != null) {
                    smokeReport.put("screenshotsWithoutErrors", screenshots.errors == 0);
                    smokeReport.put("screenshotsSaved", screenshots.saved);
                    if (screenshots.pending() > 0 && verificationStageClock < 10) return;
                    smokeReport.put("screenshotsFlushed", screenshots.pending() == 0);
                }
                boolean ok = !smokeReport.containsKey("failure") && !smokeReport.containsValue(false);
                smokeReport.put("passed", ok);
                smokeFinish();
            }
        }
    }
    private void smokeFinish() {
        if(screenshots!=null && screenshots.pending()>0) {smokeFinishPending=true;return;}
        writeSmokeReportAndStop();
    }
    private void writeSmokeReportAndStop() {
        try {
            if(screenshots!=null && screenshots.errors>0) smokeReport.put("passed",false);
            Files.createDirectories(store.directory());
            smokeReport.put("screen", screen.name());
            smokeReport.put("scenario", scenario);
            smokeReport.put("profileSaved", store.save());
            smokeReport.put("physicsObjects", physics.getPhysicsSpace().getRigidBodyList().size());
            Files.writeString(store.directory().resolve("smoke-" + scenario + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(smokeReport));
        } catch (Exception e) { throw new IllegalStateException("Cannot write smoke report", e); }
        stop();
    }
    @Override public void destroy() {
        if(screenshots!=null) screenshots.cleanup();
        checkpointPartsEditing();checkpointAuthoring();
        if(hud!=null) hud.close();
        experienceStore.flush();
        if(controllerAdapter!=null) controllerAdapter.close();controls.flush();keyboard.flush();
        closeRaceRoom();
        if (mini4wd != null) mini4wd.leaveRoom();
        if (physics != null) { disposeSession(); store.save(); }
        if(audio!=null) audio.stop();
        replayStore.close();
        super.destroy();
    }
}
