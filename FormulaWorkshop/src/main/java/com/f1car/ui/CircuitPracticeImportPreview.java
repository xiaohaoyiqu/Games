package com.f1car.ui;

import com.f1car.model.CircuitSectionPractice;
import com.f1car.persistence.CircuitStore;
import com.jme3.math.Vector3f;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

final class CircuitPracticeImportPreview {
    private static final Rectangle MAP=new Rectangle(655,213,461,283);
    private static final Color PANEL=new Color(0x142d39),MAP_PANEL=new Color(0x102430);
    private static final Color INK=new Color(0xeaf4ef),MUTED=new Color(0xa8c2c7),ROAD=new Color(0x52717b);
    private static final Color START=new Color(0x63e6bc),FINISH=new Color(0xffbd78),APPROACH=new Color(0x8dc5fd);
    private static final BasicStroke ROAD_STROKE=new BasicStroke(5,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke RUN_UP_STROKE=new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{5,5},0);

    private final CircuitStore.ImportedDraft draft;
    private final CircuitSectionPractice practice;
    private final String dirtyMap;
    private final Path2D.Float road,runUp,section;
    private final Point2D.Float startPoint,finishPoint,spawnPoint;
    private final boolean spawnBlocked;
    private final CircuitStore.Saved target;
    private final IntConsumer applySlot;
    private int selectedSlot=-1;

    CircuitPracticeImportPreview(CircuitStore.ImportedDraft draft,String dirtyMap,CircuitStore.Saved target,IntConsumer applySlot) {
        this.draft=draft;
        this.dirtyMap=dirtyMap;
        var preset=draft.slot().preset();
        var track=draft.document().track();
        practice=preset.preview(track);
        var transform=TrackMapView.fit(track,MAP,27);
        road=TrackMapView.path(track,transform);
        runUp=TrackMapView.circuitPath(track,transform,practice.spawnDistance(),practice.startDistance());
        section=TrackMapView.circuitPath(track,transform,practice.startDistance(),practice.finishDistance());
        startPoint=point(transform,track.sample(practice.startDistance()).position());
        finishPoint=point(transform,track.sample(practice.finishDistance()).position());
        spawnPoint=point(transform,track.sample(practice.spawnDistance()).position());
        spawnBlocked=practice.spawnBlocked();
        this.target=spawnBlocked?null:target;
        this.applySlot=applySlot;
    }

    List<String> lines(String fileName) {
        var document=draft.document();
        var slot=draft.slot();
        var lines=new ArrayList<String>();
        lines.add("地图 · "+document.name());
        lines.add("第 "+slot.number()+" 槽 · "+slot.name());
        lines.add(CircuitSectionHud.presetRangeLabel(slot.preset())+" · 约 "+Math.round(practice.finishDistance()-practice.startDistance())+" m");
        lines.add(CircuitSectionHud.startLabel(practice.start()));
        lines.add(spawnBlocked?"发车点被占用，导入后可调整。":"当前预览发车点空闲。");
        lines.add("文件 · "+fileName);
        var metadata=document.metadata();
        if(!metadata.author().isBlank()) lines.add("文件标注作者 · "+metadata.author());
        lines.add("路宽 "+document.width()+" m · "+document.anchors().size()+" 个路线点 · "+document.scene().items().size()+" 个物件");
        lines.add("导入为新草稿会使用一个地图槽位，四车资格需在本机重新验证。");
        if(dirtyMap!=null) lines.add("导入为新草稿时，当前未保存的地图「"+dirtyMap+"」会一起保存。");
        return lines;
    }

    boolean selectingSlot() {return selectedSlot>=0;}
    void backFromSlot() {selectedSlot=selectedSlot>0?0:-1;}
    void chooseSlot(int number) {if(selectedSlot==0) selectedSlot=number;}
    void confirmSlot() {if(selectedSlot>0) applySlot.accept(selectedSlot);}

    void render(HudRenderer h,Graphics2D graphics,String title,ReviewPages pages,Runnable back,String actionLabel,Runnable accept) {
        h.panel(124,127,1032,496,PANEL);
        h.panel(MAP.x,MAP.y,MAP.width,MAP.height,MAP_PANEL);
        drawMap(graphics);
        h.text("○ 起点    ■ 终点    + 发车    虚线 助跑",655,521,15,MUTED);
        if(selectingSlot()) renderSlotSelection(h);
        else renderFilePreview(h,title,pages,back,actionLabel,accept);
    }

