package com.f1car.model;

import java.util.*;

/** Incomplete text plus stable unpublished identities. A stale restored draft cannot duplicate a post. */
public record CircuitFeedbackDiscussionDraft(String threadId,long threadCreatedAt,String messageId,long createdAt,CircuitFeedback feedback,
        CircuitFeedbackDiscussion before,String parent,String title,String author,String body,CircuitFeedbackDiscussion.Kind kind,String note,
        CircuitFeedbackReply reply,CircuitFeedbackTrial trial) {
    public CircuitFeedbackDiscussionDraft {
        if(!CircuitFeedbackDiscussion.identity(threadId,"feedback-discussion-") || !CircuitFeedbackDiscussion.identity(messageId,"feedback-message-") || threadCreatedAt<0 || createdAt<0 || feedback==null
            || !PartsCatalog.text(title,80) || !PartsCatalog.text(author,40) || !CircuitFeedbackDiscussion.text(body,640,false) || kind==null
            || note!=null && feedback.notes().stream().noneMatch(value->value.id().equals(note)) || reply!=null && !reply.feedback().equals(feedback)
            || trial!=null && (!trial.noteId().equals(note) || !trial.matchesFeedback(feedback))) throw new IllegalArgumentException("Invalid feedback conversation draft");
        if(before==null && parent!=null || before!=null && (!before.feedback().equals(feedback) || !before.id().equals(threadId) || before.createdAt()!=threadCreatedAt || !before.title().equals(title) || before.message(parent)==null)) throw new IllegalArgumentException("Mixed draft reply context");
    }
    public static CircuitFeedbackDiscussionDraft begin(CircuitFeedback feedback,CircuitFeedbackDiscussion before,String parent,String author) {
        long now=System.currentTimeMillis();String title=before==null?"关于 "+feedback.title():before.title();if(title.length()>80) title=title.substring(0,80);
        return new CircuitFeedbackDiscussionDraft(before==null?CircuitFeedback.newId("feedback-discussion-"):before.id(),before==null?now:before.createdAt(),CircuitFeedback.newId("feedback-message-"),now,feedback,before,parent,title,author,"",before==null?CircuitFeedbackDiscussion.Kind.QUESTION:CircuitFeedbackDiscussion.Kind.REPLY,null,null,null);
    }
    public CircuitFeedbackDiscussionDraft text(String title,String author,String body) {return new CircuitFeedbackDiscussionDraft(threadId,threadCreatedAt,messageId,createdAt,feedback,before,parent,title,author,body,kind,note,reply,trial);}
    public CircuitFeedbackDiscussionDraft scope(CircuitFeedbackDiscussion.Kind kind,String note,CircuitFeedbackReply reply) {
        return new CircuitFeedbackDiscussionDraft(threadId,threadCreatedAt,messageId,createdAt,feedback,before,parent,title,author,body,kind,note,reply,
                Objects.equals(this.note,note)?trial:null);
    }
    public CircuitFeedbackDiscussionDraft trial(CircuitFeedbackTrial snapshot) {
        return new CircuitFeedbackDiscussionDraft(threadId,threadCreatedAt,messageId,createdAt,feedback,before,parent,title,author,body,kind,note,reply,snapshot);
    }
    public CircuitFeedbackDiscussion.Message message() {return new CircuitFeedbackDiscussion.Message(messageId,parent,author,kind,note,body,reply,createdAt,trial);}
    public CircuitFeedbackDiscussion build() {
        var message=message();
        if(before!=null) return before.append(message);
        var messages=List.of(message);
        return new CircuitFeedbackDiscussion(CircuitFeedbackDiscussion.versionFor(messages),threadId,title,feedback,threadCreatedAt,messages);
    }
    public boolean published(CircuitFeedbackDiscussion value) {if(value==null || !value.id().equals(threadId) || !value.title().equals(title) || !value.feedback().equals(feedback) || value.createdAt()!=threadCreatedAt
        || before!=null && before.messages().stream().anyMatch(original->!original.equals(value.message(original.id())))) return false;try {return message().equals(value.message(messageId));}catch(IllegalArgumentException incomplete) {return false;}}
    public CircuitFeedbackDiscussionDraft refresh(CircuitFeedbackDiscussion value) {
        if(before==null || value==null || !value.id().equals(threadId) || !value.title().equals(title) || value.createdAt()!=threadCreatedAt || !value.feedback().equals(feedback)
            || !Objects.equals(before.message(parent),value.message(parent)) || value.message(messageId)!=null) throw new IllegalArgumentException("Reply context differs or message already exists");
        return new CircuitFeedbackDiscussionDraft(threadId,threadCreatedAt,messageId,createdAt,feedback,value,parent,title,author,body,kind,note,reply,trial);
    }
}
