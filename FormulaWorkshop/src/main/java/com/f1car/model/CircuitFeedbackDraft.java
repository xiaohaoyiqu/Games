package com.f1car.model;

import java.util.*;

/** Unsubmitted authoring state. Its frozen identity and source survive incomplete text and restart. */
public record CircuitFeedbackDraft(int version,String id,CircuitDocument source,String physics,long createdAt,
                                   String author,String title,String summary,List<CircuitFeedback.Note> notes,int selected) {
    public CircuitFeedbackDraft {
        if(version!=1 || id==null || !id.matches("feedback-[a-f0-9]{32}") || source==null || physics==null || !physics.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || createdAt<0
            || !PartsCatalog.text(author,40) || !PartsCatalog.text(title,60) || summary==null || !PartsCatalog.text(summary.replace("\n",""),240) || summary.length()>240
            || notes==null || notes.size()>CircuitFeedback.NOTE_LIMIT || selected<0 || selected>=Math.max(1,notes.size())) throw new IllegalArgumentException("Invalid feedback draft");
        if(!PartsCatalog.text(source.name(),60) || source.name().isBlank() || !PartsCatalog.text(source.metadata().author(),40)
            || !PartsCatalog.text(source.metadata().description().replace("\n",""),240) || source.metadata().description().length()>240
            || !PartsCatalog.text(source.metadata().revision(),120)) throw new IllegalArgumentException("Invalid feedback source text");
        notes=List.copyOf(notes);
        if(notes.stream().map(CircuitFeedback.Note::id).distinct().count()!=notes.size()) throw new IllegalArgumentException("Duplicate draft note");
        String key=null;
        for(var note:notes) if(note.evidence()!=null) {
            if(key==null) key=source.driveKey();
            if(!note.evidence().documentKey().equals(key) || !note.evidence().physics().equals(physics)) throw new IllegalArgumentException("Draft observation differs from frozen source");
        }
    }
    public CircuitFeedback publish() {return new CircuitFeedback(1,id,source,physics,author,title,summary,createdAt,notes);}
}
