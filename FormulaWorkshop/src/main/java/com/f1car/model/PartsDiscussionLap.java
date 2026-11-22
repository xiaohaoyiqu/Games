package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Independent complete-lap citation; the discussion, provider and actual tuning stay immutable. */
public record PartsDiscussionLap(String id,String author,long createdAt,PartsDiscussion discussion,String message,EquipmentLapShare share,String notes) {
    public enum Origin {LOCAL("本机引用"),RECEIVED("收到引用");public final String title;Origin(String title) {this.title=title;}}
    public enum Correspondence {
        EQUIPMENT_EXACT("装备功能与实际参数一致"),EQUIPMENT_TUNED("同装备功能，实际微调不同"),PARAMETERS("仅参数一致，缺少装备来源");
        public final String title;Correspondence(String title) {this.title=title;}
    }
    public record Entry(PartsDiscussionLap citation,Origin origin) {public Entry {Objects.requireNonNull(citation);Objects.requireNonNull(origin);}}
    public PartsDiscussionLap {
        if(id==null || !id.matches("discussion-lap-[a-f0-9]{32}") || !PartsCatalog.text(author,40) || author.isBlank() || createdAt<0 || discussion==null || discussion.message(message)==null || share==null
            || notes==null || notes.length()>320 || !PartsCatalog.text(notes.replace("\n",""),320)) throw new IllegalArgumentException("Invalid discussion lap citation");
        correspondence(discussion,share.recording());
    }
    public static PartsDiscussionLap capture(String author,PartsDiscussion discussion,String message,EquipmentLapShare share,String notes) {
        return new PartsDiscussionLap("discussion-lap-"+UUID.randomUUID().toString().replace("-",""),author,System.currentTimeMillis(),discussion,message,share,notes);
    }
    public static Correspondence correspondence(PartsDiscussion discussion,EquipmentLap recording) {
        var plan=Objects.requireNonNull(discussion).plan();var build=plan.plan().build();var source=Objects.requireNonNull(recording).source();
        if(!build.vehicle().equals(source.vehicle()) || !build.physics().equals(source.physics())) throw new IllegalArgumentException("Lap vehicle or physics differs from discussion plan");
        boolean exact=build.tuning(source.tuning().livery()).equals(source.tuning());var installed=source.installed();
        if(installed==null) {if(!exact) throw new IllegalArgumentException("Without frozen equipment only exact actual parameters can correspond");return Correspondence.PARAMETERS;}
        if(build.budget()!=installed.build().budget()) throw new IllegalArgumentException("Equipment budget differs");
        // Validate plan selections/ranges/combination using the actual frozen definitions, without using today's catalog.
        new FittedParts(build,installed.definitions());
        for(var pack:plan.packs()) for(var part:pack.parts()) if(build.slots().values().stream().anyMatch(s->s.part().equals(part.id())) && !part.equals(installed.definitions().get(part.slot()))) throw new IllegalArgumentException("Declared plan definition differs from frozen lap definition");
        for(var slot:Slot.values()) if(!build.slots().get(slot).part().startsWith("builtin:") && plan.packs().stream().flatMap(p->p.parts().stream()).noneMatch(p->p.equals(installed.definitions().get(slot)))) throw new IllegalArgumentException("Plan lacks the frozen custom definition");
        return exact?Correspondence.EQUIPMENT_EXACT:Correspondence.EQUIPMENT_TUNED;
    }
    public Correspondence correspondence() {return correspondence(discussion,share.recording());}
    public PartsDiscussionTrace.Status status(PartsDiscussion local) {
        if(local==null || local.message(message)==null) return PartsDiscussionTrace.Status.MISSING;
        return local.id().equals(discussion.id()) && local.title().equals(discussion.title()) && local.plan().equals(discussion.plan()) && Objects.equals(local.evidence(),discussion.evidence())
            && local.createdAt()==discussion.createdAt() && local.message(message).equals(discussion.message(message))?PartsDiscussionTrace.Status.MATCHED:PartsDiscussionTrace.Status.CONFLICT;
    }
    public List<String> parameterDetails() {
        var lines=new ArrayList<String>();var build=discussion.plan().plan().build();var tuning=share.recording().source().tuning();
        for(var field:Field.values()) lines.add(String.format(Locale.ROOT,"%s：方案 %d%%；本圈实际 %.6f%%%s",field.title,build.slots().get(field.slot()).values().get(field),field.ratio(tuning)*100,
            Float.compare(field.ratio(tuning),build.slots().get(field.slot()).values().get(field)/100f)==0?"":"（不同）"));
        return List.copyOf(lines);
    }
}
