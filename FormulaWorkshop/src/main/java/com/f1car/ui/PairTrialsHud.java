package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.PairTrialStore;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.*;
import java.util.List;

/** Four frozen conditions with explicit run/retest previews and independent report storage. */
final class PairTrialsHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72),PANEL=new Color(0x13242e);
    private PairExperiment.Snapshot cached;private List<PairExperiment.Group> groups=List.of();private List<PairExperiment.Contrast> contrasts=List.of();
    private List<String> source=List.of();private List<List<String>> runs=List.of();private boolean library;
    private final PairTraceHud traceHud=new PairTraceHud();
    private final PairTraceCompareHud compareHud=new PairTraceCompareHud();
    private final PairTrialSharingHud sharingHud=new PairTrialSharingHud();
    private boolean historyPage;private List<PairTrialRevision> cachedHistory=List.of();private List<String> historyLabels=List.of();
    private boolean recoveryPage,recoveryAlreadySaved,cachedRecoveryBlocked;private PairTrialStore.Recovery cachedRecovery;private PairExperiment.Snapshot cachedRecoveryPending;private List<PairExperiment.Snapshot> recoveryReports=List.of();private List<String> recoverySource=List.of();private String recoveryStatus="",pendingRecoveryStatus="";
    boolean click(float x,float y) {return traceHud.click(x,y);}
    void closeTrace() {if(traceHud.showing()) traceHud.clear();compareHud.clear();sharingHud.leave();historyPage=recoveryPage=false;cachedHistory=List.of();historyLabels=List.of();cachedRecovery=null;cachedRecoveryPending=null;cachedRecoveryBlocked=false;recoveryReports=List.of();recoverySource=List.of();recoveryStatus=pendingRecoveryStatus="";recoveryAlreadySaved=false;}
    private void cache(PairExperiment.Snapshot report) {
        cached=report;if(report==null) {groups=List.of();contrasts=List.of();source=List.of();runs=List.of();return;}
        groups=report.groups();contrasts=report.contrasts();source=source(report);var rows=new ArrayList<List<String>>();
        for(int i=0;i<12;i++) {var lines=new ArrayList<String>();var condition=report.order().get(i);lines.add("第 "+(i+1)+" 次 · 第 "+(i/4+1)+" 轮 · "+condition.title);parameters(lines,report.plan(),condition);
            if(i>=report.runs().size()) lines.add("尚无有效测量或过程样本；按冻结顺序完成前组。");
            else {var run=report.runs().get(i);var value=run.measurement();lines.add("原试次 #"+run.attempt()+"；完成毫秒值 "+run.recordedAt());lines.add("原测量 "+Double.toString(value.value())+" "+report.conditions().exercise().unit+"；入口 "+Float.toString(value.entry())+" km/h；出口 "+Float.toString(value.exit())+" km/h。");lines.add("操作均值：油门 "+Float.toString(value.throttle())+"；制动 "+Float.toString(value.brake())+"；转向 RMS "+Float.toString(value.steeringRms())+"。");var observed=value.observation();lines.add(observed==null?"此组没有车辆观测。":"车辆观测：前／后平均压缩 "+observed.frontMean()+" / "+observed.rearMean()+" m；前转角 RMS "+observed.steerRms()+" rad；横摆 RMS "+observed.yawRms()+" rad/s。");var trace=run.trace();double gap=0;for(int n=1;n<trace.samples().size();n++) gap=Math.max(gap,trace.samples().get(n).seconds()-trace.samples().get(n-1).seconds());lines.add("原过程 "+trace.samples().size()+" 点；首点 "+trace.samples().get(0).seconds()+" s；最大间隔 "+gap+" s；"+(trace.positions()==null?"无位置":"原位置与对齐标记保留")+"。这里显示原过程概况，可另开本组原始曲线；原间隔保持。");}
            lines.add("重测只有成功才替换本组；失败另计，原成功测量保持。");rows.add(List.copyOf(lines));}
        runs=List.copyOf(rows);
    }
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        if(traceHud.showing()) {traceHud.render(h,g,app,(report,index)->preview(h,app,report,index));return;}
        if(compareHud.showing()) {compareHud.render(h,app,traceHud);return;}
        if(sharingHud.showing()) {sharingHud.render(h,app,traceHud);return;}
        var report=app.pairReport();if(report!=cached) {cache(report);library=false;}
        if(recoveryPage) {recovery(h,app);return;}
        if(historyPage) {history(h,app);return;}
        if(library || report==null) {library(h,app);return;}
        h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text("四条件试车 · 每条件三次",60,107,30,INK,true);
        h.smallButton(830,57,180,38,"独立记录列表",()->library=true);h.smallButton(1030,57,184,38,"返回 A/B 中心",app::closePairLab);
        h.smallButton(630,57,180,38,"成功重测历史",()->historyPage=true);
        recoveryButton(h,app);
        var plan=report.plan();h.text(plan.first().title+" "+plan.first().percent(plan.tuning(PairExperiment.Condition.BASELINE))+"% → "+plan.firstPercent()+"%；"+plan.second().title+" "+plan.second().percent(plan.tuning(PairExperiment.Condition.BASELINE))+"% → "+plan.secondPercent()+"% · "+report.conditions().exercise().title,60,151,16,SOFT);
        h.smallButton(60,179,350,36,"冻结装备、条件与完整来源",()->h.review("四条件试车的冻结背景",source,"返回四条件报告",()->{}));
        h.smallButton(430,179,350,36,report.complete()?"十二次完成 · 可逐组预览重测":"预览继续第 "+(report.runs().size()+1)+" 次",()->{if(!report.complete()) preview(h,app,report,report.runs().size());});
        h.smallButton(800,179,414,36,"查看当前四条件进度",app::viewCurrentPairReport);
        h.text("三轮各含四条件，原顺序冻结；十二次完成前不比较部分中位数。",60,234,14,AMBER);
        for(int i=0;i<groups.size();i++) {var group=groups.get(i);int x=60+i*294;h.panel(x,249,278,139,new Color(0x203c49));h.text(group.condition().title+" · "+group.count()+" / 3",x+14,277,18,MINT,true);var tuning=plan.tuning(group.condition());h.text(plan.first().title+" "+plan.first().percent(tuning)+"%",x+14,304,14,SOFT);h.text(plan.second().title+" "+plan.second().percent(tuning)+"%",x+14,327,14,SOFT);
            h.text(report.complete()?String.format(Locale.ROOT,"中位 %.3f · 极差 %.3f %s",group.median(),group.spread(),report.conditions().exercise().unit):"等待全部十二次有效测量",x+14,353,14,INK);h.text(report.complete()?String.format(Locale.ROOT,"入口 %.1f km/h",group.entry()):"原次记录可分别查看",x+14,376,13,SOFT);}
        if(report.complete()) {
            h.text(report.comparableEntry()?"描述性条件差 · 仍需核对操作和波动":"入口差异需重测 · 以下仅保留原描述性差值",60,416,16,AMBER);
            for(int i=0;i<contrasts.size();i++) {var contrast=contrasts.get(i);h.text(contrast.label()+String.format(Locale.ROOT,"：%+.3f %s",contrast.delta(),report.conditions().exercise().unit),60+(i%2)*590,447+(i/2)*29,14,INK);}
        } else h.wrapped("四种配置都会独立驾驶。本页不把模型预览当测量，不推断统计交互、因果贡献或整圈收益。",60,430,1154,18,SOFT,28);
        h.text("按冻结顺序查看原次，随后可明确预览试车或重测：",60,511,14,SOFT);
        for(int i=0;i<12;i++) {int index=i;boolean measured=i<report.runs().size();h.smallButton(60+(i%4)*290,531+(i/4)*44,274,34,(i+1)+" · "+report.order().get(i).title+(measured?" · 已收录":" · 待测"),()->h.review("原始第 "+(index+1)+" 次",runs.get(index),measured?"查看本组原始过程":"预览本组试车",()->{if(measured) traceHud.open(report,index);else preview(h,app,report,index);}));}
        h.text("有效 "+report.runs().size()+" / 12；失败 "+report.failedAttempts()+" 次另计。各三次只作描述，不是显著性或最优配置。",60,682,14,AMBER);
        footer(h,app);
    }
    private void library(HudRenderer h,FormulaGame app) {
        h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text("四条件独立报告库",60,107,30,INK,true);h.smallButton(1030,57,184,38,"返回 A/B 中心",app::closePairLab);
        h.smallButton(630,57,180,38,"原次／跨来源对照",()->compareHud.open(app));
        h.smallButton(830,57,180,38,"分享与收到库",()->sharingHud.open(app));
        recoveryButton(h,app);
        h.text("最多四份；原六次 A/B、曲线、车库与工作台分别保持。",60,151,16,SOFT);
        var reports=app.pairTrials.reports();for(int i=0;i<reports.size();i++) {var value=reports.get(i);int y=200+i*95;h.text(value.id().substring(0,8)+" · "+value.conditions().exercise().title+" · "+value.runs().size()+" / 12",60,y+19,19,INK);h.text(value.plan().first().title+" ＋ "+value.plan().second().title+" · "+value.conditions().trackName(),60,y+46,15,SOFT);
            h.smallButton(730,y,220,42,"明确查看这份报告",()->{app.viewPairReport(value.id());library=false;});h.smallButton(970,y,244,42,"移除此份独立报告",()->h.review("移除报告及其重测历史？",List.of(value.id(),"将删除这份报告、原过程及其全部重测前历史；不删除原六次报告或车库。","仍有同身份待存时须先处理；其它记录保持。"),"明确移除此份",()->{app.removePairReport(value);cache(app.pairReport());library=true;}));}
        if(reports.isEmpty()) h.wrapped("从工作台的双参数组合预览进入试车菜单，选择四条件各三次。原草稿、单改两列和组合列分别驾驶，报告独立保存。",60,300,1100,22,SOFT,36);
        if(app.pairPlan()!=null) h.smallButton(60,610,550,38,"查看当前四条件进度／待存",()->{app.viewCurrentPairReport();library=false;});
        h.wrapped(app.pairTrials.warning(),60,663,1154,15,AMBER,22);footer(h,app);
    }
    private void history(HudRenderer h,FormulaGame app) {
        var values=app.pairTrials.history();if(values!=cachedHistory) {cachedHistory=values;var labels=new ArrayList<String>();for(var row:values) {var before=row.before();labels.add(before.id().substring(0,8)+" · "+before.conditions().exercise().title+" · 原计数 #"+before.lastAttempt()+" → #"+row.afterAttempt()+" · 替换 "+row.replacements().size()+" 组");}historyLabels=List.copyOf(labels);}
        h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text("四条件 · 成功重测前的报告",60,107,30,INK,true);
        h.smallButton(630,57,180,38,"原次／跨来源对照",()->compareHud.open(app));
        h.smallButton(830,57,180,38,"分享与收到库",()->sharingHud.open(app));
        recoveryButton(h,app);
        h.smallButton(1030,57,184,38,"返回当前报告",()->historyPage=false);
        h.text("全部报告共 "+values.size()+" / "+com.f1car.persistence.PairTrialStore.HISTORY_CAPACITY+" 份历史；较大记录也受总容量限制，满额不自动淘汰。",60,151,15,SOFT);
        h.wrapped("每份保留替换前完整报告、原测量和曲线。查看不回滚当前报告、不采用装备；失败重测不新增本历史。",60,196,1154,16,SOFT,24);
        for(int i=0;i<values.size();i++) {var row=values.get(i);int y=245+i*88;h.text(historyLabels.get(i),60,y+18,17,INK);h.text("原报告有效 "+row.before().runs().size()+" / 12 · "+row.before().conditions().trackName(),60,y+47,14,SOFT);
            h.smallButton(744,y,224,38,"查看原快照曲线",()->{var lines=new ArrayList<>(source(row.before()));lines.add(0,"这是重测前保存的完整报告，不按当前测量重建旧快照。");for(var change:row.replacements()) lines.add("原第 "+(change.index()+1)+" 组：#"+change.oldAttempt()+" 被新成功试次 #"+change.newAttempt()+" 替换。");h.review("明确查看这份重测前报告",lines,"只读打开原过程",()->traceHud.openHistory(row));});
            h.smallButton(986,y,228,38,"移除此份历史",()->h.review("只移除此份重测前历史？",List.of(row.id(),"这份旧完整报告与其原曲线将被移除；当前报告与其它历史保持。","若已有待存，新候选仍须明确重试，不会因移除历史自动发布。"),"明确移除此份历史",()->app.removePairHistory(row)));
        }
        if(values.isEmpty()) h.wrapped("尚无成功重测前历史。新增测量与失败重测不产生历史；旧程序已经覆盖的原次不能补造。",60,310,1100,20,SOFT,31);
        h.wrapped(app.pairTrials.warning(),60,642,1154,14,AMBER,22);footer(h,app);
    }
    private void recoveryButton(HudRenderer h,FormulaGame app) {h.smallButton(430,57,180,38,app.pairTrials.recoveryReviewNeeded()?"有重开恢复待核对":"待存与重开恢复",()->recoveryPage=true);}
    private void recovery(HudRenderer h,FormulaGame app) {
        var store=app.pairTrials;var entry=store.recovery();var reports=store.reports();
        if(entry!=cachedRecovery || reports!=recoveryReports || store.pending()!=cachedRecoveryPending || store.recoveryBlocked()!=cachedRecoveryBlocked) {
            cachedRecovery=entry;recoveryReports=reports;cachedRecoveryPending=store.pending();cachedRecoveryBlocked=store.recoveryBlocked();recoveryAlreadySaved=false;recoverySource=entry==null?List.of():source(entry.candidate());
            pendingRecoveryStatus=store.pending()!=null?(store.pendingRecoverable()?"本次内存候选已有相同的恢复副本。":"本次内存候选尚无相同的恢复副本，请先处理本次待存。") :"当前没有内存待存候选。";
            if(entry!=null) {var current=store.find(entry.candidate().id());recoveryAlreadySaved=entry.candidate().equals(current);recoveryStatus=recoveryAlreadySaved?"候选与当前已存报告完全相符；可确认后清理副本，不重复发布。":Objects.equals(entry.base(),current)?"原基准与当前已存报告相符；可恢复为待存，再另点保存。":"原基准与当前已存报告不同；两份保持，不能直接覆盖。";}
        }
        h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text("四条件 · 待存与重开恢复",60,107,30,INK,true);h.smallButton(1030,57,184,38,"返回原报告页",()->recoveryPage=false);
        if(entry==null) {
            h.wrapped(store.recoveryBlocked()?"恢复文件读取受阻，原文件保留。修复原文件或另行保留后，再明确重读；本页不覆盖无法解析的内容。":store.pending()!=null?"本次候选仍在内存，恢复副本尚未写入；请返回重试同份待存。关闭程序前请先处理。":"没有待核对的恢复候选。每次发布前写完整候选；成功清理后不保留多份旧恢复文件。",60,176,1154,21,AMBER,34);
        } else {
            var candidate=entry.candidate();h.text(candidate.id().substring(0,8)+" · "+candidate.conditions().exercise().title+" · 有效 "+candidate.runs().size()+" / 12 · 失败 "+candidate.failedAttempts()+" · 计数 #"+candidate.lastAttempt(),60,154,18,INK);
            h.wrapped(recoveryStatus,60,196,1154,17,AMBER,27);
            h.smallButton(60,256,550,42,"核对候选的冻结装备与完整来源",()->h.review("待存恢复候选的完整来源",recoverySource,"返回恢复页",()->{}));
            if(!candidate.runs().isEmpty()) h.smallButton(640,256,574,42,"只读查看候选原曲线",()->h.review("明确查看未发布候选？",List.of("本次只读打开恢复文件中的原测量与曲线。","不会发布报告、安装装备或开始驾驶；返回后仍须明确处理。"),"只读打开候选曲线",()->traceHud.openRecovery(candidate)));
            h.wrapped("恢复副本和已存报告分别保留。重开不会自动恢复或重发；恢复为待存后还须另点保存，再按冻结车型、路线与操作预览试车。",60,338,1154,18,SOFT,31);
            h.text(pendingRecoveryStatus,60,450,16,SOFT);
            if(store.pending()==null && !store.recoveryBlocked()) {
                boolean alreadySaved=recoveryAlreadySaved;String status=recoveryStatus;
                h.smallButton(60,520,550,42,alreadySaved?"确认已经保存并清理恢复副本":"明确恢复为待存 · 暂不保存",()->h.review(alreadySaved?"确认已存内容并清理副本？":"恢复原候选为待存？",List.of(status,"确认时重新读取已存报告，并核对同份恢复文件；变化后须重新查看。","本操作不开始驾驶、不采用装备。"),alreadySaved?"确认已存并清理":"只恢复为待存",()->{if(alreadySaved?app.acknowledgePairRecovery(entry):app.restorePairRecovery(entry)) recoveryPage=false;}));
                h.smallButton(640,520,574,42,"明确放弃这一份恢复副本",()->h.review("放弃恢复候选？",List.of(candidate.id(),"只放弃这份恢复副本；若候选尚未发布，其中新测量不能再从此处恢复。","已存报告和重测历史保持；确认时再核对恢复文件。"),"明确放弃这一份",()->{if(app.discardPairRecovery(entry)) recoveryPage=false;}));
            }
        }
        h.wrapped(store.warning(),60,610,1154,15,AMBER,23);
        h.smallButton(60,704,350,38,"返回原报告页",()->recoveryPage=false);h.smallButton(430,704,350,38,"修复后明确重读恢复文件",app::reloadPairRecovery);h.smallButton(800,704,414,38,"查看保存与恢复边界",()->h.review("四条件保存与恢复",List.of("报告发布前先写候选及其完整原基准；恢复写入失败时，本次候选只在内存。","报告已发布但清理副本失败时，正式保存仍成功，重开后核对相同内容再清理。","保存中的未完成驾驶尚无新测量；恢复文件不能补造未完成的一组，也不保证断电持久性。","损坏或外部变化的恢复文件保留；修复后明确重读，不自动覆盖。"),"返回恢复页",()->{}));
    }
    private void footer(HudRenderer h,FormulaGame app) {
        h.smallButton(60,704,350,38,"返回原入口",app::backFromTuningLab);
        if(app.pairTrials.pending()!=null) {h.smallButton(430,704,350,38,"重试同份四条件待存",app::retryPairSave);h.smallButton(800,704,414,38,"明确取消未存候选",()->h.review("取消尚未保存的候选？",List.of("本次未发布的新测量或新计划会从活动上下文及其恢复副本中放弃。","恢复副本清理失败时仍保留候选，不误报取消成功。","此前成功保存的报告保持；取消后不能从原候选继续驾驶。"),"明确取消未存候选",app::cancelPairSave));}
        else if(app.pairTrials.recoveryReviewNeeded()) {h.smallButton(430,704,350,38,"先核对重开恢复副本",()->recoveryPage=true);h.smallButton(800,704,414,38,"修复后明确重读报告库",()->{app.pairTrials.reload();cache(app.pairReport());});}
        else {h.smallButton(430,704,350,38,"修复后明确重读报告库",()->{app.pairTrials.reload();if(cached!=null) app.viewPairReport(cached.id());cache(app.pairReport());library=true;});h.smallButton(800,704,414,38,"本页状态与保存说明",()->h.review("四条件报告状态",List.of(app.pairTrials.warning().isEmpty()?"当前没有待存警告。":app.pairTrials.warning(),"每次完成后明确发布到独立库；失败保留原候选供重试。","未完成记录可在相同车型配置、路线版本与操作条件下预览重开；不会自动采用或安装装备。"),"返回报告",()->{}));}
    }
    private void preview(HudRenderer h,FormulaGame app,PairExperiment.Snapshot report,int index) {
        if(index>report.runs().size()) {app.notifyUser("按冻结顺序先完成前组；已完成组可单独预览重测。");return;}var reviewed=app.reviewPairRun(report,index);if(reviewed==null) {app.notifyUser("报告未在当前库中、仍有待存或背景已变化；请先处理状态并重新查看。");return;}
        var lines=new ArrayList<>(runs.get(index));lines.add("实际开始第 "+(index+1)+" 次 · "+report.order().get(index).title+"；重新开始当前项目，不写车库或工作台。");lines.add("确认时再核对完整车型配置、路线、操作与报告；成功才替换原测量。");h.review("明确开始这一组？",lines,"开始冻结四条件试车",()->app.beginPairRun(reviewed));
    }
    static List<String> source(PairExperiment.Snapshot report) {
        var lines=new ArrayList<String>();var plan=report.plan();var c=report.conditions();lines.add("独立报告 "+report.id()+"；创建毫秒值 "+report.createdAt());lines.add("车型 "+plan.model().vehicle()+"；物理 "+c.physics()+"；模型配置身份 "+plan.modelIdentity());lines.add("路线 "+c.trackId()+" · "+c.trackName()+"；几何 "+c.geometry());lines.add("操作身份 "+c.controls()+"；项目 "+c.exercise().title);lines.add("排程种子 "+report.seed()+"；实际顺序以本份十二项顺序为准："+report.order());
        for(var condition:PairExperiment.Condition.values()) parameters(lines,plan,condition);
        for(var slot:PartsCatalog.Slot.values()) {var part=plan.equipment().definitions().get(slot);lines.add("冻结装备 "+slot.title+"："+part.name()+" · "+part.id()+"；点数 "+part.points());lines.add("用途："+part.purpose()+"；代价："+part.tradeoff());for(var field:slot.fields()) {var range=part.settings().get(field);lines.add(field.title+" 范围 "+range.min()+"–"+range.max()+"%，默认 "+range.initial()+"%。");}}
        lines.add("三轮各含四条件，轮内随机；这不消除熟练度、疲劳、操作和顺序影响。");lines.add("条件差是各三次中位数的描述，不是均值因子效应、统计交互或因果贡献。每组可查看本份原始曲线，独立交换另在分享与收到库中明确选择。");return List.copyOf(lines);
    }
    private static void parameters(List<String> lines,PairExperiment.Plan plan,PairExperiment.Condition condition) {var tuning=plan.tuning(condition);lines.add(condition.title+" 的完整实际参数：");for(var field:PartsCatalog.Field.values()) lines.add(field.title+"："+Float.toString(field.ratio(tuning))+" 倍。");}
    void driving(HudRenderer h,FormulaGame app) {
        var report=app.pairPlan();var trial=app.tuningTrial();h.panel(25,101,590,200,PANEL);h.text("四条件 · 第 "+(app.pairIndex()+1)+" / 12 次 · "+report.order().get(app.pairIndex()).title,46,133,18,MINT,true);h.text(trial.exercise().title,46,168,22,INK,true);h.wrapped(app.drivingHint(trial.exercise().instruction.replace("六次","十二次")),46,204,545,15,INK,24);h.wrapped(app.drivingHint(trial.feedback().replace("六次","十二次")),46,266,545,14,AMBER,23);
    }
    void result(HudRenderer h,FormulaGame app) {
        var report=app.pairPlan();var trial=app.tuningTrial();h.text("四条件第 "+(app.pairIndex()+1)+" 次 · "+report.order().get(app.pairIndex()).title,320,347,21,INK,true);
        if(trial.state()==TuningTrial.State.SUCCESS) h.text(String.format(Locale.ROOT,"%.3f %s",trial.measurement().value(),trial.exercise().unit),320,400,31,MINT,true);else h.text("本次失败，不替换旧成功测量。",320,399,18,AMBER);
        h.text("有效 "+report.runs().size()+" / 12；失败 "+report.failedAttempts()+" 次。",320,448,17,SOFT);h.wrapped(app.pairTrials.warning(),320,491,640,15,AMBER,23);
        h.smallButton(316,570,198,49,"重试本组 ENTER",app::retry);h.smallButton(538,570,198,49,report.complete()?"查看完整报告":"继续下一组",app::nextPairRun);h.smallButton(760,570,198,49,"四条件报告",app::openPairLab);
    }
}
