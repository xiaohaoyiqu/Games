package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.mini4wd.Mini4wdGame;
import com.f1car.mini4wd.MiniTrack;
import com.f1car.model.*;
import com.f1car.network.RaceRoom;
import com.f1car.persistence.CircuitEditionCodec;
import com.f1car.persistence.CircuitStore;
import com.f1car.persistence.ExportDestination;
import com.f1car.persistence.LiveryCodec;
import com.f1car.scene.FormulaCar;
import com.f1car.scene.ScreenCapture;
import com.f1car.input.KeyboardSettings.Command;
import com.f1car.pixel.PixelAssets;
import com.f1car.pixel.PixelScene;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture2D;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.event.KeyInputEvent;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.datatransfer.DataFlavor;

/** Pixel scene and mouse-operable Chinese interface, presented through one GUI texture. */
public final class HudRenderer {
    private record Button(Rectangle bounds,String label,Runnable action) {}
    private static final Color INK = new Color(235, 245, 240), MUTED = new Color(162, 186, 189);
    private static final Color MINT = new Color(76, 235, 193), ORANGE = new Color(255, 169, 114);
    private static final Color PANEL = new Color(10, 29, 39, 237);
    private static final Rectangle MINIMAP = new Rectangle(1040, 106, 202, 173);
    private static final Color MINIMAP_PANEL = new Color(9, 28, 39, 214);
    private static final Color MINIMAP_ROUTE = new Color(117, 150, 155);
    private final FormulaGame app;
    private final Geometry quad;
    private final Texture2D texture = new Texture2D();
    private final HudSurface surface;
    private final PixelScene scene;
    private final Mini4wdHud mini4wdHud = new Mini4wdHud();
    private final RaceRoomHud raceRoomHud = new RaceRoomHud();
    private final RoomSettingsHud roomSettingsHud=new RoomSettingsHud();
    private final TimeTrialHud timeTrialHud = new TimeTrialHud();
    private final GhostExchangeHud ghostExchangeHud = new GhostExchangeHud();
    private final TelemetryHud telemetryHud=new TelemetryHud();
    private final ReplayHud replayHud=new ReplayHud();
    private final TuningLabHud tuningLabHud=new TuningLabHud();
    private final WorkshopHud workshopHud=new WorkshopHud();
    private final EventHud eventHud=new EventHud();
    private final ChallengeBookHud bookHud=new ChallengeBookHud();
    private final CircuitEditorHud circuitEditorHud=new CircuitEditorHud();
    private final CircuitSectionPaceHud circuitSectionPaceHud=new CircuitSectionPaceHud();
    private final ControlHud controlHud=new ControlHud();
    private final KeyboardHud keyboardHud=new KeyboardHud();
    private final DrivingGuideHud drivingGuideHud=new DrivingGuideHud();
    private final TuningGuideHud tuningGuideHud=new TuningGuideHud();
    private final SetupLibraryHud setupLibraryHud=new SetupLibraryHud();
    private final PartsHud partsHud=new PartsHud();
    private EquipmentLapsHud discussionLapView;
    public void captureAuthoringDrafts() {partsHud.captureDrafts(app);circuitEditorHud.captureDrafts(app);}
    private final LiveryHud liveryHud=new LiveryHud();
    private final ExperienceHud experienceHud=new ExperienceHud();
    private final ActivityHud activityHud=new ActivityHud();
    private final CreationHud creationHud=new CreationHud();
    private record ImportContext(FormulaGame.Screen screen,BooleanSupplier draftCurrent) {}
    private record FileImport(Future<Runnable> result,ImportContext context) {}
    private ThreadPoolExecutor fileWorker;
    private Future<PixelAssets> assetReload;
    private Future<Runnable> liveryExportReview;
    private Future<?> liveryExportWrite;
    private long liveryExportRequest;
    private boolean reloadAgain;
    private FileImport fileImport;
    private volatile ImportContext importChooser;
    private boolean closed;
    private final StartMenuHud startMenuHud=new StartMenuHud();
    private final GameSetupHud gameSetupHud=new GameSetupHud();
    private final MenuSlide menuSlide=new MenuSlide();
    private record Confirmation(String title,String detail,Runnable action,Review back,boolean overwrite) {
        boolean useFocus() {return back!=null || overwrite;}
    }
    private Confirmation confirmation;
    private record ReviewAction(String label,Runnable action) { }
    private record Review(String title,ReviewPages pages,String actionLabel,Consumer<Review> action,
                          CircuitSectionSplitMap splitMap,CircuitPracticeImportPreview practiceImport,SplitProgressReview splitProgress,
                          ObservationRetestReview observationRetest,ObservationRetestComparison retestComparison,
                          CircuitRetestMap retestMap,EquipmentIntervalMap equipmentMap,EquipmentSectionPreviewHud equipmentSection,
                          EquipmentSectionRunsHud equipmentSectionRuns,Review back,ReviewAction secondary,
                          EquipmentInterruptionMapHud equipmentInterruptionMap) {
        private Review(String title,ReviewPages pages,String actionLabel,Consumer<Review> action,
                       CircuitSectionSplitMap splitMap,CircuitPracticeImportPreview practiceImport,SplitProgressReview splitProgress,
                       ObservationRetestReview observationRetest,ObservationRetestComparison retestComparison,
                       CircuitRetestMap retestMap,EquipmentIntervalMap equipmentMap,EquipmentSectionPreviewHud equipmentSection,
                       EquipmentSectionRunsHud equipmentSectionRuns,Review back) {
            this(title,pages,actionLabel,action,splitMap,practiceImport,splitProgress,observationRetest,retestComparison,
                    retestMap,equipmentMap,equipmentSection,equipmentSectionRuns,back,null,null);
        }
        private Review(String title,ReviewPages pages,String actionLabel,Consumer<Review> action,
                       CircuitSectionSplitMap splitMap,CircuitPracticeImportPreview practiceImport,SplitProgressReview splitProgress,
                       ObservationRetestReview observationRetest,ObservationRetestComparison retestComparison,
                       CircuitRetestMap retestMap,EquipmentIntervalMap equipmentMap,EquipmentSectionPreviewHud equipmentSection,
                       EquipmentSectionRunsHud equipmentSectionRuns,Review back,ReviewAction secondary) {
            this(title,pages,actionLabel,action,splitMap,practiceImport,splitProgress,observationRetest,retestComparison,
                    retestMap,equipmentMap,equipmentSection,equipmentSectionRuns,back,secondary,null);
        }
    }
    private record ObservationRetestReview(CircuitObservationRetest value,boolean saved) { }
    private record ObservationRetestComparison(CircuitObservationRetest proposed,CircuitObservationRetest.Result saved) { }
    private record SplitProgressReview(CircuitSectionPractice source,Review back,CircuitSplitProgressHud.Comparison comparison,
                                       boolean comparing) { }
    private Review review;
    private Vector2f previewPanPointer;
    private int previewPanButton;
    private final MenuFocus menuFocus=new MenuFocus();
    private final List<Button> buttons = new ArrayList<>();
    private final Font font = new Font("Microsoft YaHei", Font.PLAIN, 18);
    private Graphics2D g;
    private TrackLayout minimapTrack;
    private TrackMapView.CachedOutline minimapOutline;
    private final Vector3f drivingPosition=new Vector3f(),minimapPosition=new Vector3f();
    private final ActivitySectionPreview activitySectionPreview=new ActivitySectionPreview();
    private float scale = 1, ox, oy, mouseX, mouseY;
    private float surfaceRatioX=1,surfaceRatioY=1;
    private String promptTitle, promptValue;
    private String promptDefault;
    private int promptLimit=120;
    private Consumer<String> promptAction;
    private record NumberDialog(String title,String unit,DigitNumberEditor editor,java.util.function.IntPredicate accept) {}
    private NumberDialog numberDialog;
    private Vector2f panPointer;
    private int lessonPage;
    private int reportTab;
    private boolean garageSuspensionPage;
    private int trackPage;
    private Vector2f circuitPanPointer;

