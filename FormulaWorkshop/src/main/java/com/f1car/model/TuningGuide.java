package com.f1car.model;

import java.util.*;

/** Small single-variable experiments using only effects implemented by FormulaCar. */
public final class TuningGuide {
    private TuningGuide() {}
    public enum Goal {
        ACCELERATION("直线加速","动力输出", "power",.10f,TuningTrial.Exercise.ACCELERATION,
            "从静止全油门比较 0–60 km/h。先保持制动、悬挂和尾翼一致。",
            "动力同时改变驱动力和前进速度上限；更快到弯前，需要重新找制动点。当前没有油耗或动力温度代价。"),
        BRAKING("缩短停车距离","制动力度","brakes",.10f,TuningTrial.Exercise.BRAKING,
            "保持 68–72 km/h 通过测速门，松油门后持续全制动。比较距离与入口速度。",
            "提高总制动输入，前后比例保留；按键输入会更急。当前没有轮胎温度或磨损。"),
        LOW_DRAG("直线低阻力","尾翼方案","wing",-.15f,TuningTrial.Exercise.FLOW,
            "降低尾翼后先跑相同连续弯，再用计时圈比较直线出口。注意提前制动。",
            "空气阻力与下压力一起降低。连续弯测试只反映这一段，不能证明整圈一定更快。"),
        DOWNFORCE("高速弯下压力","尾翼方案","wing",.15f,TuningTrial.Exercise.FLOW,
            "提高尾翼后，以尽量相近的首门速度比较连续弯用时和出口速度。",
            "下压力增加，同时空气阻力增加。低速时气动力较弱；不等于所有弯都更快。"),
        SOFT_SUSPENSION("较软悬挂对照","悬挂配套","suspension",-.10f,TuningTrial.Exercise.FLOW,
            "只降低悬挂配套，在相同弯组重复测试，观察转向修正与用时。",
            "弹簧刚度与阻尼一起降低。更软是否合适取决于路线与操作，不能当作固定抓地加成。"),
        FIRM_SUSPENSION("较硬悬挂对照","悬挂配套","suspension",.10f,TuningTrial.Exercise.FLOW,
            "只提高悬挂配套，在相同入口速度下比较连续弯。先观察三次的波动。",
            "弹簧刚度与阻尼一起提高。当前平面赛道的差异可能很小，应以实际试车为准。注重平顺操作。"),
        FRONT_BRAKES("制动分配向前","前轴制动比例","brakeBias",.05f,TuningTrial.Exercise.BRAKING,
            "只提高前轴比例，后轴相应减少；具体幅度以候选预览为准。以相同入口速度比较停车距离，再到连续弯观察转向。",
            "四轮总制动输入保持不变；更靠前不等于总制动力更大，也不保证所有弯都更快。"),
        REAR_BRAKES("制动分配向后","前轴制动比例","brakeBias",-.05f,TuningTrial.Exercise.BRAKING,
            "只降低前轴比例，后轴相应增加；具体幅度以候选预览为准。保持相同入口速度，重复试车并观察带转向制动的反应。",
            "后轴承担更多制动，响应取决于负载与操作。当前不是完整轮胎锁死模型，不提供真实赛车的最优比例。"),
        FRONT_SPRING("前轴弹簧对照","前轴弹簧倍率","frontSpring",.10f,TuningTrial.Exercise.FLOW,
            "只提高前轴弹簧倍率，保留后轴与阻尼。比较相近入口速度下的连续弯和前后悬挂读数。",
            "刚度增大改变悬挂行程与车身响应；阻尼系数不自动增加。更硬不代表固定抓地加成或整圈更快。"),
        REAR_SPRING("后轴弹簧对照","后轴弹簧倍率","rearSpring",.10f,TuningTrial.Exercise.FLOW,
            "只提高后轴弹簧倍率，前轴与阻尼保持原值。重复连续弯，观察后轴位移、转角和横摆。",
            "前后响应会通过车身耦合，后轴改变也可能影响前轴读数。先比较重复波动，不据此判定甩尾或推头。"),
        FRONT_DAMPING("前轴阻尼对照","前轴阻尼倍率","frontDamping",.10f,TuningTrial.Exercise.FLOW,
            "只提高前轴阻尼倍率，弹簧刚度与后轴保持原值。比较相同弯组的悬挂与转向响应。",
            "前轴压缩和回弹系数一起增加；既可能减轻振荡，也可能改变瞬态跟随。当前没有真实轮胎载荷观测。"),
        REAR_DAMPING("后轴阻尼对照","后轴阻尼倍率","rearDamping",.10f,TuningTrial.Exercise.FLOW,
            "只提高后轴阻尼倍率，弹簧和前轴保持原值。以接近的入口速度重复三次，再比较观测。",
            "只调整后轴压缩与回弹系数，不能视为固定稳定性加成；平面赛道的收益可能很小。");
        public final String title,parameter,field,instruction,tradeoff;public final float step;public final TuningTrial.Exercise exercise;
        Goal(String title,String parameter,String field,float step,TuningTrial.Exercise exercise,String instruction,String tradeoff) {
            this.title=title;this.parameter=parameter;this.field=field;this.step=step;this.exercise=exercise;this.instruction=instruction;this.tradeoff=tradeoff;
        }
        public Tuning candidate(Tuning baseline) { return baseline.change(field,step); }
        public boolean available(Tuning baseline) { return !baseline.performanceKey().equals(candidate(baseline).performanceKey()); }
    }
    /** One frozen suggestion; installed definitions, rather than today's catalogue, set the range. */
    public record Preview(Goal goal,Tuning baseline,Tuning requested,Tuning candidate,PartsCatalog.Range range,FittedParts installed) {
        public boolean available() {return !baseline.performanceKey().equals(candidate.performanceKey());}
        public boolean limited() {return !requested.equals(candidate);}
        public float changePercent() {var field=PartsCatalog.Field.tuningKey(goal.field);return (field.ratio(candidate)-field.ratio(baseline))*100;}
    }
    public static Preview preview(Goal goal,Tuning baseline,FittedParts installed) {
        Objects.requireNonNull(goal);Objects.requireNonNull(baseline);var field=PartsCatalog.Field.tuningKey(goal.field);
        if(installed!=null && !installed.accepts(baseline)) throw new IllegalArgumentException("Baseline exceeds fitted equipment");
        var range=installed==null?new PartsCatalog.Range(field.min,field.max,field.percent(baseline)):installed.range(field);
        var requested=goal.candidate(baseline);var candidate=requested;float percent=field.ratio(requested)*100;
        if(installed!=null) {
            if(percent<range.min()) candidate=baseline.withPercent(field,range.min());
            else if(percent>range.max()) candidate=baseline.withPercent(field,range.max());
        }
        return new Preview(goal,baseline,requested,candidate,range,installed);
    }
    public static Goal suggested(TrackLayout track) { return switch(TrackCatalog.forLayout(track).theme()) {
        case PARK -> Goal.LOW_DRAG;case FOREST,STREET -> Goal.DOWNFORCE;case HARBOR -> Goal.BRAKING;
    }; }
    public record Effect(String title,float stockRatio) {}
    public static List<Effect> effects(Tuning tuning) { return List.of(new Effect("驱动力 / 速度上限",tuning.power()),new Effect("每个前轮制动输入",2*tuning.brakes()*tuning.brakeBias()),new Effect("每个后轮制动输入",2*tuning.brakes()*(1-tuning.brakeBias())),
        new Effect("前轴弹簧刚度",tuning.springRatio(true)),new Effect("后轴弹簧刚度",tuning.springRatio(false)),
        new Effect("前轴压缩 / 回弹阻尼",tuning.dampingRatio(true)),new Effect("后轴压缩 / 回弹阻尼",tuning.dampingRatio(false)),
        new Effect("空气下压力系数",tuning.wing()),new Effect("空气阻力系数",.55f+.45f*tuning.wing())); }
}
