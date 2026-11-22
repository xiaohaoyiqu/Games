package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Read-only frozen report selections. Matching and summaries are calculated only on explicit actions. */
final class TuningProjectsHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private static final DateTimeFormatter WHEN=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss",Locale.ROOT);
    private boolean open;private FormulaGame.TuningProjectsReview review;private TuningProjectStudy study;private TuningTrial.Exercise choosing;
    private final TuningCandidatesHud candidatesHud=new TuningCandidatesHud();
    boolean showing() {return open;}
    void open(FormulaGame app) {refresh(app);open=review!=null;}
    private void refresh(FormulaGame app) {var next=app.reviewTuningProjects();if(next!=null) {review=next;study=next.study();choosing=null;}}
    void render(HudRenderer h,FormulaGame app) {
        if(candidatesHud.showing()) {candidatesHud.render(h,app);return;}
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("PROJECT COMPARISON",60,65,14,MINT,true);h.text("同一组 A／B · 多项目并排",60,108,30,INK,true);
        h.smallButton(820,57,190,38,"明确刷新并重选",()->refresh(app));h.smallButton(1030,57,184,38,choosing==null?"返回报告":"返回并排页",()->{if(choosing!=null) choosing=null;else open=false;});
        boolean current=app.tuningProjectsCurrent(review);h.wrapped(current?(app.tuningSavePending()?"本机冻结报告（含尚未保存的测量）；":"本机冻结报告；")+"各项目明确选择一份，不合并测量，不根据时间替你挑选。":"本机报告或来源已变化 · 保留冻结读数；刷新前不改变所选报告。",60,151,1154,16,current?SOFT:GOLD,24);
        if(choosing!=null) {choices(h,app);return;}
        h.text("锚点："+study.anchor().snapshot().conditions().exercise().title+" · "+shortId(study.anchor().snapshot().id())+"；仅外观差异不影响参数匹配。",60,210,15,SOFT);
        h.text("项目／报告",60,254,15,SOFT);h.text("A 中位数／极差",380,254,15,SOFT);h.text("B 中位数／极差",620,254,15,SOFT);h.text("B − A",860,254,15,SOFT);
        for(int i=0;i<study.rows().size();i++) {var row=study.rows().get(i);int y=302+i*119;var selected=row.report();h.text(row.exercise().title,60,y,19,INK,true);h.smallButton(1030,y-24,184,34,"选择本机报告",()->{if(check(app)) choosing=row.exercise();});
            if(selected==null) {h.text("未选择，不补写结果",380,y,17,GOLD);continue;}
            h.text(shortId(selected.snapshot().id())+" · "+row.runs()+"/6",60,y+28,14,SOFT);String unit=row.exercise().unit;
            h.text(row.complete()?number(row.medianA())+" / "+number(row.spreadA())+" "+unit:"待完成六组",380,y,17,INK);h.text(row.complete()?number(row.medianB())+" / "+number(row.spreadB())+" "+unit:"—",620,y,17,INK);h.text(row.complete()?String.format(Locale.ROOT,"%+.3f %s",row.delta(),unit):"—",860,y,17,MINT);
            h.text(assessment(row),380,y+28,14,GOLD);h.smallButton(1030,y+20,84,29,"详情",()->details(h,row));h.smallButton(1124,y+20,90,29,"清除",()->{if(check(app)) study=study.clear(row.exercise());});h.text(row.coverage()==TuningProjectStudy.Coverage.FROZEN_PARTS?"两侧冻结部件一致":"部件来源不全 · 仅参数对应",60,y+53,14,SOFT);
        }
        h.wrapped("时间与距离各按原单位显示；入口偏差、未完成、同参数和波动分别保留。这里不合成总分、不推断圈速或最优设置，也不采用任何方案。",60,673,1154,16,GOLD,25);
        h.smallButton(60,728,550,38,"条件与来源匹配规则",()->h.review("并排查看的边界",List.of("A／B 性能参数须同方向对应；不自动互换，不按同名、日期或部件点数推断。","路线身份／几何、物理和记录的操作身份须一致；直线项目也不忽略原报告条件。","有用途来源时核对两侧完整方案和冻结定义／说明；没有完整来源则只能标参数对应，已知与未知来源不混合。","不查当前部件目录补写历史，不合并试次，不计算总分，不改变存档、测量或车库。","新报告、同身份重测或来源变化后保留本页旧读数，明确刷新后重新选择。"),"返回并排页",()->{}));
        h.smallButton(630,728,584,38,"共用基准 A · 比较多个候选 B",()->candidatesHud.open(app));
    }
    private void choices(HudRenderer h,FormulaGame app) {
        h.text("选择："+choosing.title+"；条件不同的报告可查看原因。",60,208,18,INK,true);int index=0;
        for(var candidate:study.candidates()) if(candidate.report().snapshot().conditions().exercise()==choosing) {var report=candidate.report().snapshot();int y=238+index++*49;h.text(choiceId(report)+" · "+report.runs().size()+"/6",60,y+24,16,INK);h.text(candidate.match().eligible()?(candidate.match().coverage()==TuningProjectStudy.Coverage.FROZEN_PARTS?"冻结部件及条件对应":"仅参数对应，来源不全"):cut(candidate.match().issues().get(0),41),380,y+24,15,candidate.match().eligible()?SOFT:GOLD);h.smallButton(1030,y,184,36,candidate.match().eligible()?"明确选择这份":"查看不同条件",()->{if(!candidate.match().eligible()) {h.review("这份报告不合并",candidate.match().issues(),"返回选择",()->{});return;}if(check(app)) {study=study.choose(choosing,report.id());choosing=null;}});}
        if(index==0) h.wrapped("本次冻结列表没有此项目的报告。先返回报告页，从装备工作台用相同 A／B 条件完成所需项目，再明确刷新这里。",60,285,1120,19,GOLD,30);
    }
    private boolean check(FormulaGame app) {if(app.tuningProjectsCurrent(review)) return true;app.notifyUser("本机报告或来源已变化，请明确刷新后重新选择。");return false;}
    private static void details(HudRenderer h,TuningProjectStudy.Row row) {var report=row.report();var lines=new ArrayList<String>();lines.add("冻结报告："+report.snapshot().id());lines.add(row.exercise().title+" · "+report.snapshot().conditions().trackName()+" · "+report.snapshot().conditions().physics()+" · "+report.snapshot().conditions().controls());lines.add(assessment(row));if(report.purpose()!=null) {lines.add("原问题："+report.purpose().intent().question());lines.add("作者预期："+report.purpose().intent().expectation());lines.add("关心的代价："+report.purpose().intent().concern());}else lines.add("旧报告未记录用途与完整部件来源，不按当前目录补写。");lines.add("油门、制动、转向及入口速度仍可能不同；即使三项目完整，也不是因果、收益或最优设置认证。");h.review("所选项目的冻结记录",lines,"返回并排页",()->{});}
    private static String assessment(TuningProjectStudy.Row row) {return switch(row.assessment()) {case NEED_RUNS->"未完成六组，不判断高低。";case ENTRY_MISMATCH->"入口速度不够接近，先重测。";case SAME_SETUP->"性能参数相同，观察重复波动。";case SCATTER->"差值被组内波动覆盖，先稳定重复性。";case OBSERVED_DIFFERENCE->"本次观察到中位数差异，未证明收益或因果。";};}
    private static String shortId(String value) {return value.substring(0,8);}private static String cut(String value,int length) {return value.length()<=length?value:value.substring(0,length-1)+"…";}private static String number(double value) {return String.format(Locale.ROOT,"%.3f",value);}
    private static String choiceId(TuningExperiment.Snapshot report) {return shortId(report.id())+" · "+WHEN.format(Instant.ofEpochMilli(report.createdAt()).atZone(ZoneId.systemDefault()));}
}
