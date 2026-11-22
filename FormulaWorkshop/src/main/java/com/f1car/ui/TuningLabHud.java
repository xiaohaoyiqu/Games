package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Locale;
import java.util.Objects;

final class TuningLabHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),AMBER=new Color(255,190,114),PANEL=new Color(10,29,39,245);
    private int history;
    private int view;
    private final TuningTraceHud traceHud=new TuningTraceHud();
    private TrialTraceShare received;
    private boolean receivedView;
    private final TuningProjectsHud projectsHud=new TuningProjectsHud();
    private final TuningExplanationHud explanationHud=new TuningExplanationHud();
    private final TuningLapsHud lapsHud=new TuningLapsHud();
    private final TuningStudiesHud studiesHud=new TuningStudiesHud();
    private final PairTrialsHud pairTrialsHud=new PairTrialsHud();
    private TrialTraceShare explanationReceived;
    private boolean explanationReceivedView;
    private boolean pairShown;
    private final TuningRecoveryHud recoveryHud=new TuningRecoveryHud();
    private final TuningTraceRecoveryHud traceRecoveryHud=new TuningTraceRecoveryHud();
    void synchronizeScreen(FormulaGame app) {if(app.screen!=FormulaGame.Screen.TUNING_LAB || !app.pairLabRequested()) pairTrialsHud.closeTrace();if(app.screen!=FormulaGame.Screen.TUNING_LAB || app.pairLabRequested()) {recoveryHud.clear();traceRecoveryHud.clear();}}
    boolean click(float x,float y) {return recoveryHud.showing() || traceRecoveryHud.showing()?false:pairShown?pairTrialsHud.click(x,y):(studiesHud.showing()?studiesHud.click(x,y):lapsHud.click(x,y));}
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        pairShown=app.pairLabRequested();if(pairShown) {pairTrialsHud.render(h,g,app);return;}
        if(recoveryHud.showing()) {recoveryHud.render(h,app);return;}
        if(traceRecoveryHud.showing()) {traceRecoveryHud.render(h,g,app,()->{traceRecoveryHud.clear();recoveryHud.open(app,app.tuningStore.pending());});return;}
        if(studiesHud.showing()) {studiesHud.render(h,g,app);return;}
        if(lapsHud.showing()) {lapsHud.render(h,g,app,()->explanationCurrent(app),()->studiesHud.open(app));return;}
        if(explanationHud.showing()) {explanationHud.render(h,()->showExplanationReport(app,0),()->showExplanationReport(app,2),()->{
            if(explanationCurrent(app)) lapsHud.open(app,explanationHud.brief(),explanationReceivedView?"收到的报告 · "+explanationReceived.author():"本机报告",explanationReceived);
            else app.notifyUser("当前报告或来源已变化，请返回后重新打开。");
        },()->{explanationHud.dismiss();explanationReceived=null;});return;}
        if(projectsHud.showing()) {projectsHud.render(h,app);return;}
        boolean observations=view==1;
        h.panel(28,24,1224,752,PANEL);h.smallButton(60,38,160,30,"完整对照库",()->studiesHud.open(app));h.smallButton(230,38,160,30,"四条件试车记录",app::openPairLab);h.text("A／B 调校试车",60,107,32,INK,true);
        h.smallButton(1060,57,160,42,"返回",app::backFromTuningLab);
        h.smallButton(634,57,192,42,"读取分享曲线",()->importTraces(h,app));
        if(!receivedView && app.tuningReport()!=null) h.smallButton(418,57,200,42,"多项目并排查看",()->projectsHud.open(app));
        if(received!=null) h.smallButton(842,57,200,42,receivedView?"返回本机报告":"查看收到曲线",()->{receivedView=!receivedView;view=2;});
        if(!receivedView) h.smallButton(842,105,200,28,"普通报告恢复副本",()->recoveryHud.open(app,app.tuningStore.pending()));
        if(!receivedView) h.smallButton(1060,105,160,28,"原曲线恢复",()->traceRecoveryHud.open(app,app.tuningTraceStore.pending()));
        h.text("车库快捷槽、命名配置和部件方案均可建立对照；本页“新建”使用快捷槽 A/B，交错各测三次。",60,139,16,SOFT);
        var exercises=TuningTrial.Exercise.values();
        for(int i=0;i<exercises.length;i++) { var exercise=exercises[i];h.smallButton(60+i*390,162,370,60,"新建 "+exercise.title,()->app.beginTuningExperiment(exercise)); }
        var report=receivedView && received!=null?received.report():app.tuningReport();
        if(report==null) {
            h.wrapped("三种试车分别观察加速时间、制动距离和连续弯用时。每组使用相同场地与固定起点；失败不计入六次有效测量，可随时重试。建议先只调整一个项目。",60,302,1080,22,INK,38);
            h.text("A  "+setup(app.store.profile.presets.get("A")),60,465,19,MINT);h.text("B  "+setup(app.store.profile.presets.get("B")),60,505,19,MINT);
        } else {
            boolean current=!receivedView && app.tuningPlan()!=null && app.tuningPlan().id().equals(report.id());
            String place=report.conditions().exercise()==TuningTrial.Exercise.FLOW?report.conditions().trackName():"综合训练场";
            h.text((receivedView?"收到的曲线 · 署名声明："+received.author():current?"当前试车":"历史报告")+" · "+report.conditions().exercise().title+" · "+place+" · "+report.runs().size()+"/6",60,263,18,INK,true);
            h.text("A  "+setup(report.a()),60,295,15,MINT);h.text("B  "+setup(report.b()),60,322,15,MINT);
            if(!receivedView) {h.smallButton(888,291,155,37,"车库采用 A",()->app.adoptTuningReport(TuningExperiment.Slot.A));h.smallButton(1058,291,155,37,"车库采用 B",()->app.adoptTuningReport(TuningExperiment.Slot.B));}
            h.smallButton(888,239,325,34,switch(view) {case 0->"查看悬挂与转向响应";case 1->"查看连续操作记录";default->"查看用时与操作";},()->view=(view+1)%3);
            if(view==2) {traceHud.render(h,g,report,app.tuningTraceStore,receivedView?received:null);h.smallButton(434,668,350,29,"导出当前报告与曲线",()->exportTraces(h,app,report));if(receivedView) h.text("只读查看；未写本机报告、成绩或方案讨论。",800,688,13,AMBER);}
            else {
            if(observations) {
                h.text("组次／试次",66,362,14,SOFT);h.text("方案",180,362,14,SOFT);h.text("前／后均压缩 mm",236,362,14,SOFT);
                h.text("前轮 RMS °",422,362,14,SOFT);h.text("横摆 RMS °/s",563,362,14,SOFT);h.text("观测 s",698,362,14,SOFT);
            } else h.text("组次／试次    方案     测量值        入口 km/h     油门／制动均值     转向幅度",60,362,14,SOFT);
            for(int i=0;i<6;i++) {
                int y=397+i*42;String attempt=i<report.runs().size()?" (#"+report.runs().get(i).attempt()+")":"";h.text((i+1)+attempt,66,y,16,INK,true);h.text(report.order().get(i).toString(),180,y,18,INK,true);
                if(i<report.runs().size()) {
                    var run=report.runs().get(i).measurement();var observed=run.observation();
                    if(!observations) {
                        h.text(number(run.value())+" "+report.conditions().exercise().unit,236,y,17,MINT);
                        h.text(String.format(Locale.ROOT,"%.1f",run.entry()),390,y,16,INK);h.text(String.format(Locale.ROOT,"%.0f%% / %.0f%%",run.throttle()*100,run.brake()*100),522,y,15,SOFT);h.text(String.format(Locale.ROOT,"%.0f%%",run.steeringRms()*100),698,y,15,SOFT);
                    } else if(observed==null) h.text("此组没有车辆观测",236,y,16,SOFT);
                    else {
                        h.text(String.format(Locale.ROOT,"%.1f / %.1f",observed.frontMean()*1000,observed.rearMean()*1000),236,y,16,MINT);
                        h.text(String.format(Locale.ROOT,"%.2f",Math.toDegrees(observed.steerRms())),422,y,16,INK);
                        h.text(String.format(Locale.ROOT,"%.2f",Math.toDegrees(observed.yawRms())),563,y,16,INK);
                        h.text(String.format(Locale.ROOT,"%.2f",observed.seconds()),698,y,15,SOFT);
                    }
                    int index=i;if(current) h.smallButton(765,y-24,98,32,"重测替换",()->app.startTuningRun(index));
                } else h.text("待测",240,y,16,SOFT);
            }
            h.panel(888,343,328,310,new Color(25,50,61));
            if(observations) {
                h.text("读数说明",908,374,19,INK,true);
                h.wrapped("压缩 = 静长 − 当前长度；正值压缩，负值伸长。每轴取两轮平均。",908,411,280,15,INK,25);
                h.wrapped("RMS 表示实际前轮转角和横摆角速度的幅度；只统计测量窗口内有读数的时段。",908,495,280,15,SOFT,25);
                h.wrapped("悬挂位移不能直接当作轮胎载荷，也不据此判定推头或甩尾。",908,579,280,15,AMBER,25);
            } else {
                h.text(report.complete()?"中位数 / 极差":"有效测量进度",908,374,19,INK,true);
                for(var slot:TuningExperiment.Slot.values()) { int y=414+slot.ordinal()*36;h.text(report.complete()?slot+"  "+number(report.median(slot))+" / "+number(report.spread(slot))+" "+report.conditions().exercise().unit:slot+"  已收录 "+report.measurements(slot).size()+" / 3 次",908,y,18,MINT); }
                h.text(report.complete()?"B − A  "+signed(report.delta())+" "+report.conditions().exercise().unit:"六次未完整，暂不比较",908,489,19,INK,true);
                h.wrapped(assessment(report),908,522,280,15,AMBER,24);
            }
            if(current && !report.complete()) h.smallButton(60,661,350,37,"继续第 "+(report.runs().size()+1)+" 组",app::nextTuningRun);
            h.text("未通过测量 "+report.failedAttempts()+" 次 · 成功重测才替换旧数据",440,685,14,SOFT);
            h.text("# 为完成测量次序；重测会改变顺序，交错测量不能消除熟练度与疲劳影响。",60,709,13,SOFT);
            h.text(!report.conditions().physics().equals(app.vehicleSpec.physicsVersion)?"旧物理报告仅供查看；请使用当前车辆版本重新试车。":report.changedSettings()>1?"A／B 同时改变多项设置，不能归因于其中一项。":"三次重复只作描述；驾驶、熟练度与顺序仍会影响结果。",60,730,14,SOFT);
            }
        }
        if(!receivedView) TuningSaveHud.controls(h,app.tuningStore.saveFailure());
        var reports=app.tuningStore.reports();
        if(!reports.isEmpty()) {
            history=Math.floorMod(history,reports.size());h.smallButton(60,741,182,29,"历史报告 "+(history+1)+"/"+reports.size(),()->{receivedView=false;app.viewTuningReport(reports.get(history).id());history=(history+1)%reports.size(); });
        }
        if(app.tuningPlan()!=null) h.smallButton(254,741,180,29,"查看当前试车",()->{receivedView=false;app.viewCurrentTuningPlan();});
        if(report!=null) h.smallButton(446,741,300,29,"报告概览、用途与解释",()->{explanationReceivedView=receivedView;explanationReceived=receivedView?received:null;explanationHud.open(report,receivedView?received.purpose():app.tuningPurpose(),receivedView?TuningReportBrief.Origin.RECEIVED:TuningReportBrief.Origin.LOCAL,receivedView?"收到的曲线 · 署名声明："+received.author():"本机冻结报告",app.vehicleSpec.physicsVersion);});
        if(app.tuningSavePending()) h.smallButton(760,741,456,29,"报告／连续记录尚未落盘 · 重试保存",app::retryTuningSave);
        else if(!receivedView && report!=null && report.complete()) h.smallButton(760,741,456,29,"关联报告与命名部件方案",app::openPartsEvidenceFromReport);
    }
    private void showExplanationReport(FormulaGame app,int view) {
        if(!explanationHud.showing() || app.screen!=FormulaGame.Screen.TUNING_LAB) return;
        if(!explanationCurrent(app)) {
            app.notifyUser("当前报告或来源已变化；冻结概览保持，请返回后重新打开目标报告。");return;
        }
        this.view=view;explanationHud.dismiss();explanationReceived=null;
    }
    private boolean explanationCurrent(FormulaGame app) {
        if(!explanationHud.showing() || app.screen!=FormulaGame.Screen.TUNING_LAB) return false;
        var frozen=explanationHud.brief();var current=receivedView && received!=null?received.report():app.tuningReport();
        var purpose=receivedView && received!=null?received.purpose():app.tuningPurpose();
        return receivedView==explanationReceivedView && (!receivedView || Objects.equals(received,explanationReceived))
            && Objects.equals(current,frozen.report()) && Objects.equals(purpose,frozen.purpose());
    }
    private void importTraces(HudRenderer h,FormulaGame app) {h.prompt("读取过程曲线 JSON 文件路径",app.store.directory().resolve("parts-export-traces.json").toAbsolutePath().toString(),512,text->{try {var value=com.f1car.persistence.TrialTraceCodec.read(java.nio.file.Path.of(text.strip()));h.review("查看提供者的冻结报告与曲线",java.util.List.of("署名声明："+value.author(),value.notes(),"项目："+value.report().conditions().exercise().title+"；路线："+value.report().conditions().trackName(),"物理："+value.report().conditions().physics(),value.traces().size()+" 组曲线。只读查看，不写本机报告、部件、成绩或讨论，不认证提供者数据。"),"只读查看曲线",()->{received=value;receivedView=true;view=2;});}catch(java.io.IOException | RuntimeException error) {app.notifyUser("曲线文件无法读取，请核对格式、容量和摘要；已有资料保留。");}});}
    private void exportTraces(HudRenderer h,FormulaGame app,TuningExperiment.Snapshot report) {
        var traces=new java.util.ArrayList<TuningTrace>();for(int i=0;i<report.runs().size();i++) {var trace=receivedView?received.find(i):app.tuningTraceStore.find(report,i);if(trace!=null) traces.add(trace);}
        if(traces.isEmpty()) {app.notifyUser("这份报告没有可导出的过程曲线。");return;}
        var value=receivedView?received:new TrialTraceShare(app.store.profile.playerName,"本机试车观察，不认证性能收益。",report,app.tuningPurpose(),traces);
        h.promptExportPath("导出报告与曲线 JSON 路径",app.store.directory().resolve("parts-export-traces.json"),
                (path,replace)->app.exportTrialTraces(value,path,replace));
    }
    void driving(HudRenderer h,FormulaGame app) {
        if(app.pairRunning()) {pairTrialsHud.driving(h,app);return;}
        var trial=app.tuningTrial();h.panel(25,101,570,185,PANEL);h.text("A/B · 第 "+(app.tuningIndex()+1)+" 组 · 方案 "+app.tuningPlan().order().get(app.tuningIndex()),46,133,14,MINT,true);
        h.text(trial.exercise().title,46,166,22,INK,true);h.wrapped(app.drivingHint(trial.exercise().instruction),46,196,525,15,INK,23);h.wrapped(app.drivingHint(trial.feedback()),46,252,525,14,AMBER,23);
        h.panel(25,304,465,135,PANEL);h.text("悬挂与转向响应 · 实时",46,333,16,MINT,true);
        var observed=app.player.observation();
        if(observed==null) h.text("等待车辆观测…",46,365,15,SOFT);
        else {
            h.text(String.format(Locale.ROOT,"前 / 后均压缩  %+.1f / %+.1f mm",observed.frontCompression()*1000,observed.rearCompression()*1000),46,363,16,INK);
            h.text(String.format(Locale.ROOT,"前轮转角 %+.1f° · 横摆 %+.1f°/s",Math.toDegrees(observed.frontSteer()),Math.toDegrees(observed.yawRate())),46,391,16,INK);
        }
        h.text("正值压缩，负值伸长；位移不代表轮胎载荷。",46,422,13,SOFT);
    }
    void result(HudRenderer h,FormulaGame app) {
        if(app.pairRunning()) {pairTrialsHud.result(h,app);return;}
        var trial=app.tuningTrial();h.text(trial.exercise().title+" · 方案 "+app.tuningPlan().order().get(app.tuningIndex()),320,347,22,INK,true);
        if(trial.state()==TuningTrial.State.SUCCESS) {
            var result=trial.measurement();h.text(number(result.value())+" "+trial.exercise().unit,320,399,32,MINT,true);
            h.text(String.format(Locale.ROOT,"入口 %.1f km/h · 出口 %.1f km/h",result.entry(),result.exit()),320,443,17,SOFT);
        } else h.text("本次未收录有效测量，旧数据保留。",320,399,19,AMBER);
        h.text("已收录 "+app.tuningPlan().runs().size()+"/6；重测只在成功后替换本组。",320,493,16,SOFT);
        h.smallButton(316,570,198,49,"重测本组  ENTER",app::retry);
        h.smallButton(538,570,198,49,app.tuningPlan().complete()?"查看完整报告":"继续下一组",app::nextTuningRun);
        h.smallButton(760,570,198,49,"返回试车中心",app::openTuningLab);
    }
    private static String setup(Tuning tuning) { return tuning==null?"尚未保存":String.format(Locale.ROOT,"动/刹/悬/翼 %.0f/%.0f/%.0f/%.0f%% · 前刹 %.0f%% · ",tuning.power()*100,tuning.brakes()*100,tuning.suspension()*100,tuning.wing()*100,tuning.brakeBias()*100)+tuning.suspensionSummary(); }
    private static String number(double value) { return Double.isFinite(value)?String.format(Locale.ROOT,"%.3f",value):"—"; }
    private static String signed(double value) { return Double.isFinite(value)?String.format(Locale.ROOT,"%+.3f",value):"—"; }
    private static String assessment(TuningExperiment.Snapshot report) {
        return switch(report.assessment()) {
            case NEED_RUNS -> "完成六组后再比较；时间和距离越小越好。";
            case ENTRY_MISMATCH -> "入口速度不够接近。请重测偏差较大的组，再比较成绩。";
            case SAME_SETUP -> "两套性能设置相同，本组可用于观察自己的重复波动。";
            case SCATTER -> "成绩差不超过组内波动，建议先练稳定性，再重复比较。";
            case OBSERVED_DIFFERENCE -> "这六次中，"+(report.delta()<0?"B":"A")+" 的中位数较小。先观察其它项目，再决定是否采用。";
        };
    }
}
