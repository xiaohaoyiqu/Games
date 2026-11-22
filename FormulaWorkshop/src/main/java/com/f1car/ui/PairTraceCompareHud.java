package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.*;
import java.util.List;

/** Explicit version/run selection. Lists freeze on open; a manual refresh clears both choices. */
final class PairTraceCompareHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),ORANGE=new Color(0xffb267),GREEN=new Color(0x63e6bc),PANEL=new Color(0x13242e);
    private static final int PAGE_SIZE=8;
    private record Option(PairTraceComparison.Source origin,String title,String caption,String trackTitle,List<String> source) {
        private Option {source=List.copyOf(source);}
        PairExperiment.Snapshot report() {return origin.report();}
        boolean current(FormulaGame app) {return switch(origin.kind()) {case LOCAL -> report().equals(app.pairTrials.find(report().id()));case HISTORY -> app.pairTrials.history().contains(origin.history());case RECEIVED -> origin.share().equals(app.receivedPairTrials.find(origin.share().id()));};}
    }
    private boolean showing;private List<Option> options=List.of();private final Option[] selected=new Option[2];private final int[] runs={-1,-1};
    private final List<List<String>> details=new ArrayList<>(List.of(List.of(),List.of()));
    private int sourcePicker=-1,runPicker=-1;private String status="分别选择两侧的完整来源，再明确选择各自成功原组。";
    private boolean independent;private int sourcePage;private PairTraceComparison comparison;
    boolean showing() {return showing;}
    void clear() {showing=false;options=List.of();Arrays.fill(selected,null);Arrays.fill(runs,-1);details.set(0,List.of());details.set(1,List.of());sourcePicker=runPicker=-1;sourcePage=0;independent=false;comparison=null;status="分别选择两侧的完整来源，再明确选择各自成功原组。";}
    void open(FormulaGame app) {open(app,false);}
    private void open(FormulaGame app,boolean crossSource) {
        clear();showing=true;independent=crossSource;var values=new ArrayList<Option>();
        for(var report:app.pairTrials.reports()) values.add(option(PairTraceComparison.Source.local(report)));
        for(var history:app.pairTrials.history()) values.add(option(PairTraceComparison.Source.history(history)));
        if(independent) for(var received:app.receivedPairTrials.entries()) values.add(option(PairTraceComparison.Source.received(received)));
        options=List.copyOf(values);
    }
    private static Option option(PairTraceComparison.Source origin) {
        var report=origin.report();var history=origin.history();
        String title=(origin.kind()==PairTraceComparison.Kind.RECEIVED?origin.label():history==null?"已存报告":"重测前历史")+" · "+report.id().substring(0,8)+" · 原计数 #"+report.lastAttempt()+(history==null?"":" → #"+history.afterAttempt());
        var lines=new ArrayList<>(origin.declaration());lines.add(title);
        lines.addAll(origin.kind()==PairTraceComparison.Kind.RECEIVED?PairTrialSharingHud.details(origin.share()):PairTrialsHud.source(report));
        String caption=origin.kind()==PairTraceComparison.Kind.RECEIVED?origin.label()+" · "+report.id().substring(0,8)+" · 收到原文":title;
        String track=report.conditions().trackName();if(track.codePointCount(0,track.length())>18) track=track.substring(0,track.offsetByCodePoints(0,18))+"…";
        return new Option(origin,title,caption,report.conditions().exercise().title+" · "+track,List.copyOf(lines));
    }
    private void selectSource(int side,Option option) {selected[side]=option;runs[side]=-1;details.set(side,List.of());sourcePicker=-1;updateStatus();}
    private void selectRun(int side,int index) {
        runs[side]=index;runPicker=-1;var option=selected[side];var run=option.report().runs().get(index);var value=run.measurement();var lines=new ArrayList<>(option.source());
        lines.add(0,(side==0?"橙实线":"绿虚线")+" · 第 "+(index+1)+" 组 · "+run.condition().title+" · 成功原试次 #"+run.attempt());
        lines.add("原完成毫秒值 "+run.recordedAt()+"；原测量 "+Double.toString(value.value())+" "+option.report().conditions().exercise().unit+"；入口 "+Float.toString(value.entry())+" km/h；出口 "+Float.toString(value.exit())+" km/h。");
        lines.add("原操作均值：油门 "+Float.toString(value.throttle())+"；制动 "+Float.toString(value.brake())+"；转向 RMS "+Float.toString(value.steeringRms())+"。不同操作、原起点和时间窗口需分别核对。");
        details.set(side,List.copyOf(lines));updateStatus();
    }
    private void updateStatus() {
        comparison=null;
        if(selected[0]==null || selected[1]==null || runs[0]<0 || runs[1]<0) {status="分别选择两侧的完整来源，再明确选择各自成功原组。";return;}
        if(independent) {
            var left=new PairTraceComparison.Pick(selected[0].origin(),runs[0]);var right=new PairTraceComparison.Pick(selected[1].origin(),runs[1]);var issue=PairTraceComparison.issue(left,right);
            if(issue!=PairTraceComparison.Issue.NONE) {status=issue.title;return;}
            comparison=new PairTraceComparison(left,right);status="跨来源只读对照；完整背景差异见预览。"+(comparison.routeAvailable()?"原路线坐标可查看，相同位置不保证相同条件。":comparison.routeRestriction());return;
        }
        if(!PairTrialRevision.sameSource(selected[0].report(),selected[1].report())) {status="两侧不属于同一份冻结试车计划；不能在此叠图。请核对报告身份、路线与完整背景。";return;}
        var a=selected[0].report().runs().get(runs[0]);var b=selected[1].report().runs().get(runs[1]);
        if(a.attempt()==b.attempt()) {status="两侧是同一个成功原试次的副本；不能当作两次驾驶比较。";return;}
        status=a.condition()==b.condition()?"同一冻结配置的两次成功原试次；仍需核对操作、起点和采样间隔，不据此判断收益。":"两侧使用不同的冻结配置；仅对照原始驾驶，不把曲线差归因于单项参数。";
    }
    private void preview(HudRenderer h,FormulaGame app,PairTraceHud trace) {
        if(selected[0]==null || selected[1]==null || runs[0]<0 || runs[1]<0) {app.notifyUser(status);return;}
        var a=selected[0];var b=selected[1];int ai=runs[0],bi=runs[1];
        if(independent?comparison==null:!PairTrialRevision.sameSource(a.report(),b.report()) || a.report().runs().get(ai).attempt()==b.report().runs().get(bi).attempt()) {app.notifyUser(status);return;}
        var frozenComparison=comparison;
        var left=details.get(0);var right=details.get(1);var lines=new ArrayList<String>();lines.add(status);lines.addAll(left);lines.add("—— 绿侧独立原文 ——");lines.addAll(right);
        if(frozenComparison!=null) {lines.add("—— 两侧背景分别核对 ——");lines.addAll(frozenComparison.differences());}
        h.review("明确比较这两份原次？",lines,"只读打开两侧原曲线",()->{
            if(!a.current(app) || !b.current(app)) {app.notifyUser("库中已读来源已变化或移除；保留本页选择，请明确刷新后重新选择，不换成最新版本。");return;}
            var view=frozenComparison==null?new PairTraceView(new PairTraceView.RunSource(a.report(),ai),new PairTraceView.RunSource(b.report(),bi),PairTraceView.Axis.TIME):PairTraceView.compare(frozenComparison,PairTraceView.Axis.TIME);
            trace.openComparison(view,left,right);
        });
    }
    void render(HudRenderer h,FormulaGame app,PairTraceHud trace) {
        h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text("四条件 · 选择两份原次对照",60,107,30,INK,true);
        h.smallButton(630,57,180,38,independent?"切回同计划对照":"切换跨来源对照",()->open(app,!independent));
        h.smallButton(830,57,180,38,"明确刷新来源列表",()->open(app,independent));h.smallButton(1030,57,184,38,"返回原入口",this::clear);
        if(sourcePicker>=0) {
            int side=sourcePicker;h.text((side==0?"橙侧":"绿侧")+(independent?"：跨来源选择；收到资料保持提供者声明，待存候选不进入。":"：明确选一份已存报告或完整历史；不自动选最新。"),60,157,17,SOFT);
            int start=sourcePage*PAGE_SIZE,end=Math.min(options.size(),start+PAGE_SIZE);
            for(int i=start;i<end;i++) {var option=options.get(i);h.smallButton(60,195+(i-start)*55,1154,44,option.title()+" · "+option.report().runs().size()+" 组原测量",()->selectSource(side,option));}
            if(options.isEmpty()) h.text(independent?"没有可选的已存／历史／收到记录；待存候选须先处理。":"没有可选已存报告或历史；待存／收到资料不进入此列表。",60,260,20,SOFT);
            if(options.size()>PAGE_SIZE) {h.smallButton(60,650,350,36,"上一页来源",()->sourcePage=Math.max(0,sourcePage-1));h.text("来源页 "+(sourcePage+1)+" / "+((options.size()+PAGE_SIZE-1)/PAGE_SIZE),470,675,16,SOFT);h.smallButton(800,650,414,36,"下一页来源",()->sourcePage=Math.min((options.size()-1)/PAGE_SIZE,sourcePage+1));}
            h.smallButton(60,704,1154,38,"返回选择，保留两侧原选择",()->sourcePicker=-1);return;
        }
        if(runPicker>=0) {
            int side=runPicker;var option=selected[side];h.text((side==0?"橙侧":"绿侧")+"："+option.title(),60,158,17,SOFT);
            for(int i=0;i<option.report().runs().size();i++) {int index=i;var run=option.report().runs().get(i);h.smallButton(60+(i%4)*290,230+(i/4)*70,274,48,(i+1)+" 组 · "+run.condition().title+" · #"+run.attempt(),()->selectRun(side,index));}
            if(option.report().runs().isEmpty()) h.text("这份快照还没有成功原组；请另选来源。",60,260,20,SOFT);
            h.smallButton(60,704,1154,38,"返回选择，保留原组",()->runPicker=-1);return;
        }
        h.text((independent?"跨来源模式 · ":"同计划模式 · ")+"每侧分别选来源和原组；切换或刷新会清空两侧选择。",60,153,16,SOFT);
        for(int side=0;side<2;side++) {int slot=side,x=60+side*590;var option=selected[side];h.text(side==0?"橙实线 · 所选原次":"绿虚线 · 对照原次",x,189,19,side==0?ORANGE:GREEN);
            h.smallButton(x,209,564,42,option==null?"明确选择完整来源":option.caption(),()->{sourcePicker=slot;sourcePage=0;runPicker=-1;});
            h.smallButton(x,267,564,42,runs[side]<0?"明确选择成功原组":"第 "+(runs[side]+1)+" 组 · #"+option.report().runs().get(runs[side]).attempt(),()->{if(selected[slot]==null) {app.notifyUser("请先选择这一侧的完整来源。");return;}runPicker=slot;sourcePicker=-1;});
            if(option!=null) {h.text(option.trackTitle(),x,347,16,SOFT);h.text("快照有效 "+option.report().runs().size()+" / 12；失败 "+option.report().failedAttempts()+" 次。",x,378,15,SOFT);}
            if(runs[side]>=0) {var run=option.report().runs().get(runs[side]);h.text("原配置："+run.condition().title,x,414,16,INK);h.text(String.format(Locale.ROOT,"原测量 %.6f %s",run.measurement().value(),option.report().conditions().exercise().unit),x,445,16,INK);h.smallButton(x,465,564,38,"核对本侧完整来源、原测量与操作",()->h.review("本侧选中的完整原次",details.get(slot),"返回两侧选择",()->{}));}
        }
        h.wrapped(status,60,548,1154,17,ORANGE,27);h.smallButton(60,620,1154,46,"预览两侧来源后只读打开原曲线",()->preview(h,app,trace));
        h.smallButton(60,704,550,38,"返回原入口，释放本次选择",this::clear);h.smallButton(640,704,574,38,"比较说明",()->h.review("原次对照的范围",List.of(independent?"跨来源模式允许本机、历史和收到的独立报告；只作描述性对照。":"同计划模式只比较同一冻结试车计划中明确选择的不同成功原次。","收到不提升为本机测量；封套身份不认证作者或独立驾驶。","每侧使用自己的完整快照与原时间／位置；不混合报告、不插值补点。","本页不采用配置、不开始重测、不发布报告，也不自动选择较快原次。"),"返回选择",()->{}));
    }
}
