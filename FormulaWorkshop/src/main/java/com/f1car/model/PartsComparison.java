package com.f1car.model;

import com.f1car.model.PartsCatalog.Field;
import java.util.*;

/** Frozen A/B values; full rule checks precede starting a trial. No economy or race eligibility. */
public record PartsComparison(String baselineName,Tuning a,Tuning b,List<Delta> values,List<String> issues) {
    public record Reference(String name,PartsBuild build) {public Reference {if(!PartsCatalog.text(name,40) || name.isBlank() || build==null) throw new IllegalArgumentException("Invalid comparison reference");}}
    public record Delta(Field field,int a,int b) {public boolean changed() {return a!=b;}}
    public PartsComparison {values=List.copyOf(values);issues=List.copyOf(issues);}
    public long changedSettings() {int count=0;for(var value:values) if(value.changed()) count++;return count;}
    public boolean valid() {return issues.isEmpty();}
    public static PartsComparison prepare(PartsCatalog catalog,Reference reference,PartsBuild draft,String vehicle,String physics,Tuning garage) {
        var candidate=draft.review(catalog,vehicle,physics,garage.livery());var issues=new ArrayList<String>();candidate.issues().forEach(issue->issues.add("B 草稿："+issue));Tuning a=garage;
        if(reference!=null) {var baseline=reference.build().review(catalog,vehicle,physics,garage.livery());a=baseline.candidate();baseline.issues().forEach(issue->issues.add("A 对照："+issue));}
        var values=new ArrayList<Delta>();for(var field:Field.values()) values.add(new Delta(field,field.percent(a),field.percent(candidate.candidate())));
        if(a.performanceKey().equals(candidate.candidate().performanceKey())) issues.add("A／B 性能参数相同，请先改变一项设置。");
        return new PartsComparison(reference==null?"当前车库":reference.name(),a,candidate.candidate(),values,issues);
    }
}
