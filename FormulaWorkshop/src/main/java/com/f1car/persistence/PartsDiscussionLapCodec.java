package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Separate offline envelope; nested discussion and complete-lap envelopes keep their own bounds. */
public final class PartsDiscussionLapCodec {
    public static final int MAX_BYTES=6_000_000;
    private static final Gson JSON=EquipmentLapJson.JSON;
    private PartsDiscussionLapCodec() {}
    private static String digest(JsonElement value) {return PartsTrialEvidenceCodec.normalizedDigest(value);}
    static JsonObject tree(PartsDiscussionLap value) {
        var body=new JsonObject();body.addProperty("id",value.id());body.addProperty("author",value.author());body.addProperty("createdAt",value.createdAt());body.add("discussion",PartsDiscussionCodec.tree(value.discussion()));body.addProperty("message",value.message());body.add("share",EquipmentLapShareCodec.tree(value.share()));body.addProperty("notes",value.notes());
        var root=new JsonObject();root.addProperty("kind","formula-workshop-discussion-lap");root.addProperty("version",1);root.add("content",body);root.addProperty("digest",digest(body));if(bytes(root)>MAX_BYTES) throw new IllegalArgumentException("Lap citation exceeds bound");return root;
    }
    private static int bytes(JsonElement value) {return JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;}
    public static String encode(PartsDiscussionLap value) {return JSON.toJson(tree(value));}
    static PartsDiscussionLap decode(JsonElement value) throws IOException {
        try {
            if(bytes(value)>MAX_BYTES) throw new IOException("Lap citation exceeds bound");var root=new PartsJson.Node(value,"").object("kind","version","content","digest");
            if(!root.child("kind").text(64,true).equals("formula-workshop-discussion-lap")) throw new IOException("Wrong lap citation kind");root.child("version").integer(1,1);
            var body=root.child("content").object("id","author","createdAt","discussion","message","share","notes");if(!digest(body.value()).equals(root.child("digest").text(64,true))) throw new IOException("Lap citation checksum differs");
            // Restore the original individual parser bounds as well as the larger outer structural bounds.
            PartsDiscussion discussion;try(var input=new ByteArrayInputStream(JSON.toJson(body.child("discussion").value()).getBytes(StandardCharsets.UTF_8))) {discussion=PartsDiscussionCodec.decode(PartsJson.read(input,PartsDiscussionCodec.MAX_BYTES,18));}
            EquipmentLapShare share;try(var reader=new JsonReader(new StringReader(JSON.toJson(body.child("share").value())))) {reader.setStrictness(Strictness.STRICT);share=EquipmentLapShareCodec.decode(EquipmentLapJson.element(reader,0,new int[]{0}));}
            ActivityCardCodec.validate(body.child("createdAt").value(),long.class);ActivityCardCodec.validate(body.child("notes").value(),String.class);
            return new PartsDiscussionLap(body.child("id").text(64,true),body.child("author").text(40,true),body.child("createdAt").value().getAsLong(),discussion,body.child("message").text(48,true),share,body.child("notes").value().getAsString());
        }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid lap citation",error);}
    }
    static JsonElement element(JsonReader r,int depth,int[] nodes) throws IOException {
        if(depth>28 || ++nodes[0]>480000) throw new IOException("Lap citation structure exceeds bound");
        return switch(r.peek()) {
            case BEGIN_OBJECT->{r.beginObject();var o=new JsonObject();while(r.hasNext()) {String key=r.nextName();if(key.length()>100 || o.has(key) || o.size()>=64) throw new IOException("Invalid citation fields");o.add(key,element(r,depth+1,nodes));}r.endObject();yield o;}
            case BEGIN_ARRAY->{r.beginArray();var a=new JsonArray();while(r.hasNext()) {if(a.size()>=TelemetryLap.MAX_SAMPLES) throw new IOException("Citation array exceeds bound");a.add(element(r,depth+1,nodes));}r.endArray();yield a;}
            case STRING->{String v=r.nextString();if(v.length()>4096) throw new IOException("Citation text exceeds bound");yield new JsonPrimitive(v);}
            case NUMBER->{String v=r.nextString();if(v.length()>64) throw new IOException("Citation number exceeds bound");yield TrialTraceCodec.sampleNumber(v);}
            case BOOLEAN->new JsonPrimitive(r.nextBoolean());case NULL->{r.nextNull();yield JsonNull.INSTANCE;}
            default->throw new IOException("Invalid citation token");
        };
    }
    public static PartsDiscussionLap read(Path path) throws IOException {
        try(var input=Files.newInputStream(path)) {byte[] bytes=input.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES) throw new IOException("Citation file exceeds bound");
            try(var reader=PartsDiscussionTraceCodec.reader(bytes)) {var value=element(reader,0,new int[]{0});if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Trailing citation content");return decode(value);}
        }catch(RuntimeException error) {throw new IOException("Invalid citation file",error);}
    }
    public static void write(Path path,PartsDiscussionLap value,boolean replace) throws IOException {
        String source=encode(value);if(replace) {PartsStore.write(path,source,MAX_BYTES);return;}
        var diagnostics=new SaveDiagnostics("discussion-lap-export");diagnostics.begin();ArchiveWriter.bytes(path,source.getBytes(StandardCharsets.UTF_8),MAX_BYTES,"discussion-lap-export-",diagnostics,new ArchiveWriter.Filesystem() {@Override public void atomic(Path from,Path to) throws IOException {Files.move(from,to);}});
    }
}
