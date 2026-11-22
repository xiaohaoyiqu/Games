package com.f1car.model;

import com.f1car.model.PartsCatalog.*;

/** Frozen discussion context and unsubmitted text; no message or measurement is published. */
public record DiscussionDraft(PartsPlanShare source,PartsTrialEvidence evidence,PartsPlan local,PartsDiscussion before,
                              String parent,String title,String author,String body,PartsDiscussion.Kind kind,Slot slot,Field field) {
    public DiscussionDraft {
        if(source==null || title==null || title.length()>80 || author==null || author.length()>40 || body==null || body.length()>640 || kind==null
            || !PartsCatalog.text(title,80) || !PartsCatalog.text(author,40) || !PartsCatalog.text(body.replace("\n",""),640)
            || field!=null && (slot==null || !slot.fields().contains(field))) throw new IllegalArgumentException("Invalid discussion draft");
        if(evidence!=null && !evidence.plan().equals(source) || local!=null && !local.equals(source.plan())) throw new IllegalArgumentException("Mixed draft plan");
        if(before!=null && (!before.plan().equals(source) || !java.util.Objects.equals(before.evidence(),evidence) || before.message(parent)==null)
            || before==null && parent!=null) throw new IllegalArgumentException("Invalid draft reply context");
    }
}
