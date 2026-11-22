package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.Locale;

final class TuningGuideHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),AMBER=new Color(255,190,114);
    private TuningGuide.Goal selected;
    private int goalPage;
    private FormulaGame.GuidedTuningReview review;
    void render(HudRenderer h,FormulaGame app) {
        if(selected==null) selected=TuningGuide.suggested(app.circuit);
        var goal=selected;var baseline=app.store.profile.tuning;
        if(review==null || review.goal()!=goal || !app.guidedTuningCurrent(review)) review=app.reviewGuidedTuning(goal);
        var expected=review;var preview=expected==null?null:expected.preview();var candidate=preview==null?baseline:preview.candidate();boolean available=preview!=null && preview.available();
        h.panel(28,24,1224,752,new Color(10,29,39,247));h.text("SETUP WORKSHOP",60,65,14,MINT,true);h.text("调校向导",60,107,32,INK,true);
        h.smallButton(1060,57,160,42,"返回车库",()->app.open(FormulaGame.Screen.GARAGE));
        h.text("先选一个观察目标，再以当前配置作 A、只改一项作 B。车库中的 A/B 存档保留。",60,139,16,SOFT);
        var options=TuningGuide.Goal.values();for(int index=goalPage*6;index<Math.min(options.length,goalPage*6+6);index++) { var option=options[index];int y=171+(index-goalPage*6)*61;h.smallButton(60,y,285,49,(goal==option?"● ":"")+option.title,()->selected=option); }
        h.smallButton(60,550,285,38,goalPage==0?"制动分配／前后悬挂 →":"← 返回基础调校目标",()->{goalPage=1-goalPage;selected=options[goalPage*6];});
        h.wrapped("本图可先观察："+TuningGuide.suggested(app.circuit).title+"。这是试车起点，请结合自己的路线和操作比较。",60,624,280,17,SOFT,27);
        h.text(goal.title+" · "+goal.parameter,383,194,25,MINT,true);
        h.text("A 当前  "+setup(baseline),383,229,15,INK);h.text("B 候选  "+setup(candidate),383,258,15,INK);
        h.wrapped(goal.instruction,383,296,805,18,INK,27);
        h.wrapped(goal.tradeoff,383,370,805,16,AMBER,25);
        String limit=preview==null?"装备记录未就绪或车库与装备不符；返回车库处理后再试车。":(preview.installed()==null?"自由调校":cut(preview.installed().definitions().get(PartsCatalog.Field.tuningKey(goal.field).slot()).name(),16))+" · 范围 "+preview.range().min()+"–"+preview.range().max()+"% · 本次 "+String.format(Locale.ROOT,"%+.1f",preview.changePercent())+" 个百分点"+(preview.limited()?" · 受装备边界限制":"");
        h.text(limit,383,433,14,available?MINT:AMBER);
        h.text("相对原厂的模型系数",383,469,18,INK,true);h.text("A 当前",851,469,15,SOFT);h.text("B 候选",1036,469,15,SOFT);
        var a=TuningGuide.effects(baseline);var b=TuningGuide.effects(candidate);
        for(int i=0;i<a.size();i++) { int y=497+i*18;h.text(a.get(i).title(),383,y,15,SOFT);h.text(percent(a.get(i).stockRatio()),851,y,15,INK);h.text(percent(b.get(i).stockRatio()),1036,y,15,MINT); }
        h.smallButton(383,662,487,45,available?"开始六次 A/B 对照试车":"装备固定、已到边界或待处理",()->{
            if(!available) return;
            h.confirm("开始新的对照试车？","项目："+goal.exercise.title+"\nA 为当前车库配置，B 只改变"+goal.parameter+"。\n"+limit+(preview.installed()==null?"":"\n已装四槽定义与本次实际 A/B 参数随报告保存。")+"\n当前试车计划将被替换，已保存的报告保留。",()->app.beginGuidedTuning(expected));
        });
        h.smallButton(890,662,305,45,available?"直接采用候选配置":"当前候选不可采用",()->{if(available) app.applyGuidedTuning(expected);});
        h.text("系数不是圈速预测；下压力有上限。先比较入口速度、重复波动，再看哪套适合自己。",383,744,14,SOFT);
    }
    private static String cut(String text,int max) {return text.codePointCount(0,text.length())<=max?text:text.substring(0,text.offsetByCodePoints(0,max-1))+"…";}
    private static String percent(float value) { return String.format(Locale.ROOT,"%.1f%%",value*100); }
    private static String setup(Tuning value) { return String.format(Locale.ROOT,"动/刹/悬/翼 %.0f/%.0f/%.0f/%.0f%% · 前刹 %.0f%% · ",value.power()*100,value.brakes()*100,value.suspension()*100,value.wing()*100,value.brakeBias()*100)+value.suspensionSummary(); }
}
