package com.f1car.ui;

import com.f1car.model.*;
import java.awt.Color;
import java.util.*;

/** Read-only frozen overview; full sources and every original observation remain available on request. */
final class TuningExplanationHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private boolean open;private TuningReportBrief brief;private String sourceLabel="";
    private List<String> fullExplanation=List.of(),qualityDetails=List.of();
    private List<List<String>> groupLines=List.of();
    boolean showing() {return open;}
    void open(TuningExperiment.Snapshot report,TuningPurpose purpose,TuningReportBrief.Origin origin,String sourceLabel,String physics) {
        brief=new TuningReportBrief(report,purpose,origin,physics);this.sourceLabel=Objects.requireNonNull(sourceLabel);
        fullExplanation=TuningInterpretation.explain(report,purpose,physics);
        var quality=new ArrayList<String>(brief.conditions());quality.add("下一步建议");quality.addAll(brief.nextSteps());qualityDetails=List.copyOf(quality);
        var groups=new ArrayList<List<String>>();
        for(var group:brief.groups()) groups.add(group.complete()?List.of(
            String.format(Locale.ROOT,"中位数 %.3f %s",group.median(),report.conditions().exercise().unit),
            String.format(Locale.ROOT,"极差 %.3f %s",group.spread(),report.conditions().exercise().unit),
            String.format(Locale.ROOT,"入口中位数 %.1f km/h",group.entry()))
            :List.of("已收录 "+group.runs()+" / 3 次", "六次未完整，不比较中位数", "原次记录可分别查看"));
        groupLines=List.copyOf(groups);open=true;
    }
    void dismiss() {open=false;brief=null;fullExplanation=qualityDetails=List.of();groupLines=List.of();sourceLabel="";}
    TuningReportBrief brief() {return brief;}
    void render(HudRenderer h,Runnable rawRuns,Runnable curves,Runnable laps,Runnable close) {
        h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("试车报告 · 先看结果与限制",60,99,30,INK,true);
        h.smallButton(770,57,240,38,"选择装备整圈对照",laps);
        h.smallButton(1030,57,184,38,"返回试车中心",close);
        h.text(cut(sourceLabel,48)+" · "+brief.report().conditions().exercise().title+" · "+brief.controls(),60,143,16,SOFT);
        h.smallButton(60,190,350,37,"参数变化与模型代价",()->h.review("参数作用与取舍",brief.parameterLines(),"返回报告概览",()->{}));
        h.smallButton(430,190,350,37,"完整来源与原解释",()->h.review("试车问题与完整冻结来源",fullExplanation,"返回报告概览",()->{}));
        h.smallButton(800,190,414,37,"条件、原次记录与限制",()->h.review("条件与原始观察",qualityDetails,"返回报告概览",()->{}));
        h.text(brief.headline(),60,273,24,brief.currentPhysics()?MINT:AMBER,true);
        h.wrapped(brief.observation(),60,311,1154,18,INK,27);
        for(int i=0;i<brief.groups().size();i++) {
            int x=60+i*590;h.panel(x,370,564,127,new Color(0x203c49));h.text(brief.groups().get(i).slot()+" · "+brief.groups().get(i).runs()+" / 3 次",x+20,402,20,MINT,true);
            var lines=groupLines.get(i);h.text(lines.get(0),x+20,434,18,INK);h.text(lines.get(1),x+20,463,16,SOFT);h.text(lines.get(2),x+20,488,14,SOFT);
        }
        h.text("三次重复只作描述；极差不是置信区间，操作与入口仍可能影响结果。",60,526,16,AMBER);
        h.text("试车问题："+cut(brief.question(),58),60,563,17,SOFT);
        h.text(brief.report().changedSettings()>1?"同时改变 "+brief.report().changedSettings()+" 项参数，不能分离单项贡献。":"本项目不能替代其它项目与同条件整圈。",60,594,16,AMBER);
        h.wrapped("下一步："+brief.nextSteps().get(0),60,632,1154,17,INK,25);
        h.smallButton(60,705,550,38,"查看这份报告的六次记录",rawRuns);
        h.smallButton(630,705,584,38,"查看这份报告的过程曲线",curves);
        h.text("本页冻结打开时的报告与来源；没有自动采用、生成测量或改写成绩。",60,764,13,AMBER);
    }
    private static String cut(String text,int max) {return text.codePointCount(0,text.length())<=max?text:text.substring(0,text.offsetByCodePoints(0,max-1))+"…";}
}
