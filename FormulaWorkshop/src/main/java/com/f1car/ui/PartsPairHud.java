package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.PartsCatalog.Field;
import java.awt.Color;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Two selected equipment values, cached model columns, explicit adoption, A/B or four-condition trials. */
final class PartsPairHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private boolean open,trialMenu;private FormulaGame.SetupRangeReview review;
    private Field first=Field.POWER,second=Field.WING;private int firstPercent,secondPercent,speedKph=100;
    private PartsRangePreview.Pair pair;private List<Row> rows=List.of();
    private record Row(String label,List<String> values) {Row {values=List.copyOf(values);}}
    boolean showing() {return open;}
    void open(FormulaGame app) {if(refresh(app)) {open=true;trialMenu=false;}}
    private boolean refresh(FormulaGame app) {var next=app.reviewSetupRange();if(next==null) return false;review=next;firstPercent=next.ranges().draftPercent(first);secondPercent=next.ranges().draftPercent(second);calculate();return true;}
    private void calculate() {
        pair=review.ranges().pair(first,firstPercent,second,secondPercent);speedKph=Math.min(speedKph,pair.maximumKph());var readings=pair.atKph(speedKph);var result=new ArrayList<Row>();
        result.add(row("每后轮请求驱动力 · N",readings,r->r.sample().rearWheelDriveNewtons()));
        result.add(row("每前轮制动冲量输入",readings,r->r.sample().frontWheelBrakeInput()));result.add(row("每后轮制动冲量输入",readings,r->r.sample().rearWheelBrakeInput()));
        result.add(row("前轮弹簧系数",readings,r->r.column().setup().front().stiffness()));result.add(row("后轮弹簧系数",readings,r->r.column().setup().rear().stiffness()));
        result.add(row("前轮压缩阻尼系数",readings,r->r.column().setup().front().compression()));result.add(row("后轮压缩阻尼系数",readings,r->r.column().setup().rear().compression()));
        result.add(row("前轮回弹阻尼系数",readings,r->r.column().setup().front().rebound()));result.add(row("后轮回弹阻尼系数",readings,r->r.column().setup().rear().rebound()));
        result.add(row("整车空气阻力 · N",readings,r->r.sample().dragNewtons()));
        result.add(row("空气阻力功率 · kW",readings,r->r.sample().dragPowerKilowatts()));
        result.add(new Row(PartsEffectsHud.DRIVE_MARGIN,readings.stream().map(r->PartsEffectsHud.driveMarginLabel(r.sample())).toList()));
        result.add(new Row(PartsEffectsHud.DRAG_BALANCE,readings.stream()
                .map(r->PartsEffectsHud.dragBalanceLabel(r.column().setup(),pair.maximumKph())).toList()));
        result.add(new Row("整车下压力 · N",readings.stream().map(r->number(r.sample().downforceNewtons())+(r.sample().downforceLimited()?"*":"")).toList()));
        result.add(new Row("首次达到下压力上限 · km/h",readings.stream()
                .map(r->PartsEffectsHud.downforceCapLabel(r.column().setup(),pair.maximumKph())).toList()));
        result.add(row("前进限速 · km/h",readings,r->r.column().setup().forwardLimitMps()*3.6f));rows=List.copyOf(result);
    }
    void render(HudRenderer h,FormulaGame app) {
        if(trialMenu) {renderTrials(h,app);return;}
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("TWO SETTINGS",60,65,14,MINT,true);h.text("双参数组合对照",60,108,30,INK,true);
        h.smallButton(1030,57,184,38,"返回单项范围",()->open=false);h.smallButton(820,57,190,38,"明确刷新组合",()->refresh(app));h.smallButton(600,57,200,38,"组合去同条件试车",()->{if(check(app)) trialMenu=true;});
        h.text("四列都从原草稿出发；所选装备与其余参数固定。点击参数名称切换，数值可手动输入。",60,145,16,SOFT);
        h.smallButton(60,178,340,36,"第一项："+first.title+" · 切换",()->move(app,true));h.smallButton(420,178,200,36,"预览 "+firstPercent+"%",()->edit(h,app,true));
        h.smallButton(650,178,340,36,"第二项："+second.title+" · 切换",()->move(app,false));h.smallButton(1010,178,204,36,"预览 "+secondPercent+"%",()->edit(h,app,false));
        h.text(summary(first),60,239,15,GOLD);h.text(summary(second),650,239,15,GOLD);
        h.smallButton(60,264,500,34,"同速 "+speedKph+" km/h · 四列共同 0–"+pair.maximumKph(),()->{if(!check(app)) return;var expected=pair;h.editNumber("组合对照速度",speedKph,0,pair.maximumKph()," km/h",value->{if(pair!=expected || !check(app)) return false;speedKph=value;calculate();return true;});});
        int[] speeds={40,100,160};for(int i=0;i<3;i++) {int speed=speeds[i];h.smallButton(600+i*200,264,i==2?214:186,34,speed<=pair.maximumKph()?speed+" km/h":"超出共同限速",()->{if(check(app) && speed<=pair.maximumKph()) {speedKph=speed;calculate();}});}
        boolean current=app.setupRangeCurrent(review);h.text(current?"模型输入对照 · 原草稿并非车库，未采用预览值。":"背景已变化 · 冻结读数保留，刷新前不读入或开始试车。",60,330,15,current?SOFT:new Color(255,160,130));
        String[] titles={"原草稿","只改第一项","只改第二项","同时改变"};for(int i=0;i<4;i++) h.text(titles[i],500+i*180,360,16,i==3?MINT:SOFT);
        for(int i=0;i<rows.size();i++) {var row=rows.get(i);int y=389+i*19;h.text(row.label(),60,y,16,INK);for(int c=0;c<4;c++) h.text(row.values().get(c),500+c*180,y,15,c==3?MINT:SOFT);}
        h.text(PartsEffectsHud.AERO_MARKERS+" 抵消速度和到顶速度按共同范围查找。",60,699,14,GOLD);
        h.smallButton(60,728,350,38,"把两项读为一步草稿",()->adopt(h,app));h.smallButton(426,728,350,38,"两项范围与装备说明",()->details(h));
        h.smallButton(792,728,422,38,review.context().validation().valid()?"原草稿组合通过 · 查看":"原草稿待处理 · 查看",()->h.review("冻结草稿检查",review.context().validation().issues().isEmpty()?List.of("预算与组合通过；实际试车仍需两项都改变。","本页不应用车辆、不生成测量或成绩。"):review.context().validation().issues(),"返回组合预览",()->{}));
    }
    private String summary(Field field) {var source=review.ranges();var range=source.range(field);return field.title+"："+range.min()+"–"+range.max()+"% · 原 "+source.draftPercent(field)+"% · "+cut(source.part(field).name(),17);}
    private void move(FormulaGame app,boolean primary) {if(!check(app)) return;var field=primary?first:second;var other=primary?second:first;Field next;do {next=Field.values()[(field.ordinal()+1)%Field.values().length];field=next;}while(next==other);if(primary) {first=next;firstPercent=review.ranges().draftPercent(next);}else {second=next;secondPercent=review.ranges().draftPercent(next);}calculate();}
    private void edit(HudRenderer h,FormulaGame app,boolean primary) {if(!check(app)) return;var expected=pair;var field=primary?first:second;var range=review.ranges().range(field);h.editNumber("组合预览 · "+field.title,primary?firstPercent:secondPercent,range.min(),range.max(),"%",value->{if(pair!=expected || !check(app)) return false;if(primary) firstPercent=value;else secondPercent=value;calculate();return true;});}
    private void adopt(HudRenderer h,FormulaGame app) {if(!check(app)) return;var expected=review;var a=first;var b=second;int x=firstPercent,y=secondPercent;h.review("组合读为草稿",List.of(summary(a),summary(b),"候选："+a.title+" "+x+"%，"+b.title+" "+y+"%。","两项一起读入，可一步撤销；未变化时不增加历史。","所选装备、其余字段、车库和已装装备保持；应用和安装仍分别选择。"),"明确读为一步草稿",()->{if(app.adoptSetupPair(expected,a,x,b,y)) refresh(app);});}
    private void details(HudRenderer h) {var lines=new ArrayList<String>();for(var field:List.of(first,second)) {var part=review.ranges().part(field);var range=review.ranges().range(field);lines.add(field.title+" · "+part.name()+" · "+part.id());lines.add("范围 "+range.min()+"–"+range.max()+"%，默认 "+range.initial()+"%；用途："+part.purpose()+"；代价："+part.tradeoff());}lines.add("四列只评价所选取值下的模型输入，不包含驾驶结果、轮胎温度、磨损、路面或圈速。");lines.add(PartsEffectsHud.DRIVE_MARGIN_NOTE);lines.add("原草稿与组合候选的六组 A/B 不测中间两列，也不能分离两项贡献或统计交互。");h.review("双参数范围与说明",lines,"返回组合预览",()->{});}
    private void renderTrials(HudRenderer h,FormulaGame app) {
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("TWO SETTINGS TRIAL",60,65,14,MINT,true);h.text("原草稿与组合候选 · A/B 试车",60,108,30,INK,true);h.smallButton(1030,57,184,38,"返回组合预览",()->trialMenu=false);
        h.wrapped("A 为完整原草稿；B 同时改变 "+first.title+"（"+review.ranges().draftPercent(first)+"% → "+firstPercent+"%）和"+second.title+"（"+review.ranges().draftPercent(second)+"% → "+secondPercent+"%）。",60,160,1154,18,INK,28);
        var exercises=TuningTrial.Exercise.values();for(int i=0;i<exercises.length;i++) {var exercise=exercises[i];h.smallButton(60+i*390,240,370,44,exercise.title,()->trial(h,app,exercise));}
        h.wrapped("两项必须不同且都改变，原草稿和组合候选都需通过预算、范围与依赖检查。确认后沿用六次交错顺序，各侧三次；失败组可重试。",60,335,1154,18,SOFT,30);
        h.wrapped("上方 A/B 只驾驶原草稿和同时改变。下方四条件入口会分别驾驶四列，各三次；报告独立保存，不分离驾驶贡献或证明统计交互。",60,409,1154,18,GOLD,28);
        h.text("四条件各三次 · 十二次原始测量",60,478,20,MINT,true);
        for(int i=0;i<exercises.length;i++) {var exercise=exercises[i];h.smallButton(60+i*390,499,370,44,"四条件 · "+exercise.title,()->matrix(h,app,exercise));}
        h.wrapped(app.setupRangeCurrent(review)?"四种配置均需通过原范围、预算和组合检查；保留草稿、车库和装备安装。十二次按冻结顺序进行，三轮各含四条件。":"背景已变化，请返回并明确刷新组合后重新选择。",60,594,1154,18,SOFT,30);
    }
    private void trial(HudRenderer h,FormulaGame app,TuningTrial.Exercise exercise) {if(!check(app)) return;var expected=app.reviewPairTuning(review,first,firstPercent,second,secondPercent,exercise);if(expected==null) return;h.review("组合试车 · "+exercise.title,List.of(expected.purpose().intent().question(),exercise==TuningTrial.Exercise.FLOW?"路线："+expected.track().name:"场地：综合训练场；路线条件固定。","A 原草稿，B 同时改变两项；其它字段与装备固定。","确认时再次核对草稿、目录、车库、模型、路线与操作设置。",expected.purpose().intent().expectation(),expected.purpose().intent().concern()),"开始组合 A/B 试车",()->app.beginPairTuning(expected));}
    private void matrix(HudRenderer h,FormulaGame app,TuningTrial.Exercise exercise) {if(!check(app)) return;var expected=app.reviewPairExperiment(review,first,firstPercent,second,secondPercent,exercise);if(expected==null) return;var lines=new ArrayList<>(PairTrialsHud.source(expected.initial()));lines.add("确认后保存独立计划，再开始第一组。原六次 A/B、工作台、车库和已装装备保持。");lines.add("每条件三次的中位数与条件差仅作描述；仍需其它项目、实际整圈和独立驾驶验证。");h.review("四条件 · "+exercise.title,lines,"开始十二次独立试车",()->app.beginPairExperiment(expected));}
    private boolean check(FormulaGame app) {if(app.setupRangeCurrent(review)) return true;app.notifyUser("草稿、车库、目录或模型已变化，请明确刷新双参数组合。");return false;}
    private static Row row(String title,List<PartsRangePreview.PairReading> readings,ToDoubleFunction<PartsRangePreview.PairReading> value) {return new Row(title,readings.stream().map(r->number(value.applyAsDouble(r))).toList());}
    private static String number(double value) {return String.format(Locale.ROOT,value>=1_000_000?"%.2e":"%.2f",value);}
    private static String cut(String text,int limit) {return text.length()<=limit?text:text.substring(0,limit-1)+"…";}
}
