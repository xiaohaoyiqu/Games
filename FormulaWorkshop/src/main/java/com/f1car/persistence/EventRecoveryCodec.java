package com.f1car.persistence;

import com.f1car.model.EventPendingResult;
import com.f1car.model.EventSeries;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded, strict recovery envelope with optional settlement facts in each round. */
final class EventRecoveryCodec {
    static final int MAX_BYTES=530_000,STATE_BYTES=262_144;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    static byte[] encode(EventPendingResult entry) throws IOException {
        var root=new JsonObject();root.addProperty("version",1);
        JsonElement pending=entry==null?JsonNull.INSTANCE:pendingTree(entry);
        if(entry!=null) boundStates(pending.getAsJsonObject());
        root.add("pending",pending);
        byte[] bytes=bytes(root);if(bytes.length>MAX_BYTES) throw new IOException("Event recovery exceeds bound");return bytes;
    }
    static EventPendingResult decode(byte[] bytes) throws IOException,ReflectiveOperationException {
        if(bytes.length>MAX_BYTES) throw new IOException("Event recovery exceeds bound");
        String source=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();JsonElement parsed;
        try(var reader=new JsonReader(new StringReader(source))) {reader.setStrictness(Strictness.STRICT);parsed=bounded(reader,0,new int[]{0});if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Trailing event recovery content");}
        var root=new PartsJson.Node(parsed,"").object("version","pending");root.child("version").integer(1,1);
        var node=root.child("pending").value();if(node.isJsonNull()) return null;
        validate(node,EventPendingResult.class);boundStates(node.getAsJsonObject());
        var value=Objects.requireNonNull(JSON.fromJson(node,EventPendingResult.class));
        if(!RecoveryJson.sameFacts(node,pendingTree(value),EventPendingResult.class))
            throw new IOException("Recovery values were not canonical event facts");
        return value;
    }
    private static JsonObject pendingTree(EventPendingResult entry) {
        var pending=JSON.toJsonTree(entry).getAsJsonObject();
        for(String side:List.of("base","candidate")) {
            omitUnknownSettlementFields(pending.getAsJsonObject(side));
        }
        return pending;
    }
    static JsonObject stateTree(EventSeries.State state) {
        var value=JSON.toJsonTree(state).getAsJsonObject();
        omitUnknownSettlementFields(value);
        return value;
    }
    private static void omitUnknownSettlementFields(JsonObject state) {
        for(var round:state.getAsJsonArray("results")) {
            var result=round.getAsJsonObject();
            if(result.get("settlement") instanceof JsonNull) result.remove("settlement");
            else if(result.get("settlement") instanceof JsonObject settlement && settlement.get("replayId") instanceof JsonNull)
                settlement.remove("replayId");
        }
    }
    static EventSeries.State readState(JsonReader reader) throws IOException,ReflectiveOperationException {
        var node=bounded(reader,0,new int[]{0});
        validate(node,EventSeries.State.class);
        bound(node);
        var state=Objects.requireNonNull(JSON.fromJson(node,EventSeries.State.class));
        if(!RecoveryJson.sameFacts(node,stateTree(state),EventSeries.State.class))
            throw new IOException("Archived event facts were not canonical");
        return state;
    }
    private static void boundStates(JsonObject pending) throws IOException {
        bound(pending.get("base"));
        bound(pending.get("candidate"));
    }
    private static void bound(JsonElement state) throws IOException {if(bytes(state).length>STATE_BYTES) throw new IOException("Event state exceeds bound");}
    private static byte[] bytes(JsonElement value) throws java.nio.charset.CharacterCodingException {var encoded=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(JSON.toJson(value)));var bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;}
    private static JsonElement bounded(JsonReader reader,int depth,int[] count) throws IOException {
        if(depth>20 || ++count[0]>8000) throw new IOException("Event recovery structure exceeds bound");
        return switch(reader.peek()) {
            case BEGIN_OBJECT->{reader.beginObject();var object=new JsonObject();while(reader.hasNext()) {String key=reader.nextName();if(key.length()>100 || object.has(key) || object.size()>=32) throw new IOException("Invalid recovery field");object.add(key,bounded(reader,depth+1,count));}reader.endObject();yield object;}
            case BEGIN_ARRAY->{reader.beginArray();var array=new JsonArray();while(reader.hasNext()) {if(array.size()>=8) throw new IOException("Recovery list exceeds bound");array.add(bounded(reader,depth+1,count));}reader.endArray();yield array;}
            case STRING->{String text=reader.nextString();if(text.length()>256) throw new IOException("Recovery text exceeds bound");yield new JsonPrimitive(text);}
            case NUMBER->RecoveryJson.number(reader.nextString());
            case BOOLEAN->new JsonPrimitive(reader.nextBoolean());case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}
            default->throw new IOException("Unexpected recovery token");
        };
    }
    private static void validate(JsonElement value,Type type) throws ReflectiveOperationException {
        if(value==null || value.isJsonNull()) throw new IllegalArgumentException("Missing recovery field");
        if(type instanceof ParameterizedType parameterized) {
            if(parameterized.getRawType()!=List.class || !value.isJsonArray()) throw new IllegalArgumentException("Expected recovery list");
            for(var item:value.getAsJsonArray()) validate(item,parameterized.getActualTypeArguments()[0]);return;
        }
        var klass=(Class<?>)type;
        if(klass.isRecord()) {
            if(!value.isJsonObject()) throw new IllegalArgumentException("Expected recovery object");
            var object=value.getAsJsonObject();
            var components=klass.getRecordComponents();
            String optional=klass==EventSeries.Result.class?"settlement":klass==EventSeries.Settlement.class?"replayId":null;
            boolean omitted=optional!=null && !object.has(optional);
            if(object.size()!=components.length-(omitted?1:0)) throw new IllegalArgumentException("Unknown or missing recovery fields");
            for(var component:components) {
                if(omitted && component.getName().equals(optional)) continue;
                validate(object.get(component.getName()),component.getGenericType());
            }
            return;
        }
        if(!value.isJsonPrimitive()) throw new IllegalArgumentException("Expected recovery primitive");var primitive=value.getAsJsonPrimitive();
        if(klass==String.class || klass.isEnum()) {if(!primitive.isString() || klass.isEnum() && Arrays.stream(klass.getEnumConstants()).noneMatch(item->((Enum<?>)item).name().equals(primitive.getAsString()))) throw new IllegalArgumentException("Invalid recovery text");return;}
        if(klass==boolean.class) {if(!primitive.isBoolean()) throw new IllegalArgumentException("Expected recovery boolean");return;}
        if(!primitive.isNumber()) throw new IllegalArgumentException("Expected recovery number");var number=primitive.getAsBigDecimal();
        if(klass==int.class) number.intValueExact();else if(klass==double.class) {if(!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Nonfinite recovery number");}else if(klass==float.class) {if(!Float.isFinite(number.floatValue())) throw new IllegalArgumentException("Nonfinite recovery number");}else throw new IllegalArgumentException("Unsupported recovery field");
    }
    private EventRecoveryCodec() {}
}