    public HudRenderer(FormulaGame app) {
        this(app,true);
    }
    /** The complete HUD paints an opaque background; the alpha path remains available for comparison. */
    HudRenderer(FormulaGame app,boolean opaque) {
        this.app = app;
        surface=new HudSurface(opaque);
        PixelAssets loaded;
        try { loaded = PixelAssets.load(app.assetDirectory); }
        catch (IllegalArgumentException e) { loaded = PixelAssets.load(null); app.notifyUser(e.getMessage()); }
        scene = new PixelScene(app, loaded);
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        quad = new Geometry("Chinese interface", new Quad(1, 1));
        Material m = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        m.setTexture("ColorMap", texture);
        m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        quad.setMaterial(m);
        app.getGuiNode().attachChild(quad);
        render();
    }
    public void update(float dt) {
        menuSlide.update(dt);
        finishAssetReload();
        updateLiveryExport();
        updateFileImport();
        if(scrubbingPlayback()) updateReplayPointer(app.getInputManager().getCursorPosition());
        if(previewPanPointer!=null) updatePreviewPan(app.getInputManager().getCursorPosition());
        if(app.screen!=FormulaGame.Screen.MINI4WD) scene.update(dt);
    }
    public void navigate(Runnable action) { previewPanPointer=null;endReplayPointer();menuSlide.begin(action); }
    public boolean transitioning() { return menuSlide.active(); }
    public void cancelTransition() { menuSlide.cancel();previewPanPointer=null;endReplayPointer();releaseEquipmentMapRasters();confirmation=null;review=null;numberDialog=null;cancelCreationImport();cancelLiveryExportReview(); }
    public void resetStartMenu() { startMenuHud.reset(); }
    public void backStartMenu() { startMenuHud.back(this,app); }
    public boolean backWithinCreation() {return creationHud.backOverlay();}
    public void confirm(String title,String detail,Runnable action) {
        confirm(title,detail,action,null);
    }
    private void confirm(String title,String detail,Runnable action,Review back) {
        confirm(title,detail,action,back,false);
    }
    private void confirm(String title,String detail,Runnable action,Review back,boolean overwrite) {
        numberDialog=null;promptAction=null;review=null;cancelCircuitPointer();endMiniPointer();endPreviewPan();
        if(overwrite) menuFocus.update(List.of(),"");
        confirmation=new Confirmation(title,detail,action,back,overwrite);
    }
    public void review(String title,List<String> paragraphs,String actionLabel,Runnable action) {
        openReview(title,paragraphs,actionLabel,action,null,null,null);
    }
    public void reviewJournalConflict(com.f1car.persistence.JournalStore.ConflictReview conflict) {
        if(!conflict.valid()) {
            review("核对两份驾驶履历",conflict.details(),"返回履历",null);
            return;
        }
        Runnable local=conflict.canUseLocalLook()?()->app.applyJournalConflict(conflict,true):null;
        reviewWithAlternative("核对两份驾驶履历",conflict.details(),
                conflict.lookChoice()?"保留磁盘外观并保存":"保存合并履历",()->app.applyJournalConflict(conflict,false),
                "使用本窗口外观并保存",local);
    }
    public void reviewRivalConflict(com.f1car.persistence.RivalStore.ConflictReview conflict) {
        if(!conflict.valid()) {
            review("核对两份交手记录",conflict.details(),"返回交手记录",null);
            return;
        }
        Runnable local=conflict.followChoice()?()->app.applyRivalConflict(conflict,true):null;
        reviewWithAlternative("核对两份交手记录",conflict.details(),
                conflict.followChoice()?"保留磁盘关注并保存":"保存合并交手记录",()->app.applyRivalConflict(conflict,false),
                "使用本窗口关注并保存",local);
    }
    public void reviewWorkshopConflict(com.f1car.persistence.WorkshopStore.ConflictReview conflict) {
        review("核对两份实验报告",conflict.details(),conflict.valid()?"保存合并报告":"返回实验车间",
                conflict.valid()?()->app.applyWorkshopConflict(conflict):null);
    }
    public void reviewExperienceConflict(com.f1car.persistence.ExperienceStore.ConflictReview conflict) {
        review("核对两份分段成绩",conflict.details(),conflict.valid()?"保存合并成绩":"返回分段攻关",
                conflict.valid()?()->app.applyExperienceConflict(conflict):null);
    }
    private void reviewWithAlternative(String title,List<String> details,String label,Runnable save,String otherLabel,Runnable other) {
        var alternative=other==null?null:new ReviewAction(otherLabel,()->{
            closeReview();
            other.run();
        });
        openReview(new Review(title,new ReviewPages(details),label,ignored->save.run(),
                null,null,null,null,null,null,null,null,null,null,alternative));
    }
    private void openReview(String title,List<String> paragraphs,String actionLabel,Runnable action,
                            CircuitSectionSplitMap splitMap,CircuitPracticeImportPreview practiceImport,SplitProgressReview splitProgress) {
        openReview(new Review(title,new ReviewPages(paragraphs),actionLabel,action==null?null:ignored->action.run(),
                splitMap,practiceImport,splitProgress,null,null,null,null,null,null,null));
    }
    void reviewEquipmentInterval(EquipmentIntervalMap map) {
        openReview(new Review("区间走线 A／B",new ReviewPages(List.of()),"返回曲线",null,
                null,null,null,null,null,null,map,null,null,null));
    }
    void reviewEquipmentSection(EquipmentSectionPracticeSession session,java.util.function.BooleanSupplier stillSelected) {
        var expected=app.previewEquipmentSection(session);
        if(expected==null) return;
        var back=review!=null && review.equipmentMap()!=null?review:null;
        openReview(new Review("练习所选区间",new ReviewPages(List.of()),"开始独立练习",null,
                null,null,null,null,null,null,null,new EquipmentSectionPreviewHud(expected,stillSelected),null,back));
    }
    void reviewEquipmentSectionRuns(FormulaGame.EquipmentSectionPreview source,java.util.function.BooleanSupplier stillSelected) {
        if(source==null || !source.session().hasEquipmentRuns(source.start(),source.controls().identity())) return;
        var rows=new EquipmentSectionRunsHud(source,stillSelected);
        String backLabel=review==null?"返回成绩  Esc":"返回发车预览  Esc";
        openReview(new Review("A/B 区间练习明细",new ReviewPages(List.of()),backLabel,null,
                null,null,null,null,null,null,null,null,rows,review));
    }
    private void reviewEquipmentSectionInterruptions(Review back) {
        var runs=back.equipmentSectionRuns();
        var lines=runs.interruptionLines();
        openReview(new Review("A/B 近期中止原因",new ReviewPages(lines),"返回逐次明细",null,
                null,null,null,null,null,null,null,null,null,back,
                new ReviewAction("查看中止位置地图",()->reviewEquipmentInterruptionMap(runs))));
    }
    private void reviewEquipmentInterruptionMap(EquipmentSectionRunsHud runs) {
        openReview(new Review("A/B 中止位置",new ReviewPages(List.of()),"返回中止原因",null,
                null,null,null,null,null,null,null,null,null,review,null,runs.interruptionMap()));
    }
    private void previewEquipmentInterruptionSubsection() {
        var parent=review;
        if(parent==null || parent.equipmentInterruptionMap()==null) return;
        var map=parent.equipmentInterruptionMap();
        var expected=map.subsection();
        if(expected==null) return;
        var preview=new EquipmentSectionPreviewHud(expected,map.practiceGuard());
        openReview(new Review("重练中止时尚未完成的分段",new ReviewPages(List.of()),"开始独立练习",null,
                null,null,null,null,null,null,null,preview,null,parent));
    }
    private void previewEquipmentSubsection(Review parent,EquipmentSectionPracticeSession.SplitComparison split) {
        if(review!=parent) return;
        var rows=parent.equipmentSectionRuns();
        var expected=rows.subsection(split);
        if(expected==null) return;
        var preview=new EquipmentSectionPreviewHud(expected,rows::stillSelected);
        openReview(new Review("练习第 "+split.number()+" 段",new ReviewPages(List.of()),"开始独立练习",null,
                null,null,null,null,null,null,null,preview,null,parent));
    }
    private void openReview(Review next) {
        confirm(next.title(),"",null);
        confirmation=null;
        review=next;
    }
    public void reviewCircuitPracticeImport(CircuitStore.ImportedDraft draft,String fileName,String dirtyMap,Runnable action,
                                            CircuitStore.Saved target,IntConsumer applySlot) {
        var preview=new CircuitPracticeImportPreview(draft,dirtyMap,target,number->{review=null;applySlot.accept(number);});
        var lines=preview.lines(fileName);
        openReview("区间草稿导入预览",lines,"导入为本机草稿",action,null,preview,null);
    }
    public boolean confirming() { return confirmation!=null || review!=null; }
    public void confirmLaunch() {
        var setup=app.launchSetup();if(setup==null || transitioning() || confirmation!=null) return;
        if(setup.eventStage()!=null) {review("确认按赛事固定条件开始？",setup.eventStage().lines(),"开始这个赛事阶段",()->app.commitLaunch(setup));return;}
        confirm("确认开始游戏？",setup.title+"\n"+(setup.training?"综合训练场":setup.preview.track().name)+"\n"+setup.drivingRule()+" · "+(setup.lengthRule==null?"正赛 "+setup.laps+" 圈":setup.lengthRule)+"\n配置已选好，确认后进入游戏。",()->app.commitLaunch(setup));
    }
    public void endPreviewPan() { previewPanPointer=null; }
    public boolean showEventRoundReview(com.f1car.model.EventSeries.State source,int round) {return eventHud.openReview(app,source,round);}
    public boolean showEventRecovery() {return eventHud.openRecovery(app);}
    private boolean beginPreviewPan(int button,float x,float y) {
        if(app.screen!=FormulaGame.Screen.GAME_SETUP || app.launchSetup()==null || typing() || transitioning()
                || !TrackPreview.AREA.contains(x,y)) return false;
        if(previewPanPointer==null) {
            previewPanPointer=new Vector2f(x,y);
            previewPanButton=button;
        }
        return true;
    }
    public void beginMiddlePreviewPan(Vector2f cursor) {
        beginPreviewPan(MouseInput.BUTTON_MIDDLE,uiX(cursor.x),uiY(cursor.y));
    }
    private void updatePreviewPan(Vector2f cursor) {
        if(app.screen!=FormulaGame.Screen.GAME_SETUP || app.launchSetup()==null || typing() || transitioning()) {
            endPreviewPan();
            return;
        }
        float x=uiX(cursor.x),y=uiY(cursor.y);
        app.launchSetup().preview.pan(x-previewPanPointer.x,y-previewPanPointer.y);
        previewPanPointer.set(x,y);
    }
    public void releasePreviewPan(int button,Vector2f cursor) {
        if(previewPanPointer!=null && button==previewPanButton) {
            updatePreviewPan(cursor);
            endPreviewPan();
        }
    }
    public void resetCamera() { scene.resetCamera(); }
    public String assetSource() { return scene.source(); }
    /** Export the last completed Java2D frame on the game thread for an offscreen diagnostic. */
    public void writeCanvasPng(Path file) throws Exception {
        var canvas = surface.canvas();
        if (canvas == null) throw new IOException("No rendered HUD frame");
        ScreenCapture.writePng(file.toAbsolutePath(), canvas);
    }
    public void cancelLiveryExportReview() {
        liveryExportRequest++;
        if(liveryExportReview!=null) {
            liveryExportReview.cancel(true);
            liveryExportReview=null;
            fileWorker.purge();
        }
    }
    void prepareLiveryExport(CarLivery value,Path path,BooleanSupplier current) {
        if(closed || app.screen!=FormulaGame.Screen.LIVERY) return;
        cancelLiveryExportReview();
        if(liveryExportWrite!=null) {app.notifyUser("上一份涂装正在导出，请稍后重试。");return;}
        long request=liveryExportRequest;
        try {
            liveryExportReview=fileWorker().submit(()->{
                var target=app.inspectLiveryDestination(path,true);
                return ()->{
                    if(!current.getAsBoolean()) return;
                    Runnable save=()->{
                        if(request==liveryExportRequest && app.screen==FormulaGame.Screen.LIVERY && current.getAsBoolean())
                            publishLiveryExport(value,target);
                    };
                    if(target.replacing()) confirm("替换这个导出文件？",target.path().toString(),save);
                    else save.run();
                };
            });
            app.notifyUser("正在核对涂装导出位置…");
        } catch(RejectedExecutionException | SecurityException error) {
            app.notifyUser("暂时无法核对导出文件，请重试。");
        }
    }
    private void publishLiveryExport(CarLivery value,ExportDestination target) {
        try {
            liveryExportWrite=fileWorker().submit(()->{app.writeReviewedLivery(value,target);return null;});
        } catch(RejectedExecutionException | SecurityException error) {
            app.notifyUser("暂时无法开始导出，请重试。");
        }
    }
    private void updateLiveryExport() {
        if(liveryExportReview!=null && app.screen!=FormulaGame.Screen.LIVERY) cancelLiveryExportReview();
        try {
            if(liveryExportWrite!=null && liveryExportWrite.isDone()) {
                var write=liveryExportWrite;
                liveryExportWrite=null;
                write.get();
                app.notifyUser("涂装已导出；署名与来源为作品声明。");
            }
            if(liveryExportReview!=null && liveryExportReview.isDone() && !typing() && !transitioning() && !app.helpVisible) {
                var review=liveryExportReview;
                liveryExportReview=null;
                review.get().run();
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch(ExecutionException error) {
            app.notifyUser("涂装未导出，请重新核对目标文件和目录。");
        }
    }
    public void requestAssetReload() {
        if(closed) return;
        if(assetReload!=null) {
            reloadAgain=true;
            return;
        }
        Path directory=app.assetDirectory;
        try {
            assetReload=fileWorker().submit(()->PixelAssets.load(directory));
            app.notifyUser("正在重新读取像素素材…");
        } catch(RejectedExecutionException | SecurityException unavailable) {
            app.notifyUser("暂时无法开始读取像素素材，请重试。");
        }
    }
    private void finishAssetReload() {
        var read=assetReload;
        if(read==null || !read.isDone()) return;
        assetReload=null;
        if(reloadAgain) {
            reloadAgain=false;
            requestAssetReload();
            return;
        }
        try {
            installAssets(read.get());
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch(ExecutionException error) {
            app.notifyUser(error.getCause().getMessage()+"；继续使用上次素材。");
        } catch(CancellationException canceled) {
            // No replacement was published.
        }
    }
    private void cancelAssetReload() {
        reloadAgain=false;
        if(assetReload==null) return;
        assetReload.cancel(true);
        assetReload=null;
        fileWorker.purge();
    }
    private void installAssets(PixelAssets loaded) {
        scene.replaceAssets(loaded);
        app.notifyUser("像素素材已重新载入");
    }
    public boolean reloadAssets() {
        if(closed) return false;
        cancelAssetReload();
        try {
            installAssets(PixelAssets.load(app.assetDirectory));
            return true;
        } catch(IllegalArgumentException e) { app.notifyUser(e.getMessage()+"；继续使用上次素材。"); return false; }
    }

    public void render() {
        discussionLapView=null;
        discardDepartedImport();
        replayHud.keepMarkers(app.replayViewing()?app.replayPlayer():null);
        telemetryHud.keepCharts(app.screen==FormulaGame.Screen.TELEMETRY?app.telemetryComparison():null);
        circuitEditorHud.keepLibraryPreviews(app.screen==FormulaGame.Screen.MAP_EDITOR);
        if(app.screen!=FormulaGame.Screen.MINI4WD) mini4wdHud.clearBoardImage();
        if (app.screen != FormulaGame.Screen.EXPERIENCE) experienceHud.clearMap();
        if (app.screen != FormulaGame.Screen.DRIVING && app.screen != FormulaGame.Screen.PAUSED
                && app.screen != FormulaGame.Screen.RESULTS) drivingOutline(null);
        if(review!=null && (review.splitMap()!=null && currentSplitMap()==null
                || review.splitProgress()!=null && !isCurrentSectionResult(review.splitProgress().source()))) {
            review=null;
            numberDialog=null;
        }
        int windowWidth = app.getCamera().getWidth(), windowHeight = app.getCamera().getHeight();
        if (windowWidth < 1 || windowHeight < 1) return;
        var size=app.graphicsQuality().surface(windowWidth,windowHeight);int width=size.width(),height=size.height();
        if(surface.resize(width,height)) texture.setImage(surface.image());
        surfaceRatioX=width/(float)windowWidth;
        surfaceRatioY=height/(float)windowHeight;
        var canvas=surface.canvas().createGraphics();
        g=canvas;
        try {
            drawSurface(width,height);
        }finally {
            g=null;
            canvas.dispose();
        }
        surface.upload();
        quad.setLocalScale(windowWidth, windowHeight, 1);
        quad.setLocalTranslation(0, 0, 1);
    }
    private void drawSurface(int width,int height) {
        PixelScene.crisp(g);
        g.setColor(new Color(0x122230));g.fillRect(0,0,width,height);
        scale = Math.min(width / 1280f, height / 800f);
        ox = (width - 1280 * scale) * .5f; oy = (height - 800 * scale) * .5f;
        Vector2f mouse = app.getInputManager().getCursorPosition();
        mouseX = uiX(mouse.x); mouseY = uiY(mouse.y);
        g.translate(ox, oy); g.scale(scale, scale);
        if (app.screen != FormulaGame.Screen.MINI4WD) g.drawImage(scene.render(),0,0,1280,800,null);
        buttons.clear();
        float menuOffset=menuSlide.offset();g.translate(menuOffset,0);
        tuningLabHud.synchronizeScreen(app);
        eventHud.synchronizeScreen(app);
        switch (app.screen) {
            case MENU -> mainMenu();
            case START_MENU -> startMenuHud.render(this,app);
            case GAME_SETUP -> gameSetupHud.render(this,g,app);
            case MINI_HOME -> startMenuHud.mini(this,app);
            case COURSES -> courses();
            case CHALLENGES -> challenges();
            case BOOK -> bookHud.book(this,app);
            case JOURNAL -> bookHud.journal(this,app);
            case EVENTS -> eventHud.render(this,app);
            case TRACKS -> tracks();
            case MAP_EDITOR -> circuitEditorHud.render(this,g,app);
            case GARAGE -> garage();
            case SETTINGS -> settings();
            case CONTROLS -> controlHud.render(this,app);
            case KEYBOARD -> keyboardHud.render(this,app);
            case DRIVING_GUIDE -> drivingGuideHud.render(this,app);
            case TUNING_GUIDE -> tuningGuideHud.render(this,app);
            case SETUP_LIBRARY -> setupLibraryHud.render(this,app);
            case PARTS -> partsHud.render(this,g,app);
            case LIVERY -> liveryHud.render(this,g,app);
            case HELP -> help();
            case DRIVING -> driving();
            case PAUSED -> { driving(); pause(); }
            case RESULTS -> { driving(); results(); }
            case MINI4WD -> mini4wdHud.render(this, g, app, mouseX, mouseY);
            case RACE_ROOM -> raceRoomHud.render(this,app);
            case RACE_SETUP -> roomSettingsHud.render(this,app);
            case TELEMETRY -> telemetryHud.render(this,g,app);
            case GHOSTS -> ghostExchangeHud.render(this,app);
            case REPLAYS -> replayHud.library(this,app);
            case REPLAY -> replayHud.playback(this,g,app);
            case TUNING_LAB -> tuningLabHud.render(this,g,app);
            case WORKSHOP -> workshopHud.board(this,app);
            case EXPERIENCE -> experienceHud.board(this,g,app);
            case ACTIVITIES -> activityHud.board(this,app);
            case CREATION -> creationHud.render(this,app);
        }
        g.translate(-menuOffset,0);
        if (app.helpVisible) help();
        boolean comparingStarts=review!=null && review.splitProgress()!=null && review.splitProgress().comparing();
        if(!comparingStarts) drawToast();
        if (promptAction != null) textPrompt();
        if(confirmation!=null) confirmationDialog();
        if(review!=null) reviewDialog();
        if(numberDialog!=null) numberPrompt();
        if(comparingStarts) drawToast();
        if(transitioning()) buttons.clear();
        String context=app.screen+":"+startMenuHud.context()+":"+app.helpVisible+":"+(promptAction==null?"":promptTitle)+":"+(numberDialog==null?"":numberDialog.title())+":"+(confirmation==null?"":confirmation.title())+":"+(review==null?"":review.title())+":"+(app.screen==FormulaGame.Screen.MINI4WD?mini4wdHud.navigationContext(app.mini4wd()):"");
        if(app.screen==FormulaGame.Screen.MAP_EDITOR) context+=circuitEditorHud.practicePickerContext();
        context+=replayHud.markerContext(app);
        menuFocus.update(buttons.stream().map(Button::bounds).toList(),context);
        if(confirmation!=null && confirmation.useFocus()) menuFocus.showOutline();
        menuFocus.outline().ifPresent(bounds->{ g.setStroke(new BasicStroke(3));g.setColor(ORANGE);g.drawRect(bounds.x-3,bounds.y-3,bounds.width+6,bounds.height+6); });
    }
    private void drawToast() {
        if(app.toastLeft<=0) return;
        panel(240,24,800,52,new Color(14,50,57,245));
        centered(app.toast,640,57,17,MINT);
    }
    private float uiX(float windowX) { return (windowX*surfaceRatioX-ox)/scale; }
    private float uiY(float windowY) { return ((app.getCamera().getHeight()-windowY)*surfaceRatioY-oy)/scale; }
    public void close() {
        closed=true;
        releaseEquipmentMapRasters();
        cancelCreationImport();
        cancelLiveryExportReview();
        cancelAssetReload();
        if(fileWorker!=null) {
            if(liveryExportWrite!=null && !liveryExportWrite.isDone()) fileWorker.shutdown();
            else fileWorker.shutdownNow();
        }
        quad.removeFromParent();
        texture.setImage(null);
        surface.close();
        scene.close();
        replayHud.keepMarkers(null);
        telemetryHud.keepCharts(null);
        experienceHud.clearMap();
        drivingOutline(null);
        g=null;
    }

    public void click(Vector2f position) {click(position,false,app.controlDown());}
    public void click(Vector2f position,boolean control) {click(position,false,control);}
    public void pointerPress(Vector2f position) {click(position,true,app.controlDown());}
    private void click(Vector2f position,boolean pointer,boolean control) {
        if(transitioning()) return;
        menuFocus.mouse();
        float x = uiX(position.x), y = uiY(position.y);
        if(beginPreviewPan(MouseInput.BUTTON_LEFT,x,y)) return;
        if(review!=null && review.equipmentMap()!=null && review.equipmentMap().seek(x,y)) {render();return;}
        if(numberDialog!=null || confirmation!=null || review!=null || pickingCircuitPractice() || replayHud.reviewing(app)) { for(int i=buttons.size()-1;i>=0;i--) { var button=buttons.get(i);if(button.bounds.contains(x,y)) { button.action.run();render();return; } }return; }
        if(promptAction==null && telemetryHud.click(app,x,y)) { render();return; }
        if(promptAction==null && app.screen==FormulaGame.Screen.TUNING_LAB && !app.helpVisible && tuningLabHud.click(x,y)) {render();return;}
        if(promptAction==null && app.screen==FormulaGame.Screen.PARTS && !app.helpVisible && discussionLapView!=null && discussionLapView.click(x,y)) {render();return;}
        if(promptAction==null && replayHud.click(app,x,y,pointer)) { render();return; }
        if(promptAction==null && experienceHud.click(app,x,y,pointer)) { render();return; }
        if(promptAction==null && app.screen==FormulaGame.Screen.LIVERY && liveryHud.click(app,x,y)) {render();return;}
        if(promptAction==null && editCircuit(x,y,false,control)) { render();return; }
        if (promptAction == null && editMiniTrack(x, y, false,control)) return;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Button button = buttons.get(i);
            if (button.bounds.contains(x, y)) { button.action.run(); render(); return; }
        }
    }
    private boolean scrubbingPlayback() {return replayHud.scrubbing() || experienceHud.scrubbing();}
    private void updateReplayPointer(Vector2f position) {
        if(typing() || transitioning()) endReplayPointer();
        else {
            float x=uiX(position.x);
            if(replayHud.scrubbing()) replayHud.drag(app,x);
            if(experienceHud.scrubbing()) experienceHud.drag(app,x);
        }
    }
    public void releaseReplayPointer(Vector2f position) {
        if(scrubbingPlayback()) updateReplayPointer(position);
        endReplayPointer();
    }
    public void endReplayPointer() {replayHud.endScrub();experienceHud.endScrub();}
    public void clearButtons() { buttons.clear(); }
    public void backMini() { mini4wdHud.back(app.mini4wd()); }
    public void controllerNavigate(int direction) { if(transitioning()) return;if(numberDialog!=null) {numberDirection(direction);render();return;}render();menuFocus.move(direction);render(); }
    public void controllerConfirm() {
        if(transitioning()) return;
        if(numberDialog!=null) {acceptNumber();render();return;}
        var previous=menuFocus.active()?focusedButton():null;
        render();
        if(previous!=null && (!menuFocus.active() || !isSameButton(previous,focusedButton()))) return;
        if(acceptFocusedButton()) render();
    }
    public void releaseNavigationKey(int key) {menuFocus.releaseNavigationKey(key);}
    public void clearNavigationKeys() {menuFocus.clearNavigationKeys();}
    public boolean keyboardMenuKey(KeyInputEvent event,boolean backward) {
        if(app.screen==FormulaGame.Screen.DRIVING || app.helpVisible || typing()) return false;
        int key=event.getKeyCode();
        if(app.replayViewing() && (key==KeyInput.KEY_LEFT || key==KeyInput.KEY_RIGHT)) {
            if(event.isPressed() && !event.isRepeating() && !transitioning()) {
                replayHud.stepPlayback(app.replayPlayer(),key==KeyInput.KEY_RIGHT);
                render();
            }
            return true;
        }
        int direction=switch(key) {
            case KeyInput.KEY_RIGHT -> 1;
            case KeyInput.KEY_DOWN -> 2;
            case KeyInput.KEY_LEFT -> 3;
            case KeyInput.KEY_UP -> 4;
            default -> 0;
        };
        if(direction!=0 || key==KeyInput.KEY_TAB) {
            if(event.isPressed() && !transitioning()) {
                render();
                if(!menuFocus.acceptsNavigationPress(key,event.isRepeating())) return true;
                if(key==KeyInput.KEY_TAB) menuFocus.cycle(backward);
                else menuFocus.move(direction);
                render();
            }
            return true;
        }
        if((key==KeyInput.KEY_RETURN || key==KeyInput.KEY_NUMPADENTER) && menuFocus.active()) {
            if(event.isPressed() && !event.isRepeating()) controllerConfirm();
            return true;
        }
        return false;
    }
    private Button focusedButton() {
        int index=menuFocus.index();
        return index>=0 && index<buttons.size()?buttons.get(index):null;
    }
    private static boolean isSameButton(Button before,Button after) {
        return after!=null && before.bounds().equals(after.bounds()) && before.label().equals(after.label());
    }
    private boolean acceptFocusedButton() {
        int index=menuFocus.confirm();
        if(index<0 || index>=buttons.size()) return false;
        buttons.get(index).action.run();
        return true;
    }
    public boolean cancelPrompt() {
        if(numberDialog!=null) numberDialog=null;
        else if(confirmation!=null) cancelConfirmation();
        else if(promptAction!=null) promptAction=null;
        else if(review!=null) closeReview();
        else if(replayHud.reviewing(app)) replayHud.closeMarkers();
        else if(pickingCircuitPractice()) circuitEditorHud.closeLibrary();
        else return false;
        render();
        return true;
    }
    private boolean editCircuit(float x,float y,boolean erase,boolean control) {
        if(app.screen!=FormulaGame.Screen.MAP_EDITOR || app.circuitEditor()==null || typing() || circuitLibraryOpen() || app.helpVisible || !CircuitEditorView.BOARD.contains(x,y)) return false;
        if(circuitPanPointer!=null || circuitOriginalNearbyOpen()) return true;
        var view=circuitEditorHud.view;app.circuitEditor().beginPointer(view.worldX(x),view.worldZ(y),12/view.scale(),erase,control);return true;
    }
    public void eraseCircuit(Vector2f cursor) {
        eraseCircuit(cursor,app.controlDown());
    }
    public void eraseCircuit(Vector2f cursor,boolean control) {
        if(promptAction==null && editCircuit(uiX(cursor.x),uiY(cursor.y),true,control)) render();
    }
    public void endCircuitPointer() { if(app.circuitEditor()!=null) app.circuitEditor().endPointer(); }
    public void cancelCircuitPointer() {
        endCircuitPointer();
        circuitPanPointer=null;
    }
    public void releaseCircuitPointer(Vector2f cursor) {
        if(app.circuitEditor()!=null && app.circuitEditor().dragging()) updateCircuitPointer(cursor);
        endCircuitPointer();
    }
    public void resetCircuitEditorView() { circuitPanPointer=null;if(app.circuitEditor()!=null) circuitEditorHud.view.fit(app.circuitEditor().document()); }
    public void resetCircuitEditorState() { endCircuitPointer();circuitPanPointer=null;circuitEditorHud.changedDraft();resetCircuitEditorView(); }
    public void refreshCircuitPracticeSlots() {circuitEditorHud.refreshPracticeSlots(app.circuits);}
    public void showCircuitReview() { circuitEditorHud.review(); }
    public void showCircuitObservation(int marker) {circuitEditorHud.showObservation(app.circuitEditor(),marker,app.circuitReview());}
    public void showCircuitHistory() { circuitEditorHud.history(); }
    public void showCircuitGroups() { circuitEditorHud.groups(); }
    public void showCircuitDiagnostics() { circuitEditorHud.diagnostics(); }
    public void showCircuitFeedback() {endCircuitPointer();circuitPanPointer=null;circuitEditorHud.showFeedback();}
    public void focusCircuitFeedback(CircuitFeedback.Entry entry,CircuitFeedback.Note note,CircuitDocument target) {endCircuitPointer();circuitPanPointer=null;circuitEditorHud.focusFeedback(entry,note,target);}
    public void inspectCircuitFeedbackNearby(CircuitFeedback.Entry entry,CircuitFeedback.Note note) {
        endCircuitPointer();
        circuitPanPointer=null;
        circuitEditorHud.inspectFeedbackNearby(app.circuitEditor(),new CircuitFeedback.LocationSource(entry,note));
    }
    public void resumeCircuitFeedbackNearby(CircuitFeedback.LocationSource source) {circuitEditorHud.resumeFeedbackNearby(app,source);}
    public void showCircuitFeedbackNote(CircuitFeedback.LocationSource source) {circuitEditorHud.showFeedbackNote(app,source);}
    public void showCircuitFeedbackDiscussion(CircuitFeedbackDiscussion thread,CircuitFeedbackDiscussion.Message message) {
        circuitEditorHud.showFeedbackDiscussion(thread,message);
    }
    public boolean circuitLibraryOpen() { return app.screen==FormulaGame.Screen.MAP_EDITOR && circuitEditorHud.libraryOpen(); }
    public void showCircuitLibrary() { endCircuitPointer();circuitPanPointer=null;circuitEditorHud.showLibrary(); }
    public void closeCircuitLibrary() { circuitEditorHud.closeLibrary(); }
    public boolean circuitOriginalNearbyOpen() {return app.screen==FormulaGame.Screen.MAP_EDITOR && circuitEditorHud.originalNearbyOpen();}
    public void closeCircuitOriginalNearby() {circuitEditorHud.closeOriginalNearby();render();}
    public boolean backFromCircuitNearby() {
        if(!circuitEditorHud.backFromNearby(app)) return false;
        render();
        return true;
    }
    private boolean circuitPointerAvailable() {
        return app.screen==FormulaGame.Screen.MAP_EDITOR && app.circuitEditor()!=null && !circuitLibraryOpen()
                && !circuitOriginalNearbyOpen() && !typing() && !transitioning() && !app.helpVisible;
    }
    public void updateCircuitPointer(Vector2f cursor) {
        if(!circuitPointerAvailable()) {cancelCircuitPointer();return;}
        var editor=app.circuitEditor();
        if(circuitPanPointer==null && !editor.dragging()) return;
        float x=uiX(cursor.x),y=uiY(cursor.y);
        if(circuitPanPointer!=null) { circuitEditorHud.view.pan(x-circuitPanPointer.x,y-circuitPanPointer.y);circuitPanPointer.set(x,y); }
        else editor.movePointer(circuitEditorHud.view.worldX(x),circuitEditorHud.view.worldZ(y));
    }
    public void panCircuit(boolean down,Vector2f cursor) {
        if(!down) {
            if(circuitPanPointer!=null) updateCircuitPointer(cursor);
            circuitPanPointer=null;
            return;
        }
        if(circuitPanPointer!=null || !circuitPointerAvailable() || app.circuitEditor().dragging()) return;
        float x=uiX(cursor.x),y=uiY(cursor.y);
        if(CircuitEditorView.BOARD.contains(x,y)) circuitPanPointer=new Vector2f(x,y);
    }
    public void zoomEditing(boolean inward) {
        if(app.screen==FormulaGame.Screen.GAME_SETUP && app.launchSetup()!=null) {
            if(typing() || transitioning()) return;var cursor=app.getInputManager().getCursorPosition();float x=uiX(cursor.x),y=uiY(cursor.y);if(TrackPreview.AREA.contains(x,y)) app.launchSetup().preview.zoom(inward,x,y);return;
        }
        if(app.screen!=FormulaGame.Screen.MAP_EDITOR) { zoomMini(inward);return; }
        if(app.circuitEditor()==null || circuitLibraryOpen() || typing() || app.helpVisible) return;var cursor=app.getInputManager().getCursorPosition();float x=uiX(cursor.x),y=uiY(cursor.y);
        if(circuitEditorHud.scroll(inward,x,y,app)) return;
        if(CircuitEditorView.BOARD.contains(x,y)) { endCircuitPointer();circuitEditorHud.view.zoom(inward,x,y); }
    }
    public void beginTrackSelection(String id) {
        var tracks=app.availableTracks();trackPage=0;for(int i=0;i<tracks.size();i++) if(tracks.get(i).id().equals(id)) trackPage=i/2;
    }
    public void chooseCircuitFile(boolean export) {
        if(!importPage(FormulaGame.Screen.MAP_EDITOR) || export && app.circuitEditor()==null) return;
        endCircuitPointer();
        circuitPanPointer=null;
        if(!export) {chooseImportFile();return;}
        var editor=app.circuitEditor();
        var document=editor.document();
        var directory=app.store.directory().toAbsolutePath().toFile();
        var suggested="circuit-export-"+document.name().replaceAll("[\\\\/:*?\"<>|]","_")+".json";
        SwingUtilities.invokeLater(()->{
            try {
                var chooser=new JFileChooser(directory);chooser.setDialogTitle("导出方程式地图");
                chooser.setFileFilter(new FileNameExtensionFilter("方程式地图 (*.json)","json"));
                chooser.setSelectedFile(new java.io.File(suggested));
                if(chooser.showSaveDialog(null)!=JFileChooser.APPROVE_OPTION) return;
                var path=chooser.getSelectedFile().toPath();
                if(!path.toString().toLowerCase(Locale.ROOT).endsWith(".json")) path=path.resolveSibling(path.getFileName()+".json");
                var destination=approveChooserExport(path);
                if(destination!=null) app.enqueue(()->{
                    if(importPage(FormulaGame.Screen.MAP_EDITOR) && app.circuitEditor()==editor)
                        app.exportReviewedCircuitDraft(document,null,destination);
                    return true;
                });
            } catch(IOException | RuntimeException error) { app.enqueue(()->{if(importPage(FormulaGame.Screen.MAP_EDITOR)) app.notifyUser("地图文件路径或选择窗口无法打开。");return true;}); }
        });
    }
    public void chooseCreationFile(String packId,boolean export) {
        if(!importPage(FormulaGame.Screen.CREATION)) return;
        if(!export) {chooseImportFile();return;}
        var reviewed=app.reviewCircuitPackExport(packId);
        if(reviewed==null) {app.notifyUser("赛事包已不存在，请重新选择后导出。");return;}
        var directory=app.store.directory().toAbsolutePath().toFile();
        SwingUtilities.invokeLater(()->{
            try {
                var chooser=new JFileChooser(directory);chooser.setDialogTitle("导出自制赛事包");
                chooser.setFileFilter(new FileNameExtensionFilter("自制赛事包 (*.json)","json"));
                chooser.setSelectedFile(new java.io.File("circuit-export-pack.json"));
                if(chooser.showSaveDialog(null)!=JFileChooser.APPROVE_OPTION) return;
                var path=chooser.getSelectedFile().toPath();
                if(!path.toString().toLowerCase(Locale.ROOT).endsWith(".json")) path=path.resolveSibling(path.getFileName()+".json");
                var destination=approveChooserExport(path);
                if(destination!=null) app.enqueue(()->{
                    if(importPage(FormulaGame.Screen.CREATION)) app.exportReviewedCircuitPack(reviewed,destination);
                    return true;
                });
            } catch(IOException | RuntimeException error) {app.enqueue(()->{if(importPage(FormulaGame.Screen.CREATION)) app.notifyUser("赛事包文件路径或选择窗口无法打开。");return true;});}
        });
    }

    private boolean importPage(FormulaGame.Screen origin) {
        return !closed && !app.online() && app.screen==origin
                && (origin!=FormulaGame.Screen.MAP_EDITOR || !circuitEditorHud.libraryOpen())
                && (origin!=FormulaGame.Screen.LIVERY || liveryHud.libraryOpen());
    }
    private ImportContext importContext() {return new ImportContext(app.screen,app.captureCircuitImportDraft());}

    private void chooseImportFile() {
        if(importChooser!=null) return;
        var context=importContext();
        importChooser=context;
        var directory=app.store.directory().toAbsolutePath().toFile();
        boolean map=context.screen()==FormulaGame.Screen.MAP_EDITOR;
        SwingUtilities.invokeLater(()->{
            if(importChooser!=context) return;
            try {
                var chooser=new JFileChooser(directory);
                chooser.setDialogTitle(map?"导入地图或区间草稿":"导入自制赛事包");
                chooser.setFileFilter(new FileNameExtensionFilter(map?"地图或区间草稿 (*.json)":"自制赛事包 (*.json)","json"));
                int choice=chooser.showOpenDialog(null);
                finishImportSelection(context,choice==JFileChooser.APPROVE_OPTION?chooser.getSelectedFile().toPath():null,false);
            } catch(RuntimeException error) {finishImportSelection(context,null,true);}
        });
    }

    private void finishImportSelection(ImportContext context,Path path,boolean failed) {
        app.enqueue(()->{
            if(importChooser!=context) return true;
            importChooser=null;
            if(importPage(context.screen())) {
                if(failed) app.notifyUser("导入文件路径或选择窗口无法打开。");
                else if(path!=null) startFileImport(path,context);
            }
            return true;
        });
    }

    private void discardDepartedImport() {
        if(fileImport!=null && !importPage(fileImport.context().screen()) || importChooser!=null && !importPage(importChooser.screen()))
            cancelCreationImport();
    }
    public boolean creationImportReading() {return fileImport!=null && fileImport.context().screen()==FormulaGame.Screen.CREATION;}
    public boolean circuitImportReading() {return fileImport!=null && fileImport.context().screen()==FormulaGame.Screen.MAP_EDITOR;}
    boolean liveryImportReading() {return fileImport!=null && fileImport.context().screen()==FormulaGame.Screen.LIVERY;}

    public void cancelCreationImport() {
        importChooser=null;
        if(fileImport==null) return;
        fileImport.result().cancel(true);
        fileImport=null;
        fileWorker.purge();
    }
    public void previewCreationImport(Path path) {
        if(importPage(FormulaGame.Screen.CREATION)) startFileImport(path,importContext());
    }
    public void previewCircuitImport(Path path) {
        if(importPage(FormulaGame.Screen.MAP_EDITOR)) startFileImport(path,importContext());
    }
    void previewLiveryImport(Path path,BooleanSupplier stillSelected) {
        if(importPage(FormulaGame.Screen.LIVERY)) startFileImport(path,new ImportContext(FormulaGame.Screen.LIVERY,stillSelected));
    }
    private void startFileImport(Path path,ImportContext context) {
        cancelCreationImport();
        if(!context.draftCurrent().getAsBoolean()) {
            app.notifyUser("选择文件期间当前草稿已变化，请重新选择。");
            return;
        }
        try {
            var worker=fileWorker();
            if(context.screen()==FormulaGame.Screen.MAP_EDITOR) {
                var store=app.circuits;
                fileImport=new FileImport(worker.submit(()->{
                    var draft=store.importDraftFile(path);
                    return ()->app.acceptCircuitImport(draft,path.getFileName().toString());
                }),context);
            } else if(context.screen()==FormulaGame.Screen.LIVERY) {
                fileImport=new FileImport(worker.submit(()->{
                    var value=LiveryCodec.read(path);
                    return ()->liveryHud.showImported(this,app,value);
                }),context);
            } else {
                var store=app.creationStore;
                fileImport=new FileImport(worker.submit(()->{
                    var pack=CircuitEditionCodec.read(path,store);
                    return ()->showCreationImport(pack);
                }),context);
            }
        } catch(RejectedExecutionException | SecurityException unavailable) {
            app.notifyUser("暂时无法开始读取导入文件，请重试。");
        }
    }
    private ThreadPoolExecutor fileWorker() {
        if(fileWorker==null) {
            // Imports, skin reloads and livery exports each have at most one queued task.
            fileWorker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(3),task->{
                var thread=new Thread(task,"formula-hud-file-reader");
                thread.setDaemon(true);
                return thread;
            });
            fileWorker.allowCoreThreadTimeOut(true);
        }
        return fileWorker;
    }
    private void updateFileImport() {
        discardDepartedImport();
        var read=fileImport;
        if(read==null || !read.result().isDone() || importChooser!=null || typing() || transitioning() || app.helpVisible) return;
        boolean map=read.context().screen()==FormulaGame.Screen.MAP_EDITOR;
        if(map && app.circuitEditor()!=null && app.circuitEditor().dragging()) return;
        if(read.context().screen()==FormulaGame.Screen.CREATION && creationHud.editingOverlay()) return;
        fileImport=null;
        if(!read.context().draftCurrent().getAsBoolean()) {
            app.notifyUser(read.context().screen()==FormulaGame.Screen.LIVERY?"作品选择或草稿已变化，请重新导入。":"读取期间当前草稿已变化，请重新选择文件。");
            return;
        }
        try {
            read.result().get().run();
        } catch(CancellationException canceled) {
            // A canceled reader has no result to display.
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch(ExecutionException | RuntimeException error) {
            if(read.context().screen()==FormulaGame.Screen.LIVERY) {
                var cause=error instanceof ExecutionException?error.getCause():error;
                app.notifyUser("无法读取涂装："+cause.getMessage());
            } else app.notifyUser(map?"导入失败，请检查地图或区间草稿文件。当前草稿保留。":"赛事包无法预览，请检查格式、大小与条件；已有内容保留。");
        }
    }
    private void showCreationImport(CircuitEditionCodec.Shared shared) {
        var pack=shared.pack();
        if(pack.version()<4) {app.importCircuitPack(shared);return;}
        var lines=CreationHud.activityDetails(pack.activity());
        lines.add("名称："+pack.title()+"；作者："+pack.author()+"；说明："+pack.notes());
        lines.add("收录 "+pack.circuits().size()+" 张独立本地地图与原始赛事包；不带本机驾驶资格、场次或成绩。附加条件可达性未由地图四车资格证明。");
        var editor=app.circuitEditor();
        if(editor!=null && editor.dirty()) lines.add("确认后先保存正在编辑的草稿“"+editor.document().name()+"”。");
        if(pack.partsBundle()!=null) lines.add("包含限定部件与推荐方案；收录部件、读取推荐和应用车库仍分别操作。");
        if(shared.lineage()!=null) lines.add("携带作者修订声明；署名与父包关系是内容声明，不授予驾驶资格。");
        review("导入复合条件赛事包？",lines,"收录地图与赛事包",app.reviewCircuitPackImport(shared));
    }

    public void eraseMiniTrack(Vector2f position) {
        eraseMiniTrack(position,app.controlDown());
    }
    public void eraseMiniTrack(Vector2f position,boolean control) {
        editMiniTrack(uiX(position.x),uiY(position.y),true,control);
    }

    public void updateMiniPointer(Vector2f position) {
        var game=app.mini4wd();
        if (app.screen != FormulaGame.Screen.MINI4WD || game==null || game.view()!=Mini4wdGame.View.EDITOR || typing() || transitioning()) {
            endMiniPointer();
            return;
        }
        if(panPointer==null && !game.draggingSelectionOrShape()) return;
        float x = uiX(position.x), y = uiY(position.y);
        if (panPointer != null) {
            game.pan((x - panPointer.x) / Mini4wdHud.TILE, (y - panPointer.y) / Mini4wdHud.TILE);
            panPointer.set(x, y);
            return;
        }
        game.dragShapePointer(boardPoint(x, y));
    }

    public void endMiniPointer() {
        panPointer=null;
        if (app.mini4wd()!=null) app.mini4wd().endShapeDrag();
    }

    public void releaseMiniPointer(Vector2f cursor) {
        var game=app.mini4wd();
        if(game!=null && game.draggingSelectionOrShape()) {
            updateMiniPointer(cursor);
            game.endShapeDrag();
        }
    }

    public void panMini(boolean held,Vector2f cursor) {
        if (!held) {
            if(panPointer!=null) updateMiniPointer(cursor);
            panPointer=null;
            return;
        }
        if (app.screen != FormulaGame.Screen.MINI4WD || app.mini4wd().view() != Mini4wdGame.View.EDITOR
                || typing() || transitioning() || panPointer!=null || app.mini4wd().draggingSelectionOrShape()) return;
        float x = uiX(cursor.x), y = uiY(cursor.y);
        if (Mini4wdHud.cellAt(x, y) >= 0) panPointer = new Vector2f(x, y);
    }

    public void zoomMini(boolean inward) {
        if (app.screen != FormulaGame.Screen.MINI4WD || app.mini4wd().view() != Mini4wdGame.View.EDITOR || typing()) return;
        Vector2f cursor = app.getInputManager().getCursorPosition();
        float x = uiX(cursor.x), y = uiY(cursor.y);
        if (Mini4wdHud.cellAt(x, y) < 0) return;
        app.mini4wd().zoom(inward ? 1.12 : 1 / 1.12, new MiniTrack.Point((x - Mini4wdHud.BOARD_X) / Mini4wdHud.TILE, (y - Mini4wdHud.BOARD_Y) / Mini4wdHud.TILE));
    }

    private MiniTrack.Point boardPoint(float x, float y) {
        var game = app.mini4wd();
        return new MiniTrack.Point((x - Mini4wdHud.BOARD_X) / Mini4wdHud.TILE / game.zoom() + game.panX(),
            (y - Mini4wdHud.BOARD_Y) / Mini4wdHud.TILE / game.zoom() + game.panY());
    }

    private boolean editMiniTrack(float x, float y, boolean erase,boolean control) {
        if (typing() || app.screen != FormulaGame.Screen.MINI4WD || app.mini4wd().view() != Mini4wdGame.View.EDITOR) return false;
        if (Mini4wdHud.cellAt(x, y) < 0) return false;
        var game = app.mini4wd();
        if(panPointer!=null || game.draggingSelectionOrShape()) return true;
        var point = boardPoint(x, y);
        int cell = MiniTrack.cell((int) Math.floor(point.x()), (int) Math.floor(point.y()));
        if (cell < 0) return true;
        if (game.editorMode() == Mini4wdGame.EditorMode.TEMPLATES) return true;
        if (control) game.multiSelect(point);
        else if (game.editorMode() == Mini4wdGame.EditorMode.SHAPES) {
            game.beginShapePointer(point, erase);
        } else if (game.editorMode() == Mini4wdGame.EditorMode.SELECT) {
            game.beginSelectionPointer(point, false);
            if (erase) game.deleteSelection();
        } else game.place(cell, erase);
        render();
        return true;
    }

    public void prompt(String title, String value, Consumer<String> action) {
        prompt(title,value,120,action);
    }
    public void prompt(String title,String value,int limit,Consumer<String> action) {
        if(limit<1 || limit>1024) throw new IllegalArgumentException("Invalid text input limit");
        endMiniPointer();cancelCircuitPointer();
        numberDialog=null;confirmation=null;review=null;promptDefault=null;promptTitle = title; promptValue = value; promptAction = action;promptLimit=limit;
    }
    void promptExportPath(String title,Path suggested,java.util.function.BiConsumer<Path,Boolean> write) {
        promptReviewedExportPath(title,suggested,destination->{
            if(!destination.replacing() || exportDestinationCurrent(destination))
                write.accept(destination.path(),destination.replacing());
        });
    }
    void promptReviewedExportPath(String title,Path suggested,Consumer<ExportDestination> write) {
        prompt(title,suggested.toAbsolutePath().toString(),512,value->{
            ExportDestination destination;
            try {
                if(value.isBlank()) throw new InvalidPathException(value,"Empty export path");
                destination=ExportDestination.inspect(Path.of(value.strip()));
            } catch(IOException | RuntimeException error) {
                app.notifyUser("无法核对导出路径，请填写可读取的独立文件名。");
                return;
            }
            if(destination.replacing()) confirm("覆盖这个导出文件？",destination.path().toString(),
                    ()->write.accept(destination),null,true);
            else write.accept(destination);
        });
    }
    boolean exportDestinationCurrent(ExportDestination destination) {
        try {if(destination.current()) return true;}
        catch(IOException | RuntimeException ignored) { /* An unchecked destination requires a new review. */ }
        app.notifyUser("导出目标已变化或无法核对，请重新选择和确认。");
        return false;
    }
    private static ExportDestination approveChooserExport(Path path) throws IOException {
        var destination=ExportDestination.inspect(path);
        if(destination.replacing()) {
            String[] actions={"取消","覆盖文件"};
            int selected=javax.swing.JOptionPane.showOptionDialog(null,"覆盖选定的导出文件？\n"+destination.path(),
                "确认覆盖",javax.swing.JOptionPane.DEFAULT_OPTION,javax.swing.JOptionPane.QUESTION_MESSAGE,null,actions,actions[0]);
            if(selected!=1) return null;
        }
        return destination;
    }
    public void editText(String title,String value,String defaults,int limit,Consumer<String> action) {
        prompt(title,value,limit,text->{try { if(text.length()>limit || text.chars().anyMatch(c->Character.isISOControl(c) && c!='\n')) throw new IllegalArgumentException();action.accept(text.strip()); }catch(IllegalArgumentException error) { app.notifyUser("内容无效：请检查空白、长度及控制字符。");editText(title,text,defaults,limit,action); }});promptDefault=defaults;
    }
    /** False retains this input for an explicit retry; rendering never resubmits the callback. */
    public void editTextChecked(String title,String value,String defaults,int limit,java.util.function.Predicate<String> action) {
        prompt(title,value,limit,text->{
            try {if(text.length()>limit || text.chars().anyMatch(c->Character.isISOControl(c) && c!='\n')) throw new IllegalArgumentException();if(action.test(text.strip())) return;}
            catch(IllegalArgumentException error) {app.notifyUser("内容无效：请检查空白、长度及控制字符。");}
            editTextChecked(title,text,defaults,limit,action);
        });promptDefault=defaults;
    }
    void nameCircuitDraftCopy(CircuitDocument source,CircuitStore.PracticeSlot preview) {
        String suffix=" · 副本";
        String name=source.name().length()+suffix.length()<=60?source.name()+suffix:source.name();
        String title=preview==null?"另存当前草稿 · 副本名称":"另存地图与区间 · 副本名称";
        editTextChecked(title,name,name,60,value->app.saveCircuitDraftCopy(source,value,preview));
    }
    void chooseCircuitPracticeExport(CircuitDocument source,CircuitStore.PracticeSlot preview) {
        var suggested=app.store.directory().resolve("circuit-export-practice.json").toAbsolutePath().toString();
        promptCircuitPracticeExport(source,preview,suggested,suggested);
    }
    private void promptCircuitPracticeExport(CircuitDocument source,CircuitStore.PracticeSlot preview,String current,String suggested) {
        editTextChecked("导出区间草稿 · JSON 完整路径",current,suggested,512,value->{
            if(value.isBlank()) throw new IllegalArgumentException("Empty export path");
            try {
                var destination=ExportDestination.inspect(Path.of(value));
                if(!destination.replacing()) return app.exportReviewedCircuitDraft(source,preview,destination);
                confirm("覆盖这个区间草稿文件？",destination.path().toString(),()->{
                    if(!app.exportReviewedCircuitDraft(source,preview,destination))
                        promptCircuitPracticeExport(source,preview,value,suggested);
                },null,true);
                return true;
            } catch(IOException error) {
                app.notifyUser("无法核对区间草稿导出目标，原路径保留。");
                return false;
            }
        });
    }
    void reviewCircuitDraftComparison(CircuitDocument source,CircuitStore.PracticeSlot preview) {
        var editor=app.circuitEditor();
        if(app.screen!=FormulaGame.Screen.MAP_EDITOR || editor==null || !app.circuits.needsRefresh()) return;
        if(!editor.document().equals(source)) {
            app.notifyUser("当前草稿已改变，请重新打开保存差异。");
            return;
        }
        CircuitStore.DraftComparison comparison;
        try {
            comparison=app.circuits.inspectDraftComparison(source.id());
        } catch(IOException | SecurityException error) {
            app.notifyUser("暂时无法读取磁盘资料库。当前草稿已保留，可导出副本文件。");
            return;
        }
        review("地图保存差异",CircuitConflictReview.lines(comparison,source,editor.proof(),preview),"返回",null);
    }
    private boolean pickingCircuitPractice() {return app.screen==FormulaGame.Screen.MAP_EDITOR && !circuitEditorHud.practicePickerContext().isEmpty();}
    public boolean typing() {return numberDialog!=null || promptAction!=null || confirmation!=null || review!=null || pickingCircuitPractice() || replayHud.reviewing(app);}
    public boolean canPresentReplayMarkers() {return !typing() && !transitioning() && !replayHud.scrubbing();}
    public void showReplayMarkers() {
        if(replayHud.openMarkers(app)) render();
    }

    /** The callback returns false to retain the draft when context validation or saving fails. */
    public void editNumber(String title,int value,int min,int max,String unit,java.util.function.IntPredicate accept) {
        openNumberDialog(title,value,min,max,unit,accept);
        review=null;
    }
    void openNumberDialog(String title,int value,int min,int max,String unit,java.util.function.IntPredicate accept) {
        var editor=new DigitNumberEditor(value,min,max);
        endMiniPointer();cancelCircuitPointer();endPreviewPan();
        promptAction=null;confirmation=null;numberDialog=new NumberDialog(java.util.Objects.requireNonNull(title),java.util.Objects.requireNonNull(unit),editor,java.util.Objects.requireNonNull(accept));
    }
    private void numberDirection(int direction) {
        var editor=numberDialog.editor();switch(direction) {case 1->editor.move(-1);case 2->editor.step(-1);case 3->editor.move(1);case 4->editor.step(1);default->{}}
    }
    private void acceptNumber() {
        var dialog=numberDialog;if(dialog==null || !dialog.editor().validate()) return;
        if(dialog.accept().test(dialog.editor().value().orElseThrow()) && numberDialog==dialog) numberDialog=null;
    }
    private void numberKey(KeyInputEvent event,boolean control) {
        event.setConsumed();if(!event.isPressed()) return;
        int key=event.getKeyCode();var editor=numberDialog.editor();
        if(key==KeyInput.KEY_ESCAPE) {numberDialog=null;return;}
        if(key==KeyInput.KEY_RETURN || key==KeyInput.KEY_NUMPADENTER) {if(!event.isRepeating()) acceptNumber();return;}
        if(control && key==KeyInput.KEY_A) editor.selectAll();
        else if(control && key==KeyInput.KEY_V) {try {editor.paste(Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor).toString());}catch(Exception error) {app.notifyUser("剪贴板暂时无法读取，输入值保留。");}}
        else if(!control) switch(key) {
            case KeyInput.KEY_LEFT->numberDirection(3);case KeyInput.KEY_RIGHT->numberDirection(1);
            case KeyInput.KEY_UP->numberDirection(4);case KeyInput.KEY_DOWN->numberDirection(2);
            case KeyInput.KEY_PGUP->editor.step(10);case KeyInput.KEY_PGDN->editor.step(-10);
            case KeyInput.KEY_HOME->editor.boundary(false);case KeyInput.KEY_END->editor.boundary(true);
            case KeyInput.KEY_BACK,KeyInput.KEY_DELETE->editor.backspace();
            default->{if(event.getKeyChar()>=32 && !Character.isISOControl(event.getKeyChar())) editor.type(event.getKeyChar());}
        }
    }
    private void numberPrompt() {
        var dialog=numberDialog;var editor=dialog.editor();shade();buttons.clear();panel(260,164,760,456,PANEL);
        wrapped(shorten(dialog.title(),60),292,204,696,21,INK,27);
        text("可调范围 "+editor.min()+"–"+editor.max()+dialog.unit()+(editor.min()==editor.max()?" · 此部件固定值":""),292,253,16,MUTED);
        panel(292,272,696,62,new Color(24,49,60));String shown=editor.display();
        g.setFont(font.deriveFont(25f));int textWidth=g.getFontMetrics().stringWidth(shown);
        if(editor.replacing()) {g.setColor(new Color(47,98,106));g.fillRect(306,282,textWidth+8,39);}
        else if(editor.positional()) {int index=shown.length()-1-editor.place();int dx=g.getFontMetrics().stringWidth(shown.substring(0,index));int dw=g.getFontMetrics().charWidth(shown.charAt(index));g.setColor(new Color(47,98,106));g.fillRect(310+dx,282,dw,39);}
        text(shown,310,314,25,MINT);text(dialog.unit(),Math.min(944,326+textWidth),314,20,MUTED);
        text("← → 选择个／十／百位；↑ ↓ 始终增减 1"+dialog.unit(),292,365,16,INK);
        text("直接输入数字 · Ctrl+A 全选 · Ctrl+V 粘贴 · PgUp/PgDn ±10",292,393,14,MUTED);
        int[] steps={-100,-10,-1,1,10,100};for(int i=0;i<steps.length;i++) {int step=steps[i];smallButton(292+i*118,413,106,34,(step>0?"+":"")+step,()->editor.step(step));}
        smallButton(292,461,150,31,"选百位",()->editor.selectPlace(2));smallButton(454,461,150,31,"选十位",()->editor.selectPlace(1));smallButton(616,461,150,31,"选个位",()->editor.selectPlace(0));smallButton(778,461,210,31,"重新输入",editor::selectAll);
        String error=editor.error();if(error.isEmpty() && editor.value().isEmpty()) error="请输入范围内的整数；当前输入尚未提交";
        text(shorten(error,48),292,523,14,ORANGE);
        smallButton(292,552,332,44,"确定  Enter",this::acceptNumber);smallButton(650,552,338,44,"取消  Esc",()->numberDialog=null);
    }

    void activitySectionPreview(TrackLayout track,ActivitySection section,java.util.function.IntConsumer pick) {activitySectionPreview.render(g,this,track,section,pick);}
    void discussionTraceView(TuningTraceHud renderer,TrialTraceShare excerpt) {renderer.render(this,g,excerpt.report(),app.tuningTraceStore,excerpt,excerpt.traces().get(0).index());}
    void discussionLapView(EquipmentLapsHud renderer) {discussionLapView=renderer;renderer.render(this,g,app);}
    void liveryPreview(int x,int y,int size,CarLivery design,int quarter) {scene.liveryPreview(g,x,y,size,design,quarter);}
    public String carAtlasIdentity() {return scene.carAtlasIdentity();}
    public int carFrameSize() {return scene.carFrameSize();}
    public PaintRegions paintRegions() {return scene.paintRegions();}
    public String paintRegionWarning() {return scene.paintRegionWarning();}
    void paintRegionOutline(int x,int y,int size,CarLivery.Target target) {scene.paintRegionOutline(g,x,y,size,target);}
    public void textKey(KeyInputEvent event, boolean control) {
        if(numberDialog!=null) {numberKey(event,control);render();return;}
        if(review!=null) {
            event.setConsumed();
            if(!event.isPressed() || event.isRepeating()) return;
            if(review.equipmentInterruptionMap()!=null) {
                switch(event.getKeyCode()) {
                    case KeyInput.KEY_ESCAPE -> closeReview();
                    case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> {
                        if(review.equipmentInterruptionMap().canPracticeSelected()) previewEquipmentInterruptionSubsection();
                        else closeReview();
                    }
                    case KeyInput.KEY_LEFT -> review.equipmentInterruptionMap().move(-1);
                    case KeyInput.KEY_RIGHT -> review.equipmentInterruptionMap().move(1);
                    default -> {}
                }
                render();
                return;
            }
            if(review.equipmentMap()!=null) {
                switch(event.getKeyCode()) {
                    case KeyInput.KEY_ESCAPE,KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> closeReview();
                    case KeyInput.KEY_P -> {if(!control) review.equipmentMap().openPractice();}
                    case KeyInput.KEY_LEFT -> review.equipmentMap().move(-1);
                    case KeyInput.KEY_RIGHT -> review.equipmentMap().move(1);
                    case KeyInput.KEY_PGUP -> review.equipmentMap().moveBrake(-1);
                    case KeyInput.KEY_PGDN -> review.equipmentMap().moveBrake(1);
                    default -> {}
                }
                render();
                return;
            }
            if(review.equipmentSection()!=null) {
                var preview=review.equipmentSection();
                switch(event.getKeyCode()) {
                    case KeyInput.KEY_ESCAPE -> closeReview();
                    case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> startEquipmentSection(preview);
                    case KeyInput.KEY_A -> {if(!control) preview.editStart(this,true);}
                    case KeyInput.KEY_V -> {if(!control) preview.editStart(this,false);}
                    default -> {}
                }
                render();
                return;
            }
            if(review.equipmentSectionRuns()!=null) {
                var runs=review.equipmentSectionRuns();
                switch(event.getKeyCode()) {
                    case KeyInput.KEY_ESCAPE -> closeReview();
                    case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> {
                        var split=runs.splitToPractice();
                        if(split==null) runs.openComparison();
                        else previewEquipmentSubsection(review,split);
                    }
                    case KeyInput.KEY_A -> {if(!control) runs.nextAttempt(EquipmentSectionPracticeSession.Car.A);}
                    case KeyInput.KEY_B -> {if(!control) runs.nextAttempt(EquipmentSectionPracticeSession.Car.B);}
                    case KeyInput.KEY_UP -> runs.moveSplit(-1);
                    case KeyInput.KEY_DOWN -> runs.moveSplit(1);
                    case KeyInput.KEY_PGUP -> runs.movePage(-1);
                    case KeyInput.KEY_PGDN -> runs.movePage(1);
                    default -> {}
                }
                render();
                return;
            }
            if(review.retestMap()!=null) {
                switch(event.getKeyCode()) {
                    case KeyInput.KEY_ESCAPE,KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> closeReview();
                    case KeyInput.KEY_M -> {if(!control) review.retestMap().toggleExtent();}
                    default -> {}
                }
                render();
                return;
            }
            var practiceImport=review.practiceImport();
            if(practiceImport!=null && practiceImport.selectingSlot()) {
                switch(event.getKeyCode()) {
                    case KeyInput.KEY_ESCAPE -> practiceImport.backFromSlot();
                    case KeyInput.KEY_1 -> practiceImport.chooseSlot(1);
                    case KeyInput.KEY_2 -> practiceImport.chooseSlot(2);
                    case KeyInput.KEY_3 -> practiceImport.chooseSlot(3);
                    case KeyInput.KEY_4 -> practiceImport.chooseSlot(4);
                    case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> practiceImport.confirmSlot();
                    default -> {}
                }
                render();
                return;
            }
            var progress=review.splitProgress();
            if(progress!=null && progress.comparing()) {
                switch(event.getKeyCode()) {
                    case KeyInput.KEY_ESCAPE,KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> closeReview();
                    case KeyInput.KEY_LEFT -> progress.comparison().move(-1);
                    case KeyInput.KEY_RIGHT -> progress.comparison().move(1);
                    default -> {}
                }
                render();
                return;
            }
            switch(event.getKeyCode()) {
                case KeyInput.KEY_ESCAPE -> closeReview();
                case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> acceptReview();
                case KeyInput.KEY_PGDN -> review.pages().move(1);
                case KeyInput.KEY_PGUP -> review.pages().move(-1);
                case KeyInput.KEY_M -> {
                    if(!control && review.retestComparison()!=null) openRetestComparisonMap(review);
                }
                case KeyInput.KEY_P -> practiceSelectedCircuitSplit();
                case KeyInput.KEY_A,KeyInput.KEY_V -> {
                    if(!control) editCircuitSplitStart(event.getKeyCode()==KeyInput.KEY_A);
                }
                case KeyInput.KEY_UP,KeyInput.KEY_DOWN -> {
                    if(review.splitMap()!=null) review.splitMap().moveSelection(review.pages(),event.getKeyCode()==KeyInput.KEY_UP?-1:1);
                }
                default -> {}
            }
            render();
            return;
        }
        if(confirmation!=null) {
            event.setConsumed();if(!event.isPressed() || event.isRepeating()) return;
            switch(event.getKeyCode()) {
                case KeyInput.KEY_ESCAPE -> cancelConfirmation();
                case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> {
                    if(!confirmation.useFocus()) acceptConfirmation();
                    else acceptFocusedButton();
                }
                case KeyInput.KEY_TAB,KeyInput.KEY_RIGHT -> {if(confirmation.useFocus()) menuFocus.move(1);}
                case KeyInput.KEY_LEFT -> {if(confirmation.useFocus()) menuFocus.move(3);}
                default -> {}
            }
            render();
            return;
        }
        if(replayHud.reviewing(app)) {
            event.setConsumed();
            if(event.isPressed() && !event.isRepeating()) {
                replayHud.markerKey(this,event.getKeyCode());
                render();
            }
            return;
        }
        if(promptAction==null) {
            if(pickingCircuitPractice()) {
                event.setConsumed();
                if(event.isPressed() && !event.isRepeating()) {
                    circuitEditorHud.practicePickerKey(app,event.getKeyCode());
                    render();
                }
            }
            return;
        }
        event.setConsumed();
        if (!event.isPressed()) return;
        int key = event.getKeyCode();
        if (key == KeyInput.KEY_ESCAPE) { promptAction = null; render(); return; }
        if (key == KeyInput.KEY_RETURN || key == KeyInput.KEY_NUMPADENTER) { if(!event.isRepeating()) acceptPrompt(); return; }
        if (key == KeyInput.KEY_BACK && !promptValue.isEmpty()) {
            promptValue = promptValue.substring(0, promptValue.offsetByCodePoints(promptValue.length(), -1));
        } else if (control && key == KeyInput.KEY_V) {
            try {promptValue=appendPrompt(promptValue,Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor).toString(),promptLimit);}
            catch (Exception ignored) { }
        } else if (control && key == KeyInput.KEY_A) promptValue = "";
        else if (!control && event.getKeyChar() >= 32 && !Character.isISOControl(event.getKeyChar()))
            promptValue=appendPrompt(promptValue,String.valueOf(event.getKeyChar()),promptLimit);
        render();
    }

