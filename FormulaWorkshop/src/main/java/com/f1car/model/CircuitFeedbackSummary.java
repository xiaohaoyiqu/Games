package com.f1car.model;

import com.f1car.model.CircuitFeedback.*;
import java.util.*;

/** Counts received statements, never unique players, verified defects or approval scores. */
public record CircuitFeedbackSummary(List<Group> groups,int feedbackCount,int noteCount,int replyCount) {
    public record Group(CircuitDocument source,String physics,int reports,int notes,Map<Kind,Integer> kinds,Map<State,Integer> states,int positioned,int observations,int replies) {
        public Group {Objects.requireNonNull(source);Objects.requireNonNull(physics);kinds=Map.copyOf(kinds);states=Map.copyOf(states);}
    }
    public CircuitFeedbackSummary {groups=List.copyOf(groups);}
    private static final class Totals {
        final CircuitDocument source;final String physics;int reports,notes,positioned,observations,replies;
        final EnumMap<Kind,Integer> kinds=new EnumMap<>(Kind.class);final EnumMap<State,Integer> states=new EnumMap<>(State.class);
        Totals(CircuitFeedback value) {source=value.source();physics=value.physics();for(var kind:Kind.values()) kinds.put(kind,0);for(var state:State.values()) states.put(state,0);}
        Group group() {return new Group(source,physics,reports,notes,kinds,states,positioned,observations,replies);}
    }
    public static CircuitFeedbackSummary summarize(List<Entry> entries,List<CircuitFeedbackReply> replies) {
        if(entries==null || entries.size()>32 || replies==null || replies.size()>64 || entries.stream().map(e->e.feedback().id()).distinct().count()!=entries.size() || replies.stream().map(CircuitFeedbackReply::id).distinct().count()!=replies.size()) throw new IllegalArgumentException("Invalid feedback summary inputs");
        var counts=new LinkedHashMap<String,Totals>();var byId=new HashMap<String,Entry>();int notes=0;
        for(var entry:entries) {var feedback=entry.feedback();byId.put(feedback.id(),entry);var total=counts.computeIfAbsent(key(feedback),unused->new Totals(feedback));total.reports++;for(var note:feedback.notes()) {notes++;total.notes++;total.kinds.merge(note.kind(),1,Integer::sum);total.states.merge(entry.resolution(note.id()).state(),1,Integer::sum);if(note.location()!=null) total.positioned++;if(note.evidence()!=null) total.observations++;}}
        for(var reply:replies) {if(!reply.matches(byId.get(reply.feedback().id()))) throw new IllegalArgumentException("Reply source differs");counts.get(key(reply.feedback())).replies++;}
        return new CircuitFeedbackSummary(counts.values().stream().map(Totals::group).toList(),entries.size(),notes,replies.size());
    }
    private static String key(CircuitFeedback value) {return value.source().id()+":"+value.source().driveKey()+":"+value.physics();}
}
