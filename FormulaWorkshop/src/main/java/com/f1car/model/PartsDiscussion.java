package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Append-only discussion anchored to one immutable plan, not an authenticated conversation. */
public record PartsDiscussion(int version,String id,String title,PartsPlanShare plan,PartsTrialEvidence evidence,long createdAt,List<Message> messages) {
    public static final int CAPACITY=32;
    public enum Kind { QUESTION("问题"),OBSERVATION("观察声明"),IDEA("改装建议"),REPLY("回应");public final String title;Kind(String title) {this.title=title;} }
    public record Message(String id,String parent,String author,Kind kind,Slot slot,Field field,String text,long createdAt) {
        public Message {if(!identity(id,"message-") || parent!=null && !identity(parent,"message-") || !PartsCatalog.text(author,40) || author.isBlank() || kind==null || field!=null && (slot==null || !slot.fields().contains(field)) || text==null || text.isBlank() || text.length()>640 || !PartsCatalog.text(text.replace("\n",""),640) || createdAt<0) throw new IllegalArgumentException("Invalid discussion message");}
        public static Message create(String parent,String author,Kind kind,Slot slot,Field field,String text) {return new Message(newId("message-"),parent,author,kind,slot,field,text,System.currentTimeMillis());}
        public String scope() {return slot==null?"整套方案":slot.title+(field==null?"":"／"+field.title);}
    }
    public record Merge(PartsDiscussion before,PartsDiscussion incoming,PartsDiscussion candidate,int added,int shared,List<String> issues) {
        public Merge {issues=List.copyOf(issues);}
        public boolean valid() {return candidate!=null && issues.isEmpty();}
    }
    public PartsDiscussion {
        if(version!=1 || !identity(id,"discussion-") || !PartsCatalog.text(title,80) || title.isBlank() || plan==null || evidence!=null && !evidence.plan().equals(plan) || createdAt<0 || messages==null || messages.isEmpty() || messages.size()>CAPACITY) throw new IllegalArgumentException("Invalid parts discussion");
        var byId=new HashMap<String,Message>();for(var message:messages) if(byId.put(Objects.requireNonNull(message).id(),message)!=null) throw new IllegalArgumentException("Duplicate message identity");
        if(messages.stream().filter(message->message.parent()==null).count()!=1) throw new IllegalArgumentException("Discussion must have one root");
        for(var message:messages) if(message.parent()!=null && !byId.containsKey(message.parent())) throw new IllegalArgumentException("Missing parent message");
        var ordered=new ArrayList<Message>();var remaining=new TreeMap<>(byId);var seen=new HashSet<String>();
        while(!remaining.isEmpty()) {var ready=remaining.values().stream().filter(message->message.parent()==null || seen.contains(message.parent())).findFirst().orElseThrow(()->new IllegalArgumentException("Cyclic discussion"));remaining.remove(ready.id());ordered.add(ready);seen.add(ready.id());}
        messages=List.copyOf(ordered);
    }
    public static PartsDiscussion create(String title,PartsPlanShare plan,PartsTrialEvidence evidence,Message root) {if(root.parent()!=null) throw new IllegalArgumentException("First message has a parent");return new PartsDiscussion(1,newId("discussion-"),title,plan,evidence,System.currentTimeMillis(),List.of(root));}
    public Message message(String id) {return messages.stream().filter(message->message.id().equals(id)).findFirst().orElse(null);}
    public Message root() {return messages.get(0);}
    public int depth(Message value) {int depth=0;for(var parent=value.parent();parent!=null;parent=message(parent).parent()) depth++;return depth;}
    public PartsDiscussion append(Message value) {if(value.parent()==null || message(value.parent())==null) throw new IllegalArgumentException("Reply needs a known parent");var next=new ArrayList<>(messages);next.add(value);return new PartsDiscussion(version,id,title,plan,evidence,createdAt,next);}
    public static Merge merge(PartsDiscussion before,PartsDiscussion incoming) {
        Objects.requireNonNull(incoming);if(before==null) return new Merge(null,incoming,incoming,incoming.messages().size(),0,List.of());
        if(!before.id().equals(incoming.id()) || !before.title().equals(incoming.title()) || !before.plan().equals(incoming.plan()) || !Objects.equals(before.evidence(),incoming.evidence()) || before.createdAt()!=incoming.createdAt()) return new Merge(before,incoming,null,0,0,List.of("话题身份或原始方案／报告背景不同，不能覆盖本地话题。"));
        var values=new LinkedHashMap<String,Message>();before.messages().forEach(value->values.put(value.id(),value));var issues=new ArrayList<String>();int added=0,shared=0;
        for(var message:incoming.messages()) {var old=values.get(message.id());if(old==null) {values.put(message.id(),message);added++;}else if(!old.equals(message)) issues.add("同身份留言内容不同："+message.id());else shared++;}
        if(values.size()>CAPACITY) issues.add("合并后超过三十二条留言；本地原文保持，请另开话题继续。");
        PartsDiscussion candidate=null;if(issues.isEmpty()) try {candidate=new PartsDiscussion(1,before.id(),before.title(),before.plan(),before.evidence(),before.createdAt(),List.copyOf(values.values()));}catch(IllegalArgumentException error) {issues.add("两份讨论的回复关系不一致，本地原文保持。");}
        return new Merge(before,incoming,candidate,added,shared,issues);
    }
    private static boolean identity(String value,String prefix) {return value!=null && value.matches(prefix+"[a-f0-9]{32}");}
    private static String newId(String prefix) {return prefix+UUID.randomUUID().toString().replace("-","");}
}
