package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** A provider's frozen six-run report and named plan, never a trusted performance certificate. */
public record PartsTrialEvidence(String id,PartsPlanShare plan,TuningExperiment.Snapshot report,TuningPurpose purpose,TuningExperiment.Slot slot,String notes) {
    public enum Correspondence { PARAMETERS("仅参数对应，未记录该侧部件来源"),PARTS("部件功能快照与参数对应");public final String title;Correspondence(String title) {this.title=title;} }
    public enum Origin { LOCAL("本地报告关联"),RECEIVED("收到的提供者记录");public final String title;Origin(String title) {this.title=title;} }
    public enum Link { UNLINKED("未关联本地命名卡"),MISSING("关联卡已移除"),MATCHED("关联卡参数与部件保持"),CHANGED("关联卡已改变，记录仍是旧快照");public final String title;Link(String title) {this.title=title;} }
    public record Entry(PartsTrialEvidence evidence,Origin origin,String localPlanId) {
        public Entry {if(evidence==null || origin==null || localPlanId!=null && !ActivityCard.identity(localPlanId)) throw new IllegalArgumentException("Invalid trial evidence entry");}
        public Entry link(PartsPlan plan) {if(plan!=null && !evidence.plan().plan().build().equals(plan.build())) throw new IllegalArgumentException("Plan does not match evidence");return new Entry(evidence,origin,plan==null?null:plan.id());}
        public Link status(PartsPlan plan) {if(localPlanId==null) return Link.UNLINKED;if(plan==null || !localPlanId.equals(plan.id())) return Link.MISSING;return evidence.plan().plan().build().equals(plan.build())?Link.MATCHED:Link.CHANGED;}
    }
    public PartsTrialEvidence {
        if(id==null || !id.matches("evidence-[a-f0-9]{32}") || plan==null || report==null || !report.complete() || slot==null || notes==null || notes.length()>320 || !PartsCatalog.text(notes.replace("\n",""),320)) throw new IllegalArgumentException("Invalid parts trial evidence");
        var tuning=slot==TuningExperiment.Slot.A?report.a():report.b();var build=plan.plan().build();if(!build.physics().equals(report.conditions().physics()) || !build.tuning(tuning.livery()).equals(tuning)) throw new IllegalArgumentException("Plan parameters differ from selected report side");
        if(purpose!=null) {if(!purpose.matches(report)) throw new IllegalArgumentException("Trial purpose differs");var source=slot==TuningExperiment.Slot.A?purpose.a():purpose.b();if(!source.acceptsPlan(build)) throw new IllegalArgumentException("Trial parts differ from shared plan");}
        for(var selection:build.slots().values()) if(!selection.part().startsWith("builtin:")) {var part=plan.packs().stream().flatMap(pack->pack.parts().stream()).filter(value->value.id().equals(selection.part())).findFirst().orElseThrow(()->new IllegalArgumentException("Missing custom evidence part"));if(!part.fingerprint().equals(selection.fingerprint())) throw new IllegalArgumentException("Shared custom definition differs");}
    }
    public static PartsTrialEvidence capture(PartsPlanShare plan,TuningExperiment.Snapshot report,TuningPurpose purpose,TuningExperiment.Slot slot,String notes) {return new PartsTrialEvidence("evidence-"+UUID.randomUUID().toString().replace("-",""),plan,report,purpose,slot,notes);}
    public Correspondence correspondence() {return purpose!=null && (slot==TuningExperiment.Slot.A?purpose.a():purpose.b()).complete()?Correspondence.PARTS:Correspondence.PARAMETERS;}
}
