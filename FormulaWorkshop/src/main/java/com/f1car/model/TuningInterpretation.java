package com.f1car.model;

import com.f1car.model.PartsCatalog.Field;
import java.util.*;

/** On-request explanation over existing observations. No automatic setup choice or causal score. */
public final class TuningInterpretation {
    private TuningInterpretation() {}
    public record Change(Field field,float a,float b,String behavior,String tradeoff,TuningTrial.Exercise exercise) {
        public String describe() {return String.format(Locale.ROOT,"%s：%.2f%% → %.2f%%。%s；%s",field.title,a*100,b*100,behavior,tradeoff);}
    }
    public static List<Change> changes(Tuning a,Tuning b) {
        var result=new ArrayList<Change>();for(var field:Field.values()) {float before=value(a,field),after=value(b,field);if(before==after) continue;String effect,cost;TuningTrial.Exercise exercise;
            switch(field) {
                case POWER -> {effect="同时改变驱动力与前进速度上限";cost="本模型没有油耗或动力温度代价，更快到弯前需重新找制动点";exercise=TuningTrial.Exercise.ACCELERATION;}
                case BRAKES -> {effect="改变总制动输入，前后比例独立保持";cost="不代表轮胎温度或磨损；入口速度和按键操作会影响距离";exercise=TuningTrial.Exercise.BRAKING;}
                case BRAKE_BIAS -> {effect="总制动输入不变，在前后轮之间重新分配";cost="比例更大不是总制动力更大，直线结果不能代替弯中稳定性";exercise=TuningTrial.Exercise.BRAKING;}
                case WING -> {effect="尾翼倍率同时改变阻力与整车下压力";cost="下压力与阻力一同取舍，连续弯结果不能证明直道或整圈更快";exercise=TuningTrial.Exercise.FLOW;}
                case SUSPENSION -> {effect="总配套同时改变弹簧刚度与阻尼";cost="不是固定抓地加成，前后分项倍率仍叠加";exercise=TuningTrial.Exercise.FLOW;}
                case FRONT_SPRING,REAR_SPRING -> {effect="仅改变对应轴的弹簧倍率，阻尼分项保持";cost="前后通过车身耦合，位移不代表轮胎载荷或抓地";exercise=TuningTrial.Exercise.FLOW;}
                case FRONT_DAMPING,REAR_DAMPING -> {effect="对应轴的压缩与回弹系数一起缩放";cost="两者还不能独立调节，响应取决于路线与驾驶";exercise=TuningTrial.Exercise.FLOW;}
                default -> throw new IllegalStateException();
            }
            result.add(new Change(field,before,after,effect,cost,exercise));
        }return List.copyOf(result);
    }
    private static float value(Tuning t,Field field) {return switch(field) {case POWER->t.power();case BRAKES->t.brakes();case BRAKE_BIAS->t.brakeBias();case WING->t.wing();case SUSPENSION->t.suspension();case FRONT_SPRING->t.frontSpring();case REAR_SPRING->t.rearSpring();case FRONT_DAMPING->t.frontDamping();case REAR_DAMPING->t.rearDamping();};}
    public static List<String> preview(PartsComparison comparison,TuningTrial.Exercise exercise,TuningPurpose.Draft purpose) {
        var lines=new ArrayList<String>();intent(lines,purpose.intent());origin(lines,"A",purpose.a());origin(lines,"B",purpose.b());lines.add("本次测量："+exercise.title+"；只覆盖本项目窗口，不能替代整圈与所有赛道。");describeChanges(lines,comparison.a(),comparison.b(),exercise);return List.copyOf(lines);
    }
    public static List<String> explain(TuningExperiment.Snapshot report,TuningPurpose purpose,String currentPhysics) {
        Objects.requireNonNull(report);var lines=new ArrayList<String>();lines.add("报告项目："+report.conditions().exercise().title+"；赛道条件："+report.conditions().trackName()+"；操作："+report.conditions().controls());
        if(purpose==null) lines.add("本报告未记录部件用途与试车问题；不按当前目录或同名方案补写旧来源。");else if(!purpose.matches(report)) lines.add("用途记录与本报告条件不匹配，不引用该记录中的预期或部件说明。");else {intent(lines,purpose.intent());origin(lines,"A",purpose.a());origin(lines,"B",purpose.b());}
        if(!report.conditions().physics().equals(currentPhysics)) lines.add("旧物理报告仅供查看；先在当前版本重新测量，再决定是否采用。");
        describeChanges(lines,report.a(),report.b(),report.conditions().exercise());lines.add("已收录 "+report.runs().size()+" / 6 次；未通过 "+report.failedAttempts()+" 次。失败次数不进入中位数。");
        if(!report.complete()) {lines.add("尚未完成六次测量，不判断 A／B 高低；继续未完成组，成功重测才替换原数据。");return List.copyOf(lines);}
        for(var slot:TuningExperiment.Slot.values()) lines.add(String.format(Locale.ROOT,"%s：中位数 %.3f %s；极差 %.3f %s；入口中位数 %.1f km/h",slot,report.median(slot),report.conditions().exercise().unit,report.spread(slot),report.conditions().exercise().unit,report.entry(slot)));
        lines.add(String.format(Locale.ROOT,"B − A：%+.3f %s；此处时间／距离越小越好。",report.delta(),report.conditions().exercise().unit));
        switch(report.assessment()) {
            case ENTRY_MISMATCH -> lines.add("入口速度不够接近，先重测入口偏差较大的组，暂不据中位数选择方案。");
            case SAME_SETUP -> lines.add("性能参数相同；本报告反映重复驾驶波动，不能说明部件名称或点数带来提升。");
            case SCATTER -> lines.add("中位数差不超过组内波动，先练稳定性并重复测量，暂不判断改装收益。");
            case OBSERVED_DIFFERENCE -> lines.add("这六次中 "+(report.delta()<0?"B":"A")+" 的中位数较小；这是观察到的差异，未证明由某个零件造成。");
            case NEED_RUNS -> throw new IllegalStateException();
        }
        if(report.changedSettings()>1) lines.add("同时改变多项参数，不能把差异归因于其中一项；下一轮固定其它设置，只改一个项目。");
        for(var slot:TuningExperiment.Slot.values()) {var values=report.measurements(slot);long observed=values.stream().filter(m->m.observation()!=null).count();lines.add(String.format(Locale.ROOT,"%s 操作均值的三次中位数：油门 %.1f%%、制动 %.1f%%、转向 RMS %.1f%%；有车辆观测 %d / 3 次。",slot,median(values.stream().mapToDouble(TuningTrial.Measurement::throttle).toArray())*100,median(values.stream().mapToDouble(TuningTrial.Measurement::brake).toArray())*100,median(values.stream().mapToDouble(TuningTrial.Measurement::steeringRms).toArray())*100,observed));}
        lines.add("油门、制动与转向操作也可能不同；缺失车辆读数不补零。悬挂位移、转角与横摆不能直接判定轮载、推头或甩尾。");
        lines.add("下一步：先复查入口与重复波动，再补其它相关项目；最后用相同条件计时圈与遥测检查整圈表现。这里不会自动采用方案或改写成绩。");return List.copyOf(lines);
    }
    private static double median(double[] values) {Arrays.sort(values);return values[values.length/2];}
    private static void describeChanges(List<String> lines,Tuning a,Tuning b,TuningTrial.Exercise exercise) {var changes=changes(a,b);if(changes.isEmpty()) lines.add("九项性能参数未改变；外观与部件名称不作为性能收益。");for(var change:changes) {lines.add(change.describe());if(change.exercise()!=exercise) lines.add("本项目不直接针对该项用途，建议另测："+change.exercise().title+"。 ");}if(changes.stream().anyMatch(c->c.field()==Field.WING)) lines.add("尾翼取舍还需在计时圈查看直道末端速度与弯段，当前短测不能给出整圈结论。");}
    private static void intent(List<String> lines,TuningPurpose.Intent value) {lines.add("试车问题："+value.question());lines.add("作者预期（待验证）："+value.expectation());lines.add("关心的代价："+value.concern());}
    private static void origin(List<String> lines,String slot,TuningPurpose.Origin value) {lines.add(slot+" 来源："+value.title());if(value.fitted()!=null) {lines.add("冻结已装四槽定义；安装基准与本次实际微调分别保留。");for(var field:com.f1car.model.PartsCatalog.Field.values()) lines.add(slot+" 实际 "+field.title+"："+Float.toString(field.ratio(value.fitted().settings())*100)+"%。");}if(!value.complete()) {lines.add("A 使用当时车库参数，未声明部件来源。");return;}for(var part:value.definitions()) {lines.add(slot+" · "+part.slot().title+"／"+part.name()+"（"+part.id()+"）");lines.add("作者部件用途："+part.purpose()+"；作者代价说明："+part.tradeoff());}}
}
