package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Explicit A/B background comparison. Neither time, card identity nor equal parameters imply ancestry. */
public final class PartsDiscussionComparison {
    public enum Relationship {SAME_SNAPSHOT("方案原文相同"),SAME_CARD_ID("同卡身份、原文不同；未判定先后"),DISTINCT_CARDS("两张不同身份的卡；未推断继承关系");public final String title;Relationship(String title) {this.title=title;}}
    public enum TrialContext {NONE("双方没有试车附件"),ONE_SIDED("仅一方有试车附件"),DIFFERENT("试车背景不同；分别查看"),SAME("试车背景字段相同；测量仍分别保留");public final String title;TrialContext(String title) {this.title=title;}}
    public record Parameter(Field field,int a,int b) {public int delta() {return b-a;}}
    public record Equipment(Slot slot,PartsBuild.Selection a,PartsBuild.Selection b,Part declaredA,Part declaredB) {
        public boolean samePin() {return a.part().equals(b.part()) && a.fingerprint().equals(b.fingerprint());}
        public boolean declarationChanged() {return !Objects.equals(declaredA,declaredB);}
        public String status() {return !samePin()?"选件身份或功能指纹不同":declarationChanged()?"功能指纹相同，所附定义原文不同":"功能指纹相同";}
    }
    private final PartsDiscussion a,b;private final Relationship relationship;private final TrialContext trialContext;
    private final List<Parameter> parameters;private final List<Equipment> equipment;private final List<String> summary;private final String trialNotice;
    public PartsDiscussionComparison(PartsDiscussion a,PartsDiscussion b) {
        this.a=Objects.requireNonNull(a);this.b=Objects.requireNonNull(b);var pa=a.plan();var pb=b.plan();var x=pa.plan().build();var y=pb.plan().build();
        relationship=pa.equals(pb)?Relationship.SAME_SNAPSHOT:pa.plan().id().equals(pb.plan().id())?Relationship.SAME_CARD_ID:Relationship.DISTINCT_CARDS;
        if(a.evidence()==null && b.evidence()==null) trialContext=TrialContext.NONE;
        else if(a.evidence()==null || b.evidence()==null) trialContext=TrialContext.ONE_SIDED;
        else trialContext=x.vehicle().equals(y.vehicle()) && a.evidence().report().conditions().equals(b.evidence().report().conditions())?TrialContext.SAME:TrialContext.DIFFERENT;
        trialNotice=a.evidence()!=null && b.evidence()!=null && a.evidence().report().id().equals(b.evidence().report().id())
            ?a.evidence().report().equals(b.evidence().report())?"两侧引用同一份原报告；不是两次独立试验":"同一报告身份含不同内容；原文分别保留，不合并"
            :trialContext.title;
        var values=new ArrayList<Parameter>();for(var field:Field.values()) values.add(new Parameter(field,x.slots().get(field.slot()).values().get(field),y.slots().get(field.slot()).values().get(field)));parameters=List.copyOf(values);
        var slots=new ArrayList<Equipment>();for(var slot:Slot.values()) slots.add(new Equipment(slot,x.slots().get(slot),y.slots().get(slot),definition(pa,x.slots().get(slot).part()),definition(pb,y.slots().get(slot).part())));equipment=List.copyOf(slots);
        var lines=new ArrayList<String>();lines.add(relationship.title);lines.add("A 话题 "+a.id()+"；B 话题 "+b.id());lines.add("车型 A "+x.vehicle()+"；B "+y.vehicle());lines.add("物理 A "+x.physics()+"；B "+y.physics());lines.add("试验点数上限 A "+x.budget()+"；B "+y.budget()+"，不是性能评分。");
        for(var value:parameters) lines.add(String.format(Locale.ROOT,"%s：A %d%%；B %d%%；B − A %+.0f 个百分点",value.field().title,value.a(),value.b(),(double)value.delta()));
        for(var value:equipment) {lines.add(value.slot().title+"："+value.status());selection(lines,"A",value.a(),value.declaredA());selection(lines,"B",value.b(),value.declaredB());}
        lines.add(trialContext.title);if(!trialNotice.equals(trialContext.title)) lines.add(trialNotice);trial(lines,"A",a.evidence());trial(lines,"B",b.evidence());
        lines.add("各侧保留自己的方案、署名、来源链和留言关系；同名、时间和卡身份不证明新旧顺序。缺失定义不从当前目录补写。");lines.add("只读比较不合并话题、安装部件、采用参数、写本机测量或认证收益。开启另一侧本地话题需再次检查它仍是该快照。");summary=List.copyOf(lines);
    }
    private static Part definition(PartsPlanShare plan,String id) {return plan.packs().stream().flatMap(pack->pack.parts().stream()).filter(part->part.id().equals(id)).findFirst().orElse(null);}
    private static void selection(List<String> lines,String side,PartsBuild.Selection selection,Part declared) {
        lines.add(side+" 选件 "+selection.part()+"；原功能指纹 "+selection.fingerprint());
        if(declared==null) {lines.add(side+" 未内嵌该选件的完整定义；只保留原身份、功能指纹和参数，不按当前目录补说明。包括内置部件。");return;}
        lines.add(side+" 所附定义 "+declared.name()+"；点数 "+declared.points()+"；用途 "+declared.purpose()+"；代价 "+declared.tradeoff());
        lines.add(side+" 所附定义与选择指纹"+(declared.id().equals(selection.part()) && declared.fingerprint().equals(selection.fingerprint())?"一致":"不一致，不能据此采用"));
        for(var field:declared.slot().fields()) {var range=declared.settings().get(field);lines.add(side+" "+field.title+" 范围 "+range.min()+"–"+range.max()+"%，默认 "+range.initial()+"%。");}
        lines.add(side+" 需要 "+new TreeSet<>(declared.requires())+"；禁配 "+new TreeSet<>(declared.excludes()));
    }
    private static void trial(List<String> lines,String side,PartsTrialEvidence evidence) {
        if(evidence==null) {lines.add(side+" 无试车附件");return;}var report=evidence.report();var c=report.conditions();lines.add(side+" 原报告 "+report.id()+"；引用侧 "+evidence.slot()+"；"+evidence.correspondence().title);
        lines.add(side+" 路线 "+c.trackName()+"（"+c.trackId()+"）；几何 "+c.geometry()+"；物理 "+c.physics()+"；操作 "+c.controls()+"；项目 "+c.exercise().title);
    }
    public PartsDiscussion a() {return a;}public PartsDiscussion b() {return b;}public Relationship relationship() {return relationship;}public TrialContext trialContext() {return trialContext;}
    public List<Parameter> parameters() {return parameters;}public List<Equipment> equipment() {return equipment;}public List<String> summary() {return summary;}
    public String trialNotice() {return trialNotice;}
    public boolean vehiclePhysicsMatch() {return a.plan().plan().build().vehicle().equals(b.plan().plan().build().vehicle()) && a.plan().plan().build().physics().equals(b.plan().plan().build().physics());}
}
