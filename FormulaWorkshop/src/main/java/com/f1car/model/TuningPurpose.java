package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import com.google.gson.Gson;
import java.util.*;

/** A local experiment brief. Author statements remain distinct from measured results. */
public record TuningPurpose(int version,String experiment,String identity,Intent intent,Origin a,Origin b) {
    public record Intent(String question,String expectation,String concern) {
        public Intent {if(!PartsCatalog.text(question,120) || question.isBlank() || !PartsCatalog.text(expectation,120) || !PartsCatalog.text(concern,120)) throw new IllegalArgumentException("Invalid trial question");}
        public static Intent defaults() {return new Intent("这次改装能否改善所选试车项目？","先比较三次重复的中位数与波动。","还需检查其它项目与整圈表现。");}
    }
    /** Installation values remain distinct from exact settings measured by the report. */
    public record FittedSource(FittedParts equipment,Tuning settings) {
        public FittedSource {if(equipment==null || settings==null || !equipment.accepts(settings)) throw new IllegalArgumentException("Invalid fitted trial source");}
    }
    public record Origin(String title,PartsBuild build,List<Part> parts,FittedSource fitted) {
        public Origin(String title,PartsBuild build,List<Part> parts) {this(title,build,parts,null);}
        public Origin {
            if(!PartsCatalog.text(title,40) || title.isBlank() || parts==null || (build==null? !parts.isEmpty():parts.size()!=4)) throw new IllegalArgumentException("Invalid trial source");
            if(fitted!=null && build!=null) throw new IllegalArgumentException("Mixed trial source representations");
            parts=parts.stream().sorted(Comparator.comparingInt(p->p.slot().ordinal())).toList();
            if(build!=null) {var ids=new HashSet<String>();var slots=EnumSet.noneOf(Slot.class);int points=0;for(var part:parts) {if(!ids.add(part.id()) || !slots.add(part.slot())) throw new IllegalArgumentException("Duplicate trial part");var selection=build.slots().get(part.slot());if(!part.id().equals(selection.part()) || !part.fingerprint().equals(selection.fingerprint())) throw new IllegalArgumentException("Trial part definition differs");for(var field:part.slot().fields()) {var range=part.settings().get(field);int value=selection.values().get(field);if(value<range.min() || value>range.max()) throw new IllegalArgumentException("Trial value outside part range");}points+=part.points();}if(points>build.budget() || parts.stream().anyMatch(p->!ids.containsAll(p.requires()) || !Collections.disjoint(ids,p.excludes()))) throw new IllegalArgumentException("Invalid trial combination");}
        }
        public static Origin capture(String title,PartsBuild build,PartsCatalog catalog) {return new Origin(title,build,build==null?List.of():Arrays.stream(Slot.values()).map(slot->Objects.requireNonNull(catalog.find(build.slots().get(slot).part()))).toList());}
        public static Origin fitted(String title,FittedParts equipment,Tuning tuning) {return new Origin(title,null,List.of(),new FittedSource(equipment,tuning));}
        public boolean complete() {return build!=null || fitted!=null;}
        public String vehicle() {return build!=null?build.vehicle():fitted==null?null:fitted.equipment().build().vehicle();}
        public String physics() {return build!=null?build.physics():fitted==null?null:fitted.equipment().build().physics();}
        public List<Part> definitions() {return fitted==null?parts:Arrays.stream(Slot.values()).map(slot->fitted.equipment().definitions().get(slot)).toList();}
        public boolean sameSource(Origin other) {return other!=null && Objects.equals(build,other.build) && parts.equals(other.parts) && (fitted==null?other.fitted==null:other.fitted!=null && fitted.equipment().sameEquipment(other.fitted.equipment()) && fitted.settings().withLivery(0).equals(other.fitted.settings().withLivery(0)));}
        public boolean acceptsPlan(PartsBuild plan) {
            if(fitted==null) return build==null || build.equals(plan);
            try {return fitted.equipment().sameEquipment(new FittedParts(plan,fitted.equipment().definitions())) && plan.tuning(0).equals(fitted.settings().withLivery(0));}catch(IllegalArgumentException error) {return false;}
        }
        boolean matches(Tuning tuning,String physics) {return fitted!=null?fitted.equipment().build().physics().equals(physics) && fitted.settings().withLivery(tuning.livery()).equals(tuning):build==null || build.physics().equals(physics) && build.tuning(tuning.livery()).equals(tuning);}
    }
    public record Draft(Intent intent,Origin a,Origin b) {
        public Draft {if(intent==null || a==null || b==null || !b.complete() || a.complete() && (!a.vehicle().equals(b.vehicle()) || !a.physics().equals(b.physics()))) throw new IllegalArgumentException("Invalid parts trial brief");}
        public static Draft prepare(PartsCatalog catalog,PartsComparison.Reference reference,PartsBuild draft,Intent intent) {return new Draft(intent,Origin.capture(reference==null?"当前车库":reference.name(),reference==null?null:reference.build(),catalog),Origin.capture("当前部件草稿",draft,catalog));}
        public static Draft guided(TuningGuide.Goal goal,FittedParts equipment,Tuning a,Tuning b) {return new Draft(new Intent("调校向导："+goal.title,"比较当前项目的重复测量与波动。","仍需检查其它项目和整圈；建议不证明收益。"),Origin.fitted("向导 A · 当前装备与参数",equipment,a),Origin.fitted("向导 B · 同装备候选",equipment,b));}
        public TuningPurpose bind(TuningExperiment.Snapshot report) {int version=a.fitted()!=null || b.fitted()!=null?2:1;var result=new TuningPurpose(version,report.id(),identityOf(report,version),intent,a,b);if(!result.matches(report)) throw new IllegalArgumentException("Brief differs from frozen trial");return result;}
    }
    public TuningPurpose {if(version<1 || version>2 || !ActivityCard.identity(experiment) || identity==null || !identity.matches("[a-f0-9]{64}") || intent==null || a==null || b==null || version!=(a.fitted()!=null || b.fitted()!=null?2:1)) throw new IllegalArgumentException("Invalid trial purpose");new Draft(intent,a,b);}
    public boolean matches(TuningExperiment.Snapshot report) {return report!=null && experiment.equals(report.id()) && identity.equals(identityOf(report,version)) && a.matches(report.a(),report.conditions().physics()) && b.matches(report.b(),report.conditions().physics());}
    private static String identityOf(TuningExperiment.Snapshot report,int version) {return PartsCatalog.hash(new Gson().toJson(report.conditions())+"\n"+(version==1?report.a().performanceKey():new Gson().toJson(report.a().withLivery(0)))+"\n"+(version==1?report.b().performanceKey():new Gson().toJson(report.b().withLivery(0))));}
}
