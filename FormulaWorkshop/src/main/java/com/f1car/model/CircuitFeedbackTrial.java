package com.f1car.model;

import com.f1car.model.CircuitFeedback.Binding;
import com.f1car.model.CircuitFeedback.Entry;
import com.f1car.model.CircuitFeedback.LocationSource;
import com.f1car.model.CircuitObservationRetest.Result;

/** One local author trial attached to a particular player note. */
public record CircuitFeedbackTrial(String id,String feedbackId,String noteId,Binding binding,Result result) {
    public CircuitFeedbackTrial {
        if(id==null || !id.matches("author-trial-[a-f0-9]{32}") || feedbackId==null || !feedbackId.matches("feedback-[a-f0-9]{32}")
                || noteId==null || !noteId.matches("note-[a-f0-9]{32}") || binding==null || result==null)
            throw new IllegalArgumentException("Invalid feedback trial");
    }

    public static CircuitFeedbackTrial from(LocationSource source,Result result) {
        return new CircuitFeedbackTrial(CircuitFeedback.newId("author-trial-"),source.entry().feedback().id(),
                source.note().id(),source.entry().binding(),result);
    }

    public boolean belongsTo(Entry entry) {
        return entry!=null && matchesFeedback(entry.feedback());
    }

    public boolean matchesFeedback(CircuitFeedback feedback) {
        return feedback!=null && feedbackId.equals(feedback.id())
                && feedback.notes().stream().anyMatch(note->noteId.equals(note.id()) && note.location()!=null);
    }

    public boolean matches(LocationSource source) {
        return belongsTo(source.entry()) && noteId.equals(source.note().id()) && binding.equals(source.entry().binding());
    }

    public boolean sameRecordedConditions(CircuitFeedbackTrial other) {
        if(other==null || !feedbackId.equals(other.feedbackId) || !noteId.equals(other.noteId) || !binding.equals(other.binding))
            return false;
        var compared=other.result;
        return result.driveKey().equals(compared.driveKey()) && result.physicsVersion().equals(compared.physicsVersion())
                && result.controls().equals(compared.controls()) && result.range().equals(compared.range())
                && result.split().equals(compared.split()) && result.start().equals(compared.start());
    }
}
