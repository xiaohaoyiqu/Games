package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.*;

/** Cached, read-only model numbers. Every sample is calculated on an explicit UI action. */
final class PartsEffectsHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    static final String DRIVE_MARGIN="双后轮驱动请求减阻力 · N";
    static final String DRAG_BALANCE="阻力首次抵消请求 · km/h";
    static final String DRIVE_MARGIN_NOTE="差值为两后轮的请求合计减空气阻力。抵消速度取共同范围内、限速断驱前首次达到的整数车速。";
    static final String AERO_MARKERS="* 下压力达到 5500 N。† 程序已切断驱动力请求。";
    private FormulaGame.SetupEffectsReview review;
    private boolean open;
    private int speedKph=120;
    private int maximumKph;
    private List<Row> rows=List.of();
    private final PartsRangeHud rangeHud=new PartsRangeHud();
    private record Row(String label,String a,String b) {}
    boolean showing() {return open;}
    void open(FormulaGame app) {if(refresh(app)) open=true;}
    private boolean refresh(FormulaGame app) {var next=app.reviewSetupEffects();if(next==null) return false;review=next;maximumKph=next.comparison().maximumKph();speedKph=Math.min(speedKph,maximumKph);calculate();return true;}
    private void calculate() {
        var comparison=review.comparison();var a=comparison.a();var b=comparison.b();var sa=a.sample(speedKph/3.6f);var sb=b.sample(speedKph/3.6f);var values=new ArrayList<Row>();
        values.add(row("每个后轮请求驱动力 · N",sa.rearWheelDriveNewtons(),sb.rearWheelDriveNewtons()));
        values.add(row("每个前轮制动冲量输入",sa.frontWheelBrakeInput(),sb.frontWheelBrakeInput()));values.add(row("每个后轮制动冲量输入",sa.rearWheelBrakeInput(),sb.rearWheelBrakeInput()));
        values.add(row("每个前轮弹簧系数",a.front().stiffness(),b.front().stiffness()));values.add(row("每个后轮弹簧系数",a.rear().stiffness(),b.rear().stiffness()));
        values.add(row("前轮压缩阻尼系数",a.front().compression(),b.front().compression()));values.add(row("后轮压缩阻尼系数",a.rear().compression(),b.rear().compression()));
        values.add(row("前轮回弹阻尼系数",a.front().rebound(),b.front().rebound()));values.add(row("后轮回弹阻尼系数",a.rear().rebound(),b.rear().rebound()));
        values.add(row("整车空气阻力 · N",sa.dragNewtons(),sb.dragNewtons()));
        values.add(row("空气阻力功率 · kW",sa.dragPowerKilowatts(),sb.dragPowerKilowatts()));
        values.add(new Row(DRIVE_MARGIN,driveMarginLabel(sa),driveMarginLabel(sb)));
        values.add(new Row(DRAG_BALANCE,dragBalanceLabel(a,maximumKph),dragBalanceLabel(b,maximumKph)));
        values.add(new Row("整车下压力 · N",number(sa.downforceNewtons())+(sa.downforceLimited()?" · 已达上限":""),number(sb.downforceNewtons())+(sb.downforceLimited()?" · 已达上限":"")));
        values.add(new Row("首次达到下压力上限 · km/h",downforceCapLabel(a,maximumKph),downforceCapLabel(b,maximumKph)));
        values.add(row("前进限速 · km/h",a.forwardLimitMps()*3.6f,b.forwardLimitMps()*3.6f));rows=List.copyOf(values);
    }
    void render(HudRenderer h,FormulaGame app) {
        if(rangeHud.showing()) {rangeHud.render(h,app);return;}
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("装备参数 · 模型作用对照",60,105,30,INK,true);h.text("A 当前车库 / B 当前草稿；平地直行，分别比较全油门与松油门全制动。",60,143,16,SOFT);
        h.smallButton(1030,57,180,38,"返回工作台",()->open=false);h.smallButton(820,57,190,38,"明确刷新对照",()->refresh(app));
        h.smallButton(600,57,200,38,"装备范围与微调",()->rangeHud.open(app));
        boolean current=app.setupEffectsCurrent(review);int maximum=maximumKph;
        h.smallButton(60,176,500,38,"同速对照 "+speedKph+" km/h · 可输入 0–"+maximum,()->{
            if(!check(app)) return;var expected=review;
            h.editNumber("作用对照速度",speedKph,0,maximum," km/h",value->{if(review!=expected || !check(app)) return false;speedKph=value;calculate();return true;});
        });
        int[] speeds={40,100,160};for(int i=0;i<speeds.length;i++) {int speed=speeds[i];h.smallButton(600+i*200,176,i==2?214:186,38,speed<=maximum?speed+" km/h":"超出共同限速",()->{if(check(app) && speed<=maximum) {speedKph=speed;calculate();}});}
        h.text(current?"当前参数的只读计算；请求值与系数不是实测轮胎力或圈速。":"参数已变化 · 仍显示冻结旧预览，请明确刷新。",60,241,16,current?GOLD:new Color(255,160,130));h.text("A 车库",850,241,16,SOFT);h.text("B 草稿",1025,241,16,MINT);
        for(int i=0;i<rows.size();i++) {var row=rows.get(i);int y=278+i*26;h.text(row.label(),60,y,17,INK);h.text(row.a(),850,y,16,SOFT);h.text(row.b(),1025,y,16,MINT);}
        h.text("† 程序已切断驱动力请求。抵消速度和下压力到顶速度均在共同范围内查找。",60,706,14,GOLD);
        h.smallButton(60,738,500,28,review.validation().valid()?"草稿可用于试装 · 此页不采用":"草稿待处理 "+review.validation().issues().size()+" 项 · 查看",()->h.review("草稿检查",review.validation().issues().isEmpty()?List.of("草稿通过目录检查；这不证明操控收益、圈速或最优设置。","关闭此对照后，仍需单独决定试车、采用参数或安装装备。"):review.validation().issues(),"返回只读对照",()->{}));
        h.smallButton(600,738,614,28,"查看数值含义与模型边界",()->h.review("模型作用对照说明",List.of("后轮行是全油门时向每个后轮请求的驱动力；接地与摩擦会影响最终响应。","制动行是松油门、全制动时每轮的冲量输入；弹簧和阻尼行是模型系数。","两种输入分别计算。比较速度只取两套都能评价的范围。","平地无侧滑直行时，空气阻力功率等于阻力乘车速。下压力达到 5500 N 后，阻力仍可随尾翼和车速增加。",DRIVE_MARGIN_NOTE,"实际车速还取决于轮胎响应和其它纵向受力。到顶速度是共同范围内首个触及下压力上限的整数车速。","来源："+review.comparison().a().source().name()+" · "+review.comparison().a().source().physics()),"关闭说明",()->{}));
    }
    private boolean check(FormulaGame app) {if(app.setupEffectsCurrent(review)) return true;app.notifyUser("车库、草稿、目录或车辆参数已变化，请明确刷新对照。");return false;}
    static String downforceCapLabel(VehicleSetupPreview setup,int maximumKph) {
        var speed=setup.firstDownforceCapKph(maximumKph);
        return speed.isPresent()?Integer.toString(speed.getAsInt()):"范围内未达";
    }
    static String driveMarginLabel(VehicleSetupPreview.Sample sample) {
        double value=sample.driveMinusDragNewtons();
        return String.format(Locale.ROOT,Math.abs(value)>=1_000_000?"%+.2e":"%+.2f",value)+(sample.driveCut()?"†":"");
    }
    static String dragBalanceLabel(VehicleSetupPreview setup,int maximumKph) {
        if(setup.source().engineForce()==0) return "无驱动请求";
        var speed=setup.firstDragBalanceKph(maximumKph);
        return speed.isPresent()?Integer.toString(speed.getAsInt()):"范围内未达";
    }
    private static Row row(String label,double a,double b) {return new Row(label,number(a),number(b));}
    private static String number(double value) {return String.format(Locale.ROOT,value>=1_000_000?"%.2e":"%.2f",value);}
}
