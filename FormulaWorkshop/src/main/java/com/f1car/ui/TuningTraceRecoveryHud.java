package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.*;
import java.util.List;

/** Frozen original sampling, separate report/trace restoration, and explicit confirmations. */
final class TuningTraceRecoveryHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),AMBER=new Color(0xffbe72),GREEN=new Color(0x4cebc1);
    private boolean showing,memory,unavailable,curves,canStageReport;
    private TuningTraceStore.Recovery view;private TuningStore.Archive reports;
    private TuningTraceStore.Relation relation=TuningTraceStore.Relation.CONFLICT;
    private FrozenTuningTraces frozen;private final TuningTraceHud traceHud=new TuningTraceHud();
    private List<String> details=List.of(),facts=List.of(),sampleDetails=List.of();private int sampleIndex;private String status="",warning="";
    boolean showing() {return showing;}
    void clear() {showing=memory=unavailable=curves=canStageReport=false;view=null;reports=null;frozen=null;relation=TuningTraceStore.Relation.CONFLICT;details=facts=sampleDetails=List.of();sampleIndex=0;status=warning="";traceHud.clearFrozen();}
    void open(FormulaGame app,boolean memoryView) {
        clear();showing=true;memory=memoryView;var store=app.tuningTraceStore;view=memoryView?store.pendingRecovery():store.recovery();reports=app.tuningStore.accepted();
        unavailable=store.damaged() || store.recoveryBlocked() || app.tuningStore.damaged();warning=store.warning+" "+store.recoveryWarning();
        relation=store.relation(view,reports.reports());canStageReport=view!=null && !unavailable && !app.tuningStore.pending() && !app.tuningStore.recoveryNeedsAttention() && view.reportBaseMatches(reports.reports());
        status=unavailable?"曲线库、副本或报告读取受阻；保留原文，修复后明确重读。":view==null?"此侧没有原曲线候选；纯库清理仍在试车中心明确重试，不补造采样。":switch(relation) {case READY->"已存报告匹配原次；可以明确恢复曲线为待存，再单独保存。";case ALREADY_SAVED->"匹配报告与原曲线已经保存；核对后只清理副本。";case CONFLICT->"此报告／组号的原曲线基准已变化；保留两份，不直接覆盖。";case REPORT_MISSING->"尚无匹配这份原次的已存报告；先处理报告，再恢复曲线。";};
        var lines=new ArrayList<String>();var rows=new ArrayList<String>();
        if(view!=null) {
            var trace=view.candidate();var run=view.report().runs().get(trace.index());frozen=new FrozenTuningTraces(view.report(),List.of(trace));
            rows.add("报告 "+view.report().id().substring(0,8)+" · 原第 "+(trace.index()+1)+" 组 "+run.slot()+" · 原试次 #"+run.attempt());
            rows.add(trace.samples().size()+" 个原点；首点 "+trace.samples().get(0).seconds()+" s；最大间隔 "+trace.maximumGap()+" s。");
            rows.add((trace.version()==3?"精确参数绑定；":"旧取整参数绑定保持；")+(trace.positions()==null?"原位置缺失，未补造。":"原位置与对齐标记保持。"));
            lines.add("副本只含这一原次，不用一份旧曲线库覆盖其它记录；其它曲线按当前保留报告的原规则筛选。");
            lines.add("完整报告 "+view.report().id()+"；创建毫秒 "+view.report().createdAt()+"；原完成毫秒 "+run.recordedAt()+"；绑定 "+trace.binding()+"；记录版本 "+trace.version()+"。");
            lines.add("原报告基准："+(view.reportBase()==null?"此前没有同身份已存报告":view.reportBase().id()+" · "+view.reportBase().runs().size()+" / 6 次 · 原失败 "+view.reportBase().failedAttempts()));
            lines.add("原曲线基准："+(view.base()==null?"此前该组没有曲线":view.base().binding()+" · "+view.base().samples().size()+" 个原点"));
            var conditions=view.report().conditions();lines.add("路线 "+conditions.trackId()+" / "+conditions.trackName()+"；原几何 "+conditions.geometry()+"；物理 "+conditions.physics()+"；操作 "+conditions.controls()+"。");
            for(var field:PartsCatalog.Field.values()) lines.add(field.title+"：A "+Float.toString(field.ratio(view.report().a()))+"；B "+Float.toString(field.ratio(view.report().b()))+"。");
            lines.addAll(TuningInterpretation.explain(view.report(),view.purpose(),app.vehicleSpec.physicsVersion));
            for(int i=0;i<view.report().runs().size();i++) {var original=view.report().runs().get(i);var measurement=original.measurement();lines.add("所属报告原第 "+(i+1)+" 组 "+original.slot()+" · #"+original.attempt()+" · 完成毫秒 "+original.recordedAt()+"；原测量 "+Double.toString(measurement.value())+" "+conditions.exercise().unit+"；入口／出口 "+Float.toString(measurement.entry())+" / "+Float.toString(measurement.exit())+" km/h。");}
            lines.add("原测量 "+Double.toString(run.measurement().value())+" "+view.report().conditions().exercise().unit+"；原采样时间、输入、位置不作重采样或插值。");
            lines.add("当前已读正式报告库 "+reports.reports().size()+" 份，最多八份；从副本恢复报告会沿原规则更新同身份或淘汰最旧一份，须另点保存。");
            for(var report:reports.reports()) lines.add("当前已存："+report.id()+" · "+report.conditions().exercise().title+" · "+report.runs().size()+" / 6 次。");
            updateSample(0);
        }
        if(store.recoveryFailure()!=null) {var failure=store.recoveryFailure();lines.add("副本保存阶段 "+failure.stage()+"；尝试 "+failure.attempt()+"；该次替换已返回成功="+failure.replaced());for(var cause:failure.causes()) lines.add("副本异常类别 "+cause.type()+"；文件系统原因 "+cause.filesystemReason());}
        if(store.saveFailure()!=null) {var failure=store.saveFailure();lines.add("曲线库保存阶段 "+failure.stage()+"；尝试 "+failure.attempt()+"；该次替换已返回成功="+failure.replaced());for(var cause:failure.causes()) lines.add("曲线库异常类别 "+cause.type()+"；文件系统原因 "+cause.filesystemReason());}
        addTiming(lines,"正式曲线库",store.saveTiming());
        addTiming(lines,"写入恢复副本",store.recoveryWriteTiming());
        addTiming(lines,"清理恢复副本",store.recoveryClearTiming());
        facts=List.copyOf(rows);details=List.copyOf(lines);
    }
    static void addTiming(List<String> lines,String title,SaveDiagnostics.Timing timing) {
        if(timing==null) return;
        lines.add(title+" · 最近一次处理 #"+timing.attempt()+" · "+milliseconds(timing.elapsedNanos())
                +(timing.encodedBytes()>=0?" · "+timing.encodedBytes()+" 字节":"")
                +" · 替换返回成功 "+(timing.replaced()?"是":"否"));
        var stages=new StringJoiner(" · ");
        for(var work:SaveDiagnostics.Work.values()) {
            Long elapsed=timing.workNanos().get(work);
            if(elapsed==null) continue;
            String label=switch(work) {
                case READ_CURRENT->"读取旧库";
                case ENCODE->"编码";
                case BACKUP->"备份处理";
                case WRITE_TEMP->"写入及刷新";
                case VERIFY->"复核";
                case MOVE->"原子替换";
                case FALLBACK_MOVE->"备用替换";
                case DELETE_TEMP->"清理临时文件";
            };
            stages.add(label+" "+milliseconds(elapsed));
        }
        if(stages.length()>0) lines.add(stages.toString());
    }
    private static String milliseconds(long nanos) {return String.format(Locale.ROOT,"%.3f ms",nanos/1_000_000.0);}
    private void updateSample(int index) {
        var trace=view.candidate();sampleIndex=Math.max(0,Math.min(trace.samples().size()-1,index));var sample=trace.samples().get(sampleIndex);var position=trace.positions()==null?null:trace.positions().get(sampleIndex);
        sampleDetails=List.of("原报告 "+view.report().id()+" · 原第 "+(trace.index()+1)+" 组 · 绑定 "+trace.binding()+"。",
            "原样本 "+(sampleIndex+1)+" / "+trace.samples().size()+"；原时间 "+Double.toString(sample.seconds())+" s；速度 "+Float.toString(sample.speed())+" m/s（"+Float.toString(sample.speed()*3.6f)+" km/h）。",
            "原输入：油门 "+Float.toString(sample.throttle())+"；制动 "+Float.toString(sample.brake())+"；转向 "+Float.toString(sample.steer())+"。",
            position==null?"原位置缺失，不补造。":"原位置 x="+Float.toString(position.x())+" m，z="+Float.toString(position.z())+" m，路线="+Float.toString(position.route())+" m；原对齐="+position.aligned()+"。",
            "这是副本中的原样本，未插值，不推断同步状态、轮胎力、真实驾驶或改装收益。");
    }
    void render(HudRenderer h,Graphics2D g,FormulaGame app,Runnable reportRecovery) {
        var selected=view;var original=reports;boolean memoryView=memory;h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("普通 A／B · 原曲线恢复",60,98,29,INK,true);h.smallButton(1030,57,184,38,"返回试车中心",this::clear);
        h.text("先核对匹配报告，再恢复原曲线；副本保留实际原采样，不补造缺失数据。",60,139,17,AMBER);
        h.smallButton(60,170,350,38,"查看本次内存原曲线",()->open(app,true));h.smallButton(430,170,350,38,"查看已读磁盘副本",()->open(app,false));h.smallButton(800,170,414,38,"明确重读曲线与报告",()->{app.reloadTuningTraceRecovery();open(app,memoryView);});
        if(curves && frozen!=null) {
            h.text("恢复副本原过程 · 只读查看，未发布本机曲线库",60,252,21,GREEN,true);h.wrapped(status,60,289,1154,16,AMBER,25);traceHud.renderFrozen(h,g,frozen);
            h.smallButton(430,668,190,29,"前一个原样本",()->updateSample(sampleIndex-1));h.smallButton(630,668,190,29,"后一个原样本",()->updateSample(sampleIndex+1));h.smallButton(850,668,364,29,"返回恢复核对",()->curves=false);
            h.smallButton(60,741,1154,29,"查看原样本 "+(sampleIndex+1)+" / "+selected.candidate().samples().size()+" 的完整原值",()->h.review("恢复副本原样本",sampleDetails,"返回原过程",()->{}));return;
        }
        h.text(memoryView?"本次内存原曲线 · 打开时冻结":"已读原曲线副本 · 打开时冻结",60,252,22,GREEN,true);h.wrapped(status,60,295,1154,17,AMBER,28);
        int y=385;for(var line:facts) {h.text(line,60,y,16,SOFT);y+=36;}
        if(!warning.isBlank()) h.wrapped(warning,60,530,1154,15,AMBER,25);
        if(!details.isEmpty()) h.smallButton(60,585,1154,36,selected==null?"查看保存诊断":"核对原次、来源与保存诊断",()->h.review(selected==null?"原曲线保存诊断":"原曲线恢复的完整来源",details,"返回恢复页",()->{}));
        if(selected==null) return;
        h.smallButton(60,632,550,32,"只读查看副本原曲线与原样本",()->curves=true);h.smallButton(640,632,574,32,"查看普通报告恢复副本",reportRecovery);
        if(memoryView) h.smallButton(60,684,550,40,"核对后重试保存原次",()->h.review("保存这份原报告与曲线？",details,"明确重试保存",()->{if(!Objects.equals(selected,app.tuningTraceStore.pendingRecovery())) app.notifyUser("内存原曲线已变化，请重新核对。");else app.retryTuningSave();open(app,app.tuningTraceStore.pending());}));
        else if(!unavailable && !app.tuningTraceStore.pending() && relation==TuningTraceStore.Relation.READY) h.smallButton(60,684,550,40,"只恢复曲线为待存（不发布）",()->h.review("恢复这一原次为待存？",details,"只恢复原曲线为待存",()->{app.restoreTuningTraceRecovery(selected);open(app,app.tuningTraceStore.pending());}));
        else if(!unavailable && !app.tuningTraceStore.pending() && relation==TuningTraceStore.Relation.ALREADY_SAVED) h.smallButton(60,684,550,40,"核对已存，仅清理曲线副本",()->h.review("确认原次已经保存？",details,"仅清理已存曲线副本",()->{app.acknowledgeTuningTraceRecovery(selected);open(app,false);}));
        else if(!unavailable && relation==TuningTraceStore.Relation.REPORT_MISSING && canStageReport) h.smallButton(60,684,550,40,"先恢复所属报告为待存",()->h.review("从原曲线副本恢复所属报告？",details,"只恢复报告为待存",()->{app.stageTuningReportFromTraceRecovery(selected,original);open(app,false);}));
        h.smallButton(640,684,574,40,memoryView?"明确放弃内存原曲线":"明确放弃所选磁盘副本",()->{var lines=new ArrayList<>(details);lines.add("将放弃所选原曲线；其原采样不能从测量摘要重建。报告与其它原曲线保持。");h.review("确认放弃所选原采样？",lines,"明确放弃所选内容",()->{app.discardTuningTraceRecovery(selected,memoryView);open(app,memoryView && app.tuningTraceStore.pending());});});
        h.text("新旧记录绑定不改写；没有自动恢复、自动续测或跨文件事务保证。",60,755,14,SOFT);
    }
}
