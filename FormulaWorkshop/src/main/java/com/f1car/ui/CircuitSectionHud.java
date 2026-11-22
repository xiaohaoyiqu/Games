package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.CircuitDocument;
import com.f1car.model.CircuitDriveReview;
import com.f1car.model.CircuitEditor;
import com.f1car.model.CircuitFeedback;
import com.f1car.model.CircuitFeedbackTrial;
import com.f1car.model.CircuitInspector;
import com.f1car.model.CircuitObservationPractice;
import com.f1car.model.CircuitObservationRetest;
import com.f1car.model.CircuitSectionPractice;
import com.f1car.model.TrackLayout;
import com.f1car.persistence.CircuitStore;
import com.jme3.input.KeyInput;
import com.jme3.math.Vector3f;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Editor-only preview of the draft that will be driven. */
final class CircuitSectionHud {
    private static final Rectangle MAP=new Rectangle(80,201,1120,343);
    private static final Color PANEL=new Color(0x13242e),ROAD=new Color(0x52717b),INK=new Color(0xeaf4ef);
    private static final Color START=new Color(0x63e6bc),FINISH=new Color(0xffb267);
    private static final Color APPROACH=new Color(0x8dc5fd),ORIGINAL=new Color(0x92a8ac);
    private static final BasicStroke BASE=new BasicStroke(6,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke SELECTED=new BasicStroke(5,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke RUN_UP=new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{5,5},0);
    private static final BasicStroke HISTORIC_ROAD=new BasicStroke(2,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{7,5},0);
    private CircuitDocument reviewed;
    private TrackLayout track;
    private TrackMapView.Transform map;
    private Path2D road;
    private CircuitSectionPractice.Range range;
    private CircuitSectionPractice.Start start;
    private record MapPreview(CircuitSectionPractice practice,Path2D selected,Path2D approach,Vector3f spawn,boolean blocked) {}
    private record SplitPicker(Path2D original,MapPreview candidate) {}
    private record PresetPreview(CircuitStore.PracticeSlot source,MapPreview candidate,Path2D original) {}
    private record ObservationPreview(CircuitDriveReview.Snapshot source,CircuitDriveReview.Marker marker,int markerIndex,
                                      CircuitFeedback.LocationSource feedback,
                                      List<CircuitObservationPractice.Candidate> choices,int index,MapPreview map) {
        CircuitObservationPractice.Candidate selected() {return choices.get(index);}
        CircuitInspector.Location location() {return feedback==null?marker.location():feedback.note().location();}
    }

    private record ObservationOrigin(boolean revised,CircuitDocument archivedDraft,Path2D road,boolean nearby) {}
    private MapPreview preview;
    private SplitPicker picker;
    private PresetPreview presetPreview;
    private ObservationPreview observationPreview;
    private CircuitDriveReview.Marker observationMarker;
    private CircuitObservationRetest.Source observationRetestSource;
    private CircuitFeedback.LocationSource feedbackPractice;
    private CircuitFeedbackTrial savedFeedbackTrial;
    private FormulaGame.FeedbackDiscussionReturn feedbackDiscussion;
    private record RetestOrigin(CircuitObservationRetest value,boolean saved) {}
    private RetestOrigin retestOrigin;
    private ObservationOrigin observationOrigin;
    private CircuitStore.PracticeSlot carriedPreset;
    private int split;
    private String previewDriveKey;
    private List<CircuitStore.PracticeSlot> slots=List.of();
    private int slotNumber=1;
    private boolean managingPresets;

    boolean showing() {return reviewed!=null;}
    String pickerContext() {
        if(observationPreview!=null) return ":circuit-observation-preview";
        if(picker!=null) return ":circuit-split-picker";
        return presetPreview!=null?":circuit-preset-preview":"";
    }

    boolean open(FormulaGame app,CircuitEditor editor) {
        return open(app,editor,null);
    }

    boolean open(FormulaGame app,CircuitEditor editor,CircuitStore.PracticePreset fromResult) {
        return open(app,editor,fromResult,null);
    }

    private boolean open(FormulaGame app,CircuitEditor editor,CircuitStore.PracticePreset fromResult,TrackLayout.Bounds displayBounds) {
        editor.endPointer();
        var layout=editor.inspection().track();
        if(layout==null || fromResult!=null && !fromResult.matches(editor.document())) return false;
        reviewed=editor.document();
        previewDriveKey=reviewed.driveKey();
        track=layout;
        map=TrackMapView.fit(displayBounds==null?track.bounds():displayBounds,MAP,32);
        road=path(0,100);
        refreshSlots(app.circuits);
        managingPresets=false;
        picker=null;
        presetPreview=null;
        observationPreview=null;
        observationMarker=null;
        observationRetestSource=null;
        clearFeedbackPractice();
        retestOrigin=null;
        observationOrigin=null;
        carriedPreset=null;
        if(fromResult!=null) {
            slotNumber=1;
            for(int number=1;number<=CircuitStore.PRACTICE_SLOT_LIMIT;number++) {
                if(slot(number)==null) {
                    slotNumber=number;
                    break;
                }
            }
            applyPreset(fromResult);
            return true;
        }
        var first=slots.stream().filter(this::applies).findFirst().orElse(slots.isEmpty()?null:slots.get(0));
        slotNumber=first==null?1:first.number();
        if(applies(first)) applyPreset(first.preset());
        else {
            start=CircuitSectionPractice.Start.defaults(track);
            setRange(new CircuitSectionPractice.Range(0,25));
        }
        return true;
    }

    boolean openObservation(FormulaGame app,CircuitEditor editor,CircuitDriveReview.Snapshot source,int markerIndex,boolean revised) {
        return openObservation(app,editor,source,markerIndex,revised,false,null);
    }

    boolean openNearbyObservation(FormulaGame app,CircuitEditor editor,CircuitDriveReview.Snapshot source,int markerIndex,
                                  boolean revised,CircuitObservationPractice.Candidate preferred) {
        return openObservation(app,editor,source,markerIndex,revised,true,preferred);
    }

    private boolean openObservation(FormulaGame app,CircuitEditor editor,CircuitDriveReview.Snapshot source,int markerIndex,
                                    boolean revised,boolean nearby,CircuitObservationPractice.Candidate preferred) {
        if(app.screen!=FormulaGame.Screen.MAP_EDITOR || app.circuitEditor()!=editor || source==null || source!=app.circuitReview()
                || source.status()==CircuitDriveReview.Status.RUNNING || markerIndex<0 || markerIndex>=source.markers().size()) {
            app.notifyUser("试跑记录或对应草稿已变化，请重新选择观测。");
            return false;
        }
        var marker=source.markers().get(markerIndex);
        editor.endPointer();
        var draft=editor.document();
        boolean differentLayout=!source.matches(draft);
        if(revised!=differentLayout) {
            app.notifyUser("草稿版本已变化，请重新选择观测入口。");
            return false;
        }
        var layout=editor.inspection().track();
        if(layout==null) {
            app.notifyUser("先在检查面板调整路线，再预览观测附近的区间。");
            return false;
        }
        var choices=CircuitObservationPractice.around(layout,marker.location());
        int selected=preferred==null?0:choices.indexOf(preferred);
        if(selected<0) {
            app.notifyUser("候选路段已变化，请返回试跑记录后重新查看附近内容。");
            return false;
        }
        if(choices.isEmpty() && !revised && !nearby) {
            app.notifyUser("观测附近没有合适的道路，请在区间试驾里手动选段。");
            return false;
        }
        var original=revised?originalObservationDraft(app.circuits.find(draft.id()).orElse(null),source):null;
        var shown=new ObservationPreview(source,marker,markerIndex,null,choices,selected,null);
        return openLocationPreview(app,editor,layout,shown,original,revised,nearby);
    }

    boolean openFeedbackPractice(FormulaGame app,CircuitEditor editor,CircuitFeedback.LocationSource source,
                                 CircuitObservationPractice.Candidate preferred) {
        if(app.screen!=FormulaGame.Screen.MAP_EDITOR || app.online() || app.circuitEditor()!=editor) return false;
        editor.endPointer();
        var draft=editor.document();
        if(!app.checkCircuitFeedbackPractice(source,draft,null)) return false;
        var track=editor.inspection().track();
        if(track==null) {
            app.notifyUser("路线尚不能试驾，请先在检查面板调整路线点。");
            return false;
        }
        var choices=CircuitObservationPractice.around(track,source.note().location());
        int selected=preferred==null?0:choices.indexOf(preferred);
        if(selected<0) {
            app.notifyUser("候选路段已变化，请重新查看意见附近。");
            return false;
        }
        boolean revised=source.entry().alignment(draft)==CircuitFeedback.Alignment.REFERENCE;
        var original=revised?source.entry().feedback().source():null;
        var shown=new ObservationPreview(null,null,-1,source,choices,selected,null);
        return openLocationPreview(app,editor,track,shown,original,revised,true);
    }

    private boolean openLocationPreview(FormulaGame app,CircuitEditor editor,TrackLayout layout,ObservationPreview shown,
                                        CircuitDocument original,boolean revised,boolean nearby) {
        TrackLayout previousRoad=null;
        if(original!=null) {
            try {previousRoad=original.roadPreview();}
            catch(IllegalArgumentException invalid) {original=null;}
        }
        boolean manual=shown.choices().isEmpty();
        var initial=manual?new CircuitSectionPractice.Range(0,25):shown.selected().range();
        var preset=new CircuitStore.PracticePreset(editor.document().driveKey(),initial,CircuitSectionPractice.Start.defaults(layout));
        var displayBounds=revised || manual?observationBounds(layout,previousRoad,shown.location()):null;
        if(!open(app,editor,preset,displayBounds)) return false;
        observationOrigin=new ObservationOrigin(revised,original,previousRoad==null?null:TrackMapView.path(previousRoad,map),nearby);
        observationPreview=new ObservationPreview(shown.source(),shown.marker(),shown.markerIndex(),shown.feedback(),
                shown.choices(),shown.index(),manual?null:preview);
        return true;
    }

    boolean openRetest(FormulaGame app,CircuitEditor editor,CircuitObservationRetest retest,boolean saved) {
        var result=retest.result();
        var source=retest.source();
        var draft=editor.document();
        if(!result.driveKey().equals(draft.driveKey())) {
            app.notifyUser("复测对应的草稿已变化，请从原观测按当前草稿重新选段。");
            return false;
        }
        var layout=editor.inspection().track();
        if(layout==null || result.start().runUpMeters()>CircuitSectionPractice.Start.maximumRunUp(layout)) {
            app.notifyUser("当前路线无法沿用这次发车条件，请重新选择区间。");
            return false;
        }
        boolean revised=!source.snapshot().matches(draft);
        var original=revised?originalObservationDraft(app.circuits.find(draft.id()).orElse(null),source.snapshot()):null;
        TrackLayout previousRoad=null;
        if(original!=null) {
            try {previousRoad=original.roadPreview();}
            catch(IllegalArgumentException invalid) {original=null;}
        }
        var preset=new CircuitStore.PracticePreset(result.driveKey(),result.range(),result.start(),result.split()==0?null:result.split());
        if(!open(app,editor,preset,observationBounds(layout,previousRoad,source.marker().location()))) return false;
        observationMarker=source.marker();
        observationRetestSource=source;
        retestOrigin=new RetestOrigin(retest,saved);
        observationOrigin=new ObservationOrigin(revised,original,previousRoad==null?null:TrackMapView.path(previousRoad,map),false);
        app.notifyUser("已沿用区间和发车条件；请核对预览。开始试驾使用当前物理和控制配置。");
        return true;
    }

    boolean openSavedFeedbackTrial(FormulaGame app,CircuitEditor editor,CircuitFeedback.LocationSource source,CircuitFeedbackTrial saved,
                                   FormulaGame.FeedbackDiscussionReturn discussion) {
        var result=saved.result();
        var layout=editor.inspection().track();
        if(layout==null || result.start().runUpMeters()>CircuitSectionPractice.Start.maximumRunUp(layout)) {
            app.notifyUser("当前路线无法沿用这次发车条件，请返回意见重新选段。");
            return false;
        }
        boolean revised=source.entry().alignment(editor.document())==CircuitFeedback.Alignment.REFERENCE;
        var original=revised?source.entry().feedback().source():null;
        TrackLayout previousRoad=null;
        if(original!=null) {
            try {previousRoad=original.roadPreview();}
            catch(IllegalArgumentException invalid) {original=null;}
        }
        var preset=new CircuitStore.PracticePreset(result.driveKey(),result.range(),result.start(),result.split()==0?null:result.split());
        if(!open(app,editor,preset,observationBounds(layout,previousRoad,source.note().location()))) return false;
        feedbackPractice=source;
        savedFeedbackTrial=saved;
        feedbackDiscussion=discussion;
        observationOrigin=new ObservationOrigin(revised,original,previousRoad==null?null:TrackMapView.path(previousRoad,map),false);
        return true;
    }

    private void clearFeedbackPractice() {
        feedbackPractice=null;
        savedFeedbackTrial=null;
        feedbackDiscussion=null;
    }

    private static CircuitDocument originalObservationDraft(CircuitStore.Saved saved,CircuitDriveReview.Snapshot source) {
        return saved==null?null:saved.drivingVersion(source.documentKey());
    }

    private static TrackLayout.Bounds observationBounds(TrackLayout current,TrackLayout previous,CircuitInspector.Location marker) {
        var next=current.bounds();
        var old=previous==null?next:previous.bounds();
        return new TrackLayout.Bounds(Math.min(marker.x(),Math.min(next.minX(),old.minX())),
                Math.max(marker.x(),Math.max(next.maxX(),old.maxX())),Math.min(marker.z(),Math.min(next.minZ(),old.minZ())),
                Math.max(marker.z(),Math.max(next.maxZ(),old.maxZ())));
    }

    boolean back() {
        if(observationPreview!=null) return close();
        if(picker!=null) {picker=null;return true;}
        if(presetPreview!=null) {presetPreview=null;return true;}
        if(managingPresets) {managingPresets=false;return true;}
        return close();
    }

    boolean close() {
        if(!showing()) return false;
        reviewed=null;
        track=null;
        map=null;
        road=null;
        range=null;
        start=null;
        preview=null;
        picker=null;
        presetPreview=null;
        observationPreview=null;
        observationMarker=null;
        observationRetestSource=null;
        clearFeedbackPractice();
        retestOrigin=null;
        observationOrigin=null;
        carriedPreset=null;
        split=0;
        previewDriveKey=null;
        slots=List.of();
        slotNumber=1;
        managingPresets=false;
        return true;
    }

    void render(HudRenderer h,Graphics2D g,FormulaGame app,CircuitEditor editor,String overrideBackLabel) {
        h.clearButtons();
        h.panel(24,26,1232,745,PANEL);
        if(observationPreview!=null) {renderObservationPreview(h,g,app,editor);return;}
        if(picker!=null) {renderSplitPicker(h,g,app,editor);return;}
        if(presetPreview!=null) {renderPresetPreview(h,g,app,editor);return;}
        if(managingPresets) {renderPresets(h,app,editor);return;}
        h.text("区间试驾",60,103,30,INK,true);
        String backLabel=overrideBackLabel!=null?overrideBackLabel:feedbackPractice!=null?"返回意见附近"
                :observationOrigin!=null && observationOrigin.nearby()?"返回观测附近":"返回编辑画布";
        h.smallButton(980,58,234,44,backLabel,this::close);
        String editing=split==0?"按圈长百分比选择起终点。终点小于起点时，沿赛道前进并跨过起终线。"
                :"原区间 "+rangeLabel(range)+" · 使用第 "+split+" 段原计时门";
        h.text(editing,60,148,17,INK);
        var selectedSlot=slot(slotNumber);
        h.text("保存到第 "+slotNumber+" 槽 · "+(selectedSlot==null?"空槽":selectedSlot.name()),60,179,16,INK);
        if(observationOrigin!=null && observationOrigin.revised()) {
            String label=feedbackPractice==null?"旧观测":"旧版意见";
            h.text(label+(observationOrigin.road()==null?" · 原道路图不可用":" · 灰色虚线为原道路"),665,179,16,INK);
        }
        drawMap(h,g,preview,null);
        if(observationMarker!=null) drawObservation(h,g,observationMarker);
        if(feedbackPractice!=null) drawFeedbackLocation(h,g,feedbackPractice);
        h.text(split==0?String.format(Locale.ROOT,"%s · 区间 %.0f m",rangeLabel(range),range.length(track))
                :practiceLabel(preview.practice()),60,577,18,INK);
        if(!editor.document().equals(reviewed)) {
            h.text("当前草稿已变化，请返回并重新打开区间预览。",60,689,17,FINISH);
            return;
        }
        if(savedFeedbackTrial!=null)
            h.text("已从作者试驾带入区间和发车设定。启动使用当前物理和控制配置。",60,708,15,INK);
        h.smallButton(278,58,225,44,"导出区间草稿",()->{
            if(isCurrent(app,reviewed)) h.chooseCircuitPracticeExport(reviewed,previewSlot());
        });
        h.smallButton(525,58,205,44,"练习预设",()->{refreshSlots(app.circuits);managingPresets=true;});
        boolean presetApplies=applies(selectedSlot);
        if(presetApplies) h.smallButton(750,58,210,44,"读取已存区间",()->readPreset(app));
        if(carriedPreset!=null) h.text("已按当前路线重算旧预设，尚未保存。",612,577,16,FINISH);
        if(selectedSlot!=null) {
            if(carriedPreset==null) {
                String saved=presetApplies?savedSummary(selectedSlot.preset()):"旧预设需按当前路线重新核对。";
                h.text(saved,612,577,16,presetApplies?INK:FINISH);
            }
            h.smallButton(1114,595,100,42,"清除预设",()->confirmClearPreset(h,app));
        }
        if(!preview.blocked()) h.smallButton(900,595,selectedSlot==null?314:200,42,selectedSlot==null?"保存草稿和区间":"更新草稿和区间",()->savePreset(app));
        h.smallButton(60,595,330,42,"助跑距离 · "+start.runUpMeters()+" m",()->editStart(h,app,true));
        h.smallButton(410,595,330,42,"放行速度 · "+start.speedKph()+" km/h",()->editStart(h,app,false));
        if(split==0) {
            h.smallButton(60,648,330,44,"设起点 · "+range.from()+"%",()->editBoundary(h,app,true));
            h.smallButton(410,648,330,44,"设终点 · "+range.to()+"%",()->editBoundary(h,app,false));
        } else h.smallButton(60,648,330,44,"改练原区间",()->setRange(range));
        if(range.splitCount()>1) h.smallButton(750,648,136,44,"选择分段",()->openSplitPicker(app));
        if(preview.blocked()) {
            String advice=split==0?"请调整助跑距离或起点。":"请调整助跑距离或改选分段。";
            h.text("发车点被场景占用，"+advice,60,732,16,FINISH);
        }
        else {
            h.smallButton(900,648,314,44,"开始试驾",()->startPractice(app));
            String entry=split==0?"区间起点":"分段起点";
            h.text("虚线为助跑路段。倒计时结束按设置速度放行，过"+entry+"才计时。",60,732,15,INK);
        }
    }

    private void startPractice(FormulaGame app) {
        if(!isCurrent(app,reviewed)) return;
        if(retestOrigin!=null) {
            app.startCircuitObservationRetest(reviewed,range,start,split,retestOrigin.value(),retestOrigin.saved());
            return;
        }
        if(feedbackPractice!=null) app.startCircuitFeedbackSection(reviewed,range,start,split,feedbackPractice,feedbackDiscussion,savedFeedbackTrial);
        else app.startCircuitSection(reviewed,range,start,split,observationRetestSource);
    }

    private void drawMap(HudRenderer h,Graphics2D g,MapPreview view,Path2D original) {
        h.panel(MAP.x,MAP.y,MAP.width,MAP.height,new Color(0x1b3440));
        var stroke=g.getStroke();
        g.setStroke(BASE);
        g.setColor(ROAD);
        g.draw(road);
        if(original!=null) {
            var clip=g.getClip();
            g.clip(MAP);
            g.setColor(ORIGINAL);
            g.draw(original);
            g.setClip(clip);
        }
        if(observationOrigin!=null && observationOrigin.road()!=null && presetPreview==null) {
            g.setColor(ORIGINAL);
            g.setStroke(HISTORIC_ROAD);
            g.draw(observationOrigin.road());
        }
        if(view==null) {
            g.setStroke(stroke);
            return;
        }
        g.setStroke(SELECTED);
        g.setColor(START);
        g.draw(view.selected());
        g.setStroke(RUN_UP);
        g.setColor(APPROACH);
        g.draw(view.approach());
        g.setStroke(stroke);
        var practice=view.practice();
        if(practice.hasInteriorStartLine()) mark(h,g,track.sample(0).position(),"赛道起终线",INK,10,-15);
        mark(h,g,track.sample(practice.startDistance()).position(),"起",START,-31,-10);
        mark(h,g,track.sample(practice.finishDistance()).position(),"终",FINISH,10,20);
        mark(h,g,view.spawn(),"车",view.blocked()?FINISH:APPROACH,-28,22);
    }

    private void openSplitPicker(FormulaGame app) {
        if(!isCurrent(app,reviewed) || range.splitCount()<2) return;
        int selected=split==0?1:split;
        var original=split==0?preview.selected():path(range.from(),range.endProgressPercent());
        picker=new SplitPicker(original,split==0?buildPreview(selected):preview);
    }

    private void selectSplitCandidate(FormulaGame app,int number) {
        if(picker==null || !isCurrent(app,reviewed) || number==picker.candidate().practice().focusedSplit()) return;
        picker=new SplitPicker(picker.original(),buildPreview(number));
    }

    private void applySplitCandidate(FormulaGame app) {
        if(picker==null || !isCurrent(app,reviewed)) return;
        preview=picker.candidate();
        split=preview.practice().focusedSplit();
        picker=null;
    }

    void pickerKey(FormulaGame app,int key) {
        if(observationPreview!=null) {
            switch(key) {
                case KeyInput.KEY_ESCAPE -> back();
                case KeyInput.KEY_LEFT -> selectObservation(app,-1);
                case KeyInput.KEY_RIGHT -> selectObservation(app,1);
                case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> useObservation(app);
                default -> {}
            }
            return;
        }
        if(presetPreview!=null) {
            switch(key) {
                case KeyInput.KEY_ESCAPE -> back();
                case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> usePresetPreview(app);
                default -> {}
            }
            return;
        }
        if(picker==null) return;
        switch(key) {
            case KeyInput.KEY_ESCAPE -> picker=null;
            case KeyInput.KEY_LEFT,KeyInput.KEY_RIGHT -> {
                int current=picker.candidate().practice().focusedSplit();
                int next=current+(key==KeyInput.KEY_LEFT?-1:1);
                selectSplitCandidate(app,Math.max(1,Math.min(range.splitCount(),next)));
            }
            case KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER -> applySplitCandidate(app);
            default -> {}
        }
    }

    private boolean currentObservation(FormulaGame app) {
        if(observationPreview==null || !isCurrent(app,reviewed)) return false;
        if(!observationSourceCurrent(app)) {
            app.notifyUser(observationPreview.feedback()==null?"试跑记录已变化或原地图版本已更新，请返回并重新选择观测。"
                    :"反馈或地图对应已变化，请返回意见重新预览。");
            return false;
        }
        return true;
    }

    private boolean observationSourceCurrent(FormulaGame app) {
        var feedback=observationPreview.feedback();
        if(feedback!=null) return app.screen==FormulaGame.Screen.MAP_EDITOR
                && feedback.matches(app.circuitFeedback.find(feedback.entry().feedback().id()));
        if(app.screen!=FormulaGame.Screen.MAP_EDITOR || app.circuitReview()!=observationPreview.source()) return false;
        var original=observationOrigin.archivedDraft();
        if(original==null) return true;
        var saved=app.circuits.find(reviewed.id()).orElse(null);
        return saved!=null && (original.equals(saved.document())
                || saved.history().stream().anyMatch(revision->original.equals(revision.document())));
    }

    private void selectObservation(FormulaGame app,int delta) {
        if(!currentObservation(app)) return;
        var shown=observationPreview;
        if(shown.choices().isEmpty()) return;
        int next=Math.floorMod(shown.index()+delta,shown.choices().size());
        if(next!=shown.index()) observationPreview=new ObservationPreview(shown.source(),shown.marker(),shown.markerIndex(),shown.feedback(),shown.choices(),next,
                buildPreview(shown.choices().get(next).range(),start,0));
    }

    private void useObservation(FormulaGame app) {
        if(!currentObservation(app)) return;
        var shown=observationPreview;
        var source=shown.feedback()==null?app.circuitObservationSource(shown.source(),shown.markerIndex()):null;
        if(shown.feedback()==null && source==null) {
            app.notifyUser("原观测无法定位，请重新选择试跑记录。");
            return;
        }
        if(shown.map()!=null) {
            preview=shown.map();
            range=shown.selected().range();
        } else app.notifyUser("请按当前路线手动设置区间起点和终点。");
        observationMarker=shown.marker();
        observationRetestSource=source;
        feedbackPractice=shown.feedback();
        observationPreview=null;
    }

    private void renderObservationPreview(HudRenderer h,Graphics2D g,FormulaGame app,CircuitEditor editor) {
        var shown=observationPreview;
        var marker=shown.marker();
        var feedback=shown.feedback();
        boolean revised=observationOrigin.revised();
        if(feedback==null) {
            h.text(revised?"按当前草稿预览旧观测":"预览观测附近的区间",60,103,30,INK,true);
            h.text(String.format(Locale.ROOT,"%s · 车 %d · %.1f 秒",marker.event().title,marker.car()+1,marker.time()),60,148,17,INK);
        } else {
            h.text("按意见位置预览作者试驾",60,103,30,INK,true);
            var point=feedback.note().location();
            h.text(String.format(Locale.ROOT,"%s · 意见坐标 X %.1f / Z %.1f",feedback.note().kind().title,point.x(),point.z()),60,148,17,INK);
        }
        String hint="请核对原观测和候选路线。选用后仍可调整起终点及发车条件。";
        if(revised) hint=observationOrigin.road()==null?"原道路图不可用，地图保留旧观测坐标和当前道路。"
                :"灰色虚线为原道路，蓝灰线为当前道路，绿线为当前候选区间。";
        if(feedback!=null && (!revised || observationOrigin.road()==null)) hint="按当前草稿试驾。选用候选后可调整起终点和发车条件。";
        h.text(hint,60,179,16,INK);
        drawMap(h,g,shown.map(),null);
        if(feedback==null) drawObservation(h,g,marker);
        else drawFeedbackLocation(h,g,feedback);
        if(shown.map()!=null) {
            var selected=shown.selected();
            mark(h,g,track.sample(selected.distance()).position(),"候选路线",APPROACH,10,-12);
            h.text(String.format(Locale.ROOT,"候选 %d / %d · %s · 路线中心距观测 %.1f m",shown.index()+1,shown.choices().size(),
                    rangeLabel(selected.range()),selected.separation()),80,577,18,INK,true);
        } else h.text(feedback==null?"观测附近没有当前道路候选，可手动设置复测区间。":"意见附近没有道路候选，可手动设置试驾区间。",80,577,18,FINISH,true);
        String backLabel=feedback!=null?"返回意见附近  Esc":observationOrigin.nearby()?"返回观测附近  Esc":"返回试跑记录  Esc";
        h.smallButton(80,685,284,44,backLabel,this::back);
        if(!editor.document().equals(reviewed) || !observationSourceCurrent(app)) {
            h.text("记录或地图版本已变化，请返回后重新选择观测。",80,658,17,FINISH);
            return;
        }
        if(shown.choices().size()>1) {
            h.smallButton(80,596,160,43,"上一处  ←",()->selectObservation(app,-1));
            h.smallButton(254,596,160,43,"下一处  →",()->selectObservation(app,1));
        }
        if(shown.map()!=null && shown.map().blocked()) h.text("发车点当前被占用，可选用区间后调整助跑。",80,667,16,FINISH);
        h.smallButton(921,685,280,44,shown.map()==null?"手动设置区间  Enter":"选用此区间  Enter",()->useObservation(app));
    }

    private void drawObservation(HudRenderer h,Graphics2D g,CircuitDriveReview.Marker marker) {
        mark(h,g,new Vector3f(marker.x(),0,marker.z()),observationOrigin.revised()?"旧版观测":"原观测",FINISH,10,19);
    }

    private void drawFeedbackLocation(HudRenderer h,Graphics2D g,CircuitFeedback.LocationSource source) {
        var location=source.note().location();
        mark(h,g,new Vector3f(location.x(),0,location.z()),"玩家意见",FINISH,10,19);
    }

    private void renderSplitPicker(HudRenderer h,Graphics2D g,FormulaGame app,CircuitEditor editor) {
        h.text("选择练习分段",60,103,30,INK,true);
        h.text("原区间 "+rangeLabel(range),60,148,17,INK);
        h.text("浅灰线是原区间，绿色线是候选分段。",60,179,16,INK);
        var candidate=picker.candidate();
        drawMap(h,g,candidate,picker.original());
        h.text(practiceLabel(candidate.practice())+" · "+startLabel(start),80,578,18,INK);
        h.smallButton(80,685,284,44,"返回区间预览  Esc",this::back);
        if(!editor.document().equals(reviewed)) {
            h.text("当前草稿已变化，请返回并重新打开区间预览。",80,618,17,FINISH);
            return;
        }
        int selected=candidate.practice().focusedSplit();
        int count=range.splitCount();
        for(int number=1;number<=count;number++) {
            int choice=number,x=80+(number-1)*59;
            h.smallButton(x,596,49,39,Integer.toString(number),()->selectSplitCandidate(app,choice));
            if(number==selected) h.panel(x+5,631,39,3,START);
        }
        if(candidate.blocked()) h.text("发车点被占用。可选用此分段，再调整助跑。",80,661,16,FINISH);
        h.text("← → 选段",980,662,16,INK);
        h.smallButton(921,685,280,44,"选用第 "+selected+" 段  Enter",()->applySplitCandidate(app));
    }

    private void renderPresets(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        h.text("练习预设",60,103,30,INK,true);
        h.smallButton(980,58,234,44,"返回区间预览",()->managingPresets=false);
        if(!editor.document().equals(reviewed)) {
            h.text("当前草稿已变化，请重新打开区间预览。",60,153,17,FINISH);
            return;
        }
        h.smallButton(733,58,227,44,"重读地图资料库",()->{
            if(app.reloadCircuitLibrary(reviewed)) refreshSlots(app.circuits);
        });
        if(app.circuits.needsRefresh()) {
            h.smallButton(240,58,222,44,"查看保存差异",()->{
                if(isCurrent(app,reviewed)) h.reviewCircuitDraftComparison(reviewed,previewSlot());
            });
            if(!preview.blocked()) h.smallButton(484,58,227,44,"另存地图与区间",()->copyPreview(h,app));
        }
        for(int number=1;number<=CircuitStore.PRACTICE_SLOT_LIMIT;number++) {
            int target=number,y=154+(number-1)*134;
            var entry=slot(number);
            boolean currentRoute=applies(entry);
            h.panel(60,y,1154,107,new Color(0x1b3440));
            String title="第 "+number+" 槽 · "+(entry==null?"空槽":entry.name())+(slotNumber==number?" · 当前保存目标":"");
            h.text(title,82,y+33,20,INK,true);
            if(entry!=null) h.text((currentRoute?"":"旧路线 · ")+savedSummary(entry.preset()),82,y+73,16,currentRoute?INK:FINISH);
            if(currentRoute) h.smallButton(665,y+51,161,43,"读取到预览",()->selectSlot(app,target,true));
            else if(entry!=null) h.smallButton(665,y+51,161,43,"按当前路线预览",()->openPresetPreview(app,entry));
            h.smallButton(843,y+51,180,43,"设为保存目标",()->selectSlot(app,target,false));
            if(entry!=null) h.smallButton(1044,y+51,144,43,"重命名",()->renamePreset(h,app,entry));
        }
    }

    private void openPresetPreview(FormulaGame app,CircuitStore.PracticeSlot expected) {
        if(!isCurrent(app,reviewed)) return;
        var saved=app.circuits.find(reviewed.id()).orElse(null);
        slots=saved==null?List.of():saved.slots();
        if(!expected.equals(slot(expected.number()))) {
            app.notifyUser("这份练习预设已变化，请从列表重新选择。");
            return;
        }
        var source=expected.preset();
        int runUp=Math.min(source.start().runUpMeters(),CircuitSectionPractice.Start.maximumRunUp(track));
        var candidate=buildPreview(source.range(),new CircuitSectionPractice.Start(runUp,source.start().speedKph()),source.focusedSplit());
        var originalDraft=source.matches(saved.document())?saved.document():saved.history().stream()
                .map(CircuitStore.Revision::document).filter(source::matches).findFirst().orElse(null);
        Path2D original=null;
        var oldTrack=originalDraft==null?null:CircuitInspector.inspect(originalDraft).track();
        if(oldTrack!=null) {
            var oldSection=new CircuitSectionPractice(oldTrack,source.range(),CircuitSectionPractice.Start.defaults(oldTrack),source.focusedSplit());
            original=TrackMapView.circuitPath(oldTrack,map,oldSection.startDistance(),oldSection.finishDistance());
        }
        presetPreview=new PresetPreview(expected,candidate,original);
    }

    private void usePresetPreview(FormulaGame app) {
        if(presetPreview==null || !isCurrent(app,reviewed)) return;
        var source=presetPreview.source();
        refreshSlots(app.circuits);
        if(!source.equals(slot(source.number()))) {
            app.notifyUser("这份练习预设已变化，请返回列表重新选择。");
            return;
        }
        preview=presetPreview.candidate();
        range=preview.practice().range();
        start=preview.practice().start();
        split=preview.practice().focusedSplit();
        slotNumber=source.number();
        carriedPreset=source;
        observationMarker=null;
        observationRetestSource=null;
        clearFeedbackPractice();
        retestOrigin=null;
        observationOrigin=null;
        presetPreview=null;
        managingPresets=false;
    }

    private void renderPresetPreview(HudRenderer h,Graphics2D g,FormulaGame app,CircuitEditor editor) {
        var source=presetPreview.source();
        var candidate=presetPreview.candidate();
        var practice=candidate.practice();
        h.text("按当前路线预览旧预设",60,103,30,INK,true);
        h.text("第 "+source.number()+" 槽 · "+source.name()+" · "+presetRangeLabel(source.preset()),60,148,17,INK);
        h.text(presetPreview.original()==null?"地图显示当前路线，原路线没有可用的历史图。"
                :"灰线是原路线的计时区间，绿线是按原百分比在当前路线重算的位置。",60,179,16,INK);
        drawMap(h,g,candidate,presetPreview.original());
        h.text("当前路线 · "+practiceLabel(practice)+" · "+startLabel(practice.start()),80,578,18,INK);
        if(!source.preset().start().equals(practice.start()))
            h.text("原助跑 "+source.preset().start().runUpMeters()+" m 超过当前圈长允许值，候选采用 "+practice.start().runUpMeters()+" m。",80,616,16,FINISH);
        if(candidate.blocked()) h.text("发车点被场景占用。选用后可调整助跑距离或区间。",80,649,16,FINISH);
        h.smallButton(80,685,284,44,"返回练习预设  Esc",this::back);
        if(!editor.document().equals(reviewed)) {
            h.text("当前草稿已变化，请返回后重新打开区间预览。",434,715,17,FINISH);
            return;
        }
        h.smallButton(921,685,280,44,"选用当前路线的预览  Enter",()->usePresetPreview(app));
    }

    private void copyPreview(HudRenderer h,FormulaGame app) {
        if(!isCurrent(app,reviewed)) return;
        h.nameCircuitDraftCopy(reviewed,previewSlot());
    }

    private CircuitStore.PracticeSlot previewSlot() {
        var preset=new CircuitStore.PracticePreset(previewDriveKey,range,start,split==0?null:split);
        var selected=slot(slotNumber);
        return selected==null?new CircuitStore.PracticeSlot(slotNumber,preset)
                :new CircuitStore.PracticeSlot(slotNumber,selected.name(),preset);
    }

    private static String savedSummary(CircuitStore.PracticePreset preset) {
        return "已存 · "+presetRangeLabel(preset)+" · "+startLabel(preset.start());
    }

    void refreshSlots(CircuitStore circuits) {
        slots=circuits.find(reviewed.id()).map(CircuitStore.Saved::slots).orElse(List.of());
    }

    private CircuitStore.PracticeSlot slot(int number) {
        for(var entry:slots) if(entry.number()==number) return entry;
        return null;
    }

    private boolean applies(CircuitStore.PracticeSlot entry) {
        return entry!=null && previewDriveKey.equals(entry.preset().driveKey())
                && entry.preset().start().runUpMeters()<=CircuitSectionPractice.Start.maximumRunUp(track);
    }

    private void applyPreset(CircuitStore.PracticePreset preset) {
        start=preset.start();
        carriedPreset=null;
        observationMarker=null;
        observationRetestSource=null;
        clearFeedbackPractice();
        retestOrigin=null;
        observationOrigin=null;
        setRange(preset.range(),preset.focusedSplit());
    }

    private void selectSlot(FormulaGame app,int number,boolean read) {
        if(!isCurrent(app,reviewed)) return;
        refreshSlots(app.circuits);
        var entry=slot(number);
        if(read) {
            if(!applies(entry)) {app.notifyUser("当前草稿无法读取这份预设，请核对路线。");return;}
            applyPreset(entry.preset());
        }
        slotNumber=number;
        managingPresets=false;
    }

    private void readPreset(FormulaGame app) {
        if(!isCurrent(app,reviewed)) return;
        refreshSlots(app.circuits);
        var entry=slot(slotNumber);
        if(applies(entry)) applyPreset(entry.preset());
        else app.notifyUser("当前草稿没有可用的已存区间。请核对路线后重新保存。");
    }

    private void savePreset(FormulaGame app) {
        if(!isCurrent(app,reviewed)) return;
        var target=slot(slotNumber);
        if(carriedPreset!=null && slotNumber==carriedPreset.number() && !carriedPreset.equals(target)) {
            app.notifyUser("原预设槽位已变化，请从列表重新读取或选择其他槽位。");
            return;
        }
        if(app.saveCircuitSectionPreset(reviewed,slotNumber,target,range,start,split==0?null:split)) {
            carriedPreset=null;
            refreshSlots(app.circuits);
        }
    }

    private void renamePreset(HudRenderer h,FormulaGame app,CircuitStore.PracticeSlot expected) {
        var source=reviewed;
        if(!isCurrent(app,source)) return;
        h.editTextChecked("第 "+expected.number()+" 槽预设名称",expected.name(),"练习 "+expected.number(),CircuitStore.PRACTICE_NAME_LIMIT,name->{
            if(!isCurrent(app,source)) return true;
            refreshSlots(app.circuits);
            if(!Objects.equals(slot(expected.number()),expected)) {
                app.notifyUser("练习预设已变化，请核对当前列表。");
                return true;
            }
            if(!app.renameCircuitSectionPreset(source,expected,name)) return false;
            refreshSlots(app.circuits);
            return true;
        });
    }

    private void confirmClearPreset(HudRenderer h,FormulaGame app) {
        var source=reviewed;
        var expected=slot(slotNumber);
        if(expected==null || !isCurrent(app,source)) return;
        h.confirm("清除第 "+expected.number()+" 槽练习预设？",expected.name()+"\n"+savedSummary(expected.preset())+"\n画面中的设置仍可继续调整和试驾。",()->{
            if(isCurrent(app,source) && app.clearCircuitSectionPreset(source,expected)) refreshSlots(app.circuits);
        });
    }

    private void editBoundary(HudRenderer h,FormulaGame app,boolean beginning) {
        var source=reviewed;
        h.editNumber(beginning?"区间起点 · 圈长百分比":"区间终点 · 圈长百分比",
                beginning?range.from():range.to(),beginning?0:1,beginning?99:100,"%",value->{
                    if(!isCurrent(app,source)) return true;
                    if(value==(beginning?range.to():range.from())) {
                        app.notifyUser("起点与终点重合，请输入另一个百分比。整圈可选 0% → 100%。");
                        return false;
                    }
                    setRange(beginning?new CircuitSectionPractice.Range(value,range.to())
                            :new CircuitSectionPractice.Range(range.from(),value));
                    return true;
                });
    }

    private void editStart(HudRenderer h,FormulaGame app,boolean distance) {
        var source=reviewed;
        h.editNumber(distance?"助跑距离 · 从发车点到区间起点":"放行速度 · 0 为静止起步",
                distance?start.runUpMeters():start.speedKph(),
                distance?CircuitSectionPractice.Start.MIN_RUN_UP:0,
                distance?CircuitSectionPractice.Start.maximumRunUp(track):CircuitSectionPractice.Start.MAX_SPEED_KPH,
                distance?" m":" km/h",value->{
                    if(!isCurrent(app,source)) return true;
                    start=distance?new CircuitSectionPractice.Start(value,start.speedKph())
                            :new CircuitSectionPractice.Start(start.runUpMeters(),value);
                    preview=buildPreview(split);
                    return true;
                });
    }

    private boolean isCurrent(FormulaGame app,CircuitDocument source) {
        if(source!=null && reviewed==source && app.circuitEditor()!=null && source.equals(app.circuitEditor().document())) return true;
        app.notifyUser("草稿已变化，请重新打开区间预览。");
        return false;
    }

    static String rangeLabel(CircuitSectionPractice.Range range) {
        return range.from()+"% → "+range.to()+"%"+(range.crossesStartLine()?" · 跨起终线":"");
    }

    static String presetRangeLabel(CircuitStore.PracticePreset preset) {
        return (preset.split()==null?"":"第 "+preset.split()+" 段 · ")+rangeLabel(preset.range());
    }

    static String practiceLabel(CircuitSectionPractice practice) {
        if(practice.focusedSplit()==0) return practice.range()==null
                ?String.format(Locale.ROOT,"%.2f–%.2f m",practice.startDistance(),practice.finishDistance()):rangeLabel(practice.range());
        return "第 "+practice.focusedSplit()+" 段 · 约 "+Math.round(practice.finishDistance()-practice.startDistance())+" m";
    }

    static String startLabel(CircuitSectionPractice.Start value) {
        return "助跑 "+value.runUpMeters()+" m · 放行 "+value.speedKph()+" km/h";
    }

    static String speedLabel(CircuitSectionPractice.GateSpeeds speeds) {
        return "入段 "+speedKph(speeds.entryMps())+" · 出段 "+speedKph(speeds.exitMps())+" km/h";
    }

    static String speedKph(float speedMps) {
        return Float.isFinite(speedMps)?String.format(Locale.ROOT,"%.1f",speedMps*3.6):"—";
    }

    private void setRange(CircuitSectionPractice.Range value) {
        setRange(value,0);
    }

    private void setRange(CircuitSectionPractice.Range value,int selectedSplit) {
        range=value;
        split=selectedSplit;
        preview=buildPreview(split);
    }

    private MapPreview buildPreview(int selectedSplit) {
        return buildPreview(range,start,selectedSplit);
    }

    private MapPreview buildPreview(CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,int selectedSplit) {
        var practice=new CircuitSectionPractice(track,range,start,selectedSplit);
        var selected=selectedSplit==0?path(range.from(),range.endProgressPercent())
                :TrackMapView.circuitPath(track,map,practice.startDistance(),practice.finishDistance());
        float from=practice.spawnDistance();
        var spawn=track.sample(from).position();
        var line=new Path2D.Float();
        line.moveTo(map.x(spawn.x),map.y(spawn.z));
        int steps=(start.runUpMeters()+1)/2;
        for(int step=1;step<=steps;step++) {
            var point=track.sample(from+start.runUpMeters()*(step/(float)steps)).position();
            line.lineTo(map.x(point.x),map.y(point.z));
        }
        return new MapPreview(practice,selected,line,spawn,practice.spawnBlocked());
    }

    private void mark(HudRenderer h,Graphics2D g,Vector3f p,String text,Color color,int dx,int dy) {
        int x=Math.round(map.x(p.x)),y=Math.round(map.y(p.z));
        g.setColor(color);
        g.fillOval(x-5,y-5,10,10);
        h.text(text,x+dx,y+dy,17,color,true);
    }

    private Path2D path(int from,int to) {
        var line=new Path2D.Float();
        for(int step=from*2;step<=to*2;step++) {
            var p=track.sample(track.length*((step%200)/200f)).position();
            if(step==from*2) line.moveTo(map.x(p.x),map.y(p.z));
            else line.lineTo(map.x(p.x),map.y(p.z));
        }
        return line;
    }
}
