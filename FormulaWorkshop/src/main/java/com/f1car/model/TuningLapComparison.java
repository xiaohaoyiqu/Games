package com.f1car.model;

import java.util.*;

/** Read-only correspondence between a frozen short test and independently recorded full laps. */
public final class TuningLapComparison {
    public enum Correspondence {
        EQUIPMENT("装备功能与精确参数对应"),PARAMETERS("仅精确参数对应"),DIFFERENT("条件或来源不对应");
        public final String title;Correspondence(String title) {this.title=title;}
    }
    public record Match(Correspondence correspondence,boolean reportRoute,List<String> details) {
        public Match {Objects.requireNonNull(correspondence);details=List.copyOf(details);}
        public boolean selectable() {return correspondence!=Correspondence.DIFFERENT;}
    }
    private final TuningExperiment.Snapshot report;
    private final TuningPurpose purpose;
    private final boolean matchedPurpose;
    private final FittedParts aEquipment,bEquipment;
    public TuningLapComparison(TuningExperiment.Snapshot report,TuningPurpose purpose) {
        this.report=Objects.requireNonNull(report);this.purpose=purpose;matchedPurpose=purpose!=null && purpose.matches(report);
        aEquipment=matchedPurpose?equipment(purpose.a()):null;bEquipment=matchedPurpose?equipment(purpose.b()):null;
    }
    private static FittedParts equipment(TuningPurpose.Origin origin) {
        if(origin.fitted()!=null) return origin.fitted().equipment();
        if(origin.build()==null) return null;
        var definitions=new EnumMap<PartsCatalog.Slot,PartsCatalog.Part>(PartsCatalog.Slot.class);
        origin.parts().forEach(part->definitions.put(part.slot(),part));
        return new FittedParts(origin.build(),definitions);
    }
    public TuningExperiment.Snapshot report() {return report;}
    public TuningPurpose purpose() {return purpose;}
    public Match match(TuningExperiment.Slot slot,EquipmentLap lap) {
        Objects.requireNonNull(slot);Objects.requireNonNull(lap);
        var conditions=report.conditions();var actual=lap.lap().conditions();var source=lap.source();
        var expected=slot==TuningExperiment.Slot.A?report.a():report.b();
        var equipment=slot==TuningExperiment.Slot.A?aEquipment:bEquipment;
        var issues=new ArrayList<String>();var notes=new ArrayList<String>();
        boolean route=conditions.trackId().equals(actual.trackId()) && conditions.geometry().equals(actual.geometry());
        if(!conditions.physics().equals(actual.physics())) issues.add("物理版本与报告不同。");
        if(!conditions.controls().equals(actual.controls())) issues.add("操作身份与报告不同。");
        // The legacy performance key rounds some fields. Correspondence always compares the actual nine values.
        if(!expected.withLivery(0).equals(source.tuning().withLivery(0))) issues.add("九项实际参数与报告 "+slot+" 不完全相同；不按取整成绩键对应。");
        String vehicle=matchedPurpose?purpose.b().vehicle():null;
        if(vehicle!=null && !vehicle.equals(source.vehicle())) issues.add("车型与报告冻结来源不同。");
        boolean full=equipment!=null && source.installed()!=null;
        if(full && !equipment.sameEquipment(source.installed())) issues.add("部件完整定义、组合预算或装备身份与报告 "+slot+" 不同。");
        if(!matchedPurpose) notes.add(purpose==null?"报告没有完整装备来源；仅核对实际参数，不用当前目录补写。":"所附用途不匹配报告；未使用该用途的车型或装备声明。");
        else if(!full) notes.add("至少一侧缺少完整冻结装备；仅核对实际参数与已有条件。");
        if(full) notes.add("安装基准可不同；按冻结完整定义和本次实际微调对应，不比较装备名称。");
        notes.add(route?"路线身份与几何对应报告；完整圈仍是独立驾驶。":"此圈不是报告的路线版本；只能研究所选两圈自己的路线，不能代表报告路线整圈表现。");
        if(!report.complete()) notes.add("报告六次尚未完成；对应参数不补成短测结果。");
        notes.add("对应关系不证明驾驶一致、真实性或装备收益。");
        var details=new ArrayList<>(issues);details.addAll(notes);
        return new Match(issues.isEmpty()?(full?Correspondence.EQUIPMENT:Correspondence.PARAMETERS):Correspondence.DIFFERENT,route,details);
    }
    public List<String> pairIssues(EquipmentLap a,EquipmentLap b) {
        var issues=new ArrayList<String>();
        if(a==null || b==null) {issues.add("先明确选择报告 A、B 各自对应的一份完整圈。");return List.copyOf(issues);}
        if(!match(TuningExperiment.Slot.A,a).selectable()) issues.add("A 圈不对应报告 A；请查看该圈的对应详情。");
        if(!match(TuningExperiment.Slot.B,b).selectable()) issues.add("B 圈不对应报告 B；请查看该圈的对应详情。");
        if(a.id().equals(b.id())) issues.add("同一圈及其收到副本不能当作两次独立驾驶。");
        issues.addAll(EquipmentLap.differences(a,b));
        return List.copyOf(issues);
    }
    public TelemetryComparison compare(EquipmentLap a,EquipmentLap b) {
        if(!pairIssues(a,b).isEmpty()) throw new IllegalArgumentException("Selected laps do not correspond to this report");
        return TelemetryComparison.equipment(a,b);
    }
}
