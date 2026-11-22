package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.CircuitStore;
import com.jme3.math.Vector3f;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.stream.IntStream;

final class CircuitEditorHud {
    private static final int NEARBY_TAB=7;
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132),PANEL=new Color(10,29,39,245);
    private static final BasicStroke OBSERVATION_ROAD_LINE=new BasicStroke(2,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,1,new float[]{5,5},0);
    private static final BasicStroke START_LINE=new BasicStroke(3);
    private static final BasicStroke BOARD_LINE=new BasicStroke(1),BOARD_DETAIL_LINE=new BasicStroke(2);
    private static final Color GRID=new Color(67,88,82),COMMISSION_FILL=new Color(255,169,114,24);
    private static final Font ANCHOR_FONT=new Font(Font.MONOSPACED,Font.BOLD,12);
    private static final String[] ANCHOR_LABELS=IntStream.rangeClosed(1,CircuitDocument.MAX_ANCHORS)
            .mapToObj(Integer::toString).toArray(String[]::new);
    private static final Color ROAD_EDGE=new Color(95,111,108),ROAD_SURFACE=new Color(45,60,67),TIMING_GATE=new Color(190,202,167);
    private static final TrackMapView.Transform ROAD_METRES=new TrackMapView.Transform(1,0,0,0,0);
    private static final Color TRAIL_EDGE=new Color(10,29,39),TRAIL_LINE=new Color(137,221,255);
    private static final BasicStroke TRAIL_OUTLINE=new BasicStroke(5,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke TRAIL_STROKE=new BasicStroke(2,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    final CircuitEditorView view=new CircuitEditorView();
    private final CircuitLibraryHud libraryHud=new CircuitLibraryHud();
    private final CircuitRevisionHud revisionHud=new CircuitRevisionHud();
    private final CircuitFeedbackHud feedbackHud=new CircuitFeedbackHud();
    private final CircuitSectionHud sectionHud=new CircuitSectionHud();
    void keepLibraryPreviews(boolean editorScreen) {
        if(!editorScreen || !library || libraryHud.showcaseOpen()) libraryHud.clearPreviews();
    }
    String practicePickerContext() {return sectionHud.pickerContext();}
    void practicePickerKey(FormulaGame app,int key) {sectionHud.pickerKey(app,key);}
    void captureDrafts(FormulaGame app) {feedbackHud.captureDraft(app);}
    void refreshPracticeSlots(CircuitStore circuits) {if(sectionHud.showing()) sectionHud.refreshSlots(circuits);}
    void previewFinishedSplit(FormulaGame app,CircuitStore.PracticePreset preset) {
        var editor=app.circuitEditor();
        if(activeEditor!=editor) {changedDraft();activeEditor=editor;}
        if(sectionHud.open(app,editor,preset)) {
            library=false;
            feedbackHud.close();
        }
    }
    boolean previewObservationRetest(FormulaGame app,CircuitObservationRetest retest,boolean saved) {
        var editor=app.circuitEditor();
        if(app.screen!=FormulaGame.Screen.MAP_EDITOR || app.online() || editor==null) return false;
        if(!app.currentCircuitObservationRetest(retest,saved)) return false;
        if(activeEditor!=editor) {changedDraft();activeEditor=editor;}
        if(!sectionHud.openRetest(app,editor,retest,saved)) return false;
        library=false;
        feedbackHud.close();
        return true;
    }
    boolean previewSavedFeedbackTrial(FormulaGame app,CircuitFeedbackTrial trial,FormulaGame.FeedbackDiscussionReturn discussion) {
        var source=app.savedCircuitFeedbackTrialSource(trial);
        if(source==null) {
            app.notifyUser("请打开与试驾记录一致的草稿，或从这条意见按当前草稿重新选段。");
            return false;
        }
        var editor=app.circuitEditor();
        if(!app.circuitFeedback.currentLocation(source,trial)) {
            app.notifyUser("反馈库已变化，请重新读取后核对所选意见、地图对应和试驾。");
            return false;
        }
        if(activeEditor!=editor) {changedDraft();activeEditor=editor;}
        if(!sectionHud.openSavedFeedbackTrial(app,editor,source,trial,discussion)) return false;
        library=false;
        if(discussion==null) feedbackHud.showNote(app,source.entry(),source.note());
        return true;
    }
    private boolean library;
    private boolean details;
    private int tab=1,issueIndex,markerIndex;
    private String selectedHistoryId;
    private int issueFirst;
    private CircuitEditor activeEditor;
    private RoadDrawing roadDrawing;
    private final CircuitSceneryDrawing sceneryDrawing=new CircuitSceneryDrawing(INK,MINT,GOLD);
    private CircuitDriveReview.Snapshot trailReview;
    private CircuitDocument trailDocument;
    private boolean trailMatches;
    private int trailCar=-1;
    private final Map<Integer,List<CircuitDriveTrail.Point>> decodedTrails=new HashMap<>();
    private TrailDrawing trailDrawing;
    private record OriginalNearby(CircuitDocument document,CircuitObservationNearby contents) {}
    private record NearbyObservation(CircuitDriveReview.Snapshot source,int marker,CircuitFeedback.LocationSource feedback,CircuitObservationNearby contents,
                                     boolean revised,OriginalNearby original) {}
    private NearbyObservation nearbyObservation;
    private boolean showOriginalNearby;
    private int nearbyRoadIndex,originalNearbyRoadIndex;
    private record ScreenPoint(int x,int y) {
        ScreenPoint(Vector3f point,TrackMapView.Transform view) {
            this(Math.round(view.x(point.x)),Math.round(view.y(point.z)));
        }
    }
    private record RoadGeometry(TrackLayout track,Shape edge,Shape surface,Vector3f startLeft,Vector3f startRight,
                                Vector3f start,Vector3f heading,Vector3f[] gates) {}
    private record RoadDrawing(RoadGeometry geometry,TrackMapView.Transform projection,AffineTransform screenTransform,
                               ScreenPoint startLeft,ScreenPoint startRight,
                               ScreenPoint start,ScreenPoint heading,ScreenPoint[] gates) {}
    private record TrailDrawing(List<CircuitDriveTrail.Point> points,TrackMapView.Transform projection,
                                Path2D.Float path,List<ScreenPoint> starts,ScreenPoint end) {}
    void review() { details=true;tab=2;markerIndex=0;nearbyObservation=null;selectTrail(null,-1); }
    void showObservation(CircuitEditor editor,int marker,CircuitDriveReview.Snapshot source) {
        boolean returnNearby=editor!=null && activeEditor==editor && tab==NEARBY_TAB && nearbyObservation!=null
                && nearbyObservation.feedback()==null && nearbyObservation.source()==source && nearbyObservation.marker()==marker
                && nearbyObservation.contents().matches(editor.document());
        if(activeEditor!=editor) {changedDraft();activeEditor=editor;}
        sectionHud.close();
        feedbackHud.close();
        library=false;
        if(returnNearby) {details=true;showOriginalNearby=false;}
        else review();
        markerIndex=marker;
    }
    void history() { library=false;details=true;tab=4;selectedHistoryId=null; }
    void groups() { library=false;details=true;tab=3; }
    void diagnostics() { library=false;details=true;tab=1; }
    boolean originalNearbyOpen() {return details && tab==NEARBY_TAB && showOriginalNearby && !libraryOpen();}
    void closeOriginalNearby() {showOriginalNearby=false;}
    boolean backFromNearby(FormulaGame app) {
        if(!details || tab!=NEARBY_TAB || libraryOpen()) return false;
        var feedback=nearbyObservation==null?null:nearbyObservation.feedback();
        showOriginalNearby=false;
        tab=2;
        if(feedback!=null) feedbackHud.showNote(app,feedback.entry(),feedback.note());
        return true;
    }
    boolean libraryOpen() { return library || feedbackHud.showing() || sectionHud.showing(); }
    void showLibrary() { sectionHud.close();feedbackHud.close();library=true; }
    void showFeedback() {sectionHud.close();library=false;feedbackHud.show();}
    void focusFeedback(CircuitFeedback.Entry entry,CircuitFeedback.Note note,CircuitDocument target) {
        if(tab==NEARBY_TAB) tab=2;
        nearbyObservation=null;
        showOriginalNearby=false;
        sectionHud.close();
        library=false;
        feedbackHud.focus(entry,note,target,view);
    }
    void inspectFeedbackNearby(CircuitEditor editor,CircuitFeedback.LocationSource feedback) {
        if(activeEditor!=editor) {changedDraft();activeEditor=editor;}
        sectionHud.close();
        library=false;
        var draft=editor.document();
        var entry=feedback.entry();
        var note=feedback.note();
        feedbackHud.focus(entry,note,draft,view);
        boolean revised=entry.alignment(draft)==CircuitFeedback.Alignment.REFERENCE;
        var source=entry.feedback().source();
        var location=note.location();
        var original=revised?new OriginalNearby(source,new CircuitObservationNearby(source,location)):null;
        showNearby(new NearbyObservation(null,-1,feedback,new CircuitObservationNearby(draft,location),revised,original));
    }
    void resumeFeedbackNearby(FormulaGame app,CircuitFeedback.LocationSource source) {
        var editor=app.circuitEditor();
        if(activeEditor!=editor || nearbyObservation==null || !source.equals(nearbyObservation.feedback()) || !currentNearby(app,editor)) {
            inspectFeedbackNearby(editor,source);
            return;
        }
        sectionHud.close();
        feedbackHud.close();
        library=false;
        showOriginalNearby=false;
        details=true;
        tab=NEARBY_TAB;
    }
    void showFeedbackNote(FormulaGame app,CircuitFeedback.LocationSource source) {
        sectionHud.close();
        library=false;
        nearbyObservation=null;
        showOriginalNearby=false;
        tab=2;
        feedbackHud.showNote(app,source.entry(),source.note());
    }
    void showFeedbackDiscussion(CircuitFeedbackDiscussion thread,CircuitFeedbackDiscussion.Message message) {
        sectionHud.close();
        library=false;
        feedbackHud.showDiscussionMessage(thread,message);
    }
    void editFeedbackDiscussionReply(FormulaGame app,CircuitFeedbackDiscussionDraft draft) {
        sectionHud.close();
        library=false;
        feedbackHud.editDiscussionReply(app,draft);
    }
    void closeLibrary() {if(sectionHud.back() || feedbackHud.close()) return;if(!libraryHud.closeShowcase()) library=false;}
    void changedDraft() {
        issueIndex=issueFirst=markerIndex=0;selectedHistoryId=null;
        roadDrawing=null;sceneryDrawing.clear();activeEditor=null;nearbyObservation=null;
        if(tab==NEARBY_TAB) tab=2;
        selectTrail(null,-1);sectionHud.close();libraryHud.changedDraft();revisionHud.reset();
    }
    boolean scroll(boolean inward,float x,float y,FormulaGame app) {
        if(libraryOpen() || !details || tab!=1 || app.circuitEditor()==null || x<910 || x>1232 || y<250 || y>678) return false;
        int count=app.circuitEditor().inspection().issues().size();if(count>0) { issueIndex=Math.max(0,Math.min(count-1,issueIndex+(inward?-1:1)));issueFirst=Math.max(0,Math.min(issueIndex,Math.max(issueFirst,issueIndex-3))); }return true;
    }
    void render(HudRenderer h,Graphics2D graphics,FormulaGame app) {
        var editor=app.circuitEditor();
        if(activeEditor!=editor) { changedDraft();activeEditor=editor; }
        if(editor==null) { empty(h,graphics,app);return; }
        if(sectionHud.showing()) {
            var backLabel=feedbackHud.showingDiscussion()?"返回讨论留言":feedbackHud.showing()?"返回这条意见":null;
            sectionHud.render(h,graphics,app,editor,backLabel);
            return;
        }
        var document=editor.document();
        h.panel(24,26,1232,745,PANEL);h.text("CIRCUIT WORKSHOP",48,57,14,MINT,true);h.text(document.name().length()>18?document.name().substring(0,17)+"…":document.name(),48,93,27,INK,true);
        h.text(editor.dirty()?"未保存草稿":"已保存",48,116,14,editor.dirty()?GOLD:MUTED);
        h.text("路线点 "+document.anchors().size()+" / "+CircuitDocument.MAX_ANCHORS+(editor.remainingAnchors()==0?" 已满":"")+"   ·   场景物件 "+document.scene().items().size()+" / "+CircuitScene.MAX_ITEMS+(editor.remainingItems()==0?" 已满":""),220,116,14,editor.remainingAnchors()==0 || editor.remainingItems()==0?GOLD:MUTED);
        h.smallButton(640,60,190,43,feedbackHud.showing()?"返回编辑画布":library?libraryHud.showcaseOpen()?"返回地图卡":"返回编辑画布":"地图卡／草稿库",()->{editor.endPointer();if(feedbackHud.showing() || library) closeLibrary();else library=true;});
        h.smallButton(1060,60,172,43,"保存并返回",app::closeCircuitEditor);
        h.smallButton(850,60,200,43,"创作任务／赛事包",app::openCreation);
        if(feedbackHud.showing()) {feedbackHud.render(h,app,feedbackHud::close);return;}
        if(library) { libraryHud.render(h,graphics,app,()->library=false);return; }
        pendingPracticeButtons(h,app);
        h.smallButton(44,128,70,31,"新建",()->app.newCircuit(null));
        h.smallButton(124,128,95,31,"复制海湾",()->app.newCircuit(TrackCatalog.DEFAULT_ID));
        h.smallButton(229,128,95,31,"复制林地",()->app.newCircuit("woodland-technical"));
        h.smallButton(334,128,95,31,"复制公园",()->app.newCircuit("park-highspeed"));
        if(h.circuitImportReading()) h.smallButton(439,128,70,31,"取消读取",h::cancelCreationImport);
        else h.smallButton(439,128,70,31,"导入",()->h.chooseCircuitFile(false));
        h.smallButton(519,128,70,31,"导出",()->h.chooseCircuitFile(true));
        h.smallButton(599,128,108,31,"保存草稿",app::saveCircuitDraft);h.smallButton(717,128,173,31,"四车试跑验证",app::startCircuitTrial);
        h.smallButton(900,128,112,31,"复制港区",()->app.newCircuit("dockside-street"));
        h.smallButton(1022,128,100,31,"作者试驾",app::startCircuitSolo);h.smallButton(1132,128,100,31,details?"返回工具":"属性／检查",()->details=!details);
        h.panel(44,168,846,510,new Color(22,47,46));drawBoard(graphics,editor,app);
        h.panel(910,168,322,510,new Color(24,49,60));
        if(details) detailPanel(h,app,editor);else tools(h,app,editor,document);
        h.smallButton(44,690,112,33,"上张草稿",()->app.cycleCircuitDraft(-1));h.smallButton(180,690,180,33,"玩家试玩反馈",()->{editor.endPointer();showFeedback();});h.text("已保存 "+app.circuits.all().size()+" / 32 张",380,713,15,MUTED);
        h.smallButton(526,690,120,33,"区间试驾",()->{if(!sectionHud.open(app,editor)) app.notifyUser("先在检查面板调整路线点，再选择试驾区间。");});
        h.smallButton(658,690,100,33,"复原",()->view.fit(editor.document()));h.smallButton(770,690,120,33,"下张草稿",()->app.cycleCircuitDraft(1));
        h.smallButton(910,690,322,33,"删除本张草稿",()->h.confirm("删除这张草稿？",document.name()+"\n删除地图、版本历史和试跑记录。\n当前未保存的修改也会丢弃。",()->app.deleteCircuitDraft(document.id())));
        if(app.circuits.needsRefresh()) {
            h.text("地图资料库需要重新核对，当前编辑保留。",44,752,15,GOLD);
            h.smallButton(410,731,234,34,"查看保存差异",()->h.reviewCircuitDraftComparison(document,null));
            h.smallButton(660,731,234,34,"另存当前草稿",()->h.nameCircuitDraftCopy(document,null));
            h.smallButton(910,731,322,34,"重读地图资料库",()->app.reloadCircuitLibrary(null));
        } else h.text(editor.restriction().isEmpty()?"Ctrl 点选路线点或物件 · 路线工具 Ctrl+A 全选路线 · Ctrl+C/V 复制物件 · Z/Y 撤销重做 · 中键平移／滚轮缩放 · Ctrl+S 保存":editor.restriction(),44,752,13,editor.restriction().isEmpty()?MUTED:GOLD);
    }
    private void empty(HudRenderer h,Graphics2D g,FormulaGame app) {
        h.panel(24,26,1232,745,PANEL);h.text("CIRCUIT WORKSHOP",48,57,14,MINT,true);h.text("没有草稿",48,97,29,INK,true);
        h.smallButton(640,60,190,43,feedbackHud.showing()?"返回空画布":library?"返回空画布":"地图卡／草稿库",()->{if(feedbackHud.showing()) feedbackHud.close();else library=!library;});h.smallButton(850,60,200,43,"创作任务／赛事包",app::openCreation);h.smallButton(1060,60,172,43,"返回主界面",app::closeCircuitEditor);
        if(feedbackHud.showing()) {feedbackHud.render(h,app,feedbackHud::close);return;}
        if(library) { libraryHud.render(h,g,app,()->library=false);return; }
        pendingPracticeButtons(h,app);
        h.panel(44,168,1188,510,new Color(24,49,60));h.text("当前没有可编辑的草稿",82,255,30,MINT,true);h.wrapped("新建地图、复制内置赛道或导入文件后开始编辑。删除最后一张草稿后，不会自动生成或保存替代地图。",82,307,1060,19,MUTED,30);
        h.smallButton(82,380,250,46,"新建环线草稿",()->app.newCircuit(null));
        if(h.circuitImportReading()) h.smallButton(352,380,250,46,"正在读取地图 · 取消",h::cancelCreationImport);
        else h.smallButton(352,380,250,46,"导入地图",()->h.chooseCircuitFile(false));
        if(app.circuits.needsRefresh()) h.smallButton(892,380,250,46,"重读地图资料库",()->app.reloadCircuitLibrary(null));
        h.smallButton(622,380,250,46,"玩家反馈库",this::showFeedback);
        for(int i=0;i<TrackCatalog.all().size();i++) { var track=TrackCatalog.all().get(i);h.smallButton(82+i%2*540,465+i/2*65,510,44,"复制 "+track.layout().name,()->app.newCircuit(track.id())); }
        h.text("已保存 0 / 32 张 · 请先新建或导入地图",48,724,16,MUTED);
    }
    private void pendingPracticeButtons(HudRenderer h,FormulaGame app) {
        if(!app.circuitPracticeImportPending()) return;
        h.smallButton(260,32,206,34,"查看待导入区间",app::reviewPendingCircuitPracticeImport);
        h.smallButton(484,32,133,34,"放弃待导入",app::discardPendingCircuitPracticeImport);
    }
    private void tools(HudRenderer h,FormulaGame app,CircuitEditor editor,CircuitDocument document) {
        h.text("编辑工具",930,202,22,INK,true);
        h.smallButton(930,217,128,33,selected(editor,CircuitEditor.Tool.SELECT,"选择／移动"),()->editor.tool(CircuitEditor.Tool.SELECT));
        h.smallButton(1070,217,142,33,selected(editor,CircuitEditor.Tool.POINT,"路线点"),()->editor.tool(CircuitEditor.Tool.POINT));
        h.smallButton(930,262,80,33,selected(editor,CircuitEditor.Tool.TREE,"树"),()->editor.tool(CircuitEditor.Tool.TREE));
        h.smallButton(1021,262,80,33,selected(editor,CircuitEditor.Tool.SIGN,"标牌"),()->editor.tool(CircuitEditor.Tool.SIGN));
        h.smallButton(1112,262,100,33,selected(editor,CircuitEditor.Tool.BARRIER,"护栏"),()->editor.tool(CircuitEditor.Tool.BARRIER));
        h.smallButton(930,308,128,33,selected(editor,CircuitEditor.Tool.BUILDING,"低矮建筑"),()->editor.tool(CircuitEditor.Tool.BUILDING));
        h.smallButton(1070,308,142,33,"重命名",()->h.editText("地图名称",document.name(),"我的环线",60,name->{try { editor.rename(name); } catch(IllegalArgumentException error) { app.notifyUser("地图名称需为 1–60 个字符。"); }}));
        h.text("路宽 "+Math.round(document.width())+" m",930,375,18,INK);
        h.smallButton(1090,351,52,33,"−",()->editor.width(-2));h.smallButton(1160,351,52,33,"+",()->editor.width(2));
        h.smallButton(930,394,282,33,"环境："+document.scene().theme().title,editor::theme);
        h.smallButton(930,440,282,33,"将选中路线点设为起点",editor::setStart);
        h.smallButton(930,485,133,33,"左转 90°  Q",()->editor.rotate(-(float)Math.PI/2));h.smallButton(1076,485,136,33,"右转 90°  E",()->editor.rotate((float)Math.PI/2));
        h.smallButton(930,530,282,33,"删除选中内容  DEL",editor::delete);
        h.smallButton(930,574,282,33,"成组编辑 · "+editor.selectedAnchorCount()+" 点／"+editor.selectedItemCount()+" 物件",()->{details=true;tab=3;});
        if(editor.dragging()) h.text("松开鼠标后检查路线",930,638,15,MUTED);
        else {
            var report=editor.inspection();h.text(report.ready()?"几何检查通过":"还有 "+report.errors().size()+" 项需要调整",930,637,16,report.ready()?MINT:GOLD);
            h.text(report.ready()?(editor.proof()!=null && editor.proof().matches(document,app.vehicleSpec.physicsVersion)?"四车已验证，可在选图使用":"四车合法完赛后开放选图"):"在「属性／检查」中定位问题",930,663,14,MUTED);
        }
    }
    private void detailPanel(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        h.text(tab==0?"选中属性":tab==1?"问题定位":tab==2?"试跑记录":tab==3?"成组编辑":tab==4?"版本历史":tab==6?"版本比较":tab==NEARBY_TAB?"观测附近":"记录管理",930,202,18,INK,true);
        h.smallButton(1040,174,80,29,"版本",()->tab=4);
        h.smallButton(1132,174,80,29,"成组",()->tab=3);
        h.smallButton(930,217,88,33,"属性",()->tab=0);h.smallButton(1027,217,88,33,"检查",()->tab=1);h.smallButton(1124,217,88,33,"试跑",()->tab=2);
        if(tab==0) properties(h,app,editor);else if(tab==1) diagnostics(h,app,editor);else if(tab==2) driveReview(h,app,editor);else if(tab==3) groups(h,app,editor);else if(tab==4) history(h,app,editor);else if(tab==6) revisionHud.render(h,app,editor,view,()->tab=4);else if(tab==NEARBY_TAB) nearby(h,app,editor);else manageReviews(h,app);
    }
    private void properties(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        if(editor.selectedItemCount()>1 || editor.selectedAnchorCount()>1) { groups(h,app,editor);return; }
        var document=editor.document();int anchor=editor.selectedAnchor(),index=editor.selectedItem();
        if(anchor<0 && index<0) { h.wrapped("先在画布选中路线点或场景物件，再修改属性。",930,294,276,17,MUTED,27);h.wrapped("属性修改可以撤销；仅查看或定位不会改变地图。",930,396,276,15,MUTED,24);return; }
        boolean point=anchor>=0;var item=point?null:document.scene().items().get(index);float x=point?document.anchors().get(anchor).x():item.x(),z=point?document.anchors().get(anchor).z():item.z();
        h.text(point?"路线点 "+(anchor+1):kind(item.kind())+" #"+(index+1),930,278,17,MINT,true);
        if(currentNearby(app,editor)) h.smallButton(1064,256,148,29,nearbyObservation.feedback()==null?"返回观测附近":"返回意见附近",()->tab=NEARBY_TAB);
        property(h,app,302,"X 米",x,value->editor.moveSelected((float)value,z),()->editor.moveSelected(x-2,z),()->editor.moveSelected(x+2,z));
        property(h,app,347,"Z 米",z,value->editor.moveSelected(x,(float)value),()->editor.moveSelected(x,z-2),()->editor.moveSelected(x,z+2));
        if(!point && item.solid()) {
            property(h,app,392,"角度",(float)Math.toDegrees(item.yaw()),value->editor.yawSelected((float)Math.toRadians(value)),()->editor.yawSelected(item.yaw()-(float)Math.PI/12),()->editor.yawSelected(item.yaw()+(float)Math.PI/12));
            float maxLength=item.kind()==CircuitScene.Kind.BUILDING?24:12,maxWidth=item.kind()==CircuitScene.Kind.BUILDING?16:4;
            property(h,app,437,"长 米",item.length(),value->editor.resizeSelected((float)value,item.width()),()->editor.resizeSelected(Math.max(1,item.length()-1),item.width()),()->editor.resizeSelected(Math.min(maxLength,item.length()+1),item.width()));
            property(h,app,482,"宽 米",item.width(),value->editor.resizeSelected(item.length(),(float)value),()->editor.resizeSelected(item.length(),Math.max(.5f,item.width()-.5f)),()->editor.resizeSelected(item.length(),Math.min(maxWidth,item.width()+.5f)));
        }
        if(point) h.smallButton(930,412,282,35,"将此路线点设为地图起点",editor::setStart);
        else h.smallButton(930,532,282,35,"复制物件并偏移 4 米",()->{if(!editor.duplicateSelected()) app.notifyUser(editor.restriction());});
        h.smallButton(930,578,282,35,"删除选中内容",editor::delete);
        h.wrapped(point?"路线点坐标范围 ±240 米。选中路线点后，可从这里开始作者试驾。":item.solid()?"物件坐标范围 ±280 米；长宽改变实际碰撞占地，修改后需要重新验证。":"树与标牌是装饰；可移动和复制。",930,641,276,14,MUTED,20);
    }
    private void groups(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        int routeCount=editor.selectedAnchorCount();boolean route=routeCount>0;int count=route?routeCount:editor.selectedItemCount();
        h.text("已选 "+count+(route?" 个路线点":" 个物件"),930,282,17,MINT,true);
        int active=route?editor.selectedAnchor():editor.selectedItem();h.text(active<0?"Ctrl 点选，路线与物件分别成组":"对齐基准：最后选中的 #"+(active+1),930,310,14,MUTED);
        h.smallButton(930,328,133,33,"全选物件",editor::selectAllItems);h.smallButton(1076,328,136,33,"全选路线点",editor::selectAllAnchors);
        if(route) { h.smallButton(930,373,282,33,"清空路线点选择",editor::clearSelection); }
        else { h.smallButton(930,373,133,33,"复制 Ctrl+C",app::copyCircuitSelection);h.smallButton(1076,373,136,33,"粘贴 Ctrl+V",app::pasteCircuitSelection); }
        h.smallButton(930,418,282,33,route?"从活动路线点作者试驾":"整组复制并偏移",()->{if(route) app.startCircuitSolo();else if(!editor.duplicateSelected()) app.notifyUser(editor.restriction());});
        h.smallButton(930,463,133,33,"对齐 X 坐标",()->editor.alignSelected(CircuitEditor.Axis.X));h.smallButton(1076,463,136,33,"对齐 Z 坐标",()->editor.alignSelected(CircuitEditor.Axis.Z));
        h.smallButton(930,508,63,33,"X −2",()->nudge(app,editor,-2,0));h.smallButton(1003,508,63,33,"X +2",()->nudge(app,editor,2,0));
        h.smallButton(1076,508,63,33,"Z −2",()->nudge(app,editor,0,-2));h.smallButton(1149,508,63,33,"Z +2",()->nudge(app,editor,0,2));
        h.smallButton(930,553,282,33,"删除整组选中"+(route?"路线点":"物件"),editor::delete);
        h.wrapped("拖动任一选中成员平移整组；一次操作可一步撤销。路线点保持原顺序，物件可跨草稿复制。",930,618,276,14,MUTED,20);
    }
    private void nudge(FormulaGame app,CircuitEditor editor,float dx,float dz) {
        if(editor.selectedAnchor()>=0) { var point=editor.document().anchors().get(editor.selectedAnchor());edit(app,()->editor.moveSelected(point.x()+dx,point.z()+dz)); }
        else if(editor.selectedItem()>=0) { var item=editor.document().scene().items().get(editor.selectedItem());edit(app,()->editor.moveSelected(item.x()+dx,item.z()+dz)); }
    }
    private void history(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        var versions=app.circuitHistory();
        if(versions.isEmpty()) { h.wrapped("保存草稿后，这里会记录地图版本。",930,300,276,17,MUTED,27);return; }
        int index=versions.size()-1;
        if(selectedHistoryId!=null) {
            while(index>=0 && !selectedHistoryId.equals(versions.get(index).id())) index--;
            if(index<0) {
                h.wrapped("所选版本已移出版本历史。",930,300,276,17,MUTED,27);
                h.smallButton(930,420,282,35,"查看最新版本",()->selectedHistoryId=null);
                return;
            }
        }
        int shown=index;
        var version=versions.get(shown);
        var document=version.document();
        var current=editor.document();
        h.text("版本 "+(shown+1)+" / "+versions.size(),930,286,18,MINT,true);
        h.smallButton(930,306,133,33,"上一版本",()->selectedHistoryId=versions.get(Math.floorMod(shown-1,versions.size())).id());
        h.smallButton(1076,306,136,33,"下一版本",()->selectedHistoryId=versions.get((shown+1)%versions.size()).id());
        h.wrapped(document.name(),930,382,276,18,INK,26);
        String saved=java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(version.savedAt()));
        h.text("保存于 "+saved,930,438,15,MUTED);h.text(document.anchors().size()+" 个点 · "+document.width()+" 米 · "+document.scene().items().size()+" 物件",930,475,15,INK);
        h.smallButton(930,515,282,38,"还原此版本（可撤销）",()->app.restoreCircuitRevision(version,current));
        h.smallButton(930,560,282,33,"与当前草稿叠图比较",()->{selectedHistoryId=version.id();revisionHud.select(version);tab=6;});
        h.wrapped("保留最近 12 版。还原可撤销，保存失败保留历史；驾驶条件变化需重新四车验证。",930,622,276,14,MUTED,20);
    }
    private void property(HudRenderer h,FormulaGame app,int y,String label,float value,DoubleConsumer setter,Runnable minus,Runnable plus) {
        h.smallButton(930,y,150,33,label+" "+String.format(Locale.ROOT,"%.1f",value),()->h.prompt(label,Float.toString(value),text->{try { float parsed=Float.parseFloat(text.strip());if(!Float.isFinite(parsed)) throw new IllegalArgumentException();setter.accept(parsed); }catch(IllegalArgumentException error) { app.notifyUser("数值无效，请检查该项范围。"); }}));
        h.smallButton(1090,y,52,33,"−",()->edit(app,minus));h.smallButton(1160,y,52,33,"+",()->edit(app,plus));
    }
    private void edit(FormulaGame app,Runnable action) { try { action.run(); }catch(IllegalArgumentException error) { app.notifyUser("已经到达该属性的可用范围。"); } }
    private String kind(CircuitScene.Kind kind) { return switch(kind) { case TREE->"树";case SIGN->"标牌";case BARRIER->"护栏";case BUILDING->"低矮建筑"; }; }
    private void diagnostics(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        if(editor.dragging()) { h.wrapped("松开鼠标后检查当前路线。",930,294,276,17,MUTED,26);return; }
        var report=editor.inspection();if(report.issues().isEmpty()) { h.text("几何检查通过",930,295,20,MINT,true);h.wrapped("可先单独试驾观察路线；正式选图资格仍需四车试跑验证。",930,346,276,17,MUTED,27);return; }
        issueIndex=Math.min(issueIndex,report.issues().size()-1);var issue=report.issues().get(issueIndex);
        issueFirst=Math.max(0,Math.min(issueFirst,Math.max(0,report.issues().size()-4)));if(issueIndex<issueFirst) issueFirst=issueIndex;else if(issueIndex>=issueFirst+4) issueFirst=issueIndex-3;
        h.text("问题 "+(issueIndex+1)+" / "+report.issues().size(),930,282,18,GOLD,true);
        h.smallButton(930,298,133,30,"↑ 上一项",()->issueIndex=Math.max(0,issueIndex-1));h.smallButton(1076,298,136,30,"↓ 下一项",()->issueIndex=Math.min(report.issues().size()-1,issueIndex+1));
        for(int i=issueFirst;i<Math.min(report.issues().size(),issueFirst+4);i++) { int selected=i;var row=report.issues().get(i);h.smallButton(930,337+(i-issueFirst)*34,282,29,(i==issueIndex?"● ":"")+(i+1)+" · "+row.code().title,()->issueIndex=selected); }
        h.wrapped(issue.message(),930,502,276,15,INK,22);
        h.smallButton(930,583,282,35,"定位并选择相关内容",()->{editor.focus(issue);if(issue.locations().isEmpty()) view.fit(editor.document());else view.focus(issue.locations());});
        h.smallButton(930,631,282,35,"修改建议与检查说明",()->{var advice=CircuitAuthorFeedback.explain(issue);var lines=new java.util.ArrayList<String>();lines.add(advice.reason());lines.addAll(advice.actions());lines.add(advice.verification());h.review("修改建议 · "+advice.title(),lines,"关闭说明",()->{});});
    }
    private void driveReview(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        var review=app.circuitReview();
        if(review!=null && !review.markers().isEmpty()) markerIndex=Math.min(markerIndex,review.markers().size()-1);
        var retest=app.latestCircuitObservationRetest(review,markerIndex);
        var savedRetest=app.savedCircuitObservationRetest(review,markerIndex);
        boolean hasNew=retest!=null && (savedRetest==null || !retest.result().equals(savedRetest.result()));
        boolean hasSaved=savedRetest!=null;
        if(hasNew && hasSaved) {
            h.smallButton(930,640,86,29,"记录管理",()->tab=5);
            h.smallButton(1022,640,92,29,"本次复测",()->h.reviewObservationRetest(retest,false));
            h.smallButton(1120,640,92,29,"已存复测",()->h.reviewObservationRetest(savedRetest,true));
        } else {
            h.smallButton(930,640,hasNew || hasSaved?138:282,29,"记录管理 · "+app.circuitReviews().size()+" / "+app.circuitReviewCapacity(),()->tab=5);
            if(hasSaved) h.smallButton(1074,640,138,29,"查看已存复测",()->h.reviewObservationRetest(savedRetest,true));
            else if(hasNew) h.smallButton(1074,640,138,29,"查看本次复测",()->h.reviewObservationRetest(retest,false));
        }
        if(review==null) { h.wrapped("先进行作者试驾或四车验证，再在这里查看实际观测位置。",930,298,276,17,MUTED,27);return; }
        h.smallButton(930,254,85,23,"上次试跑",()->{app.cycleCircuitReview(-1);markerIndex=0;});h.smallButton(1127,254,85,23,"下次试跑",()->{app.cycleCircuitReview(1);markerIndex=0;});
        boolean pending=app.circuitReviewIndex()<0;
        h.text(pending?"待保存":(app.circuitReviewIndex()+1)+" / "+app.circuitReviews().size(),1034,271,13,MUTED);
        h.text(review.kind().title+" · "+PracticeSession.format(review.elapsed()),930,303,17,MINT,true);h.wrapped(review.summary(),930,333,276,15,MUTED,21);
        boolean completed=review.status()!=CircuitDriveReview.Status.RUNNING && matchesTrailReview(review,editor.document());
        int y=376;
        for(var car:review.cars()) {
            String label="车 "+(car.car()+1)+" · 门 "+car.nextGate()+" · "+Math.round(car.speed()*3.6f)+" km/h";
            if(completed) label+=car.valid()?" · 有效":" · 圈无效";
            if(completed && review.trails().stream().anyMatch(path->path.car()==car.car())) {
                String indicator=trailCar==car.car()?"● ":"○ ";
                h.smallButton(930,y-15,282,22,indicator+label,()->selectTrail(review,trailCar==car.car()?-1:car.car()));
            } else h.text(label,930,y,14,completed && !car.valid()?GOLD:INK);
            y+=24;
        }
        if(review.markers().isEmpty()) { h.wrapped("本次没有位置事件；结束前的车辆状态如上。",930,493,276,16,MUTED,25);h.wrapped(pending?"记录尚未保存；保存草稿可重试。":"自动保留最近 "+app.circuitReviewCapacity()+" 次试跑，重启后可查看。",930,588,276,14,MUTED,22);return; }
        int selectedMarker=markerIndex;var marker=review.markers().get(selectedMarker);
        h.smallButton(930,454,80,32,"上一条",()->markerIndex=Math.floorMod(markerIndex-1,review.markers().size()));h.text((markerIndex+1)+" / "+review.markers().size(),1027,476,15,INK);h.smallButton(1132,454,80,32,"下一条",()->markerIndex=(markerIndex+1)%review.markers().size());
        h.text(marker.event().title+" · 车 "+(marker.car()+1)+" · "+String.format(Locale.ROOT,"%.1fs",marker.time()),930,513,16,GOLD,true);h.wrapped(marker.message(),930,546,276,14,INK,21);
        boolean matches=matchesTrailReview(review,editor.document());
        if(matches) {
            h.smallButton(930,596,84,31,"定位观测",()->view.focus(List.of(marker.location())));
            if(completed) h.smallButton(1025,596,85,31,"查看附近",()->openNearby(app,editor,review,selectedMarker,false));
            h.smallButton(1121,596,91,31,"附近复测",()->sectionHud.openObservation(app,editor,review,selectedMarker,false));
        } else {
            h.smallButton(930,596,180,31,"按当前草稿预览",()->sectionHud.openObservation(app,editor,review,selectedMarker,true));
            if(review.status()!=CircuitDriveReview.Status.RUNNING)
                h.smallButton(1121,596,91,31,"同坐标附近",()->openNearby(app,editor,review,selectedMarker,true));
        }
        h.text(!matches?"旧观测 · 对照当前草稿后选择复测区间":pending?"尚未保存，保存草稿可重试":"已保存，重启后可查看",930,637,13,MUTED);
    }

    private void openNearby(FormulaGame app,CircuitEditor editor,CircuitDriveReview.Snapshot source,int marker,boolean revised) {
        if(app.screen!=FormulaGame.Screen.MAP_EDITOR || app.circuitEditor()!=editor || app.circuitReview()!=source
                || source.status()==CircuitDriveReview.Status.RUNNING || marker!=markerIndex) {
            app.notifyUser("试跑记录或观测已变化，请重新选择。");
            return;
        }
        editor.endPointer();
        var draft=editor.document();
        if(revised==matchesTrailReview(source,draft)) {
            app.notifyUser("草稿版本已变化，请重新选择观测入口。");
            return;
        }
        var location=source.markers().get(marker).location();
        OriginalNearby original=null;
        if(revised) {
            var archive=app.circuits.find(draft.id()).orElse(null);
            var document=archive==null?null:archive.drivingVersion(source.documentKey());
            if(document!=null) original=new OriginalNearby(document,new CircuitObservationNearby(document,location));
        }
        showNearby(new NearbyObservation(source,marker,null,new CircuitObservationNearby(draft,location),revised,original));
    }

    private void showNearby(NearbyObservation shown) {
        nearbyObservation=shown;
        showOriginalNearby=false;
        nearbyRoadIndex=originalNearbyRoadIndex=0;
        view.focus(List.of(shown.contents().observation()));
        details=true;
        tab=NEARBY_TAB;
    }

    private boolean currentNearby(FormulaGame app,CircuitEditor editor) {
        var shown=nearbyObservation;
        if(shown==null || !shown.contents().matches(editor.document())) return false;
        var feedback=shown.feedback();
        if(feedback==null) return shown.source()==app.circuitReview() && shown.marker()==markerIndex;
        return feedback.matches(app.circuitFeedback.find(feedback.entry().feedback().id()));
    }

    private void nearby(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        var shown=nearbyObservation;
        var feedback=shown==null?null:shown.feedback();
        String backLabel=feedback==null?"返回试跑记录":"返回这条意见";
        Runnable back=()->backFromNearby(app);
        if(!currentNearby(app,editor)) {
            h.smallButton(930,627,282,38,backLabel,back);
            String message=feedback==null?"试跑记录、观测或草稿已变化。请返回试跑记录重新查看。"
                    :"反馈、对应地图或草稿已变化。请返回意见重新核对。";
            h.wrapped(message,930,294,276,16,GOLD,24);
            return;
        }
        var area=showOriginalNearby?shown.original().contents():shown.contents();
        if(feedback==null) {
            var marker=shown.source().markers().get(shown.marker());
            h.text(marker.event().title+" · 车 "+(marker.car()+1)+" · "+PracticeSession.format(marker.time()),930,283,15,GOLD,true);
        } else h.text("玩家意见 · "+feedback.note().kind().title,930,283,15,GOLD,true);
        var point=area.observation();
        h.text(String.format(Locale.ROOT,"X %.1f   Z %.1f · %d m 内",point.x(),point.z(),CircuitObservationNearby.RADIUS_METERS),930,313,14,MUTED);
        if(shown.revised()) {
            if(shown.original()==null) h.text("当前草稿同坐标 · 原版未找到",930,347,14,MUTED);
            else {
                h.smallButton(930,324,133,29,(showOriginalNearby?"":"✓ ")+"当前草稿",()->showOriginalNearby=false);
                String originalLabel=feedback==null?"原版只读":"反馈原版";
                h.smallButton(1076,324,136,29,(showOriginalNearby?"✓ ":"")+originalLabel,()->showOriginalNearby=true);
            }
        }
        int firstY=shown.revised()?362:335,step=shown.revised()?34:38;
        int y=firstY;
        for(var target:area.routePoints()) {nearbyTarget(h,app,editor,shown,target,y);y+=step;}
        for(var target:area.scenery()) {nearbyTarget(h,app,editor,shown,target,y);y+=step;}
        if(y==firstY) h.text("附近没有路线点或场景物件",930,firstY+32,16,MUTED);
        var road=selectedNearbyRoad(area);
        if(area.roadCandidates().size()>1) {
            int index=selectedNearbyRoadIndex();
            h.smallButton(930,568,42,44,"←",()->moveNearbyRoad(app,editor,shown,area,-1));
            h.smallButton(1170,568,42,44,"→",()->moveNearbyRoad(app,editor,shown,area,1));
            var range=road.choice().range();
            String bounds=range.from()+"%→"+range.to()+"%"+(range.crossesStartLine()?" · 跨线":"");
            h.centered("候选 "+(index+1)+"/"+area.roadCandidates().size()+" · "+bounds,1071,584,14,INK);
            h.centered(roadDistanceLabel(road.readout()),1071,606,13,MUTED);
        } else {
            h.text(roadDistanceLabel(road==null?area.roadReadout():road.readout()),930,582,15,INK);
            String hint=road!=null?"候选区间 "+CircuitSectionHud.rangeLabel(road.choice().range())
                    :showOriginalNearby?"原版候选只读":shown.revised()?"按来源坐标查找，请核对路线":"点击候选可查看属性";
            h.text(hint,930,605,14,MUTED);
        }
        boolean canPreview=!showOriginalNearby && area.road()!=null;
        h.smallButton(930,627,canPreview?118:282,38,backLabel,back);
        if(canPreview) h.smallButton(1058,627,154,38,feedback==null?"预览附近复测":"预览意见试驾",()->{
            if(showOriginalNearby || shown!=nearbyObservation || app.circuitEditor()!=editor || !currentNearby(app,editor)) {
                app.notifyUser("试跑记录或草稿已变化，请重新查看附近内容。");
                return;
            }
            var choice=road==null?null:road.choice();
            if(feedback==null) sectionHud.openNearbyObservation(app,editor,shown.source(),shown.marker(),shown.revised(),choice);
            else sectionHud.openFeedbackPractice(app,editor,feedback,choice);
        });
    }

    private int selectedNearbyRoadIndex() {return showOriginalNearby?originalNearbyRoadIndex:nearbyRoadIndex;}

    private CircuitObservationNearby.RoadCandidate selectedNearbyRoad(CircuitObservationNearby area) {
        return area.roadCandidates().isEmpty()?null:area.roadCandidates().get(selectedNearbyRoadIndex());
    }

    private void moveNearbyRoad(FormulaGame app,CircuitEditor editor,NearbyObservation shown,CircuitObservationNearby area,int delta) {
        if(shown!=nearbyObservation || !currentNearby(app,editor) || area!=(showOriginalNearby?shown.original().contents():shown.contents())) return;
        int next=Math.floorMod(selectedNearbyRoadIndex()+delta,area.roadCandidates().size());
        if(showOriginalNearby) originalNearbyRoadIndex=next;
        else nearbyRoadIndex=next;
    }

    private static String roadDistanceLabel(CircuitObservationNearby.RoadReadout road) {
        if(road==null) return "当前视图没有可绘制的道路";
        String center=String.format(Locale.ROOT,"距中心 %.1f m",road.centerlineMeters());
        return road.beyondHalfWidthMeters()==0?center+" · 半路宽内"
                :center+String.format(Locale.ROOT," · 超出半宽 %.1f m",road.beyondHalfWidthMeters());
    }

    private void nearbyTarget(HudRenderer h,FormulaGame app,CircuitEditor editor,NearbyObservation shown,
                              CircuitObservationNearby.Target target,int y) {
        String name=target.routePoint()?"路线点":kind(target.kind());
        String distance=String.format(Locale.ROOT,"%.1f m",target.meters());
        String label=name+" #"+(target.index()+1)+" · "+(target.solid()?"占地 ":"")+distance;
        if(showOriginalNearby) {
            h.text(label,940,y+21,15,INK);
            return;
        }
        h.smallButton(930,y,282,32,label,()->{
            if(showOriginalNearby || shown!=nearbyObservation || app.circuitEditor()!=editor || !currentNearby(app,editor)
                    || !editor.focus(shown.contents(),target)) {
                app.notifyUser("候选位置已变化，请返回来源后重新查看附近内容。");
                return;
            }
            view.focus(List.of(shown.contents().observation(),target.center()));
            tab=0;
        });
    }
    private void manageReviews(HudRenderer h,FormulaGame app) {
        int capacity=app.circuitReviewCapacity();var records=app.circuitReviews();
        h.text("本图已保存 "+records.size()+" / "+capacity+" 次",930,291,18,MINT,true);
        h.wrapped("超过上限时，最早的试跑及其复测一并移除。",930,335,276,16,MUTED,25);
        h.smallButton(930,403,282,38,"保留上限："+capacity+" 次 · 切换",()->{
            int next=capacity==4?8:capacity==8?12:4;
            if(records.size()>next) h.confirm("降低试跑保留上限？","将删除本图最早的 "+(records.size()-next)+" 条试跑记录及其已存复测。",()->app.changeCircuitReviewCapacity(next));
            else app.changeCircuitReviewCapacity(next);
        });
        if(!records.isEmpty()) {
            var current=records.get(Math.max(0,Math.min(app.circuitReviewIndex(),records.size()-1)));
            h.smallButton(930,457,282,38,"删除已存第 "+(records.indexOf(current)+1)+" 次记录",()->h.confirm("删除本次试跑记录？",
                    "将删除本次试跑记录及其 "+current.retests().size()+" 条已存复测。",()->app.deleteCircuitReview(current)));
        }
        boolean pending=app.circuitReviewPending();
        if(pending) h.smallButton(930,511,282,38,"删除待保存的试跑记录",()->h.confirm("删除待存试跑记录？","只删除本次运行中未保存的观测。\n已存试跑、地图、版本历史和资格保留。",app::discardPendingCircuitReview));
        if(!records.isEmpty() || pending) h.smallButton(930,pending?557:511,282,38,"清空本图试跑记录",()->h.confirm("清空本图试跑记录？","将删除本图已保存和待保存的试跑记录及相关复测。",app::clearCircuitReviews));
        h.smallButton(930,pending?608:584,282,38,"返回试跑观测",()->tab=2);
        h.text(app.circuitReviewPending()?"另有一次待保存的试跑记录":"容量设置会随地图一起保存",930,657,14,MUTED);
    }
    private String selected(CircuitEditor editor,CircuitEditor.Tool tool,String text) { return (editor.tool()==tool?"● ":"")+text; }
    private void drawBoard(Graphics2D graphics,CircuitEditor editor,FormulaGame app) {
        var g=(Graphics2D)graphics.create();g.clip(CircuitEditorView.BOARD);
        var original=originalNearbyOpen() && currentNearby(app,editor)?nearbyObservation.original():null;
        var document=original==null?editor.document():original.document();
        g.setColor(GRID);g.setStroke(BOARD_LINE);
        int left=Math.round(view.x(-280)),right=Math.round(view.x(280));
        int lower=Math.round(view.y(-280)),upper=Math.round(view.y(280));
        for(int i=-280;i<=280;i+=20) {
            int x=Math.round(view.x(i)),y=Math.round(view.y(i));
            g.drawLine(x,lower,x,upper);g.drawLine(left,y,right,y);
        }
        var commission=app.circuitCommission();if(commission!=null && original==null) { var zone=commission.zone();int x=Math.round(Math.min(view.x(zone.minX()),view.x(zone.maxX()))),y=Math.round(Math.min(view.y(zone.minZ()),view.y(zone.maxZ()))),width=Math.round(Math.abs(view.x(zone.maxX())-view.x(zone.minX()))),height=Math.round(Math.abs(view.y(zone.maxZ())-view.y(zone.minZ())));g.setColor(COMMISSION_FILL);g.fillRect(x,y,width,height);g.setColor(GOLD);g.setStroke(BOARD_DETAIL_LINE);g.drawRect(x,y,width,height); }
        drawRoad(g,original==null?editor.roadPreview():original.contents().road());
        g.setStroke(BOARD_LINE);g.setFont(ANCHOR_FONT);
        int activeAnchor=original==null?editor.selectedAnchor():-1;
        for(int i=0;i<document.anchors().size();i++) {
            var point=document.anchors().get(i);int x=Math.round(view.x(point.x())),y=Math.round(view.y(point.z()));
            g.setColor(original==null && editor.isAnchorSelected(i)?GOLD:i==0?MINT:INK);g.fillOval(x-5,y-5,10,10);
            if(i==activeAnchor) {g.setColor(MINT);g.drawOval(x-9,y-9,18,18);}
            g.drawString(ANCHOR_LABELS[i],x+7,y-7);
        }
        sceneryDrawing.draw(g,document.scene().items(),view.projection(),original==null?editor:null);
        if(details && tab==1 && !editor.dragging()) {
            var issues=editor.inspection().issues();
            if(!issues.isEmpty()) {issueIndex=Math.min(issueIndex,issues.size()-1);drawLocations(g,issues.get(issueIndex).locations(),GOLD);}
        }
        if(details && tab==2) {
            var review=app.circuitReview();
            var trace=visibleReviewTrail(review,document);
            if(review!=null && !matchesTrailReview(review,document)) {
                g.setColor(GOLD);g.drawString("当前草稿已修改，原轨迹隐藏",58,190);
            } else if(review!=null) {
                drawReviewTrail(g,trace);
                g.setColor(INK);
                String label=review.trails().isEmpty()?"本次无轨迹记录":trace.isEmpty()?"点车辆行查看轨迹"
                        :"车 "+(trailCar+1)+" · "+trace.size()+" 个采样点 · 圆起点 / 方终点";
                g.drawString(label,58,190);
                if(!review.markers().isEmpty()) {
                    markerIndex=Math.min(markerIndex,review.markers().size()-1);
                    drawLocations(g,List.of(review.markers().get(markerIndex).location()),GOLD);
                }
            }
        }
        if(details && tab==6) revisionHud.draw(g,app,editor,view);
        boolean nearbyVisible=details && (tab==NEARBY_TAB || tab==0) && currentNearby(app,editor);
        if(nearbyVisible) {
            var area=original==null?nearbyObservation.contents():original.contents();
            if(tab==NEARBY_TAB) {
                drawNearbyRoad(g,area);
                for(var target:area.routePoints()) drawLocations(g,List.of(target.center()),MINT);
                for(var target:area.scenery()) drawLocations(g,List.of(target.center()),INK);
            }
            drawLocations(g,List.of(area.observation()),GOLD);
            String label;
            if(nearbyObservation.feedback()!=null) label=original==null?"当前草稿 · 玩家意见位置":"反馈原版只读 · 玩家意见位置";
            else label=original!=null?"原版只读 · 原观测位置":nearbyObservation.revised()?"当前草稿 · 旧观测坐标":"原观测位置";
            g.setColor(INK);g.drawString(label,58,190);
        }
        var feedback=original==null && !(nearbyVisible && nearbyObservation.feedback()!=null)?feedbackHud.overlay(app,document):null;if(feedback!=null) {drawLocations(g,List.of(feedback.location()),MINT);g.setColor(INK);g.drawString(feedbackHud.overlayLabel(),58,190);}
        g.dispose();
    }

    private void drawRoad(Graphics2D g,TrackLayout track) {
        if(track==null) {roadDrawing=null;return;}
        var projection=view.projection();
        var drawing=roadDrawing;
        if(drawing==null || drawing.geometry().track()!=track || drawing.projection()!=projection) {
            var geometry=drawing!=null && drawing.geometry().track()==track?drawing.geometry():roadGeometry(track);
            drawing=roadDrawing=projectRoad(geometry,projection);
        }
        var original=g.getTransform();
        try {
            g.transform(drawing.screenTransform());
            g.setColor(ROAD_EDGE);g.fill(drawing.geometry().edge());
            g.setColor(ROAD_SURFACE);g.fill(drawing.geometry().surface());
        } finally {
            g.setTransform(original);
        }
        g.setColor(MINT);g.setStroke(START_LINE);
        g.drawLine(drawing.startLeft().x(),drawing.startLeft().y(),drawing.startRight().x(),drawing.startRight().y());
        g.drawLine(drawing.start().x(),drawing.start().y(),drawing.heading().x(),drawing.heading().y());
        g.setColor(TIMING_GATE);
        for(var gate:drawing.gates()) g.fillRect(gate.x()-2,gate.y()-2,4,4);
    }

    private static RoadGeometry roadGeometry(TrackLayout track) {
        var path=TrackMapView.path(track,ROAD_METRES);
        var edge=new BasicStroke(track.width+2,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND).createStrokedShape(path);
        var surface=new BasicStroke(track.width,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND).createStrokedShape(path);
        var sample=track.sample(0);
        var offset=sample.right().mult(track.width*.5f);
        var gates=new Vector3f[LapProgress.RACE_GATES-1];
        for(int i=1;i<=gates.length;i++)
            gates[i-1]=track.sample(i*track.length/LapProgress.RACE_GATES).position();
        return new RoadGeometry(track,edge,surface,sample.position().subtract(offset),sample.position().add(offset),
                sample.position(),sample.position().add(sample.tangent().mult(15)),gates);
    }

    private static RoadDrawing projectRoad(RoadGeometry geometry,TrackMapView.Transform projection) {
        // The stroked path uses north-up metres. Gate markers are still drawn in pixels.
        var transform=new AffineTransform(projection.scale(),0,0,projection.scale(),projection.x(0),projection.y(0));
        var gates=new ScreenPoint[geometry.gates().length];
        for(int i=0;i<gates.length;i++) gates[i]=new ScreenPoint(geometry.gates()[i],projection);
        return new RoadDrawing(geometry,projection,transform,new ScreenPoint(geometry.startLeft(),projection),
                new ScreenPoint(geometry.startRight(),projection),new ScreenPoint(geometry.start(),projection),
                new ScreenPoint(geometry.heading(),projection),gates);
    }

    private void drawNearbyRoad(Graphics2D g,CircuitObservationNearby area) {
        var candidate=selectedNearbyRoad(area);
        var road=candidate==null?area.roadReadout():candidate.readout();
        if(road==null) return;
        var source=area.observation();
        var point=road.center();
        int x=Math.round(view.x(point.x())),y=Math.round(view.y(point.z()));
        if(road.centerlineMeters()>=.01f) {
            g.setColor(GOLD);
            g.setStroke(OBSERVATION_ROAD_LINE);
            g.drawLine(Math.round(view.x(source.x())),Math.round(view.y(source.z())),x,y);
            g.setStroke(new BasicStroke(2));
            g.drawRect(x-4,y-4,8,8);
        }
        if(candidate==null || area.roadCandidates().size()<2) return;
        float dx=candidate.directionX(),dy=-candidate.directionZ();
        int tipX=Math.round(x+18*dx),tipY=Math.round(y+18*dy);
        g.setColor(MINT);
        g.setStroke(new BasicStroke(2));
        g.drawLine(x,y,tipX,tipY);
        g.drawLine(tipX,tipY,Math.round(tipX-7*dx+4*dy),Math.round(tipY-7*dy-4*dx));
        g.drawLine(tipX,tipY,Math.round(tipX-7*dx-4*dy),Math.round(tipY-7*dy+4*dx));
    }

    private void selectTrail(CircuitDriveReview.Snapshot review,int car) {
        if(trailReview!=review) {
            trailDocument=null;
            decodedTrails.clear();
        }
        trailReview=review;
        trailCar=car;
        trailDrawing=null;
    }

    List<CircuitDriveTrail.Point> visibleReviewTrail(CircuitDriveReview.Snapshot review,CircuitDocument document) {
        if(!matchesTrailReview(review,document) || trailCar<0) return List.of();
        var points=decodedTrails.get(trailCar);
        if(points!=null) return points;
        for(var path:review.trails()) {
            if(path.car()!=trailCar) continue;
            points=path.points();
            decodedTrails.put(trailCar,points);
            return points;
        }
        return List.of();
    }

    private boolean matchesTrailReview(CircuitDriveReview.Snapshot review,CircuitDocument document) {
        if(trailReview!=review) selectTrail(review,review==null || review.trails().isEmpty()?-1:review.trails().get(0).car());
        if(review==null) return false;
        if(trailDocument!=document) {trailDocument=document;trailMatches=review.matches(document);}
        return trailMatches;
    }

    private void drawReviewTrail(Graphics2D g,List<CircuitDriveTrail.Point> trace) {
        if(trace.isEmpty()) return;
        var projection=view.projection();
        var drawing=trailDrawing;
        if(drawing==null || drawing.points()!=trace || drawing.projection()!=projection)
            drawing=trailDrawing=projectTrail(trace,projection);
        g.setColor(TRAIL_EDGE);
        g.setStroke(TRAIL_OUTLINE);
        g.draw(drawing.path());
        g.setColor(TRAIL_LINE);
        g.setStroke(TRAIL_STROKE);
        g.draw(drawing.path());
        for(var start:drawing.starts()) g.fillOval(start.x()-3,start.y()-3,7,7);
        var end=drawing.end();
        g.drawRect(end.x()-4,end.y()-4,8,8);
    }

    private static TrailDrawing projectTrail(List<CircuitDriveTrail.Point> trace,TrackMapView.Transform projection) {
        var path=new Path2D.Float();
        var starts=new ArrayList<ScreenPoint>();
        for(var point:trace) {
            float x=projection.x(point.x()),y=projection.y(point.z());
            if(point.joined()) path.lineTo(x,y);
            else {
                path.moveTo(x,y);
                starts.add(new ScreenPoint(Math.round(x),Math.round(y)));
            }
        }
        var last=trace.get(trace.size()-1);
        var end=new ScreenPoint(Math.round(projection.x(last.x())),Math.round(projection.y(last.z())));
        return new TrailDrawing(trace,projection,path,List.copyOf(starts),end);
    }

    private void drawLocations(Graphics2D g,List<CircuitInspector.Location> locations,Color color) {
        g.setColor(color);g.setStroke(BOARD_DETAIL_LINE);for(var point:locations) { int x=Math.round(view.x(point.x())),y=Math.round(view.y(point.z()));g.drawOval(x-10,y-10,20,20);g.drawLine(x-14,y,x+14,y);g.drawLine(x,y-14,x,y+14); }
        if(locations.size()==2) g.drawLine(Math.round(view.x(locations.get(0).x())),Math.round(view.y(locations.get(0).z())),Math.round(view.x(locations.get(1).x())),Math.round(view.y(locations.get(1).z())));
    }
}
