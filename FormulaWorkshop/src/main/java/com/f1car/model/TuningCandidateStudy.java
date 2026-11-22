package com.f1car.model;

import java.util.*;
import com.f1car.model.TuningProjectStudy.*;

/** Three explicitly selected B candidates against one frozen A; each cell keeps its own A runs. */
public final class TuningCandidateStudy {
    public static final int MAX_COLUMNS=3;
    private final Report anchor;
    private final List<Report> reports;
    private final List<Candidate> candidates;
    private final List<TuningProjectStudy> columns;

    public TuningCandidateStudy(Report anchor,List<Report> reports) {
        // Reuse bounded identity validation; its A/B matches are deliberately not used across columns.
        var initial=new TuningProjectStudy(anchor,reports);
        this.anchor=anchor;this.reports=List.copyOf(reports);
        this.candidates=reports.stream().map(r->new Candidate(r,matchBaseline(anchor,r))).toList();
        this.columns=Collections.unmodifiableList(Arrays.asList(initial,null,null));
    }
    private TuningCandidateStudy(TuningCandidateStudy source,List<TuningProjectStudy> columns) {
        anchor=source.anchor;reports=source.reports;candidates=source.candidates;
        this.columns=Collections.unmodifiableList(new ArrayList<>(columns));
    }
    public Report anchor() {return anchor;}
    public List<Candidate> candidates() {return candidates;}
    public List<TuningProjectStudy> columns() {return columns;}
    public Match candidateMatch(int column,String id) {
        editableColumn(column);var candidate=find(id);var issues=new ArrayList<>(candidate.match().issues());
        for(int i=0;i<MAX_COLUMNS;i++) if(i!=column && columns.get(i)!=null && sameCandidate(columns.get(i).anchor(),candidate.report())) {
            issues.add("此候选 B 已在另一列；同一候选的其他项目请在原列选择。");break;
        }
        return new Match(candidate.match().coverage(),issues);
    }
    public TuningCandidateStudy chooseCandidate(int column,String id) {
        if(!candidateMatch(column,id).eligible()) throw new IllegalArgumentException("Incompatible or duplicate candidate");
        var next=new ArrayList<>(columns);next.set(column,new TuningProjectStudy(find(id).report(),reports));return new TuningCandidateStudy(this,next);
    }
    public TuningCandidateStudy clearCandidate(int column) {
        editableColumn(column);var next=new ArrayList<>(columns);next.set(column,null);return new TuningCandidateStudy(this,next);
    }
    public TuningCandidateStudy chooseProject(int column,TuningTrial.Exercise exercise,String id) {
        var next=new ArrayList<>(columns);next.set(column,requireColumn(column).choose(exercise,id));return new TuningCandidateStudy(this,next);
    }
    public TuningCandidateStudy clearProject(int column,TuningTrial.Exercise exercise) {
        var next=new ArrayList<>(columns);next.set(column,requireColumn(column).clear(exercise));return new TuningCandidateStudy(this,next);
    }
    private TuningProjectStudy requireColumn(int column) {
        if(column<0 || column>=MAX_COLUMNS || columns.get(column)==null) throw new IllegalArgumentException("Choose a candidate column first");return columns.get(column);
    }
    private static void editableColumn(int column) {if(column<1 || column>=MAX_COLUMNS) throw new IllegalArgumentException("Anchor column is fixed");}
    private Candidate find(String id) {return candidates.stream().filter(c->c.report().snapshot().id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown report"));}
    public static Match matchBaseline(Report anchor,Report candidate) {
        Objects.requireNonNull(anchor);Objects.requireNonNull(candidate);var a=anchor.snapshot();var b=candidate.snapshot();var issues=new ArrayList<String>();
        if(!a.a().withLivery(0).equals(b.a().withLivery(0))) issues.add("基准 A 的性能参数不同；不自动互换两侧。");
        if(!a.conditions().physics().equals(b.conditions().physics())) issues.add("物理版本不同。");
        if(!a.conditions().controls().equals(b.conditions().controls())) issues.add("记录的操作身份不同。");
        if(!a.conditions().trackId().equals(b.conditions().trackId()) || !a.conditions().geometry().equals(b.conditions().geometry())) issues.add("路线身份或几何条件不同。");
        var x=anchor.purpose();var y=candidate.purpose();var coverage=Coverage.PARAMETERS_ONLY;
        if((x==null)!=(y==null)) issues.add("来源记录不完整，不能混合已知与未知来源。");
        else if(x!=null) {
            if(!sameOrigin(x.a(),y.a())) issues.add("基准 A 的冻结方案或部件定义不同。");
            if(!x.b().vehicle().equals(y.b().vehicle())) issues.add("候选 B 的记录车型不同。");
            if(x.a().complete() && y.a().complete()) coverage=Coverage.FROZEN_PARTS;
        }
        return new Match(coverage,issues);
    }
    private static boolean sameCandidate(Report a,Report b) {
        if(!a.snapshot().b().withLivery(0).equals(b.snapshot().b().withLivery(0))) return false;
        return a.purpose()==null?b.purpose()==null:b.purpose()!=null && sameOrigin(a.purpose().b(),b.purpose().b());
    }
    private static boolean sameOrigin(TuningPurpose.Origin a,TuningPurpose.Origin b) {return a.sameSource(b);}
}
