package com.f1car.model;

import java.util.*;

/** Append-only offline conversation. Feedback originals, map editions and handling stay separate. */
public record CircuitFeedbackDiscussion(int version,String id,String title,CircuitFeedback feedback,long createdAt,List<Message> messages) {
    public static final int CAPACITY=32,REPLY_LIMIT=4;
    public static final int LEGACY_VERSION=1,TRIAL_VERSION=2;
    public enum Kind {QUESTION("问题"),OBSERVATION("观察声明"),IDEA("地图建议"),REPLY("回应");public final String title;Kind(String title) {this.title=title;}}
    public record Message(String id,String parent,String author,Kind kind,String note,String text,CircuitFeedbackReply reply,long createdAt,
                          CircuitFeedbackTrial trial) {
        public Message {
            if(!identity(id,"feedback-message-") || parent!=null && !identity(parent,"feedback-message-") || !PartsCatalog.text(author,40) || author.isBlank() || kind==null
                || note!=null && !identity(note,"note-") || !CircuitFeedbackDiscussion.text(text,640,true) || createdAt<0
                || trial!=null && !trial.noteId().equals(note)) throw new IllegalArgumentException("Invalid feedback discussion message");
        }
        public Message(String id,String parent,String author,Kind kind,String note,String text,CircuitFeedbackReply reply,long createdAt) {
            this(id,parent,author,kind,note,text,reply,createdAt,null);
        }
        public static Message create(String parent,String author,Kind kind,String note,String text,CircuitFeedbackReply reply) {return new Message(CircuitFeedback.newId("feedback-message-"),parent,author,kind,note,text,reply,System.currentTimeMillis());}
    }
    public record Merge(CircuitFeedbackDiscussion before,CircuitFeedbackDiscussion incoming,CircuitFeedbackDiscussion candidate,int added,int shared,List<String> issues) {
        public Merge {Objects.requireNonNull(incoming);issues=List.copyOf(issues);}
        public boolean valid() {return candidate!=null && issues.isEmpty();}
    }
    public CircuitFeedbackDiscussion {
        if(!identity(id,"feedback-discussion-") || !PartsCatalog.text(title,80) || title.isBlank() || feedback==null || createdAt<0 || messages==null || messages.isEmpty() || messages.size()>CAPACITY) throw new IllegalArgumentException("Invalid feedback discussion");
        messages=List.copyOf(messages);
        if(version!=versionFor(messages)) throw new IllegalArgumentException("Discussion version differs from attachments");
        var byId=new TreeMap<String,Message>();var replies=new HashMap<String,CircuitFeedbackReply>();int attached=0;
        var trials=new HashMap<String,CircuitFeedbackTrial>();
        for(var message:messages) {
            Objects.requireNonNull(message);if(byId.putIfAbsent(message.id(),message)!=null) throw new IllegalArgumentException("Duplicate feedback message identity");
            if(message.note()!=null && feedback.notes().stream().noneMatch(note->note.id().equals(message.note()))) throw new IllegalArgumentException("Unknown source feedback note");
            if(message.reply()!=null) {if(!message.reply().feedback().equals(feedback) || ++attached>REPLY_LIMIT) throw new IllegalArgumentException("Mixed or excessive author reply snapshots");var old=replies.putIfAbsent(message.reply().id(),message.reply());if(old!=null && !old.equals(message.reply())) throw new IllegalArgumentException("Conflicting reply snapshot identity");}
            if(message.trial()!=null) {
                var trial=message.trial();
                var previous=trials.putIfAbsent(trial.id(),trial);
                if(!trial.matchesFeedback(feedback) || previous!=null && !previous.equals(trial))
                    throw new IllegalArgumentException("Mismatched or conflicting trial snapshot");
            }
        }
        if(messages.stream().filter(message->message.parent()==null).count()!=1) throw new IllegalArgumentException("Feedback discussion requires one root");
        for(var message:messages) if(message.parent()!=null && !byId.containsKey(message.parent())) throw new IllegalArgumentException("Missing feedback message parent");
        var ordered=new ArrayList<Message>();var seen=new HashSet<String>();
        while(!byId.isEmpty()) {var ready=byId.values().stream().filter(message->message.parent()==null || seen.contains(message.parent())).findFirst().orElseThrow(()->new IllegalArgumentException("Cyclic feedback discussion"));byId.remove(ready.id());seen.add(ready.id());ordered.add(ready);}
        messages=List.copyOf(ordered);
    }
    static int versionFor(Collection<Message> messages) {
        return messages.stream().anyMatch(message->message.trial()!=null)?TRIAL_VERSION:LEGACY_VERSION;
    }
    public static CircuitFeedbackDiscussion create(String title,CircuitFeedback feedback,Message root) {if(root.parent()!=null) throw new IllegalArgumentException("Root message has a parent");return new CircuitFeedbackDiscussion(versionFor(List.of(root)),CircuitFeedback.newId("feedback-discussion-"),title,feedback,System.currentTimeMillis(),List.of(root));}
    public Message message(String id) {return messages.stream().filter(value->value.id().equals(id)).findFirst().orElse(null);}
    public Message root() {return messages.get(0);}
    public int depth(Message value) {if(value==null || !value.equals(message(value.id()))) throw new IllegalArgumentException("Message not in this discussion");int depth=0;for(String parent=value.parent();parent!=null;parent=message(parent).parent()) depth++;return depth;}
    public CircuitFeedbackDiscussion append(Message message) {if(message.parent()==null || message(message.parent())==null) throw new IllegalArgumentException("Reply needs a known parent");var values=new ArrayList<>(messages);values.add(message);return new CircuitFeedbackDiscussion(versionFor(values),id,title,feedback,createdAt,values);}
    public static Merge merge(CircuitFeedbackDiscussion before,CircuitFeedbackDiscussion incoming) {
        Objects.requireNonNull(incoming);if(before==null) return new Merge(null,incoming,incoming,incoming.messages().size(),0,List.of());
        if(!before.id().equals(incoming.id()) || !before.title().equals(incoming.title()) || !before.feedback().equals(incoming.feedback()) || before.createdAt()!=incoming.createdAt()) return new Merge(before,incoming,null,0,0,List.of("话题身份、标题或原始反馈背景不同，不能覆盖本地原文。"));
        var values=new LinkedHashMap<String,Message>();before.messages().forEach(message->values.put(message.id(),message));var issues=new ArrayList<String>();int added=0,shared=0;
        for(var message:incoming.messages()) {var old=values.get(message.id());if(old==null) {values.put(message.id(),message);added++;}else if(!old.equals(message)) issues.add("同身份留言内容不同："+message.id());else shared++;}
        if(values.size()>CAPACITY) issues.add("合并后超过三十二条留言；请保留原话题并另起讨论。");
        CircuitFeedbackDiscussion candidate=null;if(issues.isEmpty()) try {candidate=new CircuitFeedbackDiscussion(versionFor(values.values()),before.id(),before.title(),before.feedback(),before.createdAt(),List.copyOf(values.values()));}catch(IllegalArgumentException error) {issues.add("回复关系、答复或试驾快照不一致，原话题保持。");}
        return new Merge(before,incoming,candidate,added,shared,issues);
    }
    static boolean identity(String value,String prefix) {return value!=null && value.matches(prefix+"[a-f0-9]{32}");}
    static boolean text(String value,int max,boolean required) {return value!=null && value.length()<=max && (!required || !value.isBlank()) && PartsCatalog.text(value.replace("\n",""),max);}
}