    static String appendPrompt(String current,String append,int limit) {
        if(append.isEmpty()) return current;
        int count=Math.min(append.length(),Math.max(0,limit-current.length()));
        if(count>0 && count<append.length() && Character.isHighSurrogate(append.charAt(count-1))
                && Character.isLowSurrogate(append.charAt(count))) count--;
        // A supplementary character may arrive as separate key events.
        if(count==0 && current.length()==limit && !current.isEmpty() && Character.isHighSurrogate(current.charAt(current.length()-1))
                && Character.isLowSurrogate(append.charAt(0))) return current.substring(0,current.length()-1);
        return count==0?current:current+append.substring(0,count);
    }

    private void acceptPrompt() {
        if(promptValue.codePoints().anyMatch(value->value>=0xd800 && value<=0xdfff)) {
            app.notifyUser("文字中有未完整输入的字符，请修改后再确认。");
            render();
            return;
        }
        Consumer<String> action = promptAction;
        promptAction = null;
        action.accept(promptValue);
        render();
    }

    private void textPrompt() {
        shade(); buttons.clear(); panel(260, 220, 760, 315, PANEL);
        wrapped(shorten(promptTitle,68),292,258,696,20,INK,26);
        panel(292, 322, 696, 62, new Color(24, 49, 60));
        String shown=promptValue.replace('\n',' ');g.setFont(font.deriveFont(21f));
        if(g.getFontMetrics().stringWidth(shown+"│")>660) { int low=0,high=shown.codePointCount(0,shown.length());while(low<high) { int middle=(low+high)/2;if(g.getFontMetrics().stringWidth("…"+shown.substring(shown.offsetByCodePoints(0,middle))+"│")>660) low=middle+1;else high=middle; }shown="…"+shown.substring(shown.offsetByCodePoints(0,low)); }
        text(shown + "│", 310, 361, 21, MINT);
        text("输入文字，Ctrl+V 粘贴，Ctrl+A 清空", 294, 416, 14, MUTED);
        text(promptValue.length()+" / "+promptLimit,680,416,13,MUTED);
        if(promptDefault!=null) smallButton(788,396,200,31,"恢复默认",()->promptValue=promptDefault);
        smallButton(292, 452, 332, 44, "确定  Enter", this::acceptPrompt);
        smallButton(650, 452, 338, 44, "取消  Esc", () -> promptAction = null);
    }
    private void acceptConfirmation() { var value=confirmation;confirmation=null;if(value!=null) value.action().run(); }
    private void cancelConfirmation() {
        review=confirmation.back();
        confirmation=null;
    }
    private void closeReview() {
        if(review!=null && review.equipmentSectionRuns()!=null && review.equipmentSectionRuns().backFromComparison()) return;
        if(review!=null && review.equipmentMap()!=null) review.equipmentMap().releaseRaster();
        if(review!=null && review.back()!=null) {
            review=review.back();
            return;
        }
        var progress=review==null?null:review.splitProgress();
        review=progress!=null && isCurrentSectionResult(progress.source())?progress.back():null;
    }
    private void releaseEquipmentMapRasters() {
        var current=review!=null?review:confirmation==null?null:confirmation.back();
        for(;current!=null;current=current.back()) {
            if(current.equipmentMap()!=null) current.equipmentMap().releaseRaster();
        }
    }
    private void acceptReview() {
        var value=review;
        if(value==null) return;
        closeReview();
        if(value.action()!=null) value.action().accept(value);
    }
    private void startEquipmentSection(EquipmentSectionPreviewHud preview) {
        var dialog=review;
        if(dialog==null || dialog.equipmentSection()!=preview) return;
        var returnTo=dialog.back();
        Runnable reopen=returnTo==null?null:()->review=returnTo;
        if(preview.start(app,reopen,equipmentSectionBackLabel(returnTo))) review=null;
    }
    private static String equipmentSectionBackLabel(Review destination) {
        if(destination==null) return "返回整圈对照";
        if(destination.equipmentMap()!=null) return "返回区间走线";
        return destination.equipmentInterruptionMap()==null?"返回计时门对照":"返回中止位置";
    }
    static String equipmentPracticeSummary(EquipmentSectionPracticeSession.Progress a,EquipmentSectionPracticeSession.Progress b) {
        return "同条件   A "+equipmentPracticeCount(a)+"   /   B "+equipmentPracticeCount(b);
    }
    private static String equipmentPracticeCount(EquipmentSectionPracticeSession.Progress progress) {
        if(progress==null) return "0 次有效";
        double median=progress.medianSeconds();
        return progress.completedCount()+" 次有效"+(Double.isFinite(median)?" · 近三次中位 "+PracticeSession.format(median):"");
    }
    private void reviewDialog() {
        var dialog=review;
        shade();buttons.clear();
        if(dialog.equipmentInterruptionMap()!=null) {
            dialog.equipmentInterruptionMap().render(this,g,this::closeReview,this::previewEquipmentInterruptionSubsection);
            return;
        }
        if(dialog.equipmentSection()!=null) {
            var preview=dialog.equipmentSection();
            String backLabel=equipmentSectionBackLabel(dialog.back())+"  Esc";
            preview.render(this,g,app,dialog.title(),backLabel,this::closeReview,()->startEquipmentSection(preview));
            return;
        }
        if(dialog.equipmentSectionRuns()!=null) {
            dialog.equipmentSectionRuns().render(this,dialog.actionLabel(),this::closeReview,
                    split->previewEquipmentSubsection(dialog,split),()->reviewEquipmentSectionInterruptions(dialog));
            return;
        }
        if(dialog.equipmentMap()!=null) {
            dialog.equipmentMap().render(this,g,this::closeReview);
            return;
        }
        if(dialog.retestMap()!=null) {
            dialog.retestMap().render(this,g,this::closeReview);
            return;
        }
        var progress=dialog.splitProgress();
        if(progress!=null && progress.comparing()) {
            progress.comparison().render(this,dialog.title(),this::closeReview,attempt->{
                if(app.startCircuitSplitFromProgress(progress.source(),attempt)) review=null;
            });
            return;
        }
        if(dialog.splitMap()!=null) {
            dialog.splitMap().render(this,g,dialog.title(),dialog.pages(),this::acceptReview,this::practiceSelectedCircuitSplit,
                    this::reviewSelectedSplitProgress);
            return;
        }
        if(dialog.practiceImport()!=null) {
            dialog.practiceImport().render(this,g,dialog.title(),dialog.pages(),()->review=null,dialog.actionLabel(),this::acceptReview);
            return;
        }
        boolean retestPreview=dialog.observationRetest()!=null && app.screen==FormulaGame.Screen.MAP_EDITOR;
        boolean canCompareRetestMap=dialog.retestComparison()!=null;
        var secondary=dialog.secondary();
        panel(280,195,720,retestPreview || canCompareRetestMap || secondary!=null?454:410,PANEL);text(dialog.title(),312,244,25,INK,true);
        int y=289;for(String line:dialog.pages().visible()) {text(line,312,y,17,MUTED);y+=32;}
        if(dialog.action()!=null || dialog.pages().pages()>1) {
            smallButton(312,455,140,32,"上一页",()->dialog.pages().move(-1));
            text((dialog.pages().page()+1)+" / "+dialog.pages().pages()+" · PgUp / PgDn",495,478,14,MUTED);
            smallButton(828,455,140,32,"下一页",()->dialog.pages().move(1));
        }
        if(progress!=null && progress.comparison()!=null) {
            smallButton(312,528,306,43,dialog.actionLabel(),this::acceptReview);
            smallButton(642,528,326,43,"对照另一发车设定",this::reviewAlternateSplitStarts);
        } else if(dialog.action()==null) smallButton(312,528,656,43,dialog.actionLabel(),this::acceptReview);
        else {
            if(dialog.back()!=null) smallButton(312,528,306,43,dialog.retestComparison()!=null?"返回复测详情":"返回详情",this::closeReview);
            else smallButton(312,528,306,43,"返回检查",()->review=null);
            smallButton(642,528,326,43,dialog.actionLabel(),this::acceptReview);
        }
        if(retestPreview) smallButton(312,586,656,43,"沿用区间与发车条件预览",()->{
            var context=dialog.observationRetest();
            if(circuitEditorHud.previewObservationRetest(app,context.value(),context.saved())) review=null;
        });
        if(canCompareRetestMap) smallButton(312,586,656,43,"对照计时区间地图  M",()->openRetestComparisonMap(dialog));
        if(secondary!=null) smallButton(312,586,656,43,secondary.label(),secondary.action());
    }
    private void confirmationDialog() {
        var dialog=confirmation;shade();buttons.clear();panel(280,224,720,360,PANEL);text(dialog.title(),312,274,27,INK,true);
        int y=315;for(String line:dialog.detail().split("\n")) y+=wrapped(shorten(line,60),312,y,650,17,MUTED,24)*24+8;
        if(dialog.useFocus()) text("方向键或 Tab 选择 · Enter 激活 · Esc 返回",312,480,15,MUTED);
        smallButton(312,516,306,43,dialog.back()==null?"返回检查":"返回对照",this::cancelConfirmation);
        smallButton(642,516,326,43,dialog.overwrite()?"覆盖文件":dialog.back()==null?"确认":"确认替换",this::acceptConfirmation);
    }

