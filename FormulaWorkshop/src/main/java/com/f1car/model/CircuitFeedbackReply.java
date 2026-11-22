package com.f1car.model;

import com.f1car.model.CircuitFeedback.*;
import java.util.*;

/** Portable author statement. Source comments, addressed map and responses are frozen together. */
public record CircuitFeedbackReply(int version,String id,CircuitFeedback feedback,String author,String summary,long createdAt,CircuitDocument target,String physics,List<Resolution> resolutions) {
    public CircuitFeedbackReply {
        if(version!=1 || id==null || !id.matches("reply-[a-f0-9]{32}") || feedback==null || !PartsCatalog.text(author,40) || author.isBlank() || summary==null || !PartsCatalog.text(summary.replace("\n",""),240) || summary.length()>240 || createdAt<0 || target==null || physics==null || !physics.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || resolutions==null || resolutions.size()!=feedback.notes().size()) throw new IllegalArgumentException("Invalid feedback reply");
        resolutions=new Entry(feedback,null,resolutions).resolutions();if(resolutions.stream().allMatch(row->row.state()==State.OPEN && row.response().isBlank())) throw new IllegalArgumentException("Reply has no handling statement");
    }
    public static CircuitFeedbackReply capture(Entry entry,CircuitDocument target,String physics,String author,String summary) {
        Objects.requireNonNull(entry);var alignment=entry.alignment(target);if(alignment!=Alignment.MATCHED && alignment!=Alignment.REFERENCE) throw new IllegalArgumentException("Confirm the current target before replying");
        return new CircuitFeedbackReply(1,CircuitFeedback.newId("reply-"),entry.feedback(),author,summary,System.currentTimeMillis(),target,physics,entry.feedback().notes().stream().map(note->entry.resolution(note.id())).toList());
    }
    public Resolution resolution(String noteId) {return resolutions.stream().filter(row->row.noteId().equals(noteId)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown reply note"));}
    public boolean matches(Entry entry) {return entry!=null && feedback.equals(entry.feedback());}
    public boolean sameRoad() {return CircuitFeedback.roadKey(feedback.source()).equals(CircuitFeedback.roadKey(target));}
}
