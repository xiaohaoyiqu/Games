package com.f1car.model;

import java.util.Objects;

/** Selects the exact original message on one side of a frozen revision declaration. */
public record RevisionDiscussionTarget(PartsDiscussionRevision revision,Side side) {
    public enum Side {PARENT("父侧"),CHILD("子侧");public final String title;Side(String title) {this.title=title;}}
    public RevisionDiscussionTarget {Objects.requireNonNull(revision);Objects.requireNonNull(side);}
    public PartsDiscussion frozen() {return side==Side.PARENT?revision.parent():revision.child();}
    public String messageId() {return side==Side.PARENT?revision.parentMessage():revision.childMessage();}
    public PartsDiscussion.Message message() {return frozen().message(messageId());}
    public Review review(PartsDiscussion local) {return new Review(this,local,PartsDiscussionRevision.status(frozen(),local));}
    public record Review(RevisionDiscussionTarget target,PartsDiscussion local,PartsDiscussionRevision.Status status) {
        public Review {Objects.requireNonNull(target);if(status!=PartsDiscussionRevision.status(target.frozen(),local)) throw new IllegalArgumentException("Forged revision message correspondence");}
        public boolean allowed() {return status==PartsDiscussionRevision.Status.EXACT || status==PartsDiscussionRevision.Status.EXTENDED;}
        public int messagePage() {if(!allowed()) throw new IllegalStateException("Original message is not locally matched");return local.messages().indexOf(local.message(target.messageId()))/5;}
    }
}
