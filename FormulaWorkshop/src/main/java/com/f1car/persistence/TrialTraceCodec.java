package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Strict, byte/token/count/depth bounded exchange, independent of AU/AV and local sidecars. */
public final class TrialTraceCodec {
    public static final int MAX_BYTES=6_000_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private TrialTraceCodec() {}
    public static String encode(TrialTraceShare share) {
        var content=JSON.toJsonTree(share);content.getAsJsonObject().add("purpose",TuningPurposeJson.tree(share.purpose()));var root=new JsonObject();root.addProperty("kind","formula-workshop-trial-traces");root.addProperty("version",format(share));root.add("content",content);
        root.addProperty("digest",digest(content));
        String source=JSON.toJson(root);if(source.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new IllegalArgumentException("Shared trace exceeds bound");return source;
    }
    public static TrialTraceShare read(Path path) throws IOException {try(var input=Files.newInputStream(path)) {return decode(input.readNBytes(MAX_BYTES+1));}}
    public static TrialTraceShare decode(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES) throw new IOException("Shared trace exceeds bound");
        try(var reader=new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8.newDecoder()))) {
            reader.setStrictness(Strictness.STRICT);var root=bounded(reader,0,new int[]{0}).getAsJsonObject();
            if(reader.peek()!=JsonToken.END_DOCUMENT || !root.keySet().equals(Set.of("kind","version","content","digest"))
                || !root.get("kind").isJsonPrimitive() || !root.get("kind").getAsJsonPrimitive().isString() || !root.get("kind").getAsString().equals("formula-workshop-trial-traces") || !Set.of("1","2","3").contains(root.get("version").toString())) throw new IOException("Invalid shared trace envelope");
            var content=root.get("content");ActivityCardCodec.validate(content,TrialTraceShare.class);
            if(!new JsonPrimitive(digest(content)).equals(root.get("digest"))) throw new IOException("Shared trace digest differs");
            var share=Objects.requireNonNull(JSON.fromJson(content,TrialTraceShare.class));if(root.get("version").getAsInt()!=format(share)) throw new IOException("Trace source version differs");return share;
        } catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid shared trace",error);}
    }
    private static int format(TrialTraceShare share) {return share.traces().stream().anyMatch(trace->trace.version()==3)?3:share.purpose()!=null && share.purpose().version()==2?2:1;}
    private static String digest(JsonElement content) {return PartsTrialEvidenceCodec.normalizedDigest(content);}
    /** Float serialization and parsed BigDecimal may use different exponent spellings for the same value. */
    static JsonElement numbers(JsonElement value) {
        if(value.isJsonObject()) {var result=new JsonObject();value.getAsJsonObject().entrySet().forEach(entry->result.add(entry.getKey(),numbers(entry.getValue())));return result;}
        if(value.isJsonArray()) {var result=new JsonArray();value.getAsJsonArray().forEach(child->result.add(numbers(child)));return result;}
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return new JsonPrimitive(value.getAsBigDecimal().stripTrailingZeros());
        return value;
    }
    static JsonPrimitive sampleNumber(String text) {
        var decimal=new java.math.BigDecimal(text);
        // The checksum still normalizes both zeros, but reopening an original sample keeps its float sign.
        if(decimal.signum()==0 && text.charAt(0)=='-') return new JsonPrimitive(-0.0d);
        return new JsonPrimitive(decimal);
    }
    static JsonElement bounded(JsonReader reader,int depth,int[] count) throws IOException {
        return bounded(reader,depth,count,18);
    }
    /** Independent wrappers may add bounded levels; standalone traces retain the original depth 18. */
    static JsonElement bounded(JsonReader reader,int depth,int[] count,int maximumDepth) throws IOException {
        return bounded(reader,depth,count,maximumDepth,false);
    }
    static JsonElement bounded(JsonReader reader,int depth,int[] count,int maximumDepth,boolean allowBooleans) throws IOException {
        if(maximumDepth<18 || maximumDepth>32 || depth>maximumDepth || ++count[0]>240000) throw new IOException("Shared trace structure exceeds bound");
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {reader.beginObject();var object=new JsonObject();while(reader.hasNext()) {String key=reader.nextName();if(key.length()>100 || object.has(key) || object.size()>=32) throw new IOException("Invalid shared trace field");object.add(key,bounded(reader,depth+1,count,maximumDepth,allowBooleans));}reader.endObject();yield object;}
            case BEGIN_ARRAY -> {reader.beginArray();var array=new JsonArray();while(reader.hasNext()) {if(array.size()>=TuningTrace.MAX_SAMPLES) throw new IOException("Shared trace array exceeds bound");array.add(bounded(reader,depth+1,count,maximumDepth,allowBooleans));}reader.endArray();yield array;}
            case STRING -> {String text=reader.nextString();if(text.length()>4096) throw new IOException("Shared trace text exceeds bound");yield new JsonPrimitive(text);}
            case NUMBER -> {String value=reader.nextString();if(value.length()>64) throw new IOException("Shared trace number exceeds bound");yield sampleNumber(value);}
            case BOOLEAN -> {
                if(!allowBooleans) throw new IOException("Unexpected boolean in shared trace");
                yield new JsonPrimitive(reader.nextBoolean());
            }
            case NULL -> {reader.nextNull();yield JsonNull.INSTANCE;}
            default -> throw new IOException("Unexpected shared trace token");
        };
    }
    public static void write(Path path,TrialTraceShare share) throws IOException {write(path,share,true);}
    public static void write(Path path,TrialTraceShare share,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(share),MAX_BYTES,replace);
    }
}
