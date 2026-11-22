package com.f1car.model;

/** Recoverable unpublished revision context; pending metadata never duplicates the full discussions. */
public record DiscussionRevisionDraft(PartsDiscussion parent,PartsDiscussion child,boolean localParent,boolean localChild,
                                      String parentMessage,String childMessage,String author,String notes,Pending pending) {
    public record Pending(String id,long createdAt) {
        public Pending {if(id==null || !id.matches("revision-[a-f0-9]{32}") || createdAt<0) throw new IllegalArgumentException("Invalid pending revision identity");}
    }
    public DiscussionRevisionDraft {
        if(parent==null || child==null || parent.message(parentMessage)==null || child.message(childMessage)==null
            || !PartsCatalog.text(author,40) || !PartsCatalog.text(notes,320)) throw new IllegalArgumentException("Invalid revision working context");
        if(pending!=null) new PartsDiscussionRevision(1,pending.id(),parent,child,parentMessage,childMessage,author,notes,pending.createdAt());
    }
    public static DiscussionRevisionDraft begin(PartsDiscussion parent,PartsDiscussion child,boolean localParent,boolean localChild,String author) {
        return new DiscussionRevisionDraft(parent,child,localParent,localChild,parent.root().id(),child.root().id(),author,"",null);
    }
    public DiscussionRevisionDraft text(String author,String notes) {return this.author.equals(author) && this.notes.equals(notes)?this:new DiscussionRevisionDraft(parent,child,localParent,localChild,parentMessage,childMessage,author,notes,null);}
    public DiscussionRevisionDraft message(boolean before,String id) {return (before?parentMessage:childMessage).equals(id)?this:new DiscussionRevisionDraft(parent,child,localParent,localChild,before?id:parentMessage,before?childMessage:id,author,notes,null);}
    public DiscussionRevisionDraft swap() {return new DiscussionRevisionDraft(child,parent,localChild,localParent,childMessage,parentMessage,author,notes,null);}
    public DiscussionRevisionDraft prepare() {
        if(pending!=null) return this;var value=PartsDiscussionRevision.declare(parent,child,parentMessage,childMessage,author,notes);
        return new DiscussionRevisionDraft(parent,child,localParent,localChild,parentMessage,childMessage,author,notes,new Pending(value.id(),value.createdAt()));
    }
    public PartsDiscussionRevision declaration() {return pending==null?null:new PartsDiscussionRevision(1,pending.id(),parent,child,parentMessage,childMessage,author,notes,pending.createdAt());}
}
