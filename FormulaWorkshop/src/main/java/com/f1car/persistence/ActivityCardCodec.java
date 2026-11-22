package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Bounded declaration-only exchange. No results, proofs, automatic pack installation or map remapping. */
public final class ActivityCardCodec {
    public static final int MAX_BYTES=256000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private record RecordShape(RecordComponent[] components, String optionalField) {}
    private static final ClassValue<RecordShape> RECORD_SHAPES = new ClassValue<>() {
        @Override protected RecordShape computeValue(Class<?> type) {
            return new RecordShape(type.getRecordComponents(), legacyOptionalField(type));
        }
    };
    public record Shared(String author,String notes,ActivityCard card) {
        public Shared {if(!PartsCatalog.text(author,40) || !PartsCatalog.text(notes,240) || card==null) throw new IllegalArgumentException("Invalid activity attribution");}
        public ActivityCard copy() {return ActivityCard.create(card.name(),card.seed(),card.physics(),card.stages(),card.partsRule(),card.objectives());}
    }
    private record Envelope(String kind,int version,Shared content,String digest) {}
    private ActivityCardCodec() {}
    private static JsonObject payload(Shared shared) {var value=JSON.toJsonTree(shared).getAsJsonObject();omitLegacyObjectives(value);return value;}
    public static String encode(Shared shared) {var root=new JsonObject();root.addProperty("kind","formula-activity-card");root.addProperty("version",shared.card().hasSections()?3:shared.card().objectives()==null?1:2);var content=payload(shared);root.add("content",content);root.addProperty("digest",PartsCatalog.hash(JSON.toJson(content)));return JSON.toJson(root);}
    public static Shared read(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {
        var root=PartsJson.read(stream,MAX_BYTES).object("kind","version","content","digest");if(!root.child("kind").text(40,true).equals("formula-activity-card")) throw new IOException("请使用活动卡交换文件。");int version=root.child("version").integer(1,3);
        try {validate(root.child("content").value(),Shared.class);var shared=JSON.fromJson(root.child("content").value(),Shared.class);if((shared.card().hasSections()?3:shared.card().objectives()==null?1:2)!=version) throw new IOException("Activity envelope version differs from its conditions");if(!PartsCatalog.hash(JSON.toJson(payload(shared))).equals(root.child("digest").text(64,true))) throw new IOException("活动卡校验不符；修改内容后请重新导出。");return shared;}
        catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("活动卡字段无效："+Objects.toString(error.getMessage(),"请核对导出模板。"),error);}
    }}
    public static void write(Path path,Shared shared) throws IOException {write(path,shared,true);}
    public static void write(Path path,Shared shared,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(shared),MAX_BYTES,replace);
    }
    /** Null optional fields must not add a new record shape to legacy strict package exchanges. */
    static JsonElement legacyPackageJson(Object value,Gson gson) {var tree=gson.toJsonTree(value);omitLegacyObjectives(tree);return tree;}
    static String legacyPackage(Object value,Gson gson) {return gson.toJson(legacyPackageJson(value,gson));}
    private static void omitLegacyObjectives(JsonElement value) {if(value.isJsonObject()) {var object=value.getAsJsonObject();if(object.has("stages") && object.has("seed") && object.has("physics") && object.has("objectives") && object.get("objectives").isJsonNull()) object.remove("objectives");if(object.has("timeLimit") && object.has("contactLimit") && object.has("spreadLimit") && object.has("section") && object.get("section").isJsonNull()) object.remove("section");for(var child:object.entrySet()) omitLegacyObjectives(child.getValue());}else if(value.isJsonArray()) for(var child:value.getAsJsonArray()) omitLegacyObjectives(child);}
    /** Check exact record shapes and primitive types before Gson invokes validating record constructors. */
    static void validate(JsonElement value,Type type) throws ReflectiveOperationException {
        validate(value,type,false);
    }
    static void validate(JsonElement value,Type type,boolean allowExtraFields) throws ReflectiveOperationException {
        if(value==null) throw new IllegalArgumentException("Missing required field");if(value.isJsonNull()) {if(type instanceof Class<?> primitive && primitive.isPrimitive()) throw new IllegalArgumentException("Null primitive");return;}
        if(type instanceof ParameterizedType parameterized) {
            var raw=parameterized.getRawType();
            var arguments=parameterized.getActualTypeArguments();
            if(raw==Map.class) {
                if(!value.isJsonObject()) throw new IllegalArgumentException("Expected a map");
                for(var entry:value.getAsJsonObject().entrySet()) {
                    validate(new JsonPrimitive(entry.getKey()),arguments[0],allowExtraFields);
                    validate(entry.getValue(),arguments[1],allowExtraFields);
                }
                return;
            }
            if(raw!=List.class && raw!=Set.class || !value.isJsonArray()) throw new IllegalArgumentException("Expected a list or set");
            var unique=raw==Set.class?new HashSet<JsonElement>():null;
            for(var entry:value.getAsJsonArray()) {
                if(unique!=null && !unique.add(entry)) throw new IllegalArgumentException("Duplicate set value");
                validate(entry,arguments[0],allowExtraFields);
            }
            return;
        }
        var klass=(Class<?>)type;
        if(klass.isRecord()) {
            if(!value.isJsonObject()) throw new IllegalArgumentException("Expected an object");
            var object=value.getAsJsonObject();
            var shape = RECORD_SHAPES.get(klass);
            boolean omitted = shape.optionalField() != null && !object.has(shape.optionalField());
            int expected = shape.components().length - (omitted ? 1 : 0);
            if(!allowExtraFields && object.size()!=expected) throw new IllegalArgumentException("Unknown or missing fields in "+klass.getSimpleName());
            for(var component : shape.components()) {
                String name = component.getName();
                if (omitted && name.equals(shape.optionalField())) continue;
                var field = object.get(name);
                if (field == null) throw new IllegalArgumentException("Missing "+name);
                validate(field,component.getGenericType(),allowExtraFields);
            }
            return;
        }
        if(!value.isJsonPrimitive()) throw new IllegalArgumentException("Expected a primitive");var primitive=value.getAsJsonPrimitive();
        if(klass==String.class || klass.isEnum()) {if(!primitive.isString()) throw new IllegalArgumentException("Expected text");if(klass.isEnum() && Arrays.stream(klass.getEnumConstants()).noneMatch(entry->((Enum<?>)entry).name().equals(primitive.getAsString()))) throw new IllegalArgumentException("Unknown enum value");return;}
        if (klass == boolean.class || klass == Boolean.class) {
            if (!primitive.isBoolean()) throw new IllegalArgumentException("Expected a boolean");
            return;
        }
        if(!primitive.isNumber()) throw new IllegalArgumentException("Expected a number");var number=primitive.getAsBigDecimal();
        if(klass==int.class || klass==Integer.class) number.intValueExact();else if(klass==long.class || klass==Long.class) number.longValueExact();else if(klass==float.class || klass==Float.class) {if(!Float.isFinite(number.floatValue())) throw new IllegalArgumentException("Nonfinite float");}else if(klass==double.class || klass==Double.class) {if(!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Nonfinite double");}else throw new IllegalArgumentException("Unsupported field type");
    }

    private static String legacyOptionalField(Class<?> klass) {
        if(klass==ActivityCard.class) return "objectives";
        if(klass==ActivityObjective.class) return "section";
        if(klass==CircuitPackDraft.class) return "origin";
        if(klass==CircuitPackDraft.Stage.class) return "options";
        if(klass==CircuitPackDraft.StageOptions.class) return "reference";
        if(klass==TuningPurpose.Origin.class) return "fitted";
        if(klass==CircuitFeedbackDiscussion.Message.class || klass==CircuitFeedbackDiscussionDraft.class) return "trial";
        return null;
    }
}
