package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** One discussion snapshot plus one complete source trace and an explicit sample citation. */
public final class PartsDiscussionTraceCodec {
    public static final int MAX_BYTES=4_000_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private PartsDiscussionTraceCodec() {}
    private static JsonObject content(PartsDiscussionTrace value) {
        var result=new JsonObject();result.addProperty("id",value.id());result.addProperty("author",value.author());result.addProperty("createdAt",value.createdAt());
        result.add("discussion",PartsDiscussionCodec.tree(value.discussion()));result.addProperty("message",value.message());result.add("trace",JsonParser.parseString(TrialTraceCodec.encode(value.trace())));
        result.addProperty("first",value.first());result.addProperty("last",value.last());result.addProperty("notes",value.notes());return result;
    }
    private static String digest(JsonElement content) {return PartsTrialEvidenceCodec.normalizedDigest(content);}
    static JsonObject tree(PartsDiscussionTrace value) {
        var content=content(value);var root=new JsonObject();root.addProperty("kind","formula-workshop-discussion-trace");root.addProperty("version",1);root.add("content",content);root.addProperty("digest",digest(content));
        if(bytes(root)>MAX_BYTES) throw new IllegalArgumentException("Discussion trace citation exceeds bound");return root;
    }
    public static String encode(PartsDiscussionTrace value) {return JSON.toJson(tree(value));}
    static PartsDiscussionTrace decode(JsonElement value) throws IOException {try {
        if(bytes(value)>MAX_BYTES) throw new IOException("Discussion trace citation exceeds bound");var root=new PartsJson.Node(value,"").object("kind","version","content","digest");
        if(!root.child("kind").text(64,true).equals("formula-workshop-discussion-trace")) throw new IOException("Use a discussion trace citation file");root.child("version").integer(1,1);
        var body=root.child("content").object("id","author","createdAt","discussion","message","trace","first","last","notes");
        if(!digest(body.value()).equals(root.child("digest").text(64,true))) throw new IOException("Discussion trace citation checksum differs");
        var discussion=PartsDiscussionCodec.decode(body.child("discussion"));var trace=TrialTraceCodec.decode(JSON.toJson(body.child("trace").value()).getBytes(StandardCharsets.UTF_8));
        ActivityCardCodec.validate(body.child("createdAt").value(),long.class);ActivityCardCodec.validate(body.child("notes").value(),String.class);
        return new PartsDiscussionTrace(body.child("id").text(64,true),body.child("author").text(40,true),body.child("createdAt").value().getAsLong(),discussion,body.child("message").text(48,true),trace,
            body.child("first").integer(0,TuningTrace.MAX_SAMPLES-1),body.child("last").integer(0,TuningTrace.MAX_SAMPLES-1),body.child("notes").value().getAsString());
    }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid discussion trace citation",error);}}
    static JsonReader reader(byte[] bytes) {
        return reader(new ByteArrayInputStream(bytes));
    }
    static JsonReader reader(InputStream source) {
        var reader=new JsonReader(new InputStreamReader(source,StandardCharsets.UTF_8.newDecoder()));
        reader.setStrictness(Strictness.STRICT);
        return reader;
    }
    public static PartsDiscussionTrace read(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {
        byte[] bytes=stream.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES) throw new IOException("Discussion trace citation exceeds bound");
        try(var reader=reader(bytes)) {var tree=TrialTraceCodec.bounded(reader,0,new int[]{0},24);if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Trailing citation content");return decode(tree);}
    }catch(RuntimeException error) {throw new IOException("Invalid discussion trace file",error);}}
    public static void write(Path path,PartsDiscussionTrace value) throws IOException {write(path,value,true);}
    public static void write(Path path,PartsDiscussionTrace value,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(value),MAX_BYTES,replace);
    }
    private static int bytes(JsonElement value) {return JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;}
}