    private void renderFilePreview(HudRenderer h,String title,ReviewPages pages,Runnable back,String actionLabel,Runnable accept) {
        h.text(title,155,178,25,INK,true);
        int y=242;
        for(var line:pages.visible()) {h.text(line,155,y,15,INK);y+=33;}
        if(pages.pages()>1) {
            h.smallButton(155,421,127,36,"上一页",()->pages.move(-1));
            h.centered((pages.page()+1)+" / "+pages.pages()+" · PgUp / PgDn",390,444,15,MUTED);
            h.smallButton(500,421,127,36,"下一页",()->pages.move(1));
        }
        if(target!=null) h.smallButton(155,466,472,36,"存到当前地图的练习预设",()->selectedSlot=0);
        if(dirtyMap!=null) h.text("导入为新草稿也会保存当前正在编辑的地图。",155,531,16,MUTED);
        h.smallButton(155,557,472,42,"稍后确认",back);
        h.smallButton(655,557,461,42,actionLabel,accept);
    }

    private void renderSlotSelection(HudRenderer h) {
        h.text(selectedSlot==0?"选择当前地图的槽位":"核对第 "+selectedSlot+" 槽",155,178,25,INK,true);
        h.wrapped("本机已存地图 · "+target.document().name(),155,229,472,17,MUTED,22);
        if(selectedSlot==0) {
            for(int number=1;number<=CircuitStore.PRACTICE_SLOT_LIMIT;number++) {
                int slotNumber=number;
                h.smallButton(155,280+(number-1)*52,472,40,number+" · "+slotName(target.practiceSlot(number)),()->chooseSlot(slotNumber));
            }
            h.smallButton(155,557,472,42,"返回导入预览",this::backFromSlot);
            return;
        }
        var previous=target.practiceSlot(selectedSlot);
        h.text("原槽位 · "+slotName(previous),155,296,17,INK,true);
        if(previous!=null) slotSettings(h,previous,323);
        h.text("文件预设 · "+draft.slot().name(),155,396,17,INK,true);
        slotSettings(h,draft.slot(),423);
        h.text("仅保存这一槽。",155,510,16,MUTED);
        h.smallButton(155,557,472,42,"返回槽位列表",this::backFromSlot);
        h.smallButton(655,557,461,42,previous==null?"保存到第 "+selectedSlot+" 槽":"覆盖第 "+selectedSlot+" 槽",this::confirmSlot);
    }

    private static String slotName(CircuitStore.PracticeSlot slot) {return slot==null?"空槽":slot.name();}
    private static void slotSettings(HudRenderer h,CircuitStore.PracticeSlot slot,int y) {
        h.text(CircuitSectionHud.presetRangeLabel(slot.preset()),155,y,16,MUTED);
        h.text(CircuitSectionHud.startLabel(slot.preset().start()),155,y+23,16,MUTED);
    }

    private void drawMap(Graphics2D graphics) {
        var stroke=graphics.getStroke();
        graphics.setStroke(ROAD_STROKE);
        graphics.setColor(ROAD);
        graphics.draw(road);
        graphics.setColor(START);
        graphics.draw(section);
        graphics.setStroke(RUN_UP_STROKE);
        graphics.setColor(APPROACH);
        graphics.draw(runUp);
        graphics.setStroke(stroke);
        graphics.setColor(START);
        graphics.drawOval(Math.round(startPoint.x)-6,Math.round(startPoint.y)-6,12,12);
        graphics.setColor(FINISH);
        graphics.fillRect(Math.round(finishPoint.x)-4,Math.round(finishPoint.y)-4,8,8);
        graphics.setColor(spawnBlocked?FINISH:APPROACH);
        int x=Math.round(spawnPoint.x),y=Math.round(spawnPoint.y);
        graphics.drawLine(x-6,y,x+6,y);
        graphics.drawLine(x,y-6,x,y+6);
    }

    private static Point2D.Float point(TrackMapView.Transform transform,Vector3f point) {
        return new Point2D.Float(transform.x(point.x),transform.y(point.z));
    }
}