    public void chooseMapFile(boolean export) {
        var game=app.mini4wd();
        if(app.screen!=FormulaGame.Screen.MINI4WD || game==null) return;
        endMiniPointer();
        var origin=game.view();
        int slot=game.selected();
        var track=game.track();
        String name=game.activeName();
        var directory=app.store.directory().toAbsolutePath().toFile();
        String suggested="circuit-export-mini-"+name.replaceAll("[\\\\/:*?\"<>|]","_")+".json";
        SwingUtilities.invokeLater(() -> {
            try {
                JFileChooser chooser = new JFileChooser(directory);
                chooser.setDialogTitle(export ? "导出四驱车地图" : "导入四驱车地图");
                chooser.setFileFilter(new FileNameExtensionFilter("四驱车地图 (*.json)", "json"));
                if (export) chooser.setSelectedFile(new java.io.File(suggested));
                int result = export ? chooser.showSaveDialog(null) : chooser.showOpenDialog(null);
                if (result != JFileChooser.APPROVE_OPTION) return;
                java.nio.file.Path path = chooser.getSelectedFile().toPath();
                if (export && !path.toString().toLowerCase(Locale.ROOT).endsWith(".json")) path = path.resolveSibling(path.getFileName() + ".json");
                var destination=export?approveChooserExport(path):null;
                if(export && destination==null) return;
                java.nio.file.Path selected=path;
                app.enqueue(()->{
                    if(app.screen!=FormulaGame.Screen.MINI4WD || app.mini4wd()!=game || game.view()!=origin) return true;
                    if(game.selected()!=slot || game.track()!=track || !game.activeName().equals(name)) {
                        app.notifyUser("四驱车地图已变化，请再次选择文件。");
                    } else if(export) app.exportReviewedMiniMap(destination);
                    else game.importMap(selected);
                    return true;
                });
            } catch (IOException | RuntimeException e) { app.enqueue(() -> { app.notifyUser("文件路径或选择窗口无法打开。"); return Boolean.TRUE; }); }
        });
    }
    void chooseTelemetryExport() {
        if(app.telemetryComparison()==null) return;var lap=app.telemetryComparison().a();
        SwingUtilities.invokeLater(()->{
            try {
                var chooser=new JFileChooser(app.store.directory().toAbsolutePath().toFile());chooser.setDialogTitle("导出 A 圈操作数据");chooser.setFileFilter(new FileNameExtensionFilter("遥测 CSV (*.csv)","csv"));
                chooser.setSelectedFile(new java.io.File("telemetry-export-"+lap.conditions().trackId()+"-"+lap.id()+".csv"));
                if(chooser.showSaveDialog(null)!=JFileChooser.APPROVE_OPTION) return;
                var chosen=chooser.getSelectedFile().toPath();if(!chosen.toString().toLowerCase(Locale.ROOT).endsWith(".csv")) chosen=chosen.resolveSibling(chosen.getFileName()+".csv");
                var target=approveChooserExport(chosen);
                if(target==null) return;
                app.enqueue(()->{
                    app.exportReviewedTelemetry(lap,target);
                    return true;
                });
            } catch(IOException | RuntimeException error) { app.enqueue(()->{app.notifyUser("遥测导出路径或选择窗口无法打开。");return true;}); }
        });
    }

