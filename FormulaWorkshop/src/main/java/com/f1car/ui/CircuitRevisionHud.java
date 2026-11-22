package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.CircuitStore;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.*;

/** Read-only comparison plus an explicitly reviewed, undoable restore. No disk or physics work per frame. */
final class CircuitRevisionHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private static final Color OVERLAY_LABEL=new Color(8,28,38,235);
    private static final BasicStroke DASH=new BasicStroke(2,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,10,new float[]{7,5},0),LINE=new BasicStroke(2);
    private static final Font FONT=new Font("Microsoft YaHei",Font.PLAIN,13);
    private CircuitStore.Revision revision;
    private CircuitInspector.Report savedInspection;
    private TrackMapView.Transform roadProjection;
    private Path2D.Float projectedRoad;
    private final CircuitRevisionDiff.Cache cache=new CircuitRevisionDiff.Cache();
    private CircuitRevisionDiff shown;
    private int selected;
    private boolean overlay=true;
    void reset() {
        revision=null;
        savedInspection=null;
        roadProjection=null;
        projectedRoad=null;
        shown=null;
        selected=0;
    }
    void select(CircuitStore.Revision value) {
        if(Objects.equals(revision,value)) return;
        var inspection=CircuitInspector.inspect(value.document());
        reset();
        revision=value;
        savedInspection=inspection;
    }
    private boolean prepare(FormulaGame app,CircuitEditor editor) {if(revision==null || !app.circuitHistory().contains(revision)) return false;var next=cache.get(revision.document(),editor.document());if(shown!=next) {shown=next;selected=Math.max(0,Math.min(selected,shown.changes().size()-1));}return true;}
    void render(HudRenderer h,FormulaGame app,CircuitEditor editor,CircuitEditorView view,Runnable back) {
        if(!prepare(app,editor)) {h.wrapped("所选历史已不在当前地图中，请返回版本列表重新选择。",930,300,276,17,SOFT,26);h.smallButton(930,420,282,35,"返回版本列表",back);return;}
        h.smallButton(930,264,133,29,overlay?"历史虚线：开":"历史虚线：关",()->overlay=!overlay);h.smallButton(1076,264,136,29,"返回版本列表",back);
        h.text(shown.changes().size()+" 项变化 · "+(shown.drivingInputChanged()?"驾驶输入已改变":"驾驶输入未改变"),930,321,14,shown.drivingInputChanged()?AMBER:MINT,true);
        int pages=Math.max(1,(shown.changes().size()+3)/4),page=selected/4;
        for(int index=page*4;index<Math.min(shown.changes().size(),page*4+4);index++) {int target=index;var change=shown.changes().get(index);h.smallButton(930,337+(index-page*4)*34,282,29,(index==selected?"● ":"")+(index+1)+" · "+shorten(change.title(),15),()->selected=target);}
        if(shown.changes().isEmpty()) h.wrapped("所选历史与当前草稿相同。",930,384,276,17,SOFT,27);
        else {var change=shown.changes().get(selected);h.wrapped(shorten(change.description(),34),930,502,276,14,INK,21);}
        h.smallButton(930,549,133,29,"上一页 "+(page+1)+"/"+pages,()->selected=Math.max(0,(page-1)*4));h.smallButton(1076,549,136,29,"下一页",()->selected=Math.min(Math.max(0,shown.changes().size()-1),(page+1)*4));
        h.smallButton(930,590,282,35,"定位所选变化",()->{if(shown.changes().isEmpty()) return;var change=shown.changes().get(selected);editor.focus(change.focusIssue());if(change.locations().isEmpty()) view.fit(editor.document());else view.focus(change.locations());});
        h.smallButton(930,640,133,29,"全文与引用",()->details(h,app,editor));h.smallButton(1076,640,136,29,"还原所选历史",()->restore(h,app,editor));
    }
    void draw(Graphics2D graphics,FormulaGame app,CircuitEditor editor,CircuitEditorView view) {
        if(!prepare(app,editor) || !overlay) return;
        var g=(Graphics2D)graphics.create();
        try {
            g.clip(CircuitEditorView.BOARD);
            g.setColor(SOFT);
            g.setStroke(DASH);
            g.draw(roadOverlay(view));
            g.setFont(FONT);
            if(!shown.changes().isEmpty()) {
                var change=shown.changes().get(selected);
                for(int i=0;i<change.locations().size();i++) {
                    var point=change.locations().get(i);
                    boolean old=change.locations().size()==2?i==0:change.after().equals("无");
                    g.setStroke(old?DASH:LINE);
                    g.setColor(old?SOFT:MINT);
                    int x=Math.round(view.x(point.x())),y=Math.round(view.y(point.z()));
                    g.drawOval(x-9,y-9,18,18);
                    g.drawString(old?"历史":"草稿",x+12,y-12);
                }
            }
            g.setColor(OVERLAY_LABEL);
            g.fillRoundRect(56,179,720,28,6,6);
            g.setColor(INK);
            g.drawString(savedInspection.track()==null?"历史为未成形路线：虚线仅表示点的折线 · 实线为当前草稿"
                    :"虚线：所选历史 · 实线：当前草稿 · 圆圈标记所选变化",66,198);
        } finally {
            g.dispose();
        }
    }

    Path2D.Float roadOverlay(CircuitEditorView view) {
        var projection=view.projection();
        if(projectedRoad!=null && roadProjection==projection) return projectedRoad;

        var track=savedInspection.track();
        var path=track==null?new Path2D.Float():TrackMapView.path(track,projection);
        if(track==null) {
            var anchors=revision.document().anchors();
            for(int i=0;i<anchors.size();i++) {
                var anchor=anchors.get(i);
                float x=projection.x(anchor.x()),y=projection.y(anchor.z());
                if(i==0) path.moveTo(x,y);
                else path.lineTo(x,y);
            }
            // With only two points there is one visible edge. Traversing it twice would fill dash gaps.
            if(anchors.size()>2) path.closePath();
        }
        roadProjection=projection;
        projectedRoad=path;
        return path;
    }
    private void details(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        if(!prepare(app,editor)) return;var lines=new ArrayList<String>();lines.add("比较方向：所选历史 → 当前草稿。查看与定位不保存、不还原，也不产生四车证明。");lines.add("历史："+revision.document().name());lines.add("驾驶输入指纹："+shown.beforeKey()+" → "+shown.afterKey());for(var change:shown.changes()) lines.add(change.description());lines.add("路线点与物件按各自版本的索引对齐；没有稳定物件身份，增删不推断为同一个物件。");
        impacts(lines,app,editor.document(),editor.inspection(),editor.proof());h.review("版本变化与活动引用",lines,"关闭比较说明",()->{});
    }
    private void restore(HudRenderer h,FormulaGame app,CircuitEditor editor) {
        if(!prepare(app,editor)) return;var expected=editor.document();var expectedRevision=revision;var backwards=CircuitRevisionDiff.compare(expected,revision.document());var lines=new ArrayList<String>();lines.add("将当前草稿换回所选历史版本，可撤销；仍需另行保存。委托限制继续适用。");lines.add("本次变化方向：当前草稿 → 所选历史。");for(var change:backwards.changes()) lines.add(change.description());lines.add("历史记录只含地图内容，不能恢复或伪造旧四车资格。");
        var proof=editor.proof();if(proof!=null && !proof.matches(revision.document(),app.vehicleSpec.physicsVersion)) proof=null;impacts(lines,app,revision.document(),savedInspection,proof);
        h.review("还原历史为待保存草稿？",lines,"还原为草稿（可撤销）",()->app.restoreCircuitRevision(expectedRevision,expected));
    }
    private static void impacts(List<String> lines,FormulaGame app,CircuitDocument document,CircuitInspector.Report report,CircuitTrial.Proof proof) {
        lines.add("以下为保存此地图内容后，对活动卡库、当前活动场次及赛事包中相关项目的预计影响；原卡和结果保留。");var impacts=CircuitRevisionImpact.inspect(document,report,proof,app.vehicleSpec.physicsVersion,app.circuitActivityReferences());if(impacts.isEmpty()) lines.add("这些来源目前没有引用本图的项目。");for(var impact:impacts) lines.add(impact.source()+"／"+impact.activity()+" 第 "+impact.stage()+" 项："+impact.detail());
    }
    private static String shorten(String value,int limit) {return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit-1))+"…";}
}
