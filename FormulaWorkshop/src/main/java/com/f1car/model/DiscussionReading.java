package com.f1car.model;

import java.util.*;

/** Local reading choices only. No clocks, text copies, author identity or shared receipts. */
public final class DiscussionReading {
    public static final int CAPACITY=64;
    public enum Kind {PARTS,FEEDBACK}
    public record Message(String id,String digest) {
        public Message {if(id==null || !id.matches("(?:message-|feedback-message-)[a-f0-9]{32}") || !hash(digest)) throw new IllegalArgumentException("Invalid reading message");}
    }
    public record Topic(Kind kind,String id,String background,List<Message> messages) {
        public Topic {
            if(kind==null || id==null || !id.matches((kind==Kind.PARTS?"discussion-":"feedback-discussion-")+"[a-f0-9]{32}") || !hash(background) || messages==null || messages.isEmpty() || messages.size()>32) throw new IllegalArgumentException("Invalid reading topic");
            var ids=new HashSet<String>();for(var message:messages) if(message==null || !message.id().startsWith(kind==Kind.PARTS?"message-":"feedback-message-") || !ids.add(message.id())) throw new IllegalArgumentException("Mixed or duplicate reading messages");
            messages=List.copyOf(messages);
        }
        public Message message(String id) {return messages.stream().filter(value->value.id().equals(id)).findFirst().orElse(null);}
        public boolean sameBackground(Topic value) {return kind==value.kind && id.equals(value.id) && background.equals(value.background);}
    }
    public record State(int version,List<Topic> topics) {
        public State {
            if(version!=1 || topics==null || topics.size()>CAPACITY) throw new IllegalArgumentException("Invalid reading state");
            var keys=new HashSet<String>();for(var topic:topics) if(topic==null || !keys.add(topic.kind()+":"+topic.id()+":"+topic.background())) throw new IllegalArgumentException("Duplicate reading background");
            topics=List.copyOf(topics);
        }
        public static State empty() {return new State(1,List.of());}
        public boolean read(Topic visible,String id) {var message=visible.message(id);return message!=null && topics.stream().filter(value->value.sameBackground(visible)).anyMatch(value->message.equals(value.message(id)));}
        public int unread(Topic visible) {return (int)visible.messages().stream().filter(value->!read(visible,value.id())).count();}
        /** Null message selects the exact visible snapshot, including messages outside its page. */
        public State mark(Topic visible,String message,boolean read) {
            Objects.requireNonNull(visible);if(message!=null && visible.message(message)==null) throw new IllegalArgumentException("Unknown visible message");
            var old=topics.stream().filter(value->value.sameBackground(visible)).findFirst().orElse(null);var marks=new LinkedHashMap<String,Message>();if(old!=null) old.messages().forEach(value->marks.put(value.id(),value));
            for(var value:visible.messages()) if(message==null || message.equals(value.id())) {if(read) marks.put(value.id(),value);else marks.remove(value.id());}
            // A changed branch can reuse IDs. Keep at most the currently visible identities.
            marks.keySet().removeIf(id->visible.message(id)==null);
            var next=new ArrayList<>(topics);next.removeIf(value->value.sameBackground(visible));
            if(!marks.isEmpty()) {if(next.size()==CAPACITY) next.remove(0);next.add(new Topic(visible.kind(),visible.id(),visible.background(),List.copyOf(marks.values())));}
            return new State(1,next);
        }
    }
    private static boolean hash(String value) {return value!=null && value.matches("[a-f0-9]{64}");}
    private DiscussionReading() {}
}
