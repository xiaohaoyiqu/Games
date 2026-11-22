package com.f1car.model;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Self-contained circuits and activities; legacy checksums remain byte-compatible. */
public record CircuitEventPack(String format,int version,String id,String title,String author,String notes,CircuitDocument circuit,ActivityCard activity,String digest,List<CircuitDocument> additionalCircuits,CircuitPartsBundle partsBundle) {
    private record Content(String format,int version,String id,String title,String author,String notes,CircuitDocument circuit,ActivityCard activity) { }
    private record MultiContent(String format,int version,String id,String title,String author,String notes,CircuitDocument circuit,ActivityCard activity,List<CircuitDocument> additionalCircuits) { }
    private record PartsContent(String format,int version,String id,String title,String author,String notes,CircuitDocument circuit,ActivityCard activity,List<CircuitDocument> additionalCircuits,CircuitPartsBundle partsBundle) { }
    public CircuitEventPack(String format,int version,String id,String title,String author,String notes,CircuitDocument circuit,ActivityCard activity,String digest) { this(format,version,id,title,author,notes,circuit,activity,digest,null); }
    public CircuitEventPack(String format,int version,String id,String title,String author,String notes,CircuitDocument circuit,ActivityCard activity,String digest,List<CircuitDocument> additionalCircuits) {this(format,version,id,title,author,notes,circuit,activity,digest,additionalCircuits,null);}
    public record Entry(CircuitEventPack original,CircuitDocument local,ActivityCard activity,boolean imported,List<CircuitDocument> additionalLocals) {
        public Entry(CircuitEventPack original,CircuitDocument local,ActivityCard activity,boolean imported) { this(original,local,activity,imported,null); }
        public Entry {
            if(original==null || local==null || activity==null
                    || !Objects.equals(original.activity().partsRule(),activity.partsRule())
                    || !Objects.equals(original.activity().objectives(),activity.objectives())
                    || !original.activity().physics().equals(activity.physics()))
                throw new IllegalArgumentException("Invalid installed circuit pack");
            additionalLocals=additionalLocals==null || additionalLocals.isEmpty()?null:List.copyOf(additionalLocals);
            validateInstalled(original,documents(local,additionalLocals),activity);
        }
        public String id() { return original.id(); }
        public List<CircuitDocument> localCircuits() { return documents(local,additionalLocals); }
    }
    public CircuitEventPack {
        if(!"formula-event-pack".equals(format) || version<1 || version>5 || !identity(id) || !text(title,40) || !text(author,40) || notes==null || notes.length()>320 || notes.chars().anyMatch(c->Character.isISOControl(c) && c!='\n') || circuit==null || activity==null || digest==null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid circuit event pack");
        if((version>=4)!=(activity.objectives()!=null) || (version==5)!=activity.hasSections()) throw new IllegalArgumentException("Compound conditions require package version 4");
        additionalCircuits=additionalCircuits==null || additionalCircuits.isEmpty()?null:List.copyOf(additionalCircuits);
        if(version==1 && additionalCircuits!=null || version==2 && additionalCircuits==null) throw new IllegalArgumentException("Circuit package version differs from its maps");
        if(version==3 || version>=4 && activity.partsRule()!=null) {if(partsBundle==null || activity.partsRule()==null) throw new IllegalArgumentException("Parts package requires rules and companion content");partsBundle.validate(activity.partsRule());}
        else if(partsBundle!=null || activity.partsRule()!=null) throw new IllegalArgumentException("Parts rules require matching companion content");
        validate(documents(circuit,additionalCircuits),activity);
        Object content=content(format,version,id,title,author,notes,circuit,activity,additionalCircuits,partsBundle);
        if(!digest.equals(hash(content))) throw new IllegalArgumentException("Circuit pack checksum differs");
    }
    public static CircuitEventPack create(String title,String author,String notes,CircuitDocument circuit,CircuitTrial.Proof proof,ActivityCard activity,String physics) {
        if(proof==null || !proof.matches(circuit,physics) || !activity.physics().equals(physics)) throw new IllegalStateException("Locally qualify the map before packaging");
        String id=UUID.randomUUID().toString();var content=new Content("formula-event-pack",1,id,title.strip(),author.strip(),notes.strip(),circuit,activity);
        return new CircuitEventPack(content.format(),1,id,content.title(),content.author(),content.notes(),circuit,activity,hash(content));
    }
    public static CircuitEventPack create(String title,String author,String notes,List<CircuitDocument> circuits,List<CircuitTrial.Proof> proofs,ActivityCard activity,String physics) {
        if(circuits==null || circuits.isEmpty() || circuits.size()>8 || proofs==null || proofs.size()!=circuits.size()) throw new IllegalArgumentException("Invalid package maps");
        if(circuits.size()==1) return create(title,author,notes,circuits.get(0),proofs.get(0),activity,physics);
        for(int i=0;i<circuits.size();i++) if(proofs.get(i)==null || !proofs.get(i).matches(circuits.get(i),physics)) throw new IllegalStateException("Qualify every map before packaging");
        if(!activity.physics().equals(physics)) throw new IllegalArgumentException("Package physics differs");
        String id=UUID.randomUUID().toString();var extra=List.copyOf(circuits.subList(1,circuits.size()));
        var content=new MultiContent("formula-event-pack",2,id,title.strip(),author.strip(),notes.strip(),circuits.get(0),activity,extra);
        return new CircuitEventPack(content.format(),2,id,content.title(),content.author(),content.notes(),circuits.get(0),activity,hash(content),extra);
    }
    public List<CircuitDocument> circuits() { return documents(circuit,additionalCircuits); }
    public static CircuitEventPack create(String title,String author,String notes,List<CircuitDocument> circuits,List<CircuitTrial.Proof> proofs,ActivityCard activity,String physics,CircuitPartsBundle bundle) {
        if(circuits==null || circuits.isEmpty() || circuits.size()>8 || proofs==null || proofs.size()!=circuits.size() || bundle==null || !activity.physics().equals(physics)) throw new IllegalArgumentException("Invalid parts circuit package");
        for(int i=0;i<circuits.size();i++) if(proofs.get(i)==null || !proofs.get(i).matches(circuits.get(i),physics)) throw new IllegalStateException("Qualify every map before packaging");
        String id=UUID.randomUUID().toString();var extra=circuits.size()==1?null:List.copyOf(circuits.subList(1,circuits.size()));var content=new PartsContent("formula-event-pack",3,id,title.strip(),author.strip(),notes.strip(),circuits.get(0),activity,extra,bundle);
        return new CircuitEventPack(content.format(),3,id,content.title(),content.author(),content.notes(),circuits.get(0),activity,hash(content),extra,bundle);
    }
    /** Explicit opt-in: old factories never silently change their exchange version. */
    public static CircuitEventPack createCompound(String title,String author,String notes,List<CircuitDocument> circuits,List<CircuitTrial.Proof> proofs,ActivityCard activity,String physics,CircuitPartsBundle bundle) {
        if(circuits==null || circuits.isEmpty() || circuits.size()>8 || proofs==null || proofs.size()!=circuits.size() || activity==null || activity.objectives()==null || !activity.physics().equals(physics)) throw new IllegalArgumentException("Invalid compound circuit package");
        for(int i=0;i<circuits.size();i++) if(proofs.get(i)==null || !proofs.get(i).matches(circuits.get(i),physics)) throw new IllegalStateException("Qualify every map before packaging");
        String id=UUID.randomUUID().toString();var extra=circuits.size()==1?null:List.copyOf(circuits.subList(1,circuits.size()));var content=new PartsContent("formula-event-pack",4,id,title.strip(),author.strip(),notes.strip(),circuits.get(0),activity,extra,bundle);
        return new CircuitEventPack(content.format(),4,id,content.title(),content.author(),content.notes(),circuits.get(0),activity,hash(content),extra,bundle);
    }
    /** Repeated exports of the same cosmetic revision keep an identity; originals remain immutable. */
    public static CircuitEventPack createSectioned(String title,String author,String notes,List<CircuitDocument> circuits,List<CircuitTrial.Proof> proofs,ActivityCard activity,String physics,CircuitPartsBundle bundle) {
        if(circuits==null || circuits.isEmpty() || circuits.size()>8 || proofs==null || proofs.size()!=circuits.size() || activity==null || !activity.hasSections() || !activity.physics().equals(physics)) throw new IllegalArgumentException("Invalid section circuit package");
        for(int i=0;i<circuits.size();i++) if(proofs.get(i)==null || !proofs.get(i).matches(circuits.get(i),physics)) throw new IllegalStateException("Qualify every map before packaging");
        String id=UUID.randomUUID().toString();var extra=circuits.size()==1?null:List.copyOf(circuits.subList(1,circuits.size()));var content=new PartsContent("formula-event-pack",5,id,title.strip(),author.strip(),notes.strip(),circuits.get(0),activity,extra,bundle);
        return new CircuitEventPack(content.format(),5,id,content.title(),content.author(),content.notes(),circuits.get(0),activity,hash(content),extra,bundle);
    }
    /** Repeated exports of the same cosmetic revision keep an identity; originals remain immutable. */
    public CircuitEventPack withText(String title,String author,String notes) {
        title=title.strip();author=author.strip();notes=notes.strip();if(this.title.equals(title) && this.author.equals(author) && this.notes.equals(notes)) return this;
        String revised=UUID.nameUUIDFromBytes((digest+"\n"+new Gson().toJson(List.of(title,author,notes))).getBytes(StandardCharsets.UTF_8)).toString();
        var card=activity.rename(title);Object content=content(format,version,revised,title,author,notes,circuit,card,additionalCircuits,partsBundle);
        return new CircuitEventPack(format,version,revised,title,author,notes,circuit,card,hash(content),additionalCircuits,partsBundle);
    }
    public Entry own() { return new Entry(this,circuit,activity,false,additionalCircuits); }
    public Entry importedCopy() {
        var originals=circuits();var locals=originals.stream().map(CircuitDocument::importedCopy).toList();var mapping=new HashMap<String,TrackLayout>();
        for(int i=0;i<originals.size();i++) mapping.put(originals.get(i).id(),locals.get(i).track());
        var stages=activity.stages().stream().map(s->{var track=mapping.get(s.trackId());return new ActivityCard.Stage(s.kind(),track.id,track.name,track.geometryKey(),s.difficulty(),s.laps(),s.targetPlace(),s.target());}).toList();
        var card=ActivityCard.create(activity.name(),activity.seed(),activity.physics(),stages,activity.partsRule(),activity.objectives());return new Entry(this,locals.get(0),card,true,locals.size()==1?null:locals.subList(1,locals.size()));
    }
    public static boolean sameRoad(CircuitDocument a,CircuitDocument b) { return a.width()==b.width() && a.anchors().equals(b.anchors()) && a.scene().equals(b.scene()); }
    private static List<CircuitDocument> documents(CircuitDocument primary,List<CircuitDocument> extra) {
        if(extra==null) return List.of(primary);
        var result=new ArrayList<CircuitDocument>(extra.size()+1);result.add(primary);result.addAll(extra);return List.copyOf(result);
    }
    private static void validateInstalled(CircuitEventPack original,List<CircuitDocument> locals,ActivityCard activity) {
        var originals=original.circuits();
        if(originals.size()!=locals.size()) throw new IllegalArgumentException("Installed map count differs");
        var mapping=new HashMap<String,CircuitDocument>();
        var localIds=new HashSet<String>();
        for(int i=0;i<originals.size();i++) {
            var source=originals.get(i);
            var local=locals.get(i);
            if(!localIds.add(local.id()) || !sameRoad(source,local))
                throw new IllegalArgumentException("Installed map differs or duplicates an identity");
            mapping.put(source.id(),local);
        }
        var stages=original.activity().stages();
        if(stages.size()!=activity.stages().size() || original.activity().seed()!=activity.seed())
            throw new IllegalArgumentException("Imported rules differ");
        var geometry=new HashMap<String,String>();
        for(int i=0;i<stages.size();i++) {
            var source=stages.get(i);
            var installed=activity.stages().get(i);
            if(source.kind()!=installed.kind() || source.difficulty()!=installed.difficulty()
                    || source.laps()!=installed.laps() || source.targetPlace()!=installed.targetPlace()
                    || !Objects.equals(source.target(),installed.target()))
                throw new IllegalArgumentException("Imported rules differ");
            var local=mapping.get(source.trackId());
            if(!local.id().equals(installed.trackId())) throw new IllegalArgumentException("Installed itinerary differs");
            // The original already passed road, scenery and author-target checks for these exact inputs.
            // Only an ID change needs a freshly computed geometry key.
            String expected=source.trackId().equals(local.id())?source.geometry()
                    :geometry.computeIfAbsent(local.id(),ignored->local.track().geometryKey());
            if(!installed.geometry().equals(expected)) throw new IllegalArgumentException("Installed geometry differs");
        }
    }
    private static void validate(List<CircuitDocument> circuits,ActivityCard activity) {
        if(circuits.isEmpty() || circuits.size()>8 || circuits.stream().map(CircuitDocument::id).distinct().count()!=circuits.size()) throw new IllegalArgumentException("Duplicate or excessive package maps");
        var tracks=new HashMap<String,TrackLayout>();
        for(var document:circuits) { var report=CircuitInspector.inspect(document);if(!report.ready()) throw new IllegalArgumentException("Package map geometry is invalid");tracks.put(document.id(),report.track()); }
        var used=new HashSet<String>();
        for(var stage:activity.stages()) { var track=tracks.get(stage.trackId());if(track==null || !stage.geometry().equals(track.geometryKey()) || stage.target()!=null && !stage.target().matches(track,activity.physics())) throw new IllegalArgumentException("Package activity has a missing or changed map");used.add(stage.trackId()); }
        if(used.size()!=circuits.size()) throw new IllegalArgumentException("Package contains an unused map");
    }
    private static Object content(String format,int version,String id,String title,String author,String notes,CircuitDocument circuit,ActivityCard activity,List<CircuitDocument> extra,CircuitPartsBundle parts) {return switch(version) {case 1->new Content(format,version,id,title,author,notes,circuit,activity);case 2->new MultiContent(format,version,id,title,author,notes,circuit,activity,extra);case 3,4,5->new PartsContent(format,version,id,title,author,notes,circuit,activity,extra,parts);default->throw new IllegalArgumentException("Unknown package version");};}
    private static String hash(Object content) {try {var gson=new Gson();String source=content instanceof PartsContent?gson.toJson(canonical(gson.toJsonTree(content))):gson.toJson(content);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException error) {throw new IllegalStateException(error);}}
    /** V3 contains sets; their JVM iteration order must not become an exchange checksum condition. */
    private static JsonElement canonical(JsonElement value) {
        if(value.isJsonObject()) {var result=new JsonObject();for(String key:new TreeSet<>(value.getAsJsonObject().keySet())) {var child=value.getAsJsonObject().get(key);if((key.equals("requires") || key.equals("excludes")) && child.isJsonArray()) {var sorted=new JsonArray();child.getAsJsonArray().asList().stream().map(JsonElement::getAsString).sorted().forEach(sorted::add);result.add(key,sorted);}else result.add(key,canonical(child));}return result;}
        if(value.isJsonArray()) {var result=new JsonArray();for(var child:value.getAsJsonArray()) result.add(canonical(child));return result;}return value;
    }
    private static boolean identity(String id) { return id!=null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    private static boolean text(String value,int maximum) { return value!=null && !value.isBlank() && value.length()<=maximum && value.chars().noneMatch(Character::isISOControl); }
}
