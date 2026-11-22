package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.TuningLapStudyStore;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Explicit session selections; saving creates a separate immutable study after review. */
final class TuningLapsHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private static final int PAGE_SIZE=5;
    private record Row(EquipmentLap lap,EquipmentLapShare share,TuningLapComparison.Match a,TuningLapComparison.Match b) {
        String origin() {return share==null?"本机":"收到 · "+share.author();}
        TuningLapComparison.Match match(TuningExperiment.Slot slot) {return slot==TuningExperiment.Slot.A?a:b;}
    }
    private final EquipmentLapsHud chart=new EquipmentLapsHud();
    private boolean open;private int page;private TuningLapComparison context;private String origin="";
    private List<EquipmentLap> local=List.of();private List<EquipmentLapShare> received=List.of();private List<Row> rows=List.of();
    private List<String> reportDetails=List.of();private Row a,b;
    private TrialTraceShare providerReport;
    void open(FormulaGame app,TuningReportBrief brief,String origin,TrialTraceShare providerReport) {
        context=new TuningLapComparison(brief.report(),brief.purpose());this.origin=Objects.requireNonNull(origin);
        this.providerReport=providerReport;
        reportDetails=TuningInterpretation.explain(brief.report(),brief.purpose(),app.vehicleSpec.physicsVersion);refresh(app);open=true;
    }
    boolean showing() {return open;}
    boolean click(float x,float y) {return open && chart.showing() && chart.click(x,y);}
    private void refresh(FormulaGame app) {
        local=app.equipmentLaps.entries();received=app.receivedEquipmentLaps.entries();var next=new ArrayList<Row>();
        for(var value:local) next.add(row(value,null));for(var value:received) next.add(row(value.recording(),value));
        rows=List.copyOf(next);a=b=null;page=0;
    }
    private Row row(EquipmentLap lap,EquipmentLapShare share) {return new Row(lap,share,context.match(TuningExperiment.Slot.A,lap),context.match(TuningExperiment.Slot.B,lap));}
    private boolean current(FormulaGame app) {return local==app.equipmentLaps.entries() && received==app.receivedEquipmentLaps.entries();}
    private boolean ready(FormulaGame app,BooleanSupplier reportCurrent) {
        if(!reportCurrent.getAsBoolean()) {app.notifyUser("报告或来源已变化；保留冻结页面，请返回重新打开目标报告。");return false;}
        if(!current(app)) {app.notifyUser("装备圈库已变化；保留冻结选择，请明确刷新后重选。");return false;}return true;
    }
    private void dismiss() {open=false;context=null;providerReport=null;local=List.of();received=List.of();rows=List.of();reportDetails=List.of();a=b=null;origin="";}
    void render(HudRenderer h,Graphics2D g,FormulaGame app,BooleanSupplier reportCurrent,Runnable openLibrary) {
        if(chart.showing()) {chart.render(h,g,app);return;}
        h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("报告 A／B · 选择装备整圈对照",60,104,29,INK,true);
        h.smallButton(640,57,180,38,"明确刷新并重选",()->{if(reportCurrent.getAsBoolean()) refresh(app);else app.notifyUser("报告或来源已变化，请返回重新打开。");});
        h.smallButton(840,57,170,38,"报告冻结来源",()->h.review("当前报告原文",reportDetails,"返回整圈选择",()->{}));
        h.smallButton(1030,57,184,38,"返回报告概览",this::dismiss);
        h.text(cut(origin,22)+" · "+context.report().conditions().exercise().title+" · 圈库 "+rows.size()+" / 16 份",60,148,16,SOFT);
        h.text(current(app)?"报告按打开时冻结；选择与确认时复核来源。圈速与短测各自保留。":"圈库已变化 · 本页仍是旧冻结内容；确认前需明确刷新重选。",60,179,15,AMBER);
        h.text("报告 A → "+selected(a),60,218,17,MINT);h.text("报告 B → "+selected(b),60,250,17,AMBER);
        int pages=Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE);page=Math.floorMod(page,pages);
        h.smallButton(834,211,180,34,"上一页",()->page=Math.floorMod(page-1,pages));h.smallButton(1030,211,184,34,"下一页 "+(page+1)+" / "+pages,()->page=(page+1)%pages);
        for(int i=page*PAGE_SIZE;i<Math.min(rows.size(),(page+1)*PAGE_SIZE);i++) {
            var row=rows.get(i);int y=283+(i%PAGE_SIZE)*72;
            h.text(cut(row.origin()+" · "+shortId(row.lap())+" · "+row.lap().lap().conditions().trackId()+" · "+String.format(Locale.ROOT,"%.3f s",row.lap().lap().duration()),40),60,y+18,16,INK);
            h.text("A："+row.a().correspondence().title+"；B："+row.b().correspondence().title,60,y+42,13,SOFT);
            h.text(row.a().reportRoute()?"报告路线版本":"其他路线版本 · 两圈之间仍须同路线",60,y+61,13,AMBER);
            h.smallButton(730,y,130,37,row==a?"A 已选":"选为报告 A",()->choose(h,app,reportCurrent,row,TuningExperiment.Slot.A));
            h.smallButton(875,y,130,37,row==b?"B 已选":"选为报告 B",()->choose(h,app,reportCurrent,row,TuningExperiment.Slot.B));
            h.smallButton(1030,y,184,37,"对应与完整来源",()->details(h,row));
        }
        if(rows.isEmpty()) h.wrapped("尚无已收录装备整圈。先从计时挑战完成有效整圈，再到驾驶分析明确收录；本页不会把旧遥测补成带装备来源的圈。",60,341,1100,21,SOFT,34);
        h.smallButton(60,680,550,39,"复核所选两圈并查看曲线",()->review(h,app,reportCurrent));
        h.smallButton(630,680,280,39,"清空两侧选择",()->{a=b=null;});
        h.smallButton(930,680,284,39,"保存这组完整对照",()->save(h,app,reportCurrent,openLibrary));
        h.wrapped("明确保存会携带原报告、两圈及现有短测曲线；缺少的组保持缺失。原库与成绩保持，驾驶和路线仍可影响差异。",60,749,1154,15,AMBER,22);
    }
    private void choose(HudRenderer h,FormulaGame app,BooleanSupplier reportCurrent,Row row,TuningExperiment.Slot slot) {
        if(!ready(app,reportCurrent)) return;
        if(!row.match(slot).selectable()) {h.review("这份圈不对应报告 "+slot,row.match(slot).details(),"返回明确选择",()->{});return;}
        if(slot==TuningExperiment.Slot.A) a=row;else b=row;
    }
    private void details(HudRenderer h,Row row) {
        var lines=new ArrayList<String>();lines.add("原始来源："+row.origin());
        for(var slot:TuningExperiment.Slot.values()) {lines.add("对应报告 "+slot+"："+row.match(slot).correspondence().title);lines.addAll(row.match(slot).details());}
        for(var field:PartsCatalog.Field.values()) lines.add(field.title+"：报告 A "+Float.toString(field.ratio(context.report().a()))+"；报告 B "+Float.toString(field.ratio(context.report().b()))+"；本圈实际倍率 "+Float.toString(field.ratio(row.lap().source().tuning()))+"。");
        lines.addAll(EquipmentLapsHud.detailsLines(row.lap(),row.share()));h.review("圈与报告的冻结对应",lines,"返回整圈选择",()->{});
    }
    private void review(HudRenderer h,FormulaGame app,BooleanSupplier reportCurrent) {
        if(!ready(app,reportCurrent)) return;
        var issues=context.pairIssues(a==null?null:a.lap(),b==null?null:b.lap());
        if(!issues.isEmpty()) {h.review("所选两圈暂不能对照",issues,"返回重新选择",()->{});return;}
        var beforeA=a;var beforeB=b;var lines=new ArrayList<String>();
        for(var slot:TuningExperiment.Slot.values()) {var row=slot==TuningExperiment.Slot.A?a:b;lines.add("报告 "+slot+" → "+selected(row));lines.add(row.match(slot).correspondence().title);lines.addAll(row.match(slot).details());}
        lines.add("曲线沿用整圈 A − B，短测报告沿用 B − A；单位和试验各自解释，不合并或换算收益。");
        lines.add("只读查看，不安装、采用、保存关联或改变成绩；关闭后回到原选择。");
        h.review("查看明确选择的整圈对照？",lines,"查看所选两圈曲线",()->{
            if(!ready(app,reportCurrent) || a!=beforeA || b!=beforeB) return;
            chart.openReportPair(a.lap(),a.share(),b.lap(),b.share(),context.compare(a.lap(),b.lap()));
        });
    }
    private void save(HudRenderer h,FormulaGame app,BooleanSupplier reportCurrent,Runnable openLibrary) {
        if(!ready(app,reportCurrent)) return;
        var issues=context.pairIssues(a==null?null:a.lap(),b==null?null:b.lap());
        if(!context.report().complete()) {app.notifyUser("先完成报告的六次有效测量；当前参数对应仍可只读查看。");return;}
        if(!issues.isEmpty()) {h.review("先完成两侧明确选择",issues,"返回选圈",()->{});return;}
        var beforeA=a;var beforeB=b;var before=context;
        var beforeCurves=providerReport==null?app.tuningTraceStore.freeze(context.report()):new FrozenTuningTraces(context.report(),providerReport.traces());
        String name=app.store.profile.playerName;if(!PartsCatalog.text(name,40) || name.isBlank()) name="玩家";
        h.prompt("这份对照的制作者署名声明",name,40,author->h.editText("对照备注（可换行）","","",320,notes->{
            if(!ready(app,reportCurrent) || a!=beforeA || b!=beforeB || context!=before || !curvesCurrent(app,beforeCurves)) return;
            try {
                var value=TuningLapStudy.capture(author,notes,new TuningLapStudy.Report(context.report(),context.purpose(),providerReport,beforeCurves.traces()),new TuningLapStudy.Lap(a.lap(),a.share()),new TuningLapStudy.Lap(b.lap(),b.share()));
                var reviewed=app.tuningStudies.review(value,TuningLapStudyStore.Origin.LOCAL);var lines=new ArrayList<>(TuningStudiesHud.details(value,TuningLapStudyStore.Origin.LOCAL,app.vehicleSpec.physicsVersion));lines.addAll(reviewed.issues());
                h.review("保存报告与两圈的完整副本？",lines,reviewed.valid()?"明确保存独立对照":"返回检查冲突",()->{
                    if(reviewed.valid() && ready(app,reportCurrent) && a==beforeA && b==beforeB && context==before && curvesCurrent(app,beforeCurves)) {app.acceptTuningStudy(reviewed);openLibrary.run();}
                });
            }catch(IllegalArgumentException error) {app.notifyUser("对照署名、备注、完整报告或来源不再有效，请保留原选择并重新查看。");}
        }));
    }
    private boolean curvesCurrent(FormulaGame app,FrozenTuningTraces frozen) {if(providerReport!=null || app.tuningTraceStore.current(frozen)) return true;app.notifyUser("短测曲线已变化；原选择保持，请重新准备保存并查看曲线目录。");return false;}
    private static String selected(Row row) {return row==null?"尚未选择":cut(row.origin(),20)+" · "+shortId(row.lap());}
    private static String shortId(EquipmentLap value) {return value.id().substring(0,8);}
    private static String cut(String value,int max) {return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max-1))+"…";}
}
