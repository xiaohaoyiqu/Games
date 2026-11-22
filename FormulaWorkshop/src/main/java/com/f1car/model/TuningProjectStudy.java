package com.f1car.model;

import java.util.*;

/** Frozen, explicitly selected local reports. No pooling, ranking, optimum or current-catalogue lookup. */
public final class TuningProjectStudy {
    public enum Coverage {FROZEN_PARTS,PARAMETERS_ONLY}
    public record Report(TuningExperiment.Snapshot snapshot,TuningPurpose purpose) {
        public Report {Objects.requireNonNull(snapshot);if(purpose!=null && !purpose.matches(snapshot)) throw new IllegalArgumentException("Project source differs from report");}
    }
    public record Match(Coverage coverage,List<String> issues) {
        public Match {Objects.requireNonNull(coverage);issues=List.copyOf(issues);}public boolean eligible() {return issues.isEmpty();}
    }
    public record Candidate(Report report,Match match) {public Candidate {Objects.requireNonNull(report);Objects.requireNonNull(match);}}
    public record Row(TuningTrial.Exercise exercise,Report report,Coverage coverage,int runs,TuningExperiment.Assessment assessment,
                      double medianA,double spreadA,double medianB,double spreadB,double delta) {
        public boolean complete() {return report!=null && runs==6;}
    }
    private final Report anchor;private final List<Candidate> candidates;private final Map<TuningTrial.Exercise,Report> selected;private final List<Row> rows;
    public TuningProjectStudy(Report anchor,List<Report> reports) {
        this(anchor,candidates(anchor,reports),Map.of(anchor.snapshot().conditions().exercise(),anchor));
    }
    private TuningProjectStudy(Report anchor,List<Candidate> candidates,Map<TuningTrial.Exercise,Report> selected) {
        this.anchor=anchor;this.candidates=candidates;this.selected=Map.copyOf(selected);
        var rows=new ArrayList<Row>();for(var exercise:TuningTrial.Exercise.values()) {var report=selected.get(exercise);var match=report==null?null:match(anchor,report);var snapshot=report==null?null:report.snapshot();boolean complete=snapshot!=null && snapshot.complete();
            rows.add(new Row(exercise,report,match==null?null:match.coverage(),snapshot==null?0:snapshot.runs().size(),snapshot==null?TuningExperiment.Assessment.NEED_RUNS:snapshot.assessment(),
                complete?snapshot.median(TuningExperiment.Slot.A):Double.NaN,complete?snapshot.spread(TuningExperiment.Slot.A):Double.NaN,complete?snapshot.median(TuningExperiment.Slot.B):Double.NaN,complete?snapshot.spread(TuningExperiment.Slot.B):Double.NaN,complete?snapshot.delta():Double.NaN));}
        this.rows=List.copyOf(rows);
    }
    private static List<Candidate> candidates(Report anchor,List<Report> reports) {
        Objects.requireNonNull(anchor);if(reports==null || reports.isEmpty() || reports.size()>9) throw new IllegalArgumentException("Project reports must be bounded");
        var ids=new HashSet<String>();boolean found=false;var result=new ArrayList<Candidate>();for(var report:reports) {Objects.requireNonNull(report);if(!ids.add(report.snapshot().id())) throw new IllegalArgumentException("Duplicate project report identity");if(report.equals(anchor)) found=true;result.add(new Candidate(report,match(anchor,report)));}if(!found) throw new IllegalArgumentException("Anchor must be among frozen reports");return List.copyOf(result);
    }
    public Report anchor() {return anchor;}public List<Candidate> candidates() {return candidates;}public Map<TuningTrial.Exercise,Report> selected() {return selected;}public List<Row> rows() {return rows;}
    public TuningProjectStudy choose(TuningTrial.Exercise exercise,String id) {
        Objects.requireNonNull(exercise);var candidate=candidates.stream().filter(c->c.report().snapshot().id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown project report"));
        if(candidate.report().snapshot().conditions().exercise()!=exercise || !candidate.match().eligible()) throw new IllegalArgumentException("Incompatible project report");var next=new EnumMap<TuningTrial.Exercise,Report>(TuningTrial.Exercise.class);next.putAll(selected);next.put(exercise,candidate.report());return new TuningProjectStudy(anchor,candidates,next);
    }
    public TuningProjectStudy clear(TuningTrial.Exercise exercise) {Objects.requireNonNull(exercise);var next=new EnumMap<TuningTrial.Exercise,Report>(TuningTrial.Exercise.class);next.putAll(selected);next.remove(exercise);return new TuningProjectStudy(anchor,candidates,next);}
    public static Match match(Report anchor,Report candidate) {
        Objects.requireNonNull(anchor);Objects.requireNonNull(candidate);var a=anchor.snapshot();var b=candidate.snapshot();var issues=new ArrayList<String>();
        if(!a.a().withLivery(0).equals(b.a().withLivery(0)) || !a.b().withLivery(0).equals(b.b().withLivery(0))) issues.add("A／B 性能参数或方向不同；不自动互换两侧。");
        if(!a.conditions().physics().equals(b.conditions().physics())) issues.add("物理版本不同。");
        if(!a.conditions().controls().equals(b.conditions().controls())) issues.add("记录的操作身份不同。");
        if(!a.conditions().trackId().equals(b.conditions().trackId()) || !a.conditions().geometry().equals(b.conditions().geometry())) issues.add("路线身份或几何条件不同；直线项目也不自动忽略记录条件。");
        var x=anchor.purpose();var y=candidate.purpose();Coverage coverage=Coverage.PARAMETERS_ONLY;
        if((x==null)!=(y==null)) issues.add("两份报告的部件来源记录不完整，不能混合已知与未知来源。");
        else if(x!=null) {if(!sameOrigin(x.a(),y.a()) || !sameOrigin(x.b(),y.b())) issues.add("冻结的部件方案、定义或说明不同；不按当前目录补写旧来源。");if(x.a().complete() && y.a().complete()) coverage=Coverage.FROZEN_PARTS;}
        return new Match(coverage,issues);
    }
    private static boolean sameOrigin(TuningPurpose.Origin a,TuningPurpose.Origin b) {return a.sameSource(b);}
}
