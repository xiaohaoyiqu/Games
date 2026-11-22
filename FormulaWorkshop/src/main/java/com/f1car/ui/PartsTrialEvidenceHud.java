package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.PartsTrialEvidenceCodec;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, separate evidence library. Viewing a provider's report never creates local measurements. */
final class PartsTrialEvidenceHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private boolean open,chooseSelectedPlan;private int mode,page,planIndex,reportIndex,linkPage;private String selected="",author="",notes="";private TuningExperiment.Slot slot=TuningExperiment.Slot.B;private FormulaGame.PartsEvidenceRequest request;
    boolean showing() {return open;}
    void open() {open=true;mode=0;chooseSelectedPlan=true;}
    void close() {open=false;}
    void render(HudRenderer h,FormulaGame app,PartsPlan selectedPlan,Consumer<PartsPlanShare> viewPlan,Consumer<PartsTrialEvidence> discuss) {
        if(chooseSelectedPlan) {planIndex=selectedPlan==null?0:Math.max(0,app.partsPlans.entries().indexOf(selectedPlan));chooseSelectedPlan=false;}
        if(app.partsEvidenceRequest()!=null && request!=app.partsEvidenceRequest()) {request=app.partsEvidenceRequest();mode=1;reportIndex=0;for(int i=0;i<app.tuningStore.reports().size();i++) if(app.tuningStore.reports().get(i).id().equals(request.report().id())) reportIndex=i;}
        h.panel(50,210,1174,477,new Color(25,50,61));h.text(mode==1?"关联六组试车与命名方案":mode==2?"明确选择本地关联卡":"方案与试车记录",600,194,23,MINT,true);
        if(mode==1) capture(h,app);else if(mode==2) links(h,app);else library(h,app,viewPlan,discuss);
        if(!app.partsEvidence.warning().isEmpty()) h.text(cut(app.partsEvidence.warning(),68),60,680,13,AMBER);
    }
    private void library(HudRenderer h,FormulaGame app,Consumer<PartsPlanShare> viewPlan,Consumer<PartsTrialEvidence> discuss) {
        h.smallButton(60,214,244,34,"接收方案试车记录",()->importFile(h,app));h.smallButton(316,214,244,34,"重新读取记录库",app.partsEvidence::reload);h.smallButton(600,214,294,34,"关联本地报告与命名卡",()->mode=1);h.smallButton(914,214,300,34,"返回命名方案库",this::close);
        var entries=app.partsEvidence.entries();int pages=Math.max(1,(entries.size()+4)/5);page=Math.min(page,pages-1);if(entries.stream().noneMatch(e->e.evidence().id().equals(selected))) selected=entries.isEmpty()?"":entries.get(0).evidence().id();
        for(int i=page*5;i<Math.min(entries.size(),page*5+5);i++) {var row=entries.get(i);int y=265+(i-page*5)*65;h.smallButton(60,y,500,36,(row.evidence().id().equals(selected)?"● ":"")+cut(row.evidence().plan().plan().name(),24),()->selected=row.evidence().id());h.text(row.origin().title+" · "+row.evidence().report().conditions().exercise().title,72,y+57,13,SOFT);}
        h.smallButton(60,628,180,32,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages+" · "+entries.size()+" / 16",270,650,14,SOFT);h.smallButton(380,628,180,32,"下一页",()->page=Math.min(pages-1,page+1));var entry=app.partsEvidence.find(selected);
        if(entry==null) {h.wrapped("先完成六组本地 A/B，并另存对应的命名方案；也可接收别人提供的方案与试车记录。这里不写本机测量或成绩。",600,295,590,20,SOFT,32);return;}var value=entry.evidence();
        h.text(cut(value.plan().plan().name(),28),600,286,22,INK,true);h.text(entry.origin().title+" · 提供者："+cut(value.plan().author(),24),600,321,16,SOFT);h.text(value.correspondence().title,600,352,16,MINT);h.text(entry.status(app.partsPlans.find(entry.localPlanId())).title,600,383,15,AMBER);
        h.smallButton(600,410,294,36,"查看完整报告与限制",()->h.review("提供者记录 · 非性能认证",details(value,app.vehicleSpec.physicsVersion),"关闭记录",()->{}));h.smallButton(914,410,300,36,"查看方案并分别采用",()->viewPlan.accept(value.plan()));
        h.smallButton(600,457,294,36,"选择本地命名卡关联",()->{mode=2;linkPage=0;});h.smallButton(914,457,300,36,"解除本地关联",()->h.review("解除这份记录的本地关联？",List.of("原方案、完整试车快照和本机报告保持。"),"解除关联",()->app.linkPartsEvidence(entry,null)));
        h.smallButton(600,504,294,36,"预览并导出这份记录",()->export(h,app,entry));h.smallButton(914,504,300,36,"移除此记录副本",()->h.confirm("移除此份方案试车记录？","命名卡、本机试车报告、部件与外部文件保持。",()->app.removePartsEvidence(entry)));
        h.smallButton(600,552,614,34,"围绕此方案与报告讨论",()->discuss.accept(value));
        h.wrapped("提供者可编辑文件；校验不认证真实驾驶。完整六组仍可能入口失配或波动较大，需自行试车。",600,613,590,16,AMBER,31);
    }
    private void capture(HudRenderer h,FormulaGame app) {
        h.smallButton(60,214,500,34,"返回方案试车记录库",()->mode=0);h.smallButton(600,214,614,34,"打开本机试车报告",app::openPartsTuningReport);var plans=app.partsPlans.entries();var reports=app.tuningStore.reports();
        if(plans.isEmpty() || reports.stream().noneMatch(TuningExperiment.Snapshot::complete)) {h.wrapped("需要至少一张命名部件卡和一份已保存的完整六组 A/B 报告。先试车并另存方案；收到的记录不能充作本机测量。",70,320,1100,22,SOFT,36);return;}
        planIndex=Math.floorMod(planIndex,plans.size());reportIndex=Math.floorMod(reportIndex,reports.size());var plan=plans.get(planIndex);var report=reports.get(reportIndex);if(author.isEmpty()) author=app.store.profile.playerName;
        h.smallButton(60,264,244,34,"上一张命名卡",()->planIndex=Math.floorMod(planIndex-1,plans.size()));h.smallButton(316,264,244,34,"下一张命名卡",()->planIndex=(planIndex+1)%plans.size());h.text(cut(plan.name(),31),600,287,20,MINT,true);
        h.smallButton(60,312,244,34,"上一份本机报告",()->reportIndex=Math.floorMod(reportIndex-1,reports.size()));h.smallButton(316,312,244,34,"下一份本机报告",()->reportIndex=(reportIndex+1)%reports.size());h.text(report.conditions().exercise().title+" · "+report.runs().size()+" / 6 · "+cut(report.conditions().trackName(),16),600,335,17,INK);
        h.smallButton(60,360,244,36,(slot==TuningExperiment.Slot.A?"● ":"")+"对应报告 A",()->slot=TuningExperiment.Slot.A);h.smallButton(316,360,244,36,(slot==TuningExperiment.Slot.B?"● ":"")+"对应报告 B",()->slot=TuningExperiment.Slot.B);h.smallButton(600,360,614,36,"本次署名："+cut(author,24),()->h.editText("本次记录署名",author,"玩家",40,value->author=value));h.smallButton(600,408,614,36,"补充说明 · 编辑",()->h.editText("记录说明（可换行）",notes,"",320,value->notes=value));
        h.wrapped("选择同参数的一侧。若试车时记录了该侧部件，还须对应四槽身份、定义、数值与预算；没有部件来源时只标为参数对应。",72,438,480,17,SOFT,28);h.wrapped("六组完整不代表结论充分。将保留原始测量、入口速度、操作、物理、路线条件及所有限制；不会写成绩或自动采用。",600,488,590,17,AMBER,28);
        h.smallButton(600,600,614,42,"预览完整内容并关联",()->{try {var catalog=app.partsStore.catalog();var share=PartsPlanShare.capture(plan,author,app.partsPlans.trail(plan.id()),catalog);var purpose=app.purposeForReport(report);var value=PartsTrialEvidence.capture(share,report,purpose,slot,notes);h.review("关联方案与试车快照？",details(value,app.vehicleSpec.physicsVersion),"保存独立关联记录",()->{if(app.capturePartsEvidence(plan,value,catalog)!=null) {selected=value.id();mode=0;}});}catch(IllegalArgumentException error) {app.notifyUser("命名卡与所选侧的参数、物理或部件不对应，或六组尚未完成；请重新选择。");}});
    }
    private void links(HudRenderer h,FormulaGame app) {var entry=app.partsEvidence.find(selected);if(entry==null) {mode=0;return;}h.smallButton(60,214,500,34,"返回记录库",()->mode=0);var plans=app.partsPlans.entries().stream().filter(plan->plan.build().equals(entry.evidence().plan().plan().build())).toList();int pages=Math.max(1,(plans.size()+4)/5);linkPage=Math.min(linkPage,pages-1);for(int i=linkPage*5;i<Math.min(plans.size(),linkPage*5+5);i++) {var plan=plans.get(i);h.smallButton(60,276+(i-linkPage*5)*62,1154,40,cut(plan.name(),45)+" · 部件与参数对应，查看后关联",()->h.review("明确关联这张本地卡？",List.of("本地卡："+plan.name()+"；身份："+plan.id(),"仅保存本地关系；原始分享、本机报告、参数和车库保持。"),"关联这张命名卡",()->{if(app.linkPartsEvidence(entry,plan)) mode=0;}));}if(plans.isEmpty()) h.wrapped("没有部件、参数和预算都对应的本地命名卡。可返回记录库查看方案，分别另存后再关联；不会按同名自动选择。",80,330,1090,21,SOFT,34);h.smallButton(60,628,300,32,"上一页",()->linkPage=Math.max(0,linkPage-1));h.smallButton(914,628,300,32,"下一页",()->linkPage=Math.min(pages-1,linkPage+1));}
    private void importFile(HudRenderer h,FormulaGame app) {h.prompt("方案试车记录 JSON 完整路径",app.store.directory().resolve("parts-export-trial-evidence.json").toAbsolutePath().toString(),512,text->{try {var value=PartsTrialEvidenceCodec.read(Path.of(text.strip()));h.review("接收提供者的方案试车记录？",details(value,app.vehicleSpec.physicsVersion),"只收录独立记录",()->{if(app.receivePartsEvidence(value)) {selected=value.id();mode=0;}});}catch(java.io.IOException | RuntimeException error) {app.notifyUser("记录无法读取，请核对格式、对应、大小与校验；已有内容保留。");}});}
    private void export(HudRenderer h,FormulaGame app,PartsTrialEvidence.Entry entry) {
        h.review("分享冻结的方案与试车记录？",details(entry.evidence(),app.vehicleSpec.physicsVersion),"填写独立导出路径",
                ()->h.promptExportPath("方案试车记录导出路径",app.store.directory().resolve("parts-export-trial-evidence.json"),
                        (path,replace)->app.exportPartsEvidence(entry,path,replace)));
    }
    static List<String> details(PartsTrialEvidence value,String physics) {
        var lines=new ArrayList<String>();var report=value.report();var conditions=report.conditions();
        lines.add("方案："+value.plan().plan().name()+"；署名（声明）："+value.plan().author()+"；对应报告 "+value.slot());
        lines.add("对应范围："+value.correspondence().title+"；车型未单独记录在旧报告中，不能据此跨车型认证。");
        lines.add("记录身份："+value.id()+"；方案身份："+value.plan().plan().id());lines.add("报告身份："+report.id()+"；创建时间（提供者毫秒值）："+report.createdAt());
        lines.add("路线身份："+conditions.trackId()+"；名称："+conditions.trackName());lines.add("路线几何："+conditions.geometry());lines.add("物理："+conditions.physics()+"；操作："+conditions.controls());
        lines.add("交错顺序："+report.order()+"；方案车型声明："+value.plan().plan().build().vehicle()+"；预算："+value.plan().plan().build().budget());
        parameters(lines,"A",report.a());parameters(lines,"B",report.b());
        lines.add("方案备注："+value.plan().plan().notes()+"；记录说明："+value.notes());lines.addAll(TuningInterpretation.explain(report,value.purpose(),physics));
        lines.add("以下六次原始数据均来自提供者快照，未在接收者本机重测：");
        for(var run:report.runs()) {
            var m=run.measurement();lines.add(String.format(Locale.ROOT,"%s · 试次 %d · %.3f %s · 入口 %.1f / 出口 %.1f km/h · 油门 %.1f%% / 制动 %.1f%% / 转向输入 RMS %.1f%%",run.slot(),run.attempt(),m.value(),conditions.exercise().unit,m.entry(),m.exit(),m.throttle()*100,m.brake()*100,m.steeringRms()*100));
            lines.add("记录时间（提供者毫秒值）："+run.recordedAt());var observed=m.observation();
            if(observed==null) lines.add("本次车辆观测缺失，不补零。");
            else lines.add(String.format(Locale.ROOT,"车辆观测窗口 %.3f s；前／后压缩均值 %.3f / %.3f mm；前轮转角 RMS %.5f rad；横摆 RMS / 峰值 %.5f / %.5f rad/s。",observed.seconds(),observed.frontMean()*1000,observed.rearMean()*1000,observed.steerRms(),observed.yawRms(),observed.yawPeak()));
        }
        lines.add("附命名方案、完整近期来源与相关部件包；另行查看和采用。此处不含完整圈遥测、地图、比赛成绩或资格，校验不证明真实驾驶、因果或最优设置。");return List.copyOf(lines);
    }
    private static void parameters(List<String> lines,String slot,Tuning value) {
        lines.add(String.format(Locale.ROOT,"%s 参数：动力 %.4f%%、制动 %.4f%%、悬挂 %.4f%%、尾翼 %.4f%%；涂装编号 %d。",slot,value.power()*100,value.brakes()*100,value.suspension()*100,value.wing()*100,value.livery()));
        lines.add(String.format(Locale.ROOT,"%s 前刹 %.4f%%；前／后弹簧 %.4f / %.4f%%；前／后阻尼 %.4f / %.4f%%。",slot,value.brakeBias()*100,value.frontSpring()*100,value.rearSpring()*100,value.frontDamping()*100,value.rearDamping()*100));
    }
    private static String cut(String value,int limit) {return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit-1))+"…";}
}
