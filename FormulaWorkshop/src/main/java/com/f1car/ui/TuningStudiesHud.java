package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Reopens self-contained studies without resolving them against today's reports, laps or catalogue. */
final class TuningStudiesHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private final EquipmentLapsHud chart=new EquipmentLapsHud();private final TuningExplanationHud reportHud=new TuningExplanationHud();private final TuningTraceHud traces=new TuningTraceHud();
    private boolean open,traceView;private List<TuningLapStudyStore.Entry> entries=List.of();private TuningLapStudyStore.Entry selected;
    private FrozenTuningTraces frozenCurves;private List<String> curveDetails=List.of();
    void open(FormulaGame app) {closeCurves();entries=app.tuningStudies.entries();selected=null;open=true;}
    boolean showing() {return open;}
    boolean click(float x,float y) {return open && chart.showing() && chart.click(x,y);}
    private boolean current(FormulaGame app) {return entries==app.tuningStudies.entries();}
    private boolean check(FormulaGame app) {if(current(app)) return true;app.notifyUser("对照库已变化，请明确刷新后选择。");return false;}
    private void refresh(FormulaGame app) {entries=app.tuningStudies.entries();selected=null;}
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        if(chart.showing()) {chart.render(h,g,app);return;}
        if(traceView) {
            h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("本份对照的冻结短测曲线",60,105,28,INK,true);
            h.smallButton(1030,57,184,38,"返回冻结报告",this::closeCurves);
            h.text("保留 "+frozenCurves.traces().size()+" / "+frozenCurves.report().runs().size()+" 组；精确绑定 "+frozenCurves.exactBindings()+" 组，其余保留原绑定。",60,151,17,SOFT);
            h.smallButton(60,180,550,36,"原试次、采样与缺失曲线目录",()->h.review("本份对照的曲线目录",curveDetails,"返回冻结曲线",()->{}));
            h.wrapped(frozenCurves.exactBindings()<frozenCurves.traces().size()?"旧绑定对部分参数取整，不能认证细微微调完全一致；旧记录不改写为精确绑定。":"精确绑定核对实际参数与原试次，不证明真实驾驶、完整采样或装备收益。",60,256,1154,16,AMBER,25);
            traces.renderFrozen(h,g,frozenCurves);return;
        }
        if(reportHud.showing()) {
            reportHud.render(h,()->h.review("报告原始六次与条件",reportHud.brief().conditions(),"返回冻结报告",()->{}),()->{
                frozenCurves=selected.study().report().frozenTraces();curveDetails=curveDetails(frozenCurves);traceView=true;
            },()->showChart(selected),reportHud::dismiss);return;
        }
        h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("报告与装备整圈 · 完整对照库",60,109,29,INK,true);
        h.smallButton(830,57,180,38,"明确刷新列表",()->refresh(app));
        h.smallButton(1030,57,184,38,"返回原页",()->{open=false;entries=List.of();selected=null;});
        h.text(current(app)?"最多四份冻结对照；即使原报告或圈库移除，仍按本份完整副本查看。":"库已变化 · 保留旧列表，请刷新后重选。",60,151,16,AMBER);
        for(int i=0;i<entries.size();i++) {
            var entry=entries.get(i);var value=entry.study();int y=190+i*100;
            h.text(cut(entry.origin().title+" · "+value.author()+" · "+value.id().substring(0,18),56),60,y+19,17,INK);
            h.text(value.report().snapshot().conditions().exercise().title+" · "+cut(value.a().recording().lap().conditions().trackId(),26)+" · A/B 原圈独立保留",60,y+45,14,SOFT);
            h.smallButton(60,y+56,205,31,"完整来源与备注",()->h.review("冻结对照的完整原文",details(value,entry.origin(),app.vehicleSpec.physicsVersion),"返回对照库",()->{}));
            h.smallButton(285,y+56,205,31,"查看冻结报告",()->{selected=entry;var report=value.report();reportHud.open(report.snapshot(),report.purpose(),entry.origin()==TuningLapStudyStore.Origin.RECEIVED || report.received()?TuningReportBrief.Origin.RECEIVED:TuningReportBrief.Origin.LOCAL,entry.origin().title+" · 声明来源",app.vehicleSpec.physicsVersion);});
            h.smallButton(510,y+56,205,31,"查看两圈曲线",()->showChart(entry));
            h.smallButton(735,y+56,205,31,"导出完整对照",()->{if(check(app)) export(h,app,entry);});
            h.smallButton(960,y+56,254,31,"移除此份本地副本",()->{if(check(app)) h.review("只移除此份对照？",List.of(value.id(),"报告、两圈原始库和外部文件均保持；仅删除此独立库中的副本。"),"明确移除此份",()->{if(check(app)) {app.removeTuningStudy(entry);refresh(app);}});});
        }
        if(entries.isEmpty()) h.wrapped("从报告概览进入装备整圈选择，明确选 A、B 后保存完整对照；也可读取其他玩家提供的独立文件。收到内容不写入本机报告、圈库、装备或成绩。",60,275,1100,21,SOFT,35);
        h.wrapped(app.tuningStudies.warning().isEmpty()?"来源中的“本机”是制作者的声明；收到整份对照仍属于提供者记录，不证明真实驾驶或收益。":app.tuningStudies.warning(),60,621,1154,15,AMBER,23);
        if(app.tuningStudies.pending()!=null) h.smallButton(430,662,350,31,"明确取消待存对照",()->{app.tuningStudies.cancelPending();refresh(app);});
        h.smallButton(60,704,350,38,"读取完整对照文件",()->importFile(h,app));
        h.smallButton(430,704,350,38,"重试同份待存对照",()->{boolean ok=app.tuningStudies.retry();app.notifyUser(ok?"待存对照已处理；没有改写测量或成绩。":app.tuningStudies.warning());refresh(app);});
        h.smallButton(800,704,414,38,"修复文件后明确重新读取",()->{app.tuningStudies.reload();refresh(app);});
    }
    private void showChart(TuningLapStudyStore.Entry entry) {selected=entry;var value=entry.study();var c=new TuningLapComparison(value.report().snapshot(),value.report().purpose());chart.openReportPair(value.a().recording(),value.a().provider(),value.b().recording(),value.b().provider(),c.compare(value.a().recording(),value.b().recording()),lapLabel(entry,value.a()),lapLabel(entry,value.b()));}
    private static String lapLabel(TuningLapStudyStore.Entry entry,TuningLapStudy.Lap lap) {return lap.received()?"收到原圈 ":entry.origin()==TuningLapStudyStore.Origin.RECEIVED?"制作者本机声明 ":"本机 ";}
    static List<String> details(TuningLapStudy value,TuningLapStudyStore.Origin origin,String physics) {
        var lines=new ArrayList<String>();lines.add(origin.title+"；记录身份 "+value.id());lines.add("对照制作者署名声明："+value.author()+"；提供者毫秒时间 "+value.createdAt());lines.add("制作者原备注："+value.notes());
        lines.add("嵌套本机／收到均指制作者的来源声明；收到整份对照不转成本机测量，不认证作者或收益。");
        var report=value.report();lines.add(report.received()?"报告原提供者："+report.provider().author()+"；原说明："+report.provider().notes():"报告来自制作者本机声明；保留六次原值、用途及保存时携带的可用短测曲线。");
        lines.addAll(curveDetails(report.frozenTraces()));
        lines.addAll(TuningInterpretation.explain(report.snapshot(),report.purpose(),physics));lines.addAll(new TuningReportBrief(report.snapshot(),report.purpose(),TuningReportBrief.Origin.RECEIVED,physics).conditions());
        var context=new TuningLapComparison(report.snapshot(),report.purpose());
        for(var slot:TuningExperiment.Slot.values()) {var lap=slot==TuningExperiment.Slot.A?value.a():value.b();lines.add("报告 "+slot+" → "+(lap.received()?"收到的原圈":"制作者本机圈声明"));lines.addAll(context.match(slot,lap.recording()).details());lines.addAll(EquipmentLapsHud.detailsLines(lap.recording(),lap.provider()));}
        lines.add("短测 B − A 与整圈 A − B 各自解释；未合并试验或自动采用配置。查看、收录和导出均不改变原始测量库。");return List.copyOf(lines);
    }
    private void closeCurves() {traceView=false;frozenCurves=null;curveDetails=List.of();traces.clearFrozen();}
    private static List<String> curveDetails(FrozenTuningTraces frozen) {
        var lines=new ArrayList<String>();lines.add("本份报告身份："+frozen.report().id()+"；保留 "+frozen.traces().size()+" / "+frozen.report().runs().size()+" 组原始曲线。");
        for(int i=0;i<frozen.report().runs().size();i++) {var run=frozen.report().runs().get(i);var trace=frozen.find(i);lines.add("第 "+(i+1)+" 组 · "+run.slot()+" · 试次 #"+run.attempt()+"："+(trace==null?"本份没有曲线，不从当前库补造。":trace.samples().size()+" 个原点；版本 "+trace.version()+"；首点 "+Double.toString(trace.samples().get(0).seconds())+" s；最大间隔 "+Double.toString(trace.maximumGap())+" s。"));if(trace!=null) {lines.add(trace.version()==3?"使用九项精确性能参数绑定；外观不参与。":"沿用旧取整参数绑定；不能据此区分很小的微调差异。");lines.add(trace.positions()==null?"未记录位置；只可按原时间轴查看。":"原位置样本与对齐／断线标记保留，不插造采样。");}}
        lines.add("目录与曲线均来自本份冻结内容；本机或收到原库后来改变、移除或损坏不会换用新曲线。");return List.copyOf(lines);
    }
    private void importFile(HudRenderer h,FormulaGame app) {
        h.prompt("完整报告与整圈对照 JSON 路径",exportDefault(app).toString(),512,text->{try {
            var value=TuningLapStudyCodec.read(Path.of(text.strip()));var reviewed=app.tuningStudies.review(value,TuningLapStudyStore.Origin.RECEIVED);var lines=new ArrayList<>(details(value,TuningLapStudyStore.Origin.RECEIVED,app.vehicleSpec.physicsVersion));lines.addAll(reviewed.issues());
            h.review("接收完整冻结对照？",lines,reviewed.valid()?"仅收录独立对照库":"返回检查冲突",()->{if(reviewed.valid()) {app.acceptTuningStudy(reviewed);refresh(app);}});
        }catch(java.io.IOException | RuntimeException error) {app.notifyUser("对照文件无法读取；请检查类型、容量、精确对应与校验，已有内容保持。");}});
    }
    private void export(HudRenderer h,FormulaGame app,TuningLapStudyStore.Entry entry) {
        h.review("导出完整报告与两圈原文？",details(entry.study(),entry.origin(),app.vehicleSpec.physicsVersion),
            "填写独立导出路径",()->h.promptExportPath("导出到玩家资料目录之外",exportDefault(app),
                (path,replace)->app.exportTuningStudy(entry,path,replace)));
    }
    private static Path exportDefault(FormulaGame app) {return app.store.directory().toAbsolutePath().getParent().resolve("tuning-lap-study-export.json");}
    private static String cut(String value,int max) {return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max-1))+"…";}
}