    private void mainMenu() {
        panel(24,24,430,742,new Color(12,28,40,246));
        scene.showcase(g,760,170,330);
        tag("FW / 01", 50, 58, MINT);
        text("FORMULA", 50, 129, 55, INK, true);
        text("WORKSHOP", 50, 184, 55, INK, true);
        text("方程式试车工坊", 54, 228, 26, MINT, true);
        text("2D 像素赛车 · 原创方程式 · 独立实验", 54, 266, 16, MUTED);
        button(52, 310, 360, 62, "开始游戏", "正常单机 / 赛事 / 挑战 / 联机", true, () -> navigate(app::openStartMenu));
        button(52, 386, 360, 62, "进入练习", "自由驾驶 / 专项课程 / 比赛回放", false, () -> navigate(() -> app.open(FormulaGame.Screen.COURSES)));
        button(52, 462, 360, 62, "车库与改装", "配置、涂装与同条件测试", false, () -> app.open(FormulaGame.Screen.GARAGE));
        button(52, 538, 360, 62, "实验车间与围场委托", "停车交接 / 轻载送件 / 杯架 / 独立记录", false, app::openWorkshop);
        button(52, 614, 360, 62, "四驱车小游戏", "组装赛车 / 制作轨道 / 试跑与竞速", false, () -> navigate(app::openMiniHome));
        smallButton(840,174,382,39,"驾驶履历与外观",app::openJournal);
        smallButton(840,121,382,39,"活动卡与自选组合",app::openActivities);
        smallButton(840,68,382,39,"创作任务与赛事包",app::openCreation);
        smallButton(840,478,382,39,"新手入门 · 四图驾驶指南",app::openDrivingGuide);
        smallButton(52, 698, 110, 42, "设置", () -> app.open(FormulaGame.Screen.SETTINGS));
        smallButton(176, 698, 110, 42, "操作帮助", () -> app.setHelpVisible(true));
        smallButton(300, 698, 112, 42, "退出", app::exitGame);
        panel(840, 548, 382, 174, new Color(10, 29, 39, 220));
        tag("READY TO DRIVE", 866, 581, MINT);
        text("FW-01  岚隼", 866, 619, 27, INK, true);
        text(app.circuit.name+"  " + Math.round(app.circuit.length) + " m", 866, 651, 16, MUTED);
        text(app.store.profile.raceLaps + " 圈短赛  ·  " + app.difficulty() + "难度", 866, 681, 16, INK);
        text("F6 重载 assets/pixel 中的 PNG 素材", 840, 754, 13, INK);
    }

    private void courses() {
        shade(); panel(38, 32, 1204, 732, PANEL);
        tag("DRIVER ACADEMY", 70, 74, MINT);
        text("练习中心", 70, 119, 34, INK, true);
        text("直接选择，随时重试。课程与正常比赛使用相同的基础驾驶模型。", 72, 153, 16, MUTED);
        smallButton(1060, 68, 146, 42, "返回主菜单", app::mainMenu);
        completionSaveButton(770,68,270,42);
        button(70, 180, 545, 62, "赛道自由练习", "完整环线、个人圈速、无强制倒计时", true, () -> navigate(() -> app.prepareMode(FormulaGame.Mode.FREE_CIRCUIT, null)));
        button(637, 180, 570, 62, "训练场自由驾驶", "直线、圆环、蛇形区与停车框", false, () -> navigate(() -> app.prepareMode(FormulaGame.Mode.FREE_PAD, null)));
        button(70,254,1137,58,"计时挑战","三段计时 / 个人最佳与好友幽灵 / 连续跑圈",false,() -> navigate(() -> app.prepareMode(FormulaGame.Mode.TIME_TRIAL,null)));
        var lessons = PracticeSession.Lesson.values();
        int pages=(lessons.length+7)/8;lessonPage=Math.min(lessonPage,pages-1);
        for (int i = 0; i < 8 && lessonPage*8+i<lessons.length; i++) {
            var lesson = lessons[lessonPage*8+i];
            int x = 70 + i % 2 * 567, y = 332 + i / 2 * 88;
            panel(x, y, 545, 79, new Color(24, 49, 60));
            tag(lesson.id, x + 18, y + 24, MINT);
            text(lesson.title, x + 95, y + 28, 19, INK, true);
            text(shorten(lesson.description, 34), x + 18, y + 59, 13, MUTED);
            smallButton(x + 435, y + 15, 92, 34, "开始 →", () -> navigate(() -> app.prepareMode(FormulaGame.Mode.LESSON, lesson)));
        }
        smallButton(70,697,150,36,"驾驶分析",app::openTelemetry);
        smallButton(232,697,170,36,"A／B 调校试车",app::openTuningLab);
        smallButton(414,697,178,36,"赛道分段攻关",app::openExperience);
        smallButton(604,697,146,36,"比赛回放",app::openReplays);
        smallButton(762,697,162,36,"地图编辑器",app::openCircuitEditor);
        text(app.drivingHint("R 固定起点重试   ·   H 操作帮助   ·   课程成绩与整圈纪录分别保存"),72,752,13,MUTED);
        if(pages>1) {
            smallButton(950,697,80,36,"上一页",()->lessonPage=Math.max(0,lessonPage-1));
            centered((lessonPage+1)+" / "+pages,1073,722,16,MUTED);
            smallButton(1120,697,80,36,"下一页",()->lessonPage=Math.min(pages-1,lessonPage+1));
        }
    }

    private void tracks() {
        shade();panel(38,32,1204,732,PANEL);tag("CIRCUIT SELECT",70,74,MINT);
        text("选择赛道",70,120,34,INK,true);
        text("用于比赛、赛道练习、课程和计时；联机地图在房间的地图与赛事规则中选择。",72,158,16,MUTED);
        smallButton(1060,68,146,42,"返回",app::backFromTracks);
        smallButton(840,68,198,42,"制作地图／管理草稿",app::openCircuitEditor);
        var entries=app.availableTracks();int pages=(entries.size()+1)/2;trackPage=Math.min(trackPage,Math.max(0,pages-1));
        for(int i=0;i<2 && trackPage*2+i<entries.size();i++) {
            var entry=entries.get(trackPage*2+i);var track=entry.layout();int x=70+i*567,y=192;
            panel(x,y,545,460,new Color(24,49,60));
            text(track.name,x+22,y+40,26,INK,true);
            var saved=app.circuits.find(entry.id()).orElse(null);
            tag(entry.id().equals(app.circuit.id)?"当前赛道":saved==null?"原创赛道":"自制赛道",x+22,y+68,MINT);
            if(saved!=null) smallButton(x+392,y+48,130,28,"地图卡",()->app.openCircuitCard(entry.id()));
            panel(x+22,y+89,501,219,new Color(12,31,41));
            var map=TrackMapView.draw(g,track,new Rectangle(x+22,y+89,501,219),20,MUTED,5);
            var start=track.sample(0).position();g.setColor(MINT);g.fillOval(Math.round(map.x(start.x))-4,Math.round(map.y(start.z))-4,8,8);
            text(Math.round(track.length)+" m · 宽 "+Math.round(track.width)+" m · 三段计时",x+22,y+340,16,MINT);
            if(saved==null) wrapped(entry.description(),x+22,y+370,495,16,MUTED,25);
            else {
                var card=saved.document().metadata();var author=card.author().isBlank()?"未署名":card.author();
                text("作者 "+(author.length()>20?author.substring(0,19)+"…":author)+" · 难度 "+card.difficulty().title+"（作者标注）",x+22,y+365,14,MUTED);
                var description=entry.description();text(description.length()>28?description.substring(0,27)+"…":description,x+22,y+390,15,MUTED);
            }
            smallButton(x+22,y+407,501,37,entry.id().equals(app.circuit.id)?"使用当前赛道":"选择这条赛道",()->app.selectCircuit(entry.id(),track));
        }
        text(app.drivingHint("每张赛道分别保存纪录与幽灵；R 重试、暂停和车库配置继续可用。"),72,718,15,MUTED);
        if(pages>1) { smallButton(950,697,80,36,"上一页",()->trackPage=Math.max(0,trackPage-1));centered((trackPage+1)+" / "+pages,1073,722,16,MUTED);smallButton(1120,697,80,36,"下一页",()->trackPage=Math.min(pages-1,trackPage+1)); }
    }
    private void challenges() {
        shade();panel(38,32,1204,732,PANEL);tag("SHORT RACE CHALLENGES",70,74,MINT);
        text("短局挑战",70,120,34,INK,true);
        text("在相同的车辆物理下练习争位；每项都要求有效路线与干净驾驶。",72,158,16,MUTED);
        smallButton(1060,68,146,42,"返回模式选择",()->navigate(app::openStartMenu));
        completionSaveButton(770,68,270,42);
        smallButton(70,174,520,36,"本地挑战册 · 原厂目标与奖牌",app::openBook);
        var kinds=RaceChallenge.Kind.values();
        for(int i=0;i<kinds.length;i++) {
            var kind=kinds[i];int x=70+i*384,y=239;
            panel(x,y,360,357,new Color(24,49,60));tag(kind.id+" / "+kind.laps+" 圈",x+20,y+35,MINT);
            text(kind.title,x+20,y+80,25,INK,true);wrapped(kind.description,x+20,y+124,315,17,MUTED,28);
            text(app.drivingHint("不接触 · 不越界 · R 整次重试"),x+20,y+238,14,ORANGE);
            text("最佳 "+PracticeSession.format(app.bestTime("challenge:"+kind.id)),x+20,y+208,16,MINT);
            smallButton(x+20,y+282,320,48,"选择地图并开始",()->navigate(()->app.prepareChallenge(kind)));
        }
        text("成绩按挑战、赛道几何和车辆配置保存；正常比赛与自由练习仍可直接进入。",72,706,15,MUTED);
    }

