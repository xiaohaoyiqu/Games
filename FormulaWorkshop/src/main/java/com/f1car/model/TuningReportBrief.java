package com.f1car.model;

import java.math.BigDecimal;
import java.util.*;

/** Descriptive first page over a frozen six-run report; existing assessment thresholds remain authoritative. */
public final class TuningReportBrief {
    public enum Origin {LOCAL("本机报告"),RECEIVED("收到的提供者记录");public final String title;Origin(String title) {this.title=title;}}
    public enum PurposeState {ABSENT,MISMATCHED,MATCHED}
    public record Group(TuningExperiment.Slot slot,int runs,Double median,Double spread,Double entry,int observations) {
        public boolean complete() {return median!=null;}
    }
    private final TuningExperiment.Snapshot report;private final TuningPurpose purpose;private final Origin origin;
    private final PurposeState purposeState;private final boolean currentPhysics;
    private final String headline,observation,question,controls;
    private final List<Group> groups;private final List<String> nextSteps,limits,parameterLines,conditions;
    public TuningReportBrief(TuningExperiment.Snapshot report,TuningPurpose purpose,Origin origin,String physics) {
        this.report=Objects.requireNonNull(report);this.purpose=purpose;this.origin=Objects.requireNonNull(origin);
        currentPhysics=report.conditions().physics().equals(Objects.requireNonNull(physics));
        purposeState=purpose==null?PurposeState.ABSENT:purpose.matches(report)?PurposeState.MATCHED:PurposeState.MISMATCHED;
        question=purposeState==PurposeState.MATCHED?purpose.intent().question():purposeState==PurposeState.ABSENT?"未记录试车问题；不补写当前用途。":"所附用途与报告不匹配；未引用该用途说明。";
        controls=ControlIdentity.KEYBOARD.equals(report.conditions().controls())?"键盘（冻结条件）":"手柄（冻结操作设置）";
        var values=new ArrayList<Group>();
        for(var slot:TuningExperiment.Slot.values()) {
            var runs=report.measurements(slot);int observed=(int)runs.stream().filter(m->m.observation()!=null).count();
            values.add(new Group(slot,runs.size(),report.complete()?report.median(slot):null,report.complete()?report.spread(slot):null,report.complete()?report.entry(slot):null,observed));
        }
        groups=List.copyOf(values);
        String title;String seen;var next=new ArrayList<String>();
        switch(report.assessment()) {
            case NEED_RUNS -> {title="六次尚未完成 · 暂不比较 A／B";seen="已收录 "+report.runs().size()+" / 6 次有效测量；保留原次记录，等待两侧各三次。";next.add("先完成未测组；失败不计入六次，成功重测才替换原记录。");}
            case ENTRY_MISMATCH -> {title="入口速度不够接近 · 先核对入口";seen="六次已完成，但入口差异触发原有检查，暂不据中位数选择方案。";next.add("先查看原六次入口速度与过程曲线，再重测入口偏差较大的组。");}
            case SAME_SETUP -> {title="九项性能参数相同 · 查看重复波动";seen="两侧性能参数相同；部件名称、外观或点数不能作为性能收益。";next.add("先核对是否确实改变了要研究的性能参数；这份报告可用于观察驾驶波动。");}
            case SCATTER -> {title="差值未超过本组波动检查 · 暂不判断收益";seen=delta(report)+"；中位数差未超过原有组内波动检查，不表示两方案永远等效。";next.add("先查看原六次与曲线，稳定入口和操作，再重复同条件测量。");}
            case OBSERVED_DIFFERENCE -> {title="本组六次观察到差异 · 未证明改装收益";seen=delta(report)+"；这六次中 "+(report.delta()<0?"B":"A")+" 的中位数较小，不能据此归因到某个零件。";next.add("先核对入口、重复波动与操作曲线，再补其它相关项目和同条件整圈。");}
            default -> throw new IllegalStateException();
        }
        if(report.changedSettings()>1) next.add("同时改变 "+report.changedSettings()+" 项性能参数；下一轮固定其余设置，再研究单项贡献。");
        if(!currentPhysics) next.add(0,"报告物理与当前不同；先用当前版本重新测量，再决定采用。");
        if(origin==Origin.RECEIVED) next.add("收到内容只作提供者记录查看；需本机独立试车，不能当作本机测量。");
        headline=currentPhysics?title:"旧物理报告 · 保留原观察，仅供查看";observation=seen;nextSteps=List.copyOf(next);
        var warnings=new ArrayList<String>();warnings.add("各三次重复只作描述；极差不是置信区间，没有显著性或因果结论。");
        warnings.add("驾驶、熟练度、顺序与疲劳仍可能影响结果；交错测量不消除这些影响。");
        warnings.add("未通过 "+report.failedAttempts()+" 次，不进入有效组中位数。");
        warnings.add("车辆观测 A "+groups.get(0).observations()+" / "+groups.get(0).runs()+" 次；B "+groups.get(1).observations()+" / "+groups.get(1).runs()+" 次，缺失未补零。");
        if(purposeState==PurposeState.ABSENT) warnings.add("旧报告缺少用途／装备完整来源，不从当前目录补写。");
        else if(purposeState==PurposeState.MISMATCHED) warnings.add("所附用途与本报告精确条件不匹配，不引用其问题、预期或定义。");
        else warnings.add("作者预期与代价说明分别保留；预期不当作已测量结果。");
        warnings.add("本项目的时间／距离较小不代表整圈更快或其它场景更好。");limits=List.copyOf(warnings);
        var parameters=new ArrayList<String>();
        for(var change:TuningInterpretation.changes(report.a(),report.b())) {
            parameters.add(change.field().title+"：A "+percent(change.a())+"%；B "+percent(change.b())+"%。");
            parameters.add("现有模型作用："+change.behavior()+"。");parameters.add("取舍与边界："+change.tradeoff()+"。");
            if(change.exercise()!=report.conditions().exercise()) parameters.add("该项还需另测："+change.exercise().title+"。");
        }
        if(parameters.isEmpty()) parameters.add("九项性能参数没有变化；外观和部件名称不算性能差异。");
        if(purposeState==PurposeState.MATCHED) {parameters.add("作者预期（待验证）："+purpose.intent().expectation());parameters.add("作者关心的代价："+purpose.intent().concern());}
        parameterLines=List.copyOf(parameters);
        var context=new ArrayList<String>();var c=report.conditions();context.add(origin.title+"；报告身份 "+report.id());context.add("创建时间（提供值）："+report.createdAt());
        context.add("项目："+c.exercise().title+"；单位 "+c.exercise().unit+"；时间／距离较小。");context.add("路线："+c.trackName()+"（"+c.trackId()+"）");context.add("几何："+c.geometry());context.add("物理："+c.physics());context.add("完整操作条件："+c.controls());
        for(int i=0;i<report.runs().size();i++) {var run=report.runs().get(i);var m=run.measurement();context.add("第 "+(i+1)+" 组／方案 "+run.slot()+"／试次 #"+run.attempt()+"：原值 "+Double.toString(m.value())+" "+c.exercise().unit+"；入口 "+Float.toString(m.entry())+" km/h；出口 "+Float.toString(m.exit())+" km/h。");context.add("原操作均值：油门 "+percent(m.throttle())+"%；制动 "+percent(m.brake())+"%；转向 RMS "+percent(m.steeringRms())+"%。");}
        context.addAll(limits);conditions=List.copyOf(context);
    }
    private static String delta(TuningExperiment.Snapshot report) {return String.format(Locale.ROOT,"B − A 为 %+.3f %s",report.delta(),report.conditions().exercise().unit);}
    private static String percent(float value) {return new BigDecimal(Float.toString(value)).movePointRight(2).stripTrailingZeros().toPlainString();}
    public TuningExperiment.Snapshot report() {return report;}public TuningPurpose purpose() {return purpose;}public Origin origin() {return origin;}
    public PurposeState purposeState() {return purposeState;}public boolean currentPhysics() {return currentPhysics;}
    public String headline() {return headline;}public String observation() {return observation;}public String question() {return question;}public String controls() {return controls;}
    public List<Group> groups() {return groups;}public List<String> nextSteps() {return nextSteps;}public List<String> limits() {return limits;}
    public List<String> parameterLines() {return parameterLines;}public List<String> conditions() {return conditions;}
}
