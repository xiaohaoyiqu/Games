package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Shared bounded lap subtree; the existing local library shape and limits remain unchanged. */
final class EquipmentLapJson {
    static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private EquipmentLapJson() {}
    @FunctionalInterface
    interface Decoder<T> {T decode(JsonElement value) throws IOException;}
    @FunctionalInterface
    interface Encoder<T> {void encode(JsonWriter writer,T value) throws IOException;}

    static <T> List<T> readArchive(InputStream source,int capacity,Decoder<T> decode,Function<T,String> identity) throws IOException {
        var entries=new ArrayList<T>();
        var identities=new HashSet<String>();
        var fields=new HashSet<String>();
        try(var reader=new JsonReader(new InputStreamReader(source,StandardCharsets.UTF_8.newDecoder()))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while(reader.hasNext()) {
                String key=reader.nextName();
                if(!fields.add(key)) throw new IOException("Duplicate archive field");
                switch(key) {
                    case "version" -> {
                        if(reader.peek()!=JsonToken.NUMBER) throw new IOException("Unsupported archive version");
                        String version=reader.nextString();
                        if(version.length()>64 || new java.math.BigDecimal(version).intValueExact()!=1)
                            throw new IOException("Unsupported archive version");
                    }
                    case "entries" -> {
                        reader.beginArray();
                        while(reader.hasNext()) {
                            if(entries.size()>=capacity) throw new IOException("Archive capacity exceeded");
                            T row=decode.decode(element(reader,0,new int[]{0}));
                            if(!identities.add(identity.apply(row))) throw new IOException("Duplicate lap identity");
                            entries.add(row);
                        }
                        reader.endArray();
                    }
                    default -> throw new IOException("Unknown archive field");
                }
            }
            reader.endObject();
            if(!fields.equals(Set.of("version","entries")) || reader.peek()!=JsonToken.END_DOCUMENT)
                throw new IOException("Incomplete or trailing archive");
        }
        return List.copyOf(entries);
    }

    static <T> void writeArchive(Writer output,List<T> entries,Encoder<T> encode) throws IOException {
        try(var writer=JSON.newJsonWriter(output)) {
            writer.beginObject();
            writer.name("version").value(1);
            writer.name("entries").beginArray();
            for(var row:entries) encode.encode(writer,row);
            writer.endArray().endObject();
        }
    }
    static EquipmentLap decode(JsonElement value) throws IOException {
        try {
            if(encodedBytes(value)>EquipmentLapStore.ENTRY_BYTES) throw new IOException("Equipment lap exceeds bound");
            ActivityCardCodec.validate(value,EquipmentLap.class);
            validateTuning(value.getAsJsonObject().getAsJsonObject("source").getAsJsonObject("tuning"));
            return java.util.Objects.requireNonNull(JSON.fromJson(value,EquipmentLap.class));
        }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid equipment lap",error);}
    }
    static int encodedBytes(JsonElement value) throws IOException {
        return ArchiveWriter.utf8Size(writer -> JSON.toJson(value, writer));
    }
    private static void validateTuning(JsonObject tuning) throws IOException {
        var keys=Map.of(PartsCatalog.Field.POWER,"power",PartsCatalog.Field.BRAKES,"brakes",PartsCatalog.Field.BRAKE_BIAS,"brakeBias",PartsCatalog.Field.SUSPENSION,"suspension",PartsCatalog.Field.FRONT_SPRING,"frontSpring",PartsCatalog.Field.REAR_SPRING,"rearSpring",PartsCatalog.Field.FRONT_DAMPING,"frontDamping",PartsCatalog.Field.REAR_DAMPING,"rearDamping",PartsCatalog.Field.WING,"wing");
        for(var e:keys.entrySet()) {float v=tuning.get(e.getValue()).getAsFloat();if(!Float.isFinite(v) || v<e.getKey().min/100f || v>e.getKey().max/100f) throw new IOException("Tuning must not be normalized");}
        int livery=tuning.get("livery").getAsBigDecimal().intValueExact();if(livery<0 || livery>4) throw new IOException("Invalid livery");
    }
    static JsonElement element(JsonReader r,int depth,int[] nodes) throws IOException {
        if(depth>18 || ++nodes[0]>240000) throw new IOException("Equipment lap structure exceeds bound");
        return switch(r.peek()) {
            case BEGIN_OBJECT->{r.beginObject();var o=new JsonObject();while(r.hasNext()) {String key=r.nextName();if(key.length()>100 || o.has(key) || o.size()>=32) throw new IOException("Invalid object fields");o.add(key,element(r,depth+1,nodes));}r.endObject();yield o;}
            case BEGIN_ARRAY->{r.beginArray();var a=new JsonArray();while(r.hasNext()) {if(a.size()>=TelemetryLap.MAX_SAMPLES) throw new IOException("Too many samples");a.add(element(r,depth+1,nodes));}r.endArray();yield a;}
            case STRING->{String v=r.nextString();if(v.length()>4096) throw new IOException("Text exceeds bound");yield new JsonPrimitive(v);}
            case NUMBER->{String v=r.nextString();if(v.length()>64) throw new IOException("Number exceeds bound");yield TrialTraceCodec.sampleNumber(v);}
            case NULL->{r.nextNull();yield JsonNull.INSTANCE;}
            default->throw new IOException("Unexpected equipment lap token");
        };
    }
}