    private void garage() {
        panel(22,22,494,756,new Color(12,28,40,246));
        scene.showcase(g,740,150,340);
        ProfileSaveHud.controls(this,app.store.saveFailure());
        tag("GARAGE / FW-01", 44, 52, MINT);
        if(!app.online()) smallButton(782,430,444,39,"驾驶履历、车号与纪念图案",app::openJournal);
        if(!app.online()) smallButton(782,482,444,39,"像素涂装创作 · 图层与作品库",app::openLiveryEditor);
        if(!app.online()) smallButton(782,378,444,39,"A／B 加速、制动与连续弯试车",app::openTuningLab);
        if(!app.online()) smallButton(782,326,444,39,"调校向导 · 每次只改一项",app::openTuningGuide);
        if(!app.online()) smallButton(782,274,444,39,"命名配置库 · 按赛道保存与比较",app::openSetupLibrary);
        if(!app.online()) smallButton(782,222,444,39,"部件方案与试装 · 玩家部件包",app::openPartsWorkbench);
        if(!app.online()) {
            boolean checkpoint=app.fittedParts.state().installed()!=null && !app.fittedParts.pending() && !app.fittedParts.blocked();
            smallButton(782,169,checkpoint?218:300,39,app.fittedParts.pending()?"装备待完成 · 查看并重试":app.fittedParts.blocked()?"装备读取暂停 · 查看恢复":app.fittedParts.state().installed()==null?"装备记录 · 当前自由调校":"已装装备 · 查看范围",this::fittingDetails);
            if(checkpoint) smallButton(1012,169,70,39,"存基准",this::saveFittingBaseline);
            smallButton(1096,169,130,39,app.fittedParts.blocked()?"重新读取":app.fittedParts.pending()?"取消待装":app.fittedParts.state().installed()==null?"重新读取":"解除范围",this::fittingSecondary);
        }
        text("车库与改装", 44, 99, 33, INK, true);
        smallButton(272,77,216,31,garageSuspensionPage?"← 基础调校":"前后悬挂分项 →",()->garageSuspensionPage=!garageSuspensionPage);
        text(garageSuspensionPage?"轴倍率 80%–120%，叠加总配套；先做单项对照。":"停车后应用配置；每项变化都有实际驾驶效果。", 45, 132, 15, MUTED);
        Tuning t = app.store.profile.tuning;
        if(garageSuspensionPage) {
            tuningRow(170,"悬挂总配套","suspension",t.suspension(),"同时改变两轴刚度和阻尼，保留轴倍率");
            tuningRow(246,"前轴弹簧倍率","frontSpring",t.frontSpring(),"只改变前轮刚度，阻尼系数保持原值");
            tuningRow(322,"后轴弹簧倍率","rearSpring",t.rearSpring(),"只改变后轮刚度，阻尼系数保持原值");
            tuningRow(398,"前轴阻尼倍率","frontDamping",t.frontDamping(),"前轴压缩与回弹一起调整，弹簧保持原值");
            tuningRow(474,"后轴阻尼倍率","rearDamping",t.rearDamping(),"后轴压缩与回弹一起调整，弹簧保持原值");
        } else {
        tuningRow(170, "动力输出", "power", t.power(), "驱动力与前进速度上限一起调整");
        tuningRow(246, "制动力度", "brakes", t.brakes(), "总制动输入，与前后比例分别调整");
        tuningRow(322, "前轴制动比例", "brakeBias", t.brakeBias(), "后轴 "+Math.round((1-t.brakeBias())*100)+"% · 总输入不变，建议先做对照试车");
        tuningRow(398, "悬挂配套", "suspension", t.suspension(), "刚度与阻尼一起调整");
        tuningRow(474, "尾翼方案", "wing", t.wing(), "简化下压力与直线阻力的取舍");
        }
        text("车队涂装", 45, 560, 18, INK, true);
        for (int i = 0; i < 5; i++) {
            final int livery = i;
            int x = 172 + i * 53;
            g.setColor(new Color(FormulaCar.LIVERIES[i])); g.fillRoundRect(x, 538, 39, 31, 8, 8);
            if (t.livery() == i) { g.setColor(INK); g.setStroke(new BasicStroke(2)); g.drawRoundRect(x - 3, 535, 45, 37, 10, 10); }
            buttons.add(new Button(new Rectangle(x - 3, 535, 45, 37),"车队涂装 "+(i+1),() -> {
                for(int attempts=0;attempts<5 && app.store.profile.tuning.livery()!=livery;attempts++) {var before=app.store.profile.tuning;app.changeTuning("livery",1);if(before.equals(app.store.profile.tuning)) break;}
            }));
        }
        smallButton(44, 601, 102, 39, "存为 A", () -> app.savePreset("A"));
        smallButton(158, 601, 102, 39, "载入 A", () -> app.loadPreset("A"));
        smallButton(272, 601, 102, 39, "存为 B", () -> app.savePreset("B"));
        smallButton(386, 601, 102, 39, "载入 B", () -> app.loadPreset("B"));
        button(44, 657, 444, 53, "开始同条件制动测试", "L14 · 相同测速范围，记录距离与入口速度", true, () -> app.start(FormulaGame.Mode.LESSON, PracticeSession.Lesson.COMPARISON));
        smallButton(44, 730, 212, 38, "恢复原厂", app::stock);
        smallButton(272, 730, 216, 38, app.garageFromRaceRoom() ? "返回联机房间" : "返回主菜单", app::backFromGarage);
        panel(758, 539, 468, 207, new Color(10, 29, 39, 229));
        tag("TEST NOTES", 782, 574, MINT);
        smallButton(1082,552,120,31,"切换项目",()->reportTab=(reportTab+1)%3);
        text(new String[]{"制动测试记录","连续弯试车记录","稳定圈速记录"}[reportTab], 782, 610, 22, INK, true);
        if(reportTab==0) { comparisonRow(646, "原厂", Tuning.STOCK);comparisonRow(678, "当前", t); }
        else {
            var lesson=reportTab==1?PracticeSession.Lesson.FLOW:PracticeSession.Lesson.CONSISTENCY;
            courseComparisonRow(646,"原厂",Tuning.STOCK,lesson);courseComparisonRow(678,"当前",t,lesson);
            smallButton(782,485,444,39,reportTab==1?"开始连续弯试车":"开始稳定圈速试车",()->app.start(FormulaGame.Mode.LESSON,lesson));
        }
        text("单次结果也受驾驶影响，请比较多次尝试。", 782, 718, 13, MUTED);
    }
    private void tuningRow(int y, String label, String field, float value, String description) {
        text(label, 46, y + 5, 20, INK, true);
        text(description, 46, y + 37, 13, MUTED);
        var parameter=PartsCatalog.Field.tuningKey(field);
        smallButton(300, y - 22, 43, 40, "−", () -> changeGarageNumber(parameter,-1));
        smallButton(350,y-22,92,40,Math.round(value*100)+"%",()->{var expected=app.reviewGarageNumber(parameter);if(expected!=null) editNumber(label,parameter.percent(expected.tuning()),expected.range().min(),expected.range().max(),"%",next->app.setGarageNumber(expected,next));});
        smallButton(450, y - 22, 43, 40, "+", () -> changeGarageNumber(parameter,1));
        g.setColor(new Color(55, 83, 93)); g.fillRoundRect(46, y + 51, 444, 3, 3, 3);
        boolean axis=field.equals("frontSpring") || field.equals("rearSpring") || field.equals("frontDamping") || field.equals("rearDamping");
        g.setColor(MINT); g.fillRoundRect(46, y + 51, Math.round(444 * Math.max(0,Math.min(1,field.equals("brakeBias")?(value-.35f)/.30f:axis?(value-.8f)/.4f:(value-.6f)/.85f))), 3, 3, 3);
    }
    private void changeGarageNumber(PartsCatalog.Field field,int amount) {
        var review=app.reviewGarageNumber(field);if(review!=null) app.setGarageNumber(review,review.range().clamp((long)field.percent(review.tuning())+amount));
    }
    private void fittingDetails() {
        var store=app.fittedParts;var expected=store.state();var garage=app.store.profile.tuning;
        if(store.blocked()) {
            try {var disk=store.inspectDisk();review("装备记录恢复",List.of(store.warning(),"可先重新读取修复后的文件；也可保留当前磁盘副本，再保存本机保有的装备记录。","恢复记录不改车库参数；之后可单独重新应用装备参数。"),"查看恢复选择",()->confirm("保留磁盘副本并恢复装备记录？","原文件另存恢复副本；当前车库参数保留。\n文件或本机装备记录变化后需重新查看。",()->{if(!store.backupAndSave(disk,expected)) app.notifyUser(store.warning());}));
            }catch(java.io.IOException error) {app.notifyUser("装备文件暂时无法检查，原文件保留；可修复目录后重新读取。");}
            return;
        }
        if(store.pending()) {
            review("装备安装未完成",List.of("待安装："+expected.pending().definitions().values().stream().map(PartsCatalog.Part::name).collect(java.util.stream.Collectors.joining("、")),"车库参数与装备记录分阶段保存；需要明确重试后才算安装完成。","取消待安装只清除请求，当前车库参数保留。"),"重试完成安装",()->{if(!store.state().equals(expected)) {app.notifyUser("装备记录已变化，请重新查看。");return;}app.resumeFitting();});
            return;
        }
        var installed=expected.installed();var lines=new ArrayList<String>();
        if(installed==null) lines.add("当前使用原有自由调校范围。在工作台选择部件后，可明确安装整套；只采用参数不会安装装备。");
        else {
            lines.add(installed.compatible(app.vehicleSpec.id,app.vehicleSpec.physicsVersion) && installed.accepts(app.store.profile.tuning)?"已装装备与当前车库参数相符。":"当前参数或车型与已装装备不符，需重新应用或解除装备范围。");
            for(var slot:PartsCatalog.Slot.values()) {var part=installed.definitions().get(slot);lines.add(slot.title+"："+part.name()+" · "+part.id());for(var field:slot.fields()) {var range=part.settings().get(field);lines.add(field.title+" "+range.min()+"–"+range.max()+"% · 当前 "+field.percent(app.store.profile.tuning)+"%");}}
            lines.add("目录修订不会自动改变已装定义；重新安装时才采用新定义。装备沿用既有参数效果，点数为工作台试验上限。");
        }
        review("已装装备与微调范围",lines,installed==null?"关闭":"重新应用装备基准",()->{if(installed!=null) confirm("重新应用装备基准？","本次微调将替换为已存装备基准，保留当前涂装。\n可先用“存基准”保存当前微调。\n命名方案、试车报告与成绩保持。",()->{if(!app.reapplyFitting(expected,garage)) app.notifyUser("未完成基准应用，请检查车库、装备记录或待安装请求后重试。");});});
    }
    private void saveFittingBaseline() {
        var expected=app.reviewFittedSetup();if(expected==null) return;var lines=new ArrayList<String>();
        lines.add("把当前微调保存为这套装备的基准；以后重新应用装备基准时采用这些值。车库、草稿、命名方案与成绩保持。");
        var previous=expected.before().installed().initialTuning(expected.garage().livery());
        for(var field:PartsCatalog.Field.values()) lines.add(field.title+"：已存 "+field.percent(previous)+"% → 当前 "+field.percent(expected.garage())+"%");
        review("保存当前装备基准",lines,"保存为装备基准",()->app.saveFittingBaseline(expected));
    }
    private void fittingSecondary() {
        var store=app.fittedParts;var expected=store.state();
        if(store.blocked() || expected.installed()==null && !store.pending()) {store.reload();app.notifyUser(store.blocked()?store.warning():"装备记录已重新读取；车库参数保留。");return;}
        boolean pending=store.pending();confirm(pending?"取消未完成的安装？":"解除已装装备的调校范围？",pending?"待安装请求将清除，原装备记录保留。\n当前车库参数保留，可能需要重新应用原装备。":"清除本机装备选择，恢复原有自由调校范围。\n当前参数、草稿、命名方案和成绩保留。",()->app.detachFitting(expected,pending));
    }
    private void comparisonRow(int y, String name, Tuning tuning) {
        String key = app.comparisonKey(tuning);
        Double distance = app.store.profile.brakingDistances.get(key), speed = app.store.profile.brakingEntrySpeeds.get(key);
        text(name, 782, y, 16, MUTED);
        text(distance != null && speed != null ? String.format(Locale.ROOT, "%.1f m  /  入口 %.0f km/h", distance, speed) : "尚未完成测试", 856, y, 16, INK);
    }
    private void courseComparisonRow(int y,String name,Tuning tuning,PracticeSession.Lesson lesson) {
        var report=app.store.profile.courseReports.get(app.courseReportKey(lesson,tuning));
        text(name,782,y,16,MUTED);
        String value="当前赛道尚无试车记录";
        if(report!=null) value=lesson==PracticeSession.Lesson.FLOW?PracticeSession.format(report.elapsed)+" / 出口 "+Math.round(report.exitSpeed)+" km/h"
            :String.format(Locale.ROOT,"均值 %.3f s / 极差 %.3f s",report.lapTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0),report.spread);
        text(value,856,y,14,INK);
    }

    private void settings() {
        shade(); panel(215, 40, 850, 720, PANEL);
        tag("PREFERENCES", 250, 79, MINT);
        text("驾驶与声音设置", 250, 122, 30, INK, true);
        smallButton(650,83,342,38,"画面："+app.graphicsQuality().title+" · "+app.graphicsQuality().frameRate+" 帧",app::cycleGraphicsQuality);
        settingRow(166, "幽默台词", "关闭后保留课程、规则与比赛关键信息", app.store.profile.humor ? "开启" : "关闭", app::setHumor);
        settingRow(243, "背景音乐", "原创像素电子 · 场景自动切换 · 后台静音", app.store.profile.music ? "开启" : "关闭", app::setMusic);
        text("音乐音量",252,324,19,INK,true);
        smallButton(734,298,56,36,"−",()->app.changeMusicVolume(-.1f));
        centered(Math.round(app.store.profile.musicVolume*100)+"%",862,324,19,MINT);
        smallButton(936,298,56,36,"+",()->app.changeMusicVolume(.1f));
        smallButton(250,350,470,36,(app.musicPreviewing()?"试听中：":"当前曲目：")+app.musicTrack().title+" · 点击换曲试听",app::previewMusic);
        smallButton(736,350,256,36,"结束试听",app::stopMusicPreview);
        settingRow(430, "驾驶音效", "发动机、倒计时、发车与完成提示", app.store.profile.sound ? "开启" : "关闭", app::setSound);
        text("音效音量",252,510,19,INK,true);
        smallButton(734,484,56,36,"−",()->app.changeVolume(-.1f));
        centered(Math.round(app.store.profile.volume*100)+"%",862,510,19,MINT);
        smallButton(936,484,56,36,"+",()->app.changeVolume(.1f));
        settingRow(558, "AI 难度", "影响对手的目标节奏", app.difficulty(), app::cycleDifficulty);
        settingRow(629, "短赛圈数", "新比赛开始后生效", app.store.profile.raceLaps+" 圈",app::cycleLaps);
        smallButton(250,682,360,40,"手柄与操作设置",app::openControls);
        smallButton(634,682,360,40,"返回",app::backFromSettings);
        if(!app.audioAvailable()) text("音频设备暂不可用；声音偏好仍会保存。",252,747,13,ORANGE);
    }
    private void settingRow(int y, String title, String description, String value, Runnable action) {
        text(title, 252, y, 20, INK, true); text(description, 252, y + 29, 14, MUTED);
        smallButton(800, y - 25, 192, 42, value + "  切换", action);
    }

    private void driving() {
        if (app.player == null) return;
        var section=app.circuitSectionPractice();
        var sectionGuidance=section==null?null:section.guidance();
        panel(24, 22, 1232, 61, new Color(9, 28, 39, 231));
        tag(app.eventDriving()?"RACE EVENT":app.mode==FormulaGame.Mode.CHALLENGE?"CHALLENGE":app.mode == FormulaGame.Mode.TIME_TRIAL ? "TIME ATTACK" : app.mode == FormulaGame.Mode.CUP ? "EXPERIMENT" : app.mode == FormulaGame.Mode.RACE ? "RACE" : "PRACTICE", 45, 49, MINT);
        text(app.modeName(), 45, 71, 16, INK, true);
        centered(PracticeSession.format(section!=null?section.elapsed():app.experience()!=null?app.experience().elapsed():app.timeTrial!=null ? app.timeTrial.elapsed() : app.practice!=null ? app.practice.elapsed : app.sessionTime), 640, 62, 28, INK);
        eventHud.raceClock(this,app.eventRaceClock());
        var delay=app.raceRoom()==null ? RaceRoom.FrameDelay.NONE : app.raceRoom().frameDelay();
        String message=switch(delay) {
            case NONE -> null;
            case WAITING -> "房主画面更新延迟";
            case BRAKE_ONLY -> "等待房主更新 · 驾驶输入限为制动";
            case WATCHING -> "等待房主更新比赛进度";
        };
        text(app.keyLabel(Command.CAMERA)+" 视角  "+app.keyLabel(Command.RESET)+(section==null?" 复位":" 重跑")+"  ESC 暂停",900,message==null?60:47,12,MUTED);
        if(message!=null) text(message,900,71,15,ORANGE);
        if(section!=null) {
            var reference=section.referenceTimes();
            var comparison=section.lastGateComparison();
            int panelHeight=comparison!=null?239:reference!=null?185:133;
            panel(25,100,315,panelHeight,PANEL);
            text(CircuitSectionHud.practiceLabel(section),45,139,22,INK,true);
            String instruction="正在计时 · 按线路驶向终点";
            if(section.state()==CircuitSectionPractice.State.APPROACH)
                instruction=section.focusedSplit()==0?"驶过区间起点开始计时":"驶过分段起点开始计时";
            else if(section.hasInteriorStartLine()) instruction=section.passedStartLine()
                    ?"已过赛道起终线 · 计时继续":"正在计时 · 沿赛道驶过起终线";
            text(instruction,45,176,15,MINT);
            text(CircuitSectionHud.startLabel(section.start()),45,206,15,MUTED);
            if(reference!=null) {
                text("上次有效  "+PracticeSession.format(reference.last()),45,239,15,MUTED);
                text("此前最快  "+PracticeSession.format(reference.best()),45,263,15,MUTED);
            }
            circuitSectionPaceHud.draw(this,comparison);
        } else if(app.activityTrial()!=null) {
            activityHud.driving(this,app);
        } else if (app.mode == FormulaGame.Mode.RACE || app.mode==FormulaGame.Mode.CHALLENGE || app.mode==FormulaGame.Mode.EVENT_RACE || app.mode==FormulaGame.Mode.MAP_TRIAL || app.mode==FormulaGame.Mode.MAP_SOLO) {
            if(app.mode==FormulaGame.Mode.EVENT_RACE) eventHud.raceTiming(this,app);
            else {
                panel(25, 100, 253, 213, PANEL);
                var standings = app.standings();
                var driver = app.racers.get(0);
                text("P" + (standings.indexOf(driver)+1) + " / " + app.racers.size(), 44, 138, 29, MINT, true);
                text("第 " + Math.min(app.sessionLaps, driver.progress.completedLaps() + 1) + " / " + app.sessionLaps + " 圈", 172, 135, 16, INK);
                for (int i = 0; i < standings.size(); i++) {
                    var r = standings.get(i);
                    text((i + 1) + "   " + r.car.name + (r.retired ? "  退赛" : ""), 45, 179 + i * 33, 16, r.car == app.player ? MINT : INK);
                }
            }
            if (app.screen == FormulaGame.Screen.DRIVING && app.racers.get(0).progress.finished()) banner("你已完赛，等待其他车辆", app.online() ? "成绩已由房主记录" : "车辆进入自动冷却圈");
            if(app.challenge!=null) { panel(25,327,340,116,PANEL);wrapped(app.challenge.kind().description,45,357,300,15,MUTED,24);text(app.drivingHint("R 重开挑战 · 保持干净驾驶"),45,422,14,ORANGE); }
            if(app.circuitTrial()!=null) { panel(25,327,440,116,PANEL);wrapped(app.circuitTrial().feedback(),45,357,395,15,MUTED,24);text(app.circuitTrial().kind()==CircuitTrial.Kind.FOUR_CARS?app.drivingHint("300 秒内四车有效完赛 · R 整次重跑"):app.drivingHint("单车试驾 · R 重开 · 不新增四车验证资格"),45,422,14,ORANGE); }
        } else if(app.online() && app.mode==FormulaGame.Mode.QUALIFYING) {
            roomSettingsHud.qualifying(this,app);
        } else if(app.qualifying()!=null) {
            eventHud.qualifying(this,app);
        } else if (app.experience()!=null) {
            experienceHud.driving(this,app);
        } else if (app.timeTrial!=null) {
            timeTrialHud.render(this,app);
        } else if (app.workshop()!=null) {
            workshopHud.driving(this,app);
        } else if (app.tuningTrial()!=null) {
            tuningLabHud.driving(this,app);
        } else if (app.practice != null) {
            panel(25, 101, 465, 177, PANEL);
            tag(app.practice.lesson.id + " / 专项训练", 46, 134, MINT);
            wrapped(app.practice.lesson.description, 46, 166, 420, 16, INK, 26);
            wrapped(app.drivingHint(app.practice.feedback), 46, 226, 420, 14, MUTED, 22);
        } else if (app.mode == FormulaGame.Mode.CUP) {
            panel(25, 101, 381, 187, PANEL);
            tag("X05 / 杯架稳定组件", 46, 134, MINT);
            text("110 秒内合法完成一圈", 46, 169, 22, INK, true);
            text("平顺制动、转向；避免突然加减速。", 46, 200, 15, MUTED);
            if (app.cup != null) {
                text("洒水 " + Math.round(app.cup.spill) + "%", 46, 238, 23, ORANGE, true);
                text("当前得分 " + app.cup.score(), 225, 238, 17, INK);
                meter(46, 256, 330, 6, 1 - app.cup.spill / 100, MINT);
            }
        } else {
            panel(25, 101, 325, 116, PANEL);
            tag("YOUR OWN PACE", 46, 135, MINT);
            text("无倒计时 · 随时停车与重试", 46, 170, 17, INK);
            text(app.mode == FormulaGame.Mode.FREE_PAD ? "左：制动区  中：蛇形区  右：定圆" : "完整通过检查点后记录有效圈速", 46, 198, 13, MUTED);
        }
        if(app.mode==FormulaGame.Mode.EVENT_PRACTICE) smallButton(368,710,542,40,"完成熟悉路线，返回赛事中心参加排位",app::openEvents);
        if(app.bookAttempt()!=null) bookHud.driving(this,app);
        boolean showMap=app.mode!=FormulaGame.Mode.FREE_PAD && (app.practice==null || app.practice.lesson.onCircuit());
        boolean showRoadWarning=app.mode!=FormulaGame.Mode.FREE_PAD && app.mode!=FormulaGame.Mode.LESSON
                && app.mode!=FormulaGame.Mode.TUNING_TRIAL && app.mode!=FormulaGame.Mode.WORKSHOP;
        var guide=app.trackGuide;
        boolean showGuide=section==null && !app.isTraining() && guide!=null && !guide.sections().isEmpty();
        var playerPosition=showGuide || showMap || showRoadWarning?app.player.position(drivingPosition):null;
        TrackLayout.Projection projection=null;
        if(sectionGuidance!=null) CircuitSectionGuideHud.draw(this,g,app.circuit,sectionGuidance);
        else if(showGuide) {
            projection=projectPlayerPosition(playerPosition);
            guide.upcomingAt(projection).ifPresent(approach->{
                panel(500,100,500,58,PANEL);centered(approach.name()+"  ·  "+Math.round(approach.remaining())+" m",750,125,18,MINT);
                centered("距离牌为路线参照；制动点随车速与配置变化",750,147,12,MUTED);
            });
        }
        if(showMap) miniMap(sectionGuidance,playerPosition);
        if (!app.racers.isEmpty() && (app.mode == FormulaGame.Mode.RACE || app.mode == FormulaGame.Mode.FREE_CIRCUIT || app.eventDriving())) {
            var progress = app.racers.get(0).progress;
            panel(26, 600, 290, 110, PANEL);
            text("本圈   " + PracticeSession.format(progress.currentLap(app.sessionTime)), 45, 635, 18, INK);
            text("最佳   " + PracticeSession.format(app.bestTime("lap")), 45, 669, 17, MINT);
            text(progress.validLap() ? "当前圈有效" : "当前圈不计个人纪录", 45, 696, 12, progress.validLap() ? MUTED : ORANGE);
        }
        speedometer();
        if (app.radioLeft > 0 && !app.radio.isEmpty()) {
            panel(358, 649, 570, 58, new Color(12, 36, 43, 235));
            text(app.radio, 376, 684, 15, INK);
        }
        if (app.countdown > 0 && (app.mode == FormulaGame.Mode.RACE || app.mode==FormulaGame.Mode.CHALLENGE || app.mode==FormulaGame.Mode.QUALIFYING || app.mode==FormulaGame.Mode.EVENT_RACE || app.mode==FormulaGame.Mode.MAP_TRIAL || app.practice!=null && app.practice.lesson.traffic())) {
            panel(420, 292, 440, 170, PANEL);
            centered("准备起跑", 640, 335, 22, INK);
            int lit = 5 - (int) Math.ceil(app.countdown);
            for (int i = 0; i < 5; i++) { g.setColor(i <= lit ? new Color(245, 93, 79) : new Color(60, 56, 58)); g.fillOval(487 + i * 63, 366, 43, 43); }
            centered("灯灭后开始", 640, 440, 15, MUTED);
        }
        if (delay == RaceRoom.FrameDelay.NONE && app.countdown <= 0 && app.sessionTime < .8 && (app.mode == FormulaGame.Mode.RACE || app.mode==FormulaGame.Mode.CHALLENGE || app.mode==FormulaGame.Mode.QUALIFYING || app.mode==FormulaGame.Mode.EVENT_RACE || app.mode==FormulaGame.Mode.MAP_TRIAL)) centered("GO", 640, 385, 80, MINT);
        if(app.practice!=null && app.practice.startPractice()!=null && !app.practice.complete()) {
            var start=app.practice.startPractice();
            if(!start.green()) {
                panel(420,292,440,170,PANEL);centered("等待红灯熄灭",640,335,22,INK);
                for(int i=0;i<5;i++) { g.setColor(i<start.lights()?new Color(245,93,79):new Color(60,56,58));g.fillOval(487+i*63,366,43,43); }
                centered("灯灭后再按油门",640,440,15,MUTED);
            } else if(start.state()==StartPractice.State.GO) centered("GO",640,385,80,MINT);
        }
        if (app.screen == FormulaGame.Screen.DRIVING && !app.helpVisible) {
            String controls=app.driveControls().enabled()?"手柄驾驶 · 油门／制动／转向使用已校准的轴 · 暂停与重试按钮见操作设置":app.keyLabel(Command.THROTTLE)+" 油门   "+app.keyLabel(Command.BRAKE)+" 制动与倒车   "+app.keyLabel(Command.LEFT)+" / "+app.keyLabel(Command.RIGHT)+" 转向   "+app.keyLabel(Command.HANDBRAKE)+" 全制动";
            text(controls,35,772,13,new Color(232,244,237));
            if(app.driveControls().enabled() && !app.controllerInput.armed()) banner("松开摇杆、扳机和按钮", "等待控制回到静止状态；随后可继续驾驶。");
        }
        if(showRoadWarning) {
            float limit=app.circuit.width*.5f+TrackGuide.ROAD_MARGIN_METERS;
            boolean onRoad=projection==null?app.circuit.withinDistance(playerPosition,limit):projection.separation()<=limit;
            if(!onRoad) banner("驶出赛道",app.drivingHint(app.mode==FormulaGame.Mode.TIME_TRIAL || app.mode==FormulaGame.Mode.CHALLENGE
                    ? "本次不计最佳；R 可重开本次挑战" : "返回道路；R 可回到上一合法区段"));
        }
    }

    private TrackLayout.Projection projectPlayerPosition(Vector3f position) {
        if(!Vector3f.isValidVector(position)) return null;
        if(!app.racers.isEmpty() && app.racers.get(0).car==app.player) {
            var projection=app.racers.get(0).progress.trackProjection(app.circuit,position);
            if(projection!=null) return projection;
        }
        return app.circuit.project(position);
    }
    private void speedometer() {
        panel(1004, 605, 250, 139, PANEL);
        float speed = app.player.speed() * 3.6f;
        text(String.format(Locale.ROOT, "%03d", Math.round(speed)), 1026, 679, 58, INK, true);
        text("km/h", 1161, 678, 17, MUTED);
        text("AUTO " + (app.player.throttle < 0 ? "R" : Math.max(1, Math.min(6, 1 + (int) speed / 44))), 1029, 710, 16, MINT);
        meter(1123, 700, 106, 7, Math.max(0, app.player.throttle), MINT);
        meter(1123, 718, 106, 7, app.player.braking, ORANGE);
        text("制动 前 "+Math.round(app.player.tuning.brakeBias()*100)+"% / 后 "+Math.round((1-app.player.tuning.brakeBias())*100)+"%",1026,737,12,MUTED);
    }
    TrackMapView.CachedOutline drivingOutline(TrackLayout track) {
        if (minimapTrack != track) {
            if (minimapOutline != null) minimapOutline.releaseRaster();
            minimapTrack = track;
            minimapOutline = track == null ? null : new TrackMapView.CachedOutline(track, MINIMAP, 15, MINIMAP_ROUTE, 5);
        }
        return minimapOutline;
    }

    private void miniMap(CircuitSectionPractice.Guidance sectionGuidance,Vector3f playerPosition) {
        panel(MINIMAP.x, MINIMAP.y, MINIMAP.width, MINIMAP.height, MINIMAP_PANEL);
        var map = drivingOutline(app.circuit).draw(g);
        var section=app.circuitSectionPractice();
        if(section!=null) {
            var from=app.circuit.sample(section.startDistance()).position();
            var to=app.circuit.sample(section.finishDistance()).position();
            if(section.hasInteriorStartLine()) {
                var line=app.circuit.sample(0).position();
                int px=Math.round(map.x(line.x)),py=Math.round(map.y(line.z));
                g.setColor(section.passedStartLine()?MUTED:INK);
                g.drawRect(px-3,py-3,6,6);
            }
            text("起",Math.round(map.x(from.x))-15,Math.round(map.y(from.z))-7,14,MINT,true);
            text("终",Math.round(map.x(to.x))+6,Math.round(map.y(to.z))+12,14,ORANGE,true);
        }
        for (var r : app.racers) {
            var p=r.car==app.player?playerPosition:r.car.position(minimapPosition);
            if(!Vector3f.isValidVector(p)) continue;
            g.setColor(r.car == app.player ? MINT : ORANGE);
            float px = map.x(p.x), py = map.y(p.z);
            g.fillOval(Math.round(px - 4), Math.round(py - 4), 8, 8);
        }
        if(sectionGuidance!=null) CircuitSectionGuideHud.drawGate(g,app.circuit,map,sectionGuidance);
    }

    private void pause() {
        shade(); buttons.clear(); panel(418,170,444,485,PANEL);
        tag(app.online() ? "ONLINE / LOCAL MENU" : "SESSION PAUSED", 456, 214, MINT);
        text(app.online() ? "驾驶菜单" : "已暂停", 456, 265, 34, INK, true);
        if (app.online()) text("联机继续进行；你的车辆制动等候。",456,285,14,MUTED);
        button(454, 294, 370, 59, "继续驾驶", "ESC / ENTER", true, app::resume);
        smallButton(454, 372, 370, 46, app.online() ? (app.raceRoom().host() ? "结束活动并返回房间" : "退出本局并等候") : "重新开始本次活动", app::retry);
        smallButton(454, 433, 370, 46, "设置", () -> app.open(FormulaGame.Screen.SETTINGS));
        smallButton(454, 494, 370, 46, "操作帮助", () -> app.setHelpVisible(true));
        var section=app.circuitSectionPractice();
        if(section!=null && section.hasOriginalResult()) smallButton(454,555,370,46,"返回原区间成绩",app::returnToOriginalSectionResult);
        else if(app.equipmentSectionActive()) smallButton(454,555,370,46,app.equipmentSectionReturnLabel(),app::returnToEquipmentSection);
        else if(app.sectionFeedback()!=null) smallButton(454,555,370,46,app.sectionFeedbackReturnLabel(),app::returnToCircuitFeedback);
        else if(section!=null) smallButton(454,555,370,46,"返回区间预览",app::returnToCircuitEditor);
        else smallButton(454, 555, 370, 46, app.activityTrial()!=null?"返回活动库（保留已接受项）":app.experience()!=null?"返回赛道分段攻关":app.workshop()!=null?"返回围场任务板":app.tuningTrial()!=null?"返回试车中心（保留已收录组）":app.circuitTrial()!=null?"返回地图编辑器":app.eventDriving()?"返回赛事中心（保留阶段）":"返回主菜单", ()->{if(app.activityTrial()!=null) app.openActivities();else if(app.experience()!=null) app.openExperience();else if(app.workshop()!=null) app.openWorkshop();else if(app.tuningTrial()!=null) app.openTuningLab();else if(app.circuitTrial()!=null) app.returnToCircuitEditor();else if(app.eventDriving()) app.openEvents();else app.mainMenu();});
        if(!app.online()) smallButton(454,611,370,35,"比赛视觉回看",app::openReplays);
        else {
            raceRoomHud.drawTiming(this,app.raceRoom(),456,618,14);
            raceRoomHud.drawTransferRate(this,app.raceRoom(),456,639);
        }
    }
    private void results() {
        shade(); buttons.clear(); panel(280, 137, 720, 526, PANEL);
        tag(app.mode == FormulaGame.Mode.CUP ? "EXPERIMENT REPORT" : "SESSION REPORT", 316, 179, MINT);
        text(app.resultTitle, 316, 226, 34, INK, true);
        wrapped(app.resultDescription, 316, 265, 645, 17, MUTED, 28);
        int y = 335;
        var section=app.circuitSectionPractice();
        if(section!=null) {
            var retest=app.sectionObservationRetest();
            var feedback=app.sectionFeedback();
            if(retest!=null) {
                smallButton(656,151,142,37,"查看复测条件",()->reviewObservationRetest(retest,false));
                smallButton(809,151,149,37,"返回原观测",app::returnToCircuitObservation);
            }
            if(feedback!=null) text("作者试驾 · "+feedback.note().kind().title+"意见",507,177,15,MUTED);
            var feedbackTrial=app.sectionFeedbackTrial();
            if(feedbackTrial!=null) {
                boolean saved=feedbackTrial.equals(app.circuitFeedback.trial(feedbackTrial.feedbackId(),feedbackTrial.noteId()));
                smallButton(735,151,223,37,saved?"查看已存作者试驾":"核对并保存试驾",()->reviewFeedbackTrial(feedbackTrial,false));
            }
            String label=CircuitSectionHud.practiceLabel(section);
            text(section.focusedSplit()==0?"区间 "+label:label,316,y,22,INK,true);
            var car=app.equipmentSectionCar();
            String start=CircuitSectionHud.startLabel(section.start());
            text(car==null?start:car.label()+" · "+start,316,y+35,17,MUTED);
            var original=app.equipmentSectionReference();
            if(original!=null) {
                String controls=app.controlsIdentity();
                text(EquipmentSectionPreviewHud.originalStart(original),655,y+35,13,MUTED);
                if(!original.matchesLaunch(section.start(),controls))
                    text(EquipmentSectionPreviewHud.DIFFERENT_LAUNCH,655,y+61,13,ORANGE);
                text(equipmentSectionOriginalReading("A",original.a(),section,original,controls),316,511,15,INK);
                text(equipmentSectionOriginalReading("B",original.b(),section,original,controls),316,539,15,INK);
            }
            if(section.state()==CircuitSectionPractice.State.FINISHED)
                text(CircuitSectionHud.speedLabel(section.completedGateSpeeds()),316,y+61,17,MUTED);
            var reference=section.referenceTimes();
            if(reference!=null) {
                sectionReference("上次有效",reference.last(),section,y+87);
                sectionReference("此前最快",reference.best(),section,y+125);
            }
            if(section.state()==CircuitSectionPractice.State.FINISHED && section.splitCount()>1) {
                smallButton(316,500,313,40,"查看分段用时",()->reviewSectionSplits(section,0,null));
                var loss=section.largestSplitLoss();
                if(loss!=null) smallButton(645,500,313,40,"查看失时最多的第 "+loss.splitNumber()+" 段",
                        ()->reviewSectionSplits(section,loss.splitNumber(),null));
                var lastPractice=app.lastCircuitSplitPractice(section);
                if(lastPractice!=null) smallButton(316,626,642,28,"继续查看上次练习的第 "+lastPractice.number()+" 段",
                        ()->reviewSectionSplits(section,lastPractice.number(),lastPractice.start()));
            }
            if(section.focusedSplit()>0) {
                if(app.splitPracticeProgress(section,section.focusedSplit(),section.start())!=null)
                    smallButton(316,500,313,40,"查看本组练习记录",()->reviewSplitProgress(section,section.focusedSplit(),section.start(),null));
                if(section.state()==CircuitSectionPractice.State.FINISHED && section.range()!=null)
                    smallButton(645,500,313,40,"带回区间预览",()->previewFinishedSplit(section));
                smallButton(316,570,313,49,"重跑此分段  ENTER",app::retry);
                if(section.hasOriginalResult()) smallButton(645,570,313,49,"返回原区间成绩",app::returnToOriginalSectionResult);
                else if(app.equipmentSectionActive()) smallButton(645,570,313,49,app.equipmentSectionReturnLabel(),app::returnToEquipmentSection);
                else if(feedback!=null) smallButton(645,570,313,49,app.sectionFeedbackReturnLabel(),app::returnToCircuitFeedback);
                else smallButton(645,570,313,49,"返回区间预览",app::returnToCircuitEditor);
            } else {
                smallButton(316,570,313,49,"重跑此区间  ENTER",app::retry);
                if(app.equipmentSectionActive()) smallButton(645,570,313,49,app.equipmentSectionReturnLabel(),app::returnToEquipmentSection);
                else if(feedback!=null) smallButton(645,570,313,49,app.sectionFeedbackReturnLabel(),app::returnToCircuitFeedback);
                else smallButton(645,570,313,49,"返回区间预览",app::returnToCircuitEditor);
                var a=EquipmentSectionPracticeSession.Car.A;
                var b=EquipmentSectionPracticeSession.Car.B;
                boolean switchToA=app.canSwitchEquipmentSectionCar(section,a);
                boolean switchToB=app.canSwitchEquipmentSectionCar(section,b);
                if(switchToA || switchToB) {
                    var progressA=app.equipmentSectionProgress(a);
                    var progressB=app.equipmentSectionProgress(b);
                    text(equipmentPracticeSummary(progressA,progressB),316,484,15,MUTED);
                    if(app.equipmentSectionHasRuns())
                        smallButton(742,151,216,37,"查看 A/B 逐次明细",()->reviewEquipmentSectionRuns(app.equipmentSectionResultContext(section),
                                ()->app.screen==FormulaGame.Screen.RESULTS && app.circuitSectionPractice()==section));
                }
                if(switchToA) smallButton(316,626,313,30,"用 A 圈参数重跑",()->app.switchEquipmentSectionCar(section,a));
                if(switchToB) smallButton(645,626,313,30,"用 B 圈参数重跑",()->app.switchEquipmentSectionCar(section,b));
            }
            return;
        }
        if(app.activityTrial()!=null) { activityHud.result(this,app);return; }
        if(app.experience()!=null) { experienceHud.result(this,app);return; }
        if(app.workshop()!=null) { workshopHud.result(this,app);return; }
        if(app.tuningTrial()!=null) { tuningLabHud.result(this,app);return; }
        if(app.practice!=null || app.challenge!=null || app.pendingLapBestCount()>0) completionSaveButton(735,151,223,37);
        if(app.bookAttempt()!=null) {
            bookHud.result(this,app);
        } else if(app.eventDriving()) {
            eventHud.result(this,app,320,y);
        } else if(app.online() && app.mode==FormulaGame.Mode.QUALIFYING) {
            roomSettingsHud.qualifyingResult(this,app,y);
        } else if (app.mode == FormulaGame.Mode.RACE || app.mode==FormulaGame.Mode.CHALLENGE || app.mode==FormulaGame.Mode.MAP_TRIAL || app.mode==FormulaGame.Mode.MAP_SOLO) {
            var standings = app.standings();
            for (int i = 0; i < standings.size(); i++) {
                var r = standings.get(i);
                text((i + 1) + "   " + r.car.name, 320, y, 20, r.car == app.player ? MINT : INK);
                String result=r.retired?"退赛":"未完赛";
                if(r.progress.finished()) result=PracticeSession.format(r.result());
                text(result, 760, y, 19, INK);
                if (r.penalty > 0) text("含罚时 +" + (int) r.penalty + "s", 770, y + 21, 12, ORANGE);
                y += 49;
            }
        } else if (app.practice != null) {
            text(app.practice.lesson.id + "  " + app.practice.lesson.title, 320, y, 23, INK, true);
            text("用时  " + PracticeSession.format(app.practice.elapsed), 320, y + 44, 27, MINT);
            text(app.newBest ? "已保存当前配置的个人最佳" : "成绩按课程与配置分别记录", 320, y + 85, 16, MUTED);
            if (app.practice.lesson.braking() && app.practice.stopDistance > 0) text(String.format(Locale.ROOT, "入口 %.0f km/h  ·  制动距离 %.1f m", app.practice.entrySpeed, app.practice.stopDistance), 320, y + 124, 18, INK);
            if(app.practice.startPractice()!=null && app.practice.startPractice().reaction()>=0) {
                var start=app.practice.startPractice();String acceleration=start.accelerationTime()<0?"未完成":String.format(Locale.ROOT,"%.3f s",start.accelerationTime());
                text(String.format(Locale.ROOT,"起步反应 %.3f s · 加速区 %s",start.reaction(),acceleration),320,y+124,18,INK);
            }
        } else if (app.cup != null) {
            text("测试得分  " + app.cup.score(), 320, y + 10, 32, MINT, true);
            text("用时  " + PracticeSession.format(app.cup.time) + "  ·  洒水 " + Math.round(app.cup.spill) + "%", 320, y + 58, 21, INK);
            text("杯架挑战独立计分，不覆盖正常圈速。", 320, y + 102, 16, MUTED);
            if (app.store.profile.humor) text("工程师：测试数据收到了，清洁组也收到了。", 320, y + 142, 16, ORANGE);
        }
        smallButton(316, 570, 198, 49, app.eventDriving()?"赛事中心  ENTER":app.online() ? "返回联机房间" : "再来一次  ENTER", app::retry);
        smallButton(538, 570, 198, 49, app.circuitTrial()!=null?"返回编辑器":app.bookAttempt()!=null?"挑战册":app.online() ? "退出联机" : "返回车库", () -> { if(app.circuitTrial()!=null) app.returnToCircuitEditor();else if(app.bookAttempt()!=null) app.openBook();else if (app.online()) app.leaveRaceRoom(); else { app.mainMenu(); app.open(FormulaGame.Screen.GARAGE); } });
        smallButton(760, 570, 198, 49, app.bookAttempt()!=null?"驾驶履历":"主菜单", ()->{if(app.bookAttempt()!=null) app.openJournal();else app.mainMenu();});
        if(app.circuitTrial()!=null) smallButton(316,626,642,28,"返回编辑器查看试跑位置",app::viewCircuitReview);
        else if(app.mode==FormulaGame.Mode.EVENT_RACE && app.activeEvent()!=null && !app.activeEvent().state().results().isEmpty()) {var source=app.activeEvent().state();smallButton(316,626,642,28,"查看本站回顾、罚时与下一步",()->showEventRoundReview(source,source.results().size()-1));}
        else if(!app.online()) smallButton(316,626,642,28,"查看已保存的比赛回看",app::openReplays);
    }
    private void completionSaveButton(int x,int y,int width,int height) {
        int pending=app.pendingLapBestCount()+(app.online()?0:app.pendingCompletions().size());
        if(pending>0) smallButton(x,y,width,height,"待保存成绩 "+pending+" 项",()->reviewCompletion(0));
    }

    private void reviewCompletion(int index) {
        var pending=app.online()?List.<FormulaGame.PendingCompletion>of():app.pendingCompletions();
        var laps=app.pendingLapBests();
        int total=pending.size()+laps.size();
        if(total==0) return;
        int position=Math.floorMod(index,total);
        String page="第 "+(position+1)+" / "+total+" 项";
        Runnable next=total>1?()->reviewCompletion(position+1):null;
        if(position>=pending.size()) {
            var lap=laps.get(position-pending.size());
            reviewWithAlternative("核对待保存圈速",List.of(
                    (lap.online()?"联机":"单机")+" · "+lap.trackName(),"有效圈速 "+PracticeSession.format(lap.seconds()),
                    "原车辆配置 "+lap.tuning().performanceKey()+" · "+(ControlIdentity.KEYBOARD.equals(lap.controls())?"键盘":"手柄"),
                    "此圈速暂存在本次游戏中，退出前可重试。",page),
                    "重试保存",()->app.retryLapBest(lap),"下一项",next);
            return;
        }
        var item=pending.get(position);
        reviewWithAlternative("核对待保存成绩",List.of(
                item.title()+" · "+item.trackName(),
                "原用时 "+PracticeSession.format(item.result().elapsed()),
                "原车辆配置 "+item.tuning().performanceKey()+" · "+(ControlIdentity.KEYBOARD.equals(item.controls())?"键盘":"手柄"),
                page),
                "重试保存",()->app.retryCompletion(item),"下一项",next);
    }
    void reviewFeedbackTrial(CircuitFeedbackTrial trial,boolean archivedView) {
        var entry=app.circuitFeedback.find(trial.feedbackId());
        var saved=app.circuitFeedback.trial(trial.feedbackId(),trial.noteId());
        var lines=feedbackTrialLines(trial);
        if(trial.belongsTo(entry)) {
            var note=entry.feedback().notes().stream().filter(value->value.id().equals(trial.noteId())).findFirst().orElseThrow();
            lines.add(0,entry.feedback().title()+" · "+note.kind().title+"意见\n"+note.text());
            boolean currentBinding=trial.binding().equals(entry.binding());
            lines.add(1,currentBinding?"试驾对应与该意见的当前本地对应一致。":"本次试驾属于该意见先前的地图对应。");
        }
        boolean alreadySaved=trial.equals(saved);
        if(archivedView && alreadySaved) {
            var result=trial.result();
            boolean canPreview=app.savedCircuitFeedbackTrialSource(trial)!=null;
            lines.add(1,canPreview?"当前草稿与记录的赛道及布景一致，可带入区间和发车设定。"
                    :"沿用设定需要打开与这次试驾一致的草稿。改过路线时可从意见重新选段。");
            boolean samePhysics=result.physicsVersion().equals(app.vehicleSpec.physicsVersion);
            boolean sameControls=result.controls().equals(app.controls.settings().identity());
            lines.add(2,"当前物理版本 "+(samePhysics?"一致":"有变化")+" · 当前控制配置 "+(sameControls?"一致":"有变化")
                    +"\n再次发车使用当前物理和控制配置。");
            var action=canPreview?new ReviewAction("沿用区间与发车条件预览",()->{
                if(circuitEditorHud.previewSavedFeedbackTrial(app,trial,null)) review=null;
            }):null;
            openReview(new Review("已存作者试驾",new ReviewPages(lines),"删除试驾记录",
                    ignored->confirm("删除这次已存试驾？","将从这条意见移除作者试驾结果。",()->{
                        if(app.circuitFeedback.removeTrial(trial)) app.notifyUser("已移除作者试驾结果。");
                        else app.notifyUser(app.circuitFeedback.warning());
                    }),null,null,null,null,null,null,null,null,null,null,action));
        } else if(!archivedView && app.canReplyWithFeedbackTrial(trial)) {
            lines.add(0,"试驾已保存。可回应发起本次试驾的原留言，草稿会附上本次记录。");
            review("已存作者试驾",lines,"写回复并附上试驾",()->prepareFeedbackTrialReply(trial));
        } else if(archivedView || alreadySaved || !trial.belongsTo(entry) || !trial.binding().equals(entry.binding())) {
            review(alreadySaved?"已存作者试驾":"作者试驾详情",lines,"关闭详情",null);
        } else if(saved==null) {
            review("本次作者试驾",lines,"保存到这条意见",()->saveFeedbackTrial(trial,null));
        } else {
            openReview(new Review("本次作者试驾",new ReviewPages(lines),"对照已存试驾",
                    details->compareFeedbackTrials(trial,saved,details),null,null,null,null,null,null,null,null,null,null));
        }
    }

    boolean previewDiscussionFeedbackTrial(FormulaGame.FeedbackDiscussionReturn discussion) {
        return circuitEditorHud.previewSavedFeedbackTrial(app,discussion.message().trial(),discussion);
    }

    void reviewFeedbackDiscussionMessage(CircuitFeedbackDiscussion thread,CircuitFeedbackDiscussion.Message message,
                                         List<String> lines,String actionLabel,Runnable action) {
        var parent=message.parent()==null?null:thread.message(message.parent());
        var comparison=parent!=null && parent.trial()!=null && message.trial()!=null
                ?new ReviewAction("对照父留言所附试驾",()->reviewDiscussionTrials(parent,message)):null;
        openReview(new Review("留言原文 · 声明",new ReviewPages(lines),actionLabel,action==null?null:ignored->action.run(),
                null,null,null,null,null,null,null,null,null,null,comparison));
    }

    private void reviewDiscussionTrials(CircuitFeedbackDiscussion.Message parent,CircuitFeedbackDiscussion.Message message) {
        var earlier=parent.trial();
        var later=message.trial();
        var lines=new ArrayList<String>();
        if(earlier.id().equals(later.id())) {
            lines.add("两条留言引用同一份试驾。");
        } else if(earlier.result().state()!=CircuitSectionPractice.State.FINISHED
                || later.result().state()!=CircuitSectionPractice.State.FINISHED) {
            lines.add("有试驾中止，各自的原计时与原因见下方。");
        } else if(earlier.sameRecordedConditions(later)) {
            lines.add("已记录的试驾条件一致。");
            double delta=later.result().elapsed()-earlier.result().elapsed();
            lines.add("用时差（本条对父留言） "+CircuitSectionPaceHud.deltaLabel(delta));
        } else {
            lines.add("试驾条件有变化，各自的原用时见下方。");
        }
        lines.add("读数来自两条留言各自的附件。");
        lines.add("父留言署名\n"+parent.author()+"\n试驾编号\n"+earlier.id());
        lines.add("本条留言署名\n"+message.author()+"\n试驾编号\n"+later.id());
        var fields=feedbackTrialDifferences(earlier,later);
        fields.add(new RetestDifference("原意见编号",earlier.noteId(),later.noteId()));
        fields.forEach(field->lines.add(field.text("父留言","本条留言")));
        openReview(new Review("两条留言所附试驾",new ReviewPages(lines),"返回留言详情",null,
                null,null,null,null,null,null,null,null,null,review));
    }

    static ArrayList<String> feedbackTrialLines(CircuitFeedbackTrial trial) {
        var result=trial.result();
        return new ArrayList<>(List.of(
                observationState(result)+" · 区间计时 "+result.elapsed()+" 秒"+(result.failure().isEmpty()?"":"\n"+result.failure()),
                "区间 "+CircuitSectionHud.rangeLabel(result.range())+" · "+retestSplitLabel(result)
                        +"\n"+CircuitSectionHud.startLabel(result.start()),
                "试驾地图\n"+trial.binding().targetId()+"\n道路和布景指纹\n"+trial.binding().roadKey(),
                "试驾条件指纹\n"+result.driveKey()+"\n物理版本\n"+result.physicsVersion()+"\n控制配置\n"+result.controls()));
    }

    private static ArrayList<RetestDifference> feedbackTrialDifferences(CircuitFeedbackTrial earlier,CircuitFeedbackTrial later) {
        var fields=new ArrayList<>(sectionResultDifferences(earlier.result(),later.result(),"试驾条件指纹"));
        fields.add(new RetestDifference("对应地图",earlier.binding().targetId(),later.binding().targetId()));
        fields.add(new RetestDifference("道路和布景指纹",earlier.binding().roadKey(),later.binding().roadKey()));
        return fields;
    }

    private void compareFeedbackTrials(CircuitFeedbackTrial trial,CircuitFeedbackTrial saved,Review details) {
        var fields=feedbackTrialDifferences(saved,trial);
        var lines=new ArrayList<String>(List.of("这条意见只保存一项作者试驾。"));
        fields.forEach(field->lines.add(field.text()));
        openReview(new Review("替换前核对两次作者试驾",new ReviewPages(lines),"替换已存试驾",
                comparison->confirm("替换这条意见的已存试驾？","确认将这次的条件和成绩作为该意见的已存试驾。",
                        ()->saveFeedbackTrial(trial,saved),comparison),
                null,null,null,null,null,null,null,null,null,details));
    }

    private void saveFeedbackTrial(CircuitFeedbackTrial trial,CircuitFeedbackTrial expected) {
        if(app.saveCircuitFeedbackTrial(trial,expected)) {
            if(app.canReplyWithFeedbackTrial(trial)) reviewFeedbackTrial(trial,false);
            return;
        }
        String warning=app.circuitFeedback.warning();
        review("作者试驾尚未保存",List.of("本次试驾仍可在成绩页查看。",
                        warning.isEmpty()?"请核对意见及地图对应。":warning),
                "重新读取反馈库",()->{
                    app.circuitFeedback.reload();
                    reviewFeedbackTrial(trial,false);
                });
    }

    private void prepareFeedbackTrialReply(CircuitFeedbackTrial trial) {
        var candidate=app.prepareFeedbackTrialReply(trial);
        if(candidate==null) return;
        circuitEditorHud.captureDrafts(app);
        var existing=app.feedbackDiscussionEditing.current();
        if(existing==null) {
            openFeedbackTrialReply(candidate);
            return;
        }
        confirm("改为本次试驾的回复草稿？","当前未提交的讨论草稿将被替换。返回可保留原草稿和本次已存试驾。",()->{
            if(!existing.equals(app.feedbackDiscussionEditing.current())) {
                app.notifyUser("讨论草稿已变化，请重新选择。");
                return;
            }
            var current=app.prepareFeedbackTrialReply(trial);
            if(current!=null) openFeedbackTrialReply(current);
        });
    }

    private void openFeedbackTrialReply(CircuitFeedbackDiscussionDraft draft) {
        app.returnToCircuitEditor();
        circuitEditorHud.editFeedbackDiscussionReply(app,draft);
    }

    void reviewObservationRetest(CircuitObservationRetest retest,boolean archivedView) {
        var source=retest.source();
        if(source.savedAt()!=CircuitObservationRetest.Source.PENDING
                && app.circuits.find(source.mapId()).filter(entry->CircuitStore.observationReviewIndex(entry.reviews(),source)>=0).isEmpty()) {
            reviewUnlocatedObservationRetest(retest);
            return;
        }
        var result=retest.result();
        var saved=app.savedCircuitObservationResult(source);
        boolean alreadySaved=result.equals(saved);
        String title=archivedView || alreadySaved?"已存观测复测":"本次运行的观测复测";
        var lines=observationRetestLines(retest);
        var editor=app.circuitEditor();
        boolean currentMap=editor!=null && source.mapId().equals(editor.document().id());
        String currentDriveKey=currentMap?editor.document().driveKey():null;
        String currentPhysics=app.vehicleSpec.physicsVersion;
        String currentControls=app.controls.settings().identity();
        lines.add(1,"与打开详情时的当前条件核对\n"
                +"草稿："+(!currentMap?"原地图未打开":result.driveKey().equals(currentDriveKey)?"一致":"已变化")+"\n"
                +"物理版本："+(result.physicsVersion().equals(currentPhysics)?"一致":"已变化")+"\n"
                +"控制配置："+(result.controls().equals(currentControls)?"一致":"已变化"));
        lines.add("打开详情时的当前草稿试驾指纹\n"+(currentMap?currentDriveKey:"原地图未打开")
                +"\n当前物理版本\n"+currentPhysics+"\n当前控制配置\n"+currentControls);
        lines.add("核对结果随本次详情固定，重新打开可更新。条件一致仅说明记录对应，仍需实际驾驶判断改图效果。");
        String actionLabel;
        Consumer<Review> action=null;
        if(archivedView) {
            actionLabel="删除已存复测";
            action=ignored->confirm("删除已存复测？",
                    "将删除原观测第 "+(source.markerIndex()+1)+" 条下的已存复测。",()->app.deleteSavedCircuitObservationRetest(retest));
        } else if(alreadySaved || source.savedAt()==CircuitObservationRetest.Source.PENDING) {
            actionLabel="关闭详情";
            if(!alreadySaved) lines.add(1,"请先在编辑器保存原试跑记录，再从原观测打开本次复测。");
        } else if(saved==null) {
            actionLabel="保存复测";
            action=ignored->saveObservationRetest(retest,null);
        } else {
            lines.add(1,"原观测还有一条已存复测。");
            actionLabel="替换已存复测";
            action=details->reviewObservationRetestReplacement(details,retest,saved);
        }
        openReview(new Review(title,new ReviewPages(lines),actionLabel,action,null,null,null,
                new ObservationRetestReview(retest,archivedView || alreadySaved),null,null,null,null,null,null));
    }

    private static ArrayList<String> observationRetestLines(CircuitObservationRetest retest) {
        var source=retest.source();
        var marker=source.marker();
        var result=retest.result();
        return new ArrayList<>(List.of(
                observationOutcome(result)+(result.failure().isEmpty()?"":"\n"+result.failure()),
                "原观测第 "+(source.markerIndex()+1)+" 条 · "+marker.event().title+" · 车 "+(marker.car()+1),
                "原记录 "+PracticeSession.format(marker.time())+" · X "+marker.x()+" · Z "+marker.z()+"\n"+marker.message(),
                "区间 "+CircuitSectionHud.rangeLabel(result.range())+(result.split()==0?"":" · 第 "+result.split()+" 段")
                        +"\n"+CircuitSectionHud.startLabel(result.start()),
                "本次物理版本\n"+result.physicsVersion()+"\n本次控制配置\n"+result.controls(),
                "复测草稿试驾指纹\n"+result.driveKey()+"\n原试跑指纹\n"+source.snapshot().documentKey(),
                "原试跑物理版本\n"+source.physicsVersion()));
    }

    private void saveObservationRetest(CircuitObservationRetest retest,CircuitObservationRetest.Result expected) {
        if(!app.saveCircuitObservationRetest(retest,expected) && app.circuits.needsRefresh())
            reviewObservationRetestReload(retest);
    }

    private void reviewObservationRetestReload(CircuitObservationRetest retest) {
        review("复测保存需要重读资料库",List.of(
                app.toast,
                "本次复测仍可查看。重读后将按原试跑和事件定位，再由你确认是否保存。"),
                "重读并核对原观测",()->{
                    switch(app.reloadObservationRetest(retest)) {
                        case REOPENED -> reviewObservationRetest(retest,false);
                        case SOURCE_MISSING -> reviewUnlocatedObservationRetest(retest);
                        case FAILED -> reviewObservationRetestReload(retest);
                    }
                });
    }

    private void reviewUnlocatedObservationRetest(CircuitObservationRetest retest) {
        var lines=observationRetestLines(retest);
        lines.add(0,"原试跑已移除或无法唯一定位。可继续查看本次复测。");
        lines.add(2,"原计时 "+Double.toString(retest.result().elapsed())+" 秒");
        review("原观测已无法定位",lines,"关闭详情",null);
    }

    private void reviewObservationRetestReplacement(Review details,CircuitObservationRetest proposed,CircuitObservationRetest.Result saved) {
        var source=proposed.source();
        var result=proposed.result();
        var marker=source.marker();
        var fields=sectionResultDifferences(saved,result,"复测草稿试驾指纹");
        var changes=fields.stream().filter(RetestDifference::changed).map(RetestDifference::label).toList();
        var lines=new ArrayList<String>(List.of(
                changes.isEmpty()?"两条记录的条件和结果一致":"变化 "+changes.size()+" 项 · "+String.join("、",changes),
                "原观测第 "+(source.markerIndex()+1)+" 条 · "+marker.event().title+" · 车 "+(marker.car()+1),
                "已存结果  "+observationOutcome(saved),
                "将存结果  "+observationOutcome(result)));
        fields.forEach(field->lines.add(field.text()));
        lines.add("原记录 "+PracticeSession.format(marker.time())+" · X "+marker.x()+" · Z "+marker.z()+"\n"+marker.message());
        lines.add("原试跑指纹\n"+source.snapshot().documentKey()+"\n原试跑物理版本\n"+source.physicsVersion());
        lines.add("此事件只保留一条已存复测。确认时仍核对这两条记录。判断改图效果需结合试驾条件与后续驾驶。");
        openReview(new Review("替换前核对两次复测",new ReviewPages(lines),"继续替换",
                comparison->confirm("替换已存复测？",
                        "将替换原观测第 "+(source.markerIndex()+1)+" 条下的已存复测。\n当前已存  "+observationOutcome(saved)
                                +"\n将保存  "+observationOutcome(result),()->saveObservationRetest(proposed,saved),comparison),
                null,null,null,null,new ObservationRetestComparison(proposed,saved),null,null,null,null,details));
    }

    private static List<RetestDifference> sectionResultDifferences(CircuitObservationRetest.Result saved,
                                                                  CircuitObservationRetest.Result result,String driveLabel) {
        return List.of(
                new RetestDifference("结果状态",observationState(saved),observationState(result)),
                new RetestDifference("区间",CircuitSectionHud.rangeLabel(saved.range()),CircuitSectionHud.rangeLabel(result.range())),
                new RetestDifference("分段",retestSplitLabel(saved),retestSplitLabel(result)),
                new RetestDifference("发车条件",CircuitSectionHud.startLabel(saved.start()),CircuitSectionHud.startLabel(result.start())),
                new RetestDifference("区间原计时（秒）",Double.toString(saved.elapsed()),Double.toString(result.elapsed())),
                new RetestDifference("中止原因",saved.failure(),result.failure()),
                new RetestDifference(driveLabel,saved.driveKey(),result.driveKey()),
                new RetestDifference("物理版本",saved.physicsVersion(),result.physicsVersion()),
                new RetestDifference("控制配置",saved.controls(),result.controls()));
    }

    private void openRetestComparisonMap(Review comparison) {
        var pair=comparison.retestComparison();
        var editor=app.circuitEditor();
        var archive=app.circuits.find(pair.proposed().source().mapId()).orElse(null);
        var map=new CircuitRetestMap(pair.proposed(),pair.saved(),editor==null?null:editor.document(),archive);
        openReview(new Review("两次复测的计时区间",new ReviewPages(List.of()),"返回条件对照",null,
                null,null,null,null,null,map,null,null,null,comparison));
    }

    private record RetestDifference(String label,String saved,String proposed) {
        boolean changed() {return !saved.equals(proposed);}
        String text() {
            return text("已存","将存");
        }
        String text(String priorLabel,String proposedLabel) {
            return label+" · "+(changed()?"不同":"一致")+"\n"+priorLabel+"  "+(saved.isEmpty()?"无":saved)
                    +"\n"+proposedLabel+"  "+(proposed.isEmpty()?"无":proposed);
        }
    }

    private static String retestSplitLabel(CircuitObservationRetest.Result result) {
        return result.split()==0?"完整区间":"第 "+result.split()+" 段";
    }

    private static String observationState(CircuitObservationRetest.Result result) {
        return result.state()==CircuitSectionPractice.State.FINISHED?"完成":"中止";
    }

    private static String observationOutcome(CircuitObservationRetest.Result result) {
        return result.state()==CircuitSectionPractice.State.FINISHED
                ? "复测完成 · "+PracticeSession.format(result.elapsed())
                : "复测中止 · 区间计时 "+PracticeSession.format(result.elapsed());
    }

    private void sectionReference(String label,double reference,CircuitSectionPractice section,int y) {
        text(label,316,y,17,MUTED);
        text(PracticeSession.format(reference),448,y,19,INK,true);
        if(section.state()==CircuitSectionPractice.State.FINISHED) {
            double delta=section.elapsed()-reference;
            String difference=CircuitSectionPaceHud.deltaLabel(delta);
            text(difference,615,y,17,delta<-.0005?MINT:INK);
        }
    }
    static String equipmentSectionOriginalReading(String car,EquipmentSectionPracticeSession.SplitReading reading,
                                                  CircuitSectionPractice section,EquipmentSectionPracticeSession.SubsectionReference original,
                                                  String controls) {
        String line=EquipmentSectionPreviewHud.originalReading(car,reading);
        if(section.state()==CircuitSectionPractice.State.FINISHED && original.matchesLaunch(section.start(),controls))
            line+=" · 本次 "+CircuitSectionPaceHud.deltaLabel(section.elapsed()-reading.seconds());
        return line;
    }
    private void previewFinishedSplit(CircuitSectionPractice result) {
        var preset=app.finishedSplitPreset(result);
        if(preset==null) return;
        app.returnToCircuitEditor();
        circuitEditorHud.previewFinishedSplit(app,preset);
    }

    private void reviewSectionSplits(CircuitSectionPractice section,int focusSplit,CircuitSectionPractice.Start start) {
        if(!isCurrentSectionResult(section)) return;
        var splits=section.completedSplits();
        if(splits.size()<2) return;
        boolean compared=section.referenceTimes()!=null;
        var lines=new ArrayList<String>();
        for(var split:splits) {
            String end=switch(split.endpoint()) {
                case START -> "起点";
                case CHECKPOINT -> "路门";
                case START_FINISH -> "起终线";
                case FINISH -> "终点";
            };
            String line=String.format(Locale.ROOT,"%d %s %.3f",split.number(),end,split.seconds());
            if(compared) {
                double delta=split.differenceSeconds();
                String difference=Math.abs(delta)<.0005?"0.000":String.format(Locale.ROOT,"%+.3f",delta);
                line+=String.format(Locale.ROOT,"/%.3f %s",split.bestSeconds(),difference);
            }
            lines.add(line);
        }
        String title=compared?"分段用时（秒） · 本次 / 此前最快 · 正值较慢":"分段用时（秒） · 本次";
        var map=new CircuitSectionSplitMap(section,splits,(number,launch)->app.splitPracticeProgress(section,number,launch));
        openReview(title,lines,"返回区间成绩",null,map,null,null);
        if(focusSplit>0) map.focusSplit(review.pages(),focusSplit);
        if(start!=null) map.setStart(start);
    }
    private void reviewSelectedSplitProgress() {
        var view=currentSplitMap();
        if(view!=null && !reviewSplitProgress(view.result(),view.selectedNumber(),view.start(),review)) view.refreshProgress();
    }
    private boolean reviewSplitProgress(CircuitSectionPractice section,int number,CircuitSectionPractice.Start start,Review back) {
        var progress=app.splitPracticeProgress(section,number,start);
        if(progress==null) return false;
        var alternatives=app.splitPracticeAlternateStarts(section,progress);
        var comparison=alternatives.isEmpty()?null:new CircuitSplitProgressHud.Comparison(progress,alternatives);
        openReview("第 "+number+" 段 · 同条件练习记录",CircuitSplitProgressHud.lines(progress),back==null?"返回分段成绩":"返回成绩地图",
                null,null,null,new SplitProgressReview(section,back,comparison,false));
        return true;
    }
    private void reviewAlternateSplitStarts() {
        var report=review;
        var reviewProgress=report==null?null:report.splitProgress();
        if(reviewProgress==null || reviewProgress.comparison()==null || !isCurrentSectionResult(reviewProgress.source())) return;
        var comparison=reviewProgress.comparison();
        int number=comparison.splitNumber();
        openReview("第 "+number+" 段 · 对照发车设定",List.of(),"返回练习记录",null,null,null,
                new SplitProgressReview(reviewProgress.source(),report,comparison,true));
    }
    private void practiceSelectedCircuitSplit() {
        var view=currentSplitMap();
        if(view!=null && app.startCircuitSplit(view.result(),view.selectedNumber(),view.start())) review=null;
    }
    private CircuitSectionSplitMap currentSplitMap() {
        var view=review==null?null:review.splitMap();
        return view!=null && isCurrentSectionResult(view.result())?view:null;
    }
    private boolean isCurrentSectionResult(CircuitSectionPractice source) {
        return app.screen==FormulaGame.Screen.RESULTS && app.circuitSectionPractice()==source;
    }
    void editCircuitSplitStart(boolean distance) {
        var view=currentSplitMap();
        if(view==null) return;
        var start=view.start();
        openNumberDialog(distance?"分段助跑距离 · 从发车点到该段起点":"分段放行速度 · 0 为静止起步",
                distance?start.runUpMeters():start.speedKph(),distance?CircuitSectionPractice.Start.MIN_RUN_UP:0,
                distance?CircuitSectionPractice.Start.maximumRunUp(view.result().track()):CircuitSectionPractice.Start.MAX_SPEED_KPH,
                distance?" m":" km/h",value->{
                    if(currentSplitMap()==view) view.setStart(distance?new CircuitSectionPractice.Start(value,start.speedKph())
                            :new CircuitSectionPractice.Start(start.runUpMeters(),value));
                    return true;
                });
    }
    private void help() {
        shade(); buttons.clear(); panel(248, 110, 784, 580, PANEL);
        tag("CONTROLS", 285, 153, MINT);
        text("先慢一点，再快一点", 285, 201, 32, INK, true);
        String[][] rows = {{app.keyLabel(Command.THROTTLE), "油门"}, {app.keyLabel(Command.BRAKE), "向前行驶时制动，停下后可倒车"}, {app.keyLabel(Command.LEFT)+" / "+app.keyLabel(Command.RIGHT), "按车辆朝向左右转向"}, {app.keyLabel(Command.HANDBRAKE), "全制动"}, {app.keyLabel(Command.RESET), "课程重试；比赛回到合法区段并罚时"}, {app.keyLabel(Command.CAMERA), "俯视跟随、斜俯视跟随、赛道总览"}, {"F6", "重载外部像素素材，驾驶时先暂停"}, {"F12", "保存游戏画面截图"}};
        for (int i = 0; i < rows.length; i++) { text(rows[i][0], 290, 252 + i * 41, 17, MINT, true); text(rows[i][1], 473, 252 + i * 41, 16, INK); }
        text("原厂前进上限 230 km/h · 倒车上限 29 km/h · 画面 30／60 FPS", 290, 608, 15, MUTED);
        smallButton(286, 634, 708, 39, "明白了  "+app.keyLabel(Command.HELP)+" / ESC", () -> app.setHelpVisible(false));
    }

    private void banner(String title, String subtitle) {
        panel(400, 109, 480, 80, new Color(55, 42, 35, 235));
        centered(title, 640, 140, 22, ORANGE); centered(subtitle, 640, 169, 14, INK);
    }
    private void shade() { g.setColor(new Color(4, 16, 26, 135)); g.fillRect(0, 0, 1280, 800); }
    void panel(int x, int y, int w, int h, Color color) {
        HudPanelPainter.draw(g,x,y,w,h,color);
    }
    private void tag(String value, int x, int y, Color color) { text(value, x, y, 13, color, true); }
    void text(String value, int x, int y, int size, Color color) { text(value, x, y, size, color, false); }
    void text(String value, int x, int y, int size, Color color, boolean bold) {
        g.setFont(font.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size)); g.setColor(color); g.drawString(value, x, y);
    }
    void centered(String value, int x, int y, int size, Color color) {
        g.setFont(font.deriveFont(Font.BOLD, (float) size)); g.setColor(color);
        g.drawString(value, x - g.getFontMetrics().stringWidth(value) / 2, y);
    }
    int wrapped(String value, int x, int y, int width, int size, Color color, int lineHeight) {
        g.setFont(font.deriveFont((float) size));
        StringBuilder line = new StringBuilder(); int row = 0;
        for (char c : value.toCharArray()) {
            if (g.getFontMetrics().stringWidth(line.toString() + c) > width) { text(line.toString(), x, y + row++ * lineHeight, size, color); line.setLength(0); }
            line.append(c);
        }
        if (!line.isEmpty()) text(line.toString(), x, y + row * lineHeight, size, color);
        return row+1;
    }
    private void meter(int x, int y, int width, int height, float value, Color color) {
        g.setColor(new Color(57, 78, 85)); g.fillRoundRect(x, y, width, height, height, height);
        g.setColor(color); g.fillRoundRect(x, y, Math.round(width * Math.max(0, Math.min(1, value))), height, height, height);
    }
    void button(int x, int y, int w, int h, String title, String subtitle, boolean primary, Runnable action) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        panel(x, y, w, h, primary ? (hover ? new Color(90, 245, 205) : MINT) : (hover ? new Color(36, 69, 79) : new Color(25, 47, 59)));
        Color foreground = primary ? new Color(8, 36, 42) : INK;
        text(title, x + 19, y + 27, 20, foreground, true);
        text(subtitle, x + 20, y + 49, 12, primary ? new Color(26, 78, 75) : MUTED);
        text("→", x + w - 37, y + 37, 23, foreground);
        buttons.add(new Button(new Rectangle(x,y,w,h),title,action));
    }
    void smallButton(int x, int y, int w, int h, String label, Runnable action) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        panel(x, y, w, h, hover ? new Color(51, 91, 99) : new Color(32, 58, 70));
        centered(label, x + w / 2, y + h / 2 + 6, 15, INK);
        buttons.add(new Button(new Rectangle(x,y,w,h),label,action));
    }
    static String shorten(String s,int max) {
        if(s.length()<=max) return s;
        int end=Math.max(0,max-1);
        if(end>0 && Character.isHighSurrogate(s.charAt(end-1))) end--;
        return s.substring(0,end)+"…";
    }
}
