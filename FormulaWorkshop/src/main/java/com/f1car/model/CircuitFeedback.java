package com.f1car.model;

import com.google.gson.Gson;
import java.util.*;

/** A player's original comments are immutable; binding and handling belong to the recipient. */
public record CircuitFeedback(int version,String id,CircuitDocument source,String physics,String author,String title,String summary,long createdAt,List<Note> notes) {
    public static final int NOTE_LIMIT=16;
    public enum Kind { DRIVING("驾驶"),VISIBILITY("路线辨识"),SCENERY("布景"),IDEA("建议");public final String title;Kind(String title) {this.title=title;} }
    public enum State { OPEN("待查看"),ACCEPTED("准备处理"),DONE("已处理"),NOT_REPRODUCED("未复现"),DEFERRED("暂缓");public final String title;State(String title) {this.title=title;} }
    public enum Alignment { UNBOUND("未对应本地图"),MATCHED("道路与布景相同"),REFERENCE("已确认旧版参考"),CHANGED("本地图再次改变，需重新对应"),MISSING("对应地图已移除");public final String title;Alignment(String title) {this.title=title;} }
    public record Evidence(String documentKey,String physics,CircuitTrial.Kind kind,CircuitDriveReview.Status status,double elapsed,CircuitDriveReview.Marker marker) {
        public Evidence {if(documentKey==null || !documentKey.matches("[a-f0-9]{64}") || !token(physics) || kind==null || status==null || status==CircuitDriveReview.Status.RUNNING || !Double.isFinite(elapsed) || elapsed<0 || elapsed>300 || marker==null || marker.time()>elapsed || marker.car()>=kind.drivers || !text(marker.message(),160,true)) throw new IllegalArgumentException("Invalid feedback observation");}
        public static Evidence from(CircuitDocument source,String physics,CircuitDriveReview.Snapshot review,CircuitDriveReview.Marker marker) {if(review==null || !review.matches(source) || !review.markers().contains(marker)) throw new IllegalArgumentException("Observation belongs to another draft");return new Evidence(review.documentKey(),physics,review.kind(),review.status(),review.elapsed(),marker);}
    }
    public record Note(String id,Kind kind,String text,CircuitInspector.Location location,Evidence evidence) {
        public Note {if(!identity(id,"note-") || kind==null || !CircuitFeedback.text(text,320,true) || evidence!=null && !Objects.equals(location,evidence.marker().location())) throw new IllegalArgumentException("Invalid feedback note");}
        public static Note create(Kind kind,String text,CircuitInspector.Location location) {return new Note(newId("note-"),kind,text,location,null);}
        public Note located(CircuitInspector.Location value) {return new Note(id,kind,text,value,Objects.equals(value,location)?evidence:null);}
    }
    public record Binding(String targetId,String roadKey) {public Binding {if(targetId==null || !targetId.matches("user-[a-f0-9]{32}") || roadKey==null || !roadKey.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid local feedback binding");}}
    public record Resolution(String noteId,State state,String response) {public Resolution {if(!identity(noteId,"note-") || state==null || !text(response,320,false)) throw new IllegalArgumentException("Invalid local feedback response");}}
    public record LocationSource(Entry entry,Note note) {
        public LocationSource {
            if(entry==null || entry.binding()==null || note==null || note.location()==null || !entry.feedback().notes().contains(note))
                throw new IllegalArgumentException("A bound feedback note with a location is required");
        }

        public boolean matches(Entry current) {
            return current!=null && entry.feedback().equals(current.feedback()) && entry.binding().equals(current.binding());
        }
    }
    public record Entry(CircuitFeedback feedback,Binding binding,List<Resolution> resolutions) {
        public Entry {Objects.requireNonNull(feedback);resolutions=List.copyOf(resolutions);if(resolutions.size()>NOTE_LIMIT || resolutions.stream().map(Resolution::noteId).distinct().count()!=resolutions.size() || resolutions.stream().anyMatch(row->feedback.notes().stream().noneMatch(note->note.id().equals(row.noteId())))) throw new IllegalArgumentException("Unknown or duplicate feedback resolution");resolutions=resolutions.stream().sorted(Comparator.comparing(Resolution::noteId)).toList();}
        public static Entry received(CircuitFeedback feedback) {return new Entry(feedback,null,List.of());}
        public Resolution resolution(String noteId) {if(feedback.notes().stream().noneMatch(note->note.id().equals(noteId))) throw new IllegalArgumentException("Unknown feedback note");return resolutions.stream().filter(row->row.noteId().equals(noteId)).findFirst().orElse(new Resolution(noteId,State.OPEN,""));}
        public Entry resolve(Resolution value) {var next=new ArrayList<>(resolutions.stream().filter(row->!row.noteId().equals(value.noteId())).toList());next.add(value);return new Entry(feedback,binding,next);}
        public Entry bind(CircuitDocument target) {return new Entry(feedback,target==null?null:new Binding(target.id(),roadKey(target)),resolutions);}
        public Alignment alignment(CircuitDocument target) {
            if(binding==null) return Alignment.UNBOUND;
            if(target==null || !binding.targetId().equals(target.id())) return Alignment.MISSING;
            if(!binding.roadKey().equals(roadKey(target))) return Alignment.CHANGED;
            return target.sameDrivingLayout(feedback.source())?Alignment.MATCHED:Alignment.REFERENCE;
        }
    }
    private record Road(float width,List<CircuitDocument.Point> anchors,CircuitScene scene) {}
    private static final Gson ROAD_JSON=new Gson();
    public static String roadKey(CircuitDocument document) {return PartsCatalog.hash(ROAD_JSON.toJson(new Road(document.width(),document.anchors(),document.scene())));}
    public CircuitFeedback {
        if(version!=1 || !identity(id,"feedback-") || source==null || !token(physics) || !PartsCatalog.text(author,40) || author.isBlank() || !PartsCatalog.text(title,60) || title.isBlank() || !text(summary,240,false) || createdAt<0 || notes==null || notes.isEmpty() || notes.size()>NOTE_LIMIT) throw new IllegalArgumentException("Invalid circuit feedback");
        if(!text(source.name(),60,true) || !text(source.metadata().author(),40,false) || !text(source.metadata().description(),240,false) || !text(source.metadata().revision(),120,false)) throw new IllegalArgumentException("Invalid source text");
        notes=List.copyOf(notes);if(notes.stream().map(Note::id).distinct().count()!=notes.size()) throw new IllegalArgumentException("Duplicate feedback notes");
        for(var note:notes) if(note.evidence()!=null && (!note.evidence().documentKey().equals(source.driveKey()) || !note.evidence().physics().equals(physics))) throw new IllegalArgumentException("Observation does not match feedback source");
    }
    public static CircuitFeedback create(CircuitDocument source,String physics,String author,String title,String summary,List<Note> notes) {return new CircuitFeedback(1,newId("feedback-"),source,physics,author,title,summary,System.currentTimeMillis(),notes);}
    public static String newId(String prefix) {return prefix+UUID.randomUUID().toString().replace("-","");}
    private static boolean identity(String value,String prefix) {return value!=null && value.matches(prefix+"[a-f0-9]{32}");}
    private static boolean token(String value) {return value!=null && value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}");}
    private static boolean text(String value,int max,boolean required) {return value!=null && value.length()<=max && (!required || !value.isBlank()) && PartsCatalog.text(value.replace("\n",""),max);}
}
