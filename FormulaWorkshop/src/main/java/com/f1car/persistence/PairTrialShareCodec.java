package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One standalone provider envelope; complete original samples and source, strict bounded UTF-8. */
public final class PairTrialShareCodec {
    public static final int MAX_BYTES=8_010_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private PairTrialShareCodec() {}
    private static JsonObject content(PairTrialShare value) {
        var body=new JsonObject();body.addProperty("id",value.id());body.addProperty("sharedAt",value.sharedAt());body.addProperty("author",value.author());body.addProperty("notes",value.notes());body.add("report",JSON.toJsonTree(value.report()));
        if(value.history()==null) body.add("history",JsonNull.INSTANCE);
        else {var history=value.history();var node=new JsonObject();node.addProperty("id",history.id());node.addProperty("afterAttempt",history.afterAttempt());node.add("replacements",JSON.toJsonTree(history.replacements()));body.add("history",node);}
        return body;
    }
    private static String digest(JsonElement content) {return PartsTrialEvidenceCodec.normalizedDigest(content);}
    static byte[] bytes(JsonElement value) throws java.nio.charset.CharacterCodingException {
        var buffer=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(JSON.toJson(value)));var bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;
    }
    static JsonObject tree(PairTrialShare value) throws IOException {
        Objects.requireNonNull(value);var body=content(value);if(bytes(body.get("report")).length>PairTrialStore.ENTRY_BYTES) throw new IOException("Four-condition report exceeds bound");
        var root=new JsonObject();root.addProperty("kind","formula-workshop-pair-trial");root.addProperty("version",1);root.add("content",body);root.addProperty("digest",digest(body));
        if(bytes(root).length>MAX_BYTES) throw new IOException("Four-condition share exceeds bound");return root;
    }
    public static String encode(PairTrialShare value) throws IOException {return JSON.toJson(tree(value));}
    static PairTrialShare decode(JsonElement element) throws IOException {
        try {
            if(bytes(element).length>MAX_BYTES) throw new IOException("Share exceeds bound");var root=new PartsJson.Node(element,"").object("kind","version","content","digest");
            if(!root.child("kind").text(64,true).equals("formula-workshop-pair-trial")) throw new IOException("Wrong share kind");root.child("version").integer(1,1);
            var body=root.child("content").object("id","sharedAt","author","notes","report","history");String expected=root.child("digest").text(64,true);
            if(!digest(body.value()).equals(expected) || bytes(body.child("report").value()).length>PairTrialStore.ENTRY_BYTES) throw new IOException("Share checksum or report size differs");
            ActivityCardCodec.validate(body.child("sharedAt").value(),long.class);ActivityCardCodec.validate(body.child("author").value(),String.class);ActivityCardCodec.validate(body.child("notes").value(),String.class);ActivityCardCodec.validate(body.child("report").value(),PairExperiment.Snapshot.class);
            var report=Objects.requireNonNull(JSON.fromJson(body.child("report").value(),PairExperiment.Snapshot.class));PairTrialRevision history=null;
            if(!body.child("history").value().isJsonNull()) {var h=body.child("history").object("id","afterAttempt","replacements");ActivityCardCodec.validate(h.child("afterAttempt").value(),long.class);h.child("replacements").array(1,12);ActivityCardCodec.validate(h.child("replacements").value(),new com.google.gson.reflect.TypeToken<List<PairTrialRevision.Replacement>>(){}.getType());List<PairTrialRevision.Replacement> rows=JSON.fromJson(h.child("replacements").value(),new com.google.gson.reflect.TypeToken<List<PairTrialRevision.Replacement>>(){}.getType());history=new PairTrialRevision(1,h.child("id").text(48,true),report,h.child("afterAttempt").value().getAsLong(),rows);}
            var value=new PairTrialShare(1,body.child("id").text(48,true),body.child("sharedAt").value().getAsLong(),body.child("author").value().getAsString(),body.child("notes").value().getAsString(),report,history);
            if(!digest(content(value)).equals(expected)) throw new IOException("Decoded share differs from original content");return value;
        }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid four-condition share",error);}
    }
    public static PairTrialShare decode(byte[] bytes) throws IOException {return decode(TuningLapStudyCodec.readTree(Objects.requireNonNull(bytes),MAX_BYTES,2_100_000));}
    public static PairTrialShare read(Path path) throws IOException {try(var input=Files.newInputStream(path)) {return decode(TuningLapStudyCodec.readTree(input,MAX_BYTES,2_100_000));}}
    public static void write(Path path,PairTrialShare value,boolean replace) throws IOException {
        var bytes=bytes(tree(value));var diagnostics=new SaveDiagnostics("pair-trial-export");diagnostics.begin();
        if(replace) ArchiveWriter.bytes(path,bytes,MAX_BYTES,"pair-trial-export-",diagnostics);
        else ArchiveWriter.bytes(path,bytes,MAX_BYTES,"pair-trial-export-",diagnostics,new ArchiveWriter.Filesystem(){@Override public void atomic(Path from,Path to) throws IOException {Files.move(from,to);}});
    }
}
