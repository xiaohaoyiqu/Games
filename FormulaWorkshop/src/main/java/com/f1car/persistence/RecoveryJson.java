package com.f1car.persistence;

import com.f1car.model.TuningTrace;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict recovery-only parsing keeps signed zero before record conversion and rejects rounded facts. */
final class RecoveryJson {
    static JsonElement read(byte[] bytes,int maximumBytes,int maximumNodes) throws IOException {
        if(bytes.length>maximumBytes) throw new IOException("Recovery exceeds byte bound");String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        try(var reader=new JsonReader(new StringReader(text))) {reader.setStrictness(Strictness.STRICT);var root=value(reader,0,new int[]{0},maximumNodes);if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Trailing recovery content");return root;}
    }
    private static JsonElement value(JsonReader reader,int depth,int[] count,int maximumNodes) throws IOException {
        if(depth>32 || ++count[0]>maximumNodes) throw new IOException("Recovery structure exceeds bound");
        return switch(reader.peek()) {
            case BEGIN_OBJECT->{reader.beginObject();var object=new JsonObject();while(reader.hasNext()) {String key=reader.nextName();if(key.length()>100 || object.has(key) || object.size()>=32) throw new IOException("Invalid recovery field");object.add(key,value(reader,depth+1,count,maximumNodes));}reader.endObject();yield object;}
            case BEGIN_ARRAY->{reader.beginArray();var array=new JsonArray();while(reader.hasNext()) {if(array.size()>=TuningTrace.MAX_SAMPLES) throw new IOException("Recovery array exceeds bound");array.add(value(reader,depth+1,count,maximumNodes));}reader.endArray();yield array;}
            case STRING->{String text=reader.nextString();if(text.length()>4096) throw new IOException("Recovery text exceeds bound");yield new JsonPrimitive(text);}
            case NUMBER->number(reader.nextString());
            case BOOLEAN->new JsonPrimitive(reader.nextBoolean());
            case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}
            default->throw new IOException("Unexpected recovery token");
        };
    }
    static JsonPrimitive number(String token) throws IOException {
        if(token.length()>64) throw new IOException("Recovery number exceeds bound");
        var decimal=new BigDecimal(token);
        return token.startsWith("-") && decimal.signum()==0?new JsonPrimitive(-0.0d):new JsonPrimitive(decimal);
    }
    static boolean sameFacts(JsonElement original,JsonElement decoded,Type type) {
        // String comparison after exact decimal normalization avoids JsonPrimitive's double underflow equality.
        return normalized(original,type).toString().equals(normalized(decoded,type).toString());
    }
    private static JsonElement normalized(JsonElement value,Type type) {
        if(value==null || value.isJsonNull()) return JsonNull.INSTANCE;
        if(value.isJsonObject()) {
            var result=new JsonObject();var fields=new HashMap<String,Type>();
            if(type instanceof Class<?> klass && klass.isRecord()) for(var component:klass.getRecordComponents()) fields.put(component.getName(),component.getGenericType());
            Type mapped=type instanceof ParameterizedType p && p.getRawType()==Map.class?p.getActualTypeArguments()[1]:null;
            value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->result.add(entry.getKey(),normalized(entry.getValue(),mapped!=null?mapped:fields.get(entry.getKey()))));return result;
        }
        if(value.isJsonArray()) {
            var items=new ArrayList<JsonElement>();Type element=type instanceof ParameterizedType p?p.getActualTypeArguments()[0]:null;
            for(var item:value.getAsJsonArray()) items.add(normalized(item,element));
            if(type instanceof ParameterizedType p && p.getRawType()==Set.class) items.sort(Comparator.comparing(JsonElement::toString));
            var result=new JsonArray();items.forEach(result::add);return result;
        }
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            if(value.getAsBigDecimal().signum()==0 && value.toString().startsWith("-")) return new JsonPrimitive(-0.0d);
            return new JsonPrimitive(value.getAsBigDecimal().stripTrailingZeros());
        }
        return value.deepCopy();
    }
    static void unicode(JsonElement value) {
        if(value.isJsonObject()) for(var entry:value.getAsJsonObject().entrySet()) {unicode(new JsonPrimitive(entry.getKey()));unicode(entry.getValue());}
        else if(value.isJsonArray()) value.getAsJsonArray().forEach(RecoveryJson::unicode);
        else if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().codePoints().anyMatch(cp->cp>=0xd800 && cp<=0xdfff)) throw new IllegalArgumentException("Unpaired surrogate in recovery text");
    }
    private RecoveryJson() {}
}
