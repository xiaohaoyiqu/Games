package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Independent bounded wrapper; received nested envelopes retain their original formats and content. */
public final class TuningLapStudyCodec {
    public static final int MAX_BYTES=14_500_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private TuningLapStudyCodec() {}
    private static JsonObject content(TuningLapStudy value) {
        var body=new JsonObject();body.addProperty("id",value.id());body.addProperty("author",value.author());body.addProperty("createdAt",value.createdAt());body.addProperty("notes",value.notes());
        var report=new JsonObject();report.addProperty("origin",value.report().received()?"RECEIVED":"LOCAL");
        if(value.report().received()) report.add("content",JsonParser.parseString(TrialTraceCodec.encode(value.report().provider())));
        else {var local=new JsonObject();local.add("snapshot",JSON.toJsonTree(value.report().snapshot()));local.add("purpose",TuningPurposeJson.tree(value.report().purpose()));if(!value.report().traces().isEmpty()) local.add("traces",JSON.toJsonTree(value.report().traces()));if(bytes(local)>TrialTraceCodec.MAX_BYTES) throw new IllegalArgumentException("Local study report exceeds trace bound");report.add("content",local);}
        body.add("report",report);body.add("a",lap(value.a()));body.add("b",lap(value.b()));return body;
    }
    private static JsonObject lap(TuningLapStudy.Lap value) {
        var node=new JsonObject();node.addProperty("origin",value.received()?"RECEIVED":"LOCAL");
        var content=value.received()?EquipmentLapShareCodec.tree(value.provider()):JSON.toJsonTree(value.recording());
        if(!value.received() && bytes(content)>EquipmentLapStore.ENTRY_BYTES) throw new IllegalArgumentException("Study lap exceeds original entry bound");
        node.add("content",content);return node;
    }
    private static String digest(JsonElement value) {return PartsTrialEvidenceCodec.normalizedDigest(value);}
    static JsonObject tree(TuningLapStudy value) {
        var body=content(value);var root=new JsonObject();root.addProperty("kind","formula-workshop-tuning-lap-study");root.addProperty("version",format(value));root.add("content",body);root.addProperty("digest",digest(body));
        if(bytes(root)>MAX_BYTES) throw new IllegalArgumentException("Study exceeds bound");return root;
    }
    public static String encode(TuningLapStudy value) {return JSON.toJson(tree(value));}
    static int format(TuningLapStudy value) {return !value.report().received() && !value.report().traces().isEmpty()?2:1;}
    static TuningLapStudy decode(JsonElement value) throws IOException {
        try {
            if(bytes(value)>MAX_BYTES) throw new IOException("Study exceeds bound");var root=new PartsJson.Node(value,"").object("kind","version","content","digest");
            if(!root.child("kind").text(64,true).equals("formula-workshop-tuning-lap-study")) throw new IOException("Wrong study kind");int version=root.child("version").integer(1,2);
            var body=root.child("content").object("id","author","createdAt","notes","report","a","b");ActivityCardCodec.validate(body.child("createdAt").value(),long.class);ActivityCardCodec.validate(body.child("notes").value(),String.class);
            var study=new TuningLapStudy(body.child("id").text(48,true),body.child("author").text(40,true),body.child("createdAt").value().getAsLong(),body.child("notes").value().getAsString(),
                report(body.child("report"),version),lap(body.child("a")),lap(body.child("b")));
            if(version!=format(study)) throw new IOException("Study curve version differs");
            if(!digest(content(study)).equals(root.child("digest").text(64,true))) throw new IOException("Study checksum differs");return study;
        }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid report/lap study",error);}
    }
    private static TuningLapStudy.Report report(PartsJson.Node node,int version) throws IOException,ReflectiveOperationException {
        node.object("origin","content");String origin=node.child("origin").text(16,true);
        if(origin.equals("RECEIVED")) {var share=TrialTraceCodec.decode(JSON.toJson(node.child("content").value()).getBytes(StandardCharsets.UTF_8));return new TuningLapStudy.Report(share.report(),share.purpose(),share);}
        if(!origin.equals("LOCAL")) throw new IOException("Unknown study report origin");
        var local=node.child("content").value();if(bytes(local)>TrialTraceCodec.MAX_BYTES) throw new IOException("Local study report exceeds trace bound");
        try(var reader=new JsonReader(new StringReader(JSON.toJson(local)))) {reader.setStrictness(Strictness.STRICT);local=TrialTraceCodec.bounded(reader,0,new int[]{0});}
        var body=new PartsJson.Node(local,"");if(version==1) body.object("snapshot","purpose");else body.object("snapshot","purpose","traces");
        ActivityCardCodec.validate(body.child("snapshot").value(),TuningExperiment.Snapshot.class);ActivityCardCodec.validate(body.child("purpose").value(),TuningPurpose.class);
        var traces=new ArrayList<TuningTrace>();if(version==2) {var rows=body.child("traces").array(1,6);for(var row:rows) {ActivityCardCodec.validate(row,TuningTrace.class);traces.add(JSON.fromJson(row,TuningTrace.class));}}
        return new TuningLapStudy.Report(JSON.fromJson(body.child("snapshot").value(),TuningExperiment.Snapshot.class),JSON.fromJson(body.child("purpose").value(),TuningPurpose.class),null,traces);
    }
    private static TuningLapStudy.Lap lap(PartsJson.Node node) throws IOException {
        node.object("origin","content");String origin=node.child("origin").text(16,true);
        JsonElement content;
        try(var reader=new JsonReader(new StringReader(JSON.toJson(node.child("content").value())))) {reader.setStrictness(Strictness.STRICT);content=EquipmentLapJson.element(reader,0,new int[]{0});}
        if(origin.equals("RECEIVED")) {var share=EquipmentLapShareCodec.decode(content);return new TuningLapStudy.Lap(share.recording(),share);}
        if(!origin.equals("LOCAL")) throw new IOException("Unknown study lap origin");return new TuningLapStudy.Lap(EquipmentLapJson.decode(content),null);
    }
    static JsonElement readTree(InputStream input,int maximumBytes,int maximumNodes) throws IOException {
        return readTree(input.readNBytes(maximumBytes+1),maximumBytes,maximumNodes);
    }
    static JsonElement readTree(byte[] data,int maximumBytes,int maximumNodes) throws IOException {
        if(data.length>maximumBytes) throw new IOException("Study input exceeds bound");
        var decoder=StandardCharsets.UTF_8.newDecoder();
        try(var reader=new JsonReader(new InputStreamReader(new ByteArrayInputStream(data),decoder))) {reader.setStrictness(Strictness.STRICT);var value=bounded(reader,0,new int[]{0},maximumNodes);if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Trailing study content");return value;}
        catch(RuntimeException error) {throw new IOException("Invalid study input",error);}
    }
    private static JsonElement bounded(JsonReader reader,int depth,int[] count,int maximumNodes) throws IOException {
        if(depth>32 || ++count[0]>maximumNodes) throw new IOException("Study structure exceeds bound");
        return switch(reader.peek()) {
            case BEGIN_OBJECT->{reader.beginObject();var object=new JsonObject();while(reader.hasNext()) {String key=reader.nextName();if(key.length()>100 || object.has(key) || object.size()>=32) throw new IOException("Invalid study field");object.add(key,bounded(reader,depth+1,count,maximumNodes));}reader.endObject();yield object;}
            case BEGIN_ARRAY->{reader.beginArray();var array=new JsonArray();while(reader.hasNext()) {if(array.size()>=TelemetryLap.MAX_SAMPLES) throw new IOException("Study array exceeds bound");array.add(bounded(reader,depth+1,count,maximumNodes));}reader.endArray();yield array;}
            case STRING->{String text=reader.nextString();if(text.length()>4096) throw new IOException("Study text exceeds bound");yield new JsonPrimitive(text);}
            case NUMBER->{String text=reader.nextString();if(text.length()>64) throw new IOException("Study number exceeds bound");yield TrialTraceCodec.sampleNumber(text);}
            case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}
            default->throw new IOException("Unexpected study token");
        };
    }
    public static TuningLapStudy read(Path path) throws IOException {try(var input=Files.newInputStream(path)) {return decode(readTree(input,MAX_BYTES,1_500_000));}}
    public static void write(Path path,TuningLapStudy value,boolean replace) throws IOException {
        var source=encode(value);if(replace) {PartsStore.write(path,source,MAX_BYTES);return;}
        var diagnostics=new SaveDiagnostics("tuning-lap-study-export");diagnostics.begin();
        ArchiveWriter.bytes(path,source.getBytes(StandardCharsets.UTF_8),MAX_BYTES,"lap-study-export-",diagnostics,new ArchiveWriter.Filesystem() {
            @Override public void atomic(Path from,Path to) throws IOException {Files.move(from,to);}
        });
    }
    private static int bytes(JsonElement value) {
        validUnicode(value);
        try {return StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(JSON.toJson(value))).remaining();}
        catch(CharacterCodingException error) {throw new IllegalArgumentException("Study text cannot be encoded without replacement",error);}
    }
    private static void validUnicode(JsonElement value) {
        if(value.isJsonObject()) {for(var entry:value.getAsJsonObject().entrySet()) {validUnicode(new JsonPrimitive(entry.getKey()));validUnicode(entry.getValue());}}
        else if(value.isJsonArray()) value.getAsJsonArray().forEach(TuningLapStudyCodec::validUnicode);
        else if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().codePoints().anyMatch(cp->cp>=0xd800 && cp<=0xdfff)) throw new IllegalArgumentException("Unpaired surrogate in study text");
    }
}
