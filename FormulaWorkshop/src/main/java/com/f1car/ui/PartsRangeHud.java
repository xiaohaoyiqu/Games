package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.PartsCatalog.Field;
import java.awt.Color;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Cached five-column comparison. No sampling, native access or file reads during rendering. */
final class PartsRangeHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private boolean open,trialMenu;private FormulaGame.SetupRangeReview review;private Field field=Field.POWER;private int speedKph=100;
    private PartsRangePreview.Sweep sweep;private List<Row> rows=List.of();
    private final PartsPairHud pairHud=new PartsPairHud();
    private record Row(String label,List<String> values) {Row {values=List.copyOf(values);}}
    boolean showing() {return open;}
    void open(FormulaGame app) {if(refresh(app)) {open=true;trialMenu=false;}}
    private boolean refresh(FormulaGame app) {var next=app.reviewSetupRange();if(next==null) return false;review=next;choose(field,next.ranges().draftPercent(field));return true;}
    private void choose(Field next,int percent) {field=next;sweep=review.ranges().sweep(field,percent);speedKph=Math.min(speedKph,sweep.maximumKph());calculate();}
    private void calculate() {
        var readings=sweep.atKph(speedKph);var result=new ArrayList<Row>();
        result.add(row("每后轮请求驱动力 · N",readings,r->r.sample().rearWheelDriveNewtons()));
        result.add(row("每前轮制动冲量输入",readings,r->r.sample().frontWheelBrakeInput()));result.add(row("每后轮制动冲量输入",readings,r->r.sample().rearWheelBrakeInput()));
        result.add(row("前轮弹簧系数",readings,r->r.column().setup().front().stiffness()));result.add(row("后轮弹簧系数",readings,r->r.column().setup().rear().stiffness()));
        result.add(row("前轮压缩阻尼系数",readings,r->r.column().setup().front().compression()));result.add(row("后轮压缩阻尼系数",readings,r->r.column().setup().rear().compression()));
        result.add(row("前轮回弹阻尼系数",readings,r->r.column().setup().front().rebound()));result.add(row("后轮回弹阻尼系数",readings,r->r.column().setup().rear().rebound()));
        result.add(row("整车空气阻力 · N",readings,r->r.sample().dragNewtons()));
        result.add(row("空气阻力功率 · kW",readings,r->r.sample().dragPowerKilowatts()));
        result.add(new Row(PartsEffectsHud.DRIVE_MARGIN,readings.stream().map(r->PartsEffectsHud.driveMarginLabel(r.sample())).toList()));
        result.add(new Row(PartsEffectsHud.DRAG_BALANCE,readings.stream()
                .map(r->PartsEffectsHud.dragBalanceLabel(r.column().setup(),sweep.maximumKph())).toList()));
        result.add(new Row("整车下压力 · N",readings.stream().map(r->number(r.sample().downforceNewtons())+(r.sample().downforceLimited()?"*":"")).toList()));
        result.add(new Row("首次达到下压力上限 · km/h",readings.stream()
                .map(r->PartsEffectsHud.downforceCapLabel(r.column().setup(),sweep.maximumKph())).toList()));
        result.add(row("前进限速 · km/h",readings,r->r.column().setup().forwardLimitMps()*3.6f));rows=List.copyOf(result);
    }
    void render(HudRenderer h,FormulaGame app) {
        if(pairHud.showing()) {pairHud.render(h,app);return;}
        if(trialMenu) {renderTrials(h,app);return;}
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("EQUIPMENT RANGE",60,65,14,MINT,true);h.text("装备范围与单项微调",60,108,30,INK,true);
        h.smallButton(1030,57,184,38,"返回模型对照",()->open=false);h.smallButton(820,57,190,38,"明确刷新范围",()->refresh(app));
        h.smallButton(600,57,200,38,"当前单项去试车",()->{if(check(app)) trialMenu=true;});
        h.text("A 是独立车库对照；B 四列固定草稿其他参数，只改变当前一项。",60,143,16,SOFT);
        h.smallButton(60,174,142,36,"上一个参数",()->move(app,-1));h.smallButton(218,174,370,36,field.title+" · "+sweep.range().min()+"–"+sweep.range().max()+"%",()->details(h));h.smallButton(604,174,142,36,"下一个参数",()->move(app,1));
        h.smallButton(762,174,452,36,"输入 B 预览值："+sweep.selected()+"%",()->{if(!check(app)) return;var expected=review;var selected=field;h.editNumber("范围内单项预览 · "+field.title,sweep.selected(),sweep.range().min(),sweep.range().max(),"%",value->{if(review!=expected || field!=selected || !check(app)) return false;choose(selected,value);return true;});});
        h.smallButton(60,224,500,34,"同速 "+speedKph+" km/h · 五列共同 0–"+sweep.maximumKph(),()->{if(!check(app)) return;var expected=review;var before=sweep;h.editNumber("范围对照速度",speedKph,0,sweep.maximumKph()," km/h",value->{if(review!=expected || sweep!=before || !check(app)) return false;speedKph=value;calculate();return true;});});
        int[] presets={40,100,160};for(int i=0;i<3;i++) {int speed=presets[i];h.smallButton(600+i*200,224,i==2?214:186,34,speed<=sweep.maximumKph()?speed+" km/h":"超出共同限速",()->{if(check(app) && speed<=sweep.maximumKph()) {speedKph=speed;calculate();}});}
        boolean current=app.setupRangeCurrent(review);var part=review.ranges().part(field);h.text(current?"范围来自："+cut(part.name(),20)+" · 默认 "+sweep.range().initial()+"%；当前草稿 "+review.ranges().draftPercent(field)+"%":"背景已变化 · 保留冻结预览，刷新前不读取为草稿。",60,289,15,current?GOLD:new Color(255,160,130));
        String[] headings={"A 车库","B 下限","B 默认","B 预览","B 上限"};for(int i=0;i<5;i++) h.text(headings[i],490+i*145,321,15,i==3?MINT:SOFT);
        for(int i=0;i<rows.size();i++) {var row=rows.get(i);int y=352+i*21;h.text(row.label(),60,y,16,INK);for(int c=0;c<5;c++) h.text(row.values().get(c),490+c*145,y,15,c==3?MINT:SOFT);}
        h.text(PartsEffectsHud.AERO_MARKERS+" 抵消速度和到顶速度按共同范围查找。",60,692,14,GOLD);
        h.smallButton(60,728,350,38,"把 B 预览值读为一项草稿",()->{if(!check(app)) return;var expected=review;var selected=field;int value=sweep.selected();h.review("单项读为草稿",List.of(selected.title+"："+review.ranges().draftPercent(selected)+"% → "+value+"%。","只修改这一项，可撤销。其他字段、所选部件、目录、车库与已装装备保持。","固定其他参数的预览不能证明组合收益，仍需相同条件试车。"),"明确读为草稿",()->{if(app.adoptSetupRange(expected,selected,value)) refresh(app);});});
        h.smallButton(426,728,350,38,review.context().validation().valid()?"原草稿组合通过 · 查看":"原草稿待处理 · 查看",()->h.review("冻结草稿检查",review.context().validation().issues().isEmpty()?List.of("预算与组合通过；此页不应用车辆、不生成测量或成绩。"):review.context().validation().issues(),"返回预览",()->{}));
        h.smallButton(792,728,422,38,"双参数组合对照",()->{if(check(app)) pairHud.open(app);});
    }
    private void renderTrials(HudRenderer h,FormulaGame app) {
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("SINGLE SETTING TRIAL",60,65,14,MINT,true);h.text("只改当前一项 · 同条件试车",60,108,30,INK,true);
        h.smallButton(1030,57,184,38,"返回范围预览",()->trialMenu=false);
        h.wrapped("A 是完整原草稿，B 只改变 "+field.title+"："+review.ranges().draftPercent(field)+"% → "+sweep.selected()+"%。车库对照列不参与本次试车。",60,160,1154,18,INK,28);
        var exercises=TuningTrial.Exercise.values();for(int i=0;i<exercises.length;i++) {var exercise=exercises[i];h.smallButton(60+i*390,240,370,44,exercise.title,()->trial(h,app,exercise));}
        h.wrapped("分别选择加速、制动或连续弯。每项固定路线、操作和两份参数，交错各测三次；可重试失败组。当前只是选择项目，确认预览后才开始驾驶。",60,335,1154,18,SOFT,30);
        h.wrapped("原草稿与候选必须通过范围、预算和依赖检查，同值不能开始。试车保留车库、已装装备、快捷槽、草稿与撤销历史；报告另存来源快照。",60,450,1154,18,SOFT,30);
        h.wrapped(app.setupRangeCurrent(review)?"本次只比较一个字段；结果仍不证明整圈收益、因果或最优设置。":"背景已变化，原预览保留。请返回并明确刷新范围后再选项目。",60,580,1154,18,GOLD,30);
    }
    private void trial(HudRenderer h,FormulaGame app,TuningTrial.Exercise exercise) {
        if(!check(app)) return;var expected=app.reviewRangeTuning(review,field,sweep.selected(),exercise);if(expected==null) return;
        h.review("单项试车 · "+exercise.title,List.of(expected.purpose().intent().question(),"A：完整原草稿；B：只修改当前参数。车库 A 列、已有固定 A 方案不参与。",
            exercise==TuningTrial.Exercise.FLOW?"路线："+expected.track().name:"场地：综合训练场；路线条件固定。",
            "确认时再次核对草稿、装备定义、车库、模型、路线与操作设置，开始后固定这些试车条件。",
            "交错各测三次，保留原草稿、撤销历史、车库和已装装备。结果及两侧装备来源随报告保存。",
            expected.purpose().intent().expectation(),expected.purpose().intent().concern()),"开始单项对照试车",()->app.beginRangeTuning(expected));
    }
    private void move(FormulaGame app,int delta) {if(!check(app)) return;var next=Field.values()[Math.floorMod(field.ordinal()+delta,Field.values().length)];choose(next,review.ranges().draftPercent(next));}
    private boolean check(FormulaGame app) {if(app.setupRangeCurrent(review)) return true;app.notifyUser("草稿、车库、目录或模型来源已变化，请明确刷新范围。");return false;}
    private void details(HudRenderer h) {var part=review.ranges().part(field);h.review("装备范围与解释",List.of("所选："+part.name()+" · "+part.id(),"用途（声明）："+part.purpose(),"代价（声明）："+part.tradeoff(),"下限 "+sweep.range().min()+"%／默认 "+sweep.range().initial()+"%／上限 "+sweep.range().max()+"%，B 当前预览 "+sweep.selected()+"%。","B 四列仅改变 "+field.title+"，其余参数保持冻结草稿。A 保留真实车库值，可能还有其他差异。","请求驱动力／制动冲量与弹簧阻尼系数不是实测接地力。空气阻力与下压力按平地直行计算。",PartsEffectsHud.DRIVE_MARGIN_NOTE,"没有计算轮胎温度、磨损、路面、圈速或全局最优；需用相同条件分别试车。","车型与物理："+review.ranges().source().vehicle()+" · "+review.ranges().source().physics()),"返回范围预览",()->{});}
    private static Row row(String title,List<PartsRangePreview.Reading> readings,ToDoubleFunction<PartsRangePreview.Reading> value) {return new Row(title,readings.stream().map(r->number(value.applyAsDouble(r))).toList());}
    private static String number(double value) {return String.format(Locale.ROOT,value>=1_000_000?"%.2e":"%.2f",value);}
    private static String cut(String text,int limit) {return text.length()<=limit?text:text.substring(0,limit-1)+"…";}
}
