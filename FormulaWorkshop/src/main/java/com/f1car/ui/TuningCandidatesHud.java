package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.TuningProjectStudy.*;
import java.awt.Color;
import java.util.*;

/** Session-only comparison of up to three candidates; summaries and choices freeze on actions. */
final class TuningCandidatesHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private boolean open;private FormulaGame.TuningProjectsReview review;private TuningCandidateStudy study;
    private int choosing=-1;private TuningTrial.Exercise exercise;private List<Choice> choices=List.of();
    private record Choice(Report report,Match match,String summary) {}
    boolean showing() {return open;}
    void open(FormulaGame app) {refresh(app);open=review!=null;}
    private void refresh(FormulaGame app) {
        var next=app.reviewTuningProjects();if(next==null) return;review=next;
        study=new TuningCandidateStudy(next.study().anchor(),next.study().candidates().stream().map(Candidate::report).toList());choosing=-1;choices=List.of();
    }
    void render(HudRenderer h,FormulaGame app) {
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("CANDIDATE COMPARISON",60,65,14,MINT,true);h.text("共用基准 A · 多个候选 B",60,108,30,INK,true);
        h.smallButton(820,57,190,38,"明确刷新并重选",()->refresh(app));h.smallButton(1030,57,184,38,choosing<0?"返回项目页":"返回候选页",()->{if(choosing>=0) choosing=-1;else open=false;});
        boolean current=app.tuningProjectsCurrent(review);
        h.wrapped(current?"基准参数与来源相同；各格仍保留自己报告的 A 测量。相同设置不代表同一场次，不合并试次。":"本机报告或来源已变化 · 保留旧读数；请明确刷新后重新选择。",60,151,1154,16,current?SOFT:GOLD,24);
        if(choosing>=0) {renderChoices(h,app);return;}
        for(int i=0;i<TuningCandidateStudy.MAX_COLUMNS;i++) {
            final int column=i;int x=60+390*i;var project=study.columns().get(i);
            h.text("候选 "+(i+1)+(i==0?" · 原报告锚点":""),x,222,19,INK,true);
            if(i>0) {h.smallButton(x,238,218,34,"选择／更换候选",()->{if(check(app)) choose(column,null);});h.smallButton(x+228,238,144,34,"清空整列",()->{if(check(app)) study=study.clearCandidate(column);});}
            else h.text("原 B 固定；各项目分别选择",x,260,15,SOFT);
            if(project==null) {h.text("尚未选择候选 B",x,319,18,GOLD);continue;}
            h.text("B "+(project.anchor().purpose()==null?"来源未记录":cut(project.anchor().purpose().b().title(),14))+" · "+id(project.anchor()),x,296,14,SOFT);
            for(var row:project.rows()) {
                int y=328+126*row.exercise().ordinal();var selected=row.report();
                h.text(row.exercise().title+" · "+row.exercise().unit,x,y,17,INK,true);
                if(selected==null) h.text("未选择，不补写结果",x,y+24,15,GOLD);
                else {
                    h.text(id(selected)+" · "+row.runs()+"/6 · "+(row.coverage()==Coverage.FROZEN_PARTS?"冻结部件对应":"仅参数对应"),x,y+23,13,SOFT);
                    h.text(row.complete()?"A "+number(row.medianA())+" / "+number(row.spreadA())+"   B "+number(row.medianB())+" / "+number(row.spreadB()):"待完成六组，不判断高低",x,y+45,14,INK);
                    h.text(row.complete()?String.format(Locale.ROOT,"B − A %+.3f %s · %s",row.delta(),row.exercise().unit,assessment(row)):"—",x,y+67,14,GOLD);
                }
                h.smallButton(x,y+76,132,28,"选择报告",()->{if(check(app)) choose(column,row.exercise());});
                if(selected!=null) {h.smallButton(x+142,y+76,106,28,"记录详情",()->details(h,selected));h.smallButton(x+258,y+76,114,28,"清除此格",()->{if(check(app)) study=study.clearProject(column,row.exercise());});}
            }
        }
        h.wrapped("每格：中位数／极差，单位见项目；各列的 A 测量也可能不同。候选列不计算总分或直接 B 对 B 的胜负；更换候选会清空该列的其他项目选择。",60,728,1154,15,GOLD,24);
    }
    private void choose(int column,TuningTrial.Exercise selectedExercise) {
        choosing=column;exercise=selectedExercise;
        choices=exercise==null?study.candidates().stream().map(c->new Choice(c.report(),study.candidateMatch(column,c.report().snapshot().id()),summary(c.report()))).toList():
            study.columns().get(column).candidates().stream().filter(c->c.report().snapshot().conditions().exercise()==exercise).map(c->new Choice(c.report(),c.match(),summary(c.report()))).toList();
    }
    private void renderChoices(HudRenderer h,FormulaGame app) {
        h.text("候选 "+(choosing+1)+" · "+(exercise==null?"明确选择 B；该列其他项目会清空":exercise.title+"；须该列同一组 A／B"),60,208,18,INK,true);
        for(int i=0;i<choices.size();i++) {var choice=choices.get(i);var report=choice.report();int y=238+49*i;
            h.text(id(report)+" · "+report.snapshot().conditions().exercise().title+" · "+report.snapshot().runs().size()+"/6",60,y+24,16,INK);
            h.text(choice.match().eligible()?choice.match().coverage()==Coverage.FROZEN_PARTS?"冻结基准及条件对应":"仅参数对应，来源不全":cut(choice.match().issues().get(0),41),380,y+15,14,choice.match().eligible()?SOFT:GOLD);
            h.text(choice.summary(),380,y+37,13,SOFT);
            h.smallButton(1030,y,184,36,choice.match().eligible()?"明确选择这份":"查看不同条件",()->{
                if(!choice.match().eligible()) {h.review("此报告不能放入这列",choice.match().issues(),"返回选择",()->{});return;}
                if(check(app)) {study=exercise==null?study.chooseCandidate(choosing,report.snapshot().id()):study.chooseProject(choosing,exercise,report.snapshot().id());choosing=-1;choices=List.of();}
            });
        }
        if(choices.isEmpty()) h.wrapped("本次冻结列表没有此项目的报告。完成同条件试车后，再明确刷新并选择。",60,285,1120,19,GOLD,30);
    }
    private boolean check(FormulaGame app) {if(app.tuningProjectsCurrent(review)) return true;app.notifyUser("本机报告或来源已变化，请明确刷新并重新选择候选。");return false;}
    private static void details(HudRenderer h,Report report) {
        var lines=new ArrayList<>(TuningInterpretation.explain(report.snapshot(),report.purpose(),report.snapshot().conditions().physics()));
        if(report.purpose()!=null) for(var part:report.purpose().b().definitions()) {
            lines.add("冻结 B 范围 · "+part.name()+"（点数 "+part.points()+"，不是性能评分）");
            for(var field:part.slot().fields()) {var range=part.settings().get(field);String value=Float.toString(field.ratio(report.snapshot().b())*100);lines.add(field.title+"：采用 "+value+"%，允许 "+range.min()+"–"+range.max()+"%，默认 "+range.initial()+"%。");}
        }
        lines.add("此格使用自己的 A 三次测量；跨报告相同基准不证明同一场次或可直接作 B 对 B 检验。");
        h.review("候选的冻结记录与装备范围",lines,"返回候选页",()->{});
    }
    private static String assessment(Row row) {return switch(row.assessment()) {case NEED_RUNS->"待完成";case ENTRY_MISMATCH->"入口偏差";case SAME_SETUP->"同参数";case SCATTER->"波动覆盖";case OBSERVED_DIFFERENCE->"观察差异";};}
    private static String summary(Report report) {
        var changes=TuningInterpretation.changes(report.snapshot().a(),report.snapshot().b());
        String values=changes.isEmpty()?"九项性能参数相同":String.join("，",changes.stream().limit(2).map(c->String.format(Locale.ROOT,"%s %.2f%%",c.field().title,c.b()*100)).toList())+(changes.size()>2?" 等 "+changes.size()+" 项":"");
        return cut((report.purpose()==null?"B 来源未记录":report.purpose().b().title())+" · "+values,44);
    }
    private static String id(Report report) {return report.snapshot().id().substring(0,8);}
    private static String number(double n) {return String.format(Locale.ROOT,"%.3f",n);}
    private static String cut(String text,int length) {return text.length()<=length?text:text.substring(0,length-1)+"…";}
}
