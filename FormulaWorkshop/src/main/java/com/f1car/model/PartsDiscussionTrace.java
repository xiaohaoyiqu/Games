package com.f1car.model;

import java.util.*;

/** Independent immutable citation; original discussion and trace envelopes are never rewritten. */
public record PartsDiscussionTrace(String id,String author,long createdAt,PartsDiscussion discussion,String message,
                                   TrialTraceShare trace,int first,int last,String notes) {
    public enum Origin {LOCAL("本机引用"),RECEIVED("收到的引用");public final String title;Origin(String title) {this.title=title;}}
    public enum Status {MATCHED("对应本地原留言"),MISSING("本地没有原留言"),CONFLICT("本地背景或原文不同");public final String title;Status(String title) {this.title=title;}}
    public record Entry(PartsDiscussionTrace attachment,Origin origin) {public Entry {Objects.requireNonNull(attachment);Objects.requireNonNull(origin);}}
    public PartsDiscussionTrace {
        if(id==null || !id.matches("discussion-trace-[a-f0-9]{32}") || !PartsCatalog.text(author,40) || author.isBlank() || createdAt<0
            || discussion==null || discussion.message(message)==null || trace==null || trace.traces().size()!=1
            || notes==null || notes.length()>320 || !PartsCatalog.text(notes.replace("\n",""),320)) throw new IllegalArgumentException("Invalid discussion trace citation");
        int count=trace.traces().get(0).samples().size();if(first<0 || last<first || last>=count) throw new IllegalArgumentException("Invalid cited sample range");
        correspondence(discussion,trace,trace.traces().get(0).index());
    }
    public static PartsDiscussionTrace capture(String author,PartsDiscussion discussion,String message,TrialTraceShare source,int index,int first,int last,String notes) {
        var selected=Objects.requireNonNull(source.find(index),"Missing selected trace");
        var single=new TrialTraceShare(source.author(),source.notes(),source.report(),source.purpose(),List.of(selected));
        return new PartsDiscussionTrace("discussion-trace-"+UUID.randomUUID().toString().replace("-",""),author,System.currentTimeMillis(),discussion,message,single,first,last,notes);
    }
    public static PartsTrialEvidence.Correspondence correspondence(PartsDiscussion discussion,TrialTraceShare source,int index) {
        Objects.requireNonNull(discussion);Objects.requireNonNull(source);var selected=source.find(index);
        if(selected==null || !selected.matches(source.report(),index)) throw new IllegalArgumentException("Missing or mismatched trace attempt");
        var build=discussion.plan().plan().build();var tuning=source.report().tuning(index);
        if(!build.physics().equals(source.report().conditions().physics()) || !build.tuning(tuning.livery()).equals(tuning)) throw new IllegalArgumentException("Trace parameters do not match discussion plan");
        if(source.purpose()!=null) {var side=source.report().order().get(index)==TuningExperiment.Slot.A?source.purpose().a():source.purpose().b();
            if(side.complete()) {if(!side.acceptsPlan(build)) throw new IllegalArgumentException("Trace parts do not match discussion plan");return PartsTrialEvidence.Correspondence.PARTS;}}
        return PartsTrialEvidence.Correspondence.PARAMETERS;
    }
    public PartsTrialEvidence.Correspondence correspondence() {return correspondence(discussion,trace,trace.traces().get(0).index());}
    public Status status(PartsDiscussion local) {
        if(local==null || local.message(message)==null) return Status.MISSING;
        return local.id().equals(discussion.id()) && local.title().equals(discussion.title()) && local.plan().equals(discussion.plan())
            && Objects.equals(local.evidence(),discussion.evidence()) && local.createdAt()==discussion.createdAt() && local.message(message).equals(discussion.message(message))?Status.MATCHED:Status.CONFLICT;
    }
    /** Source sample times and gaps remain unchanged. This is a view, not a replacement source trace. */
    public TrialTraceShare excerpt() {
        var original=trace.traces().get(0);var selected=new TuningTrace(original.version(),original.report(),original.index(),original.binding(),
            original.samples().subList(first,last+1),original.positions()==null?null:original.positions().subList(first,last+1));
        return new TrialTraceShare(trace.author(),trace.notes(),trace.report(),trace.purpose(),List.of(selected));
    }
}
