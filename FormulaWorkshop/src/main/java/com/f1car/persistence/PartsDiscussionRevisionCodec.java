package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Independent exchange; the embedded discussion and plan formats are unchanged. */
public final class PartsDiscussionRevisionCodec {
    public static final int MAX_BYTES=3400000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private PartsDiscussionRevisionCodec() {}
    private static JsonObject content(PartsDiscussionRevision value) {
        var body=new JsonObject();body.addProperty("id",value.id());
        body.add("parent",PartsDiscussionCodec.tree(value.parent()));body.add("child",PartsDiscussionCodec.tree(value.child()));
        body.addProperty("parentMessage",value.parentMessage());body.addProperty("childMessage",value.childMessage());
        body.addProperty("author",value.author());body.addProperty("notes",value.notes());body.addProperty("createdAt",value.createdAt());return body;
    }
    private static String digest(JsonElement body) {return PartsCatalog.hash(JSON.toJson(PartsTrialEvidenceCodec.canonical(body)));}
    static JsonObject tree(PartsDiscussionRevision value) {
        var body=content(value);var root=new JsonObject();root.addProperty("kind","formula-workshop-parts-revision");
        root.addProperty("version",1);root.add("content",body);root.addProperty("digest",digest(body));
        if(bytes(root)>MAX_BYTES) throw new IllegalArgumentException("Revision exceeds bound");return root;
    }
    public static String encode(PartsDiscussionRevision value) {return JSON.toJson(tree(value));}
    static PartsDiscussionRevision decode(PartsJson.Node root) throws IOException {
        try {
            root.object("kind","version","content","digest");
            if(!root.child("kind").text(64,true).equals("formula-workshop-parts-revision")) throw new IOException("Use an independent parts revision file");
            root.child("version").integer(1,1);if(bytes(root.value())>MAX_BYTES) throw new IOException("Revision exceeds bound");
            var body=root.child("content").object("id","parent","child","parentMessage","childMessage","author","notes","createdAt");
            ActivityCardCodec.validate(body.child("createdAt").value(),long.class);
            var result=new PartsDiscussionRevision(1,body.child("id").text(48,true),PartsDiscussionCodec.decode(body.child("parent")),PartsDiscussionCodec.decode(body.child("child")),
                body.child("parentMessage").text(48,true),body.child("childMessage").text(48,true),body.child("author").text(40,true),body.child("notes").text(320,true),body.child("createdAt").value().getAsLong());
            if(!digest(content(result)).equals(root.child("digest").text(64,true))) throw new IOException("Revision checksum differs");return result;
        }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid parts revision",error);}
    }
    public static PartsDiscussionRevision read(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {return decode(PartsJson.read(stream,MAX_BYTES,28));}}
    public static void write(Path path,PartsDiscussionRevision value) throws IOException {write(path,value,true);}
    public static void write(Path path,PartsDiscussionRevision value,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(value),MAX_BYTES,replace);
    }
    private static int bytes(JsonElement value) {return JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;}
}
