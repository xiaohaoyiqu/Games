package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;

/** New independent discussion envelope. Original feedback/reply formats keep their existing shape. */
public final class CircuitFeedbackDiscussionCodec {
    public static final int MAX_BYTES=1_600_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private record Envelope(String kind,int version,CircuitFeedbackDiscussion discussion,String digest) {}
    private CircuitFeedbackDiscussionCodec() {}
    static JsonObject contentTree(CircuitFeedbackDiscussion value) {
        var body=JSON.toJsonTree(java.util.Objects.requireNonNull(value)).getAsJsonObject();
        for(var item:body.getAsJsonArray("messages")) {
            var message=item.getAsJsonObject();
            if(message.get("trial").isJsonNull()) message.remove("trial");
        }
        return body;
    }
    private static String digest(JsonObject body) {
        return PartsTrialEvidenceCodec.normalizedDigest(body);
    }
    public static String digest(CircuitFeedbackDiscussion value) {return digest(contentTree(value));}
    static JsonObject tree(CircuitFeedbackDiscussion value) {
        var envelope=envelope(value);
        boundedSource(envelope);
        return envelope;
    }
    public static String encode(CircuitFeedbackDiscussion value) {return boundedSource(envelope(value));}

    private static JsonObject envelope(CircuitFeedbackDiscussion value) {
        var body=contentTree(value);
        var envelope=new JsonObject();
        envelope.addProperty("kind","formula-circuit-feedback-discussion");
        envelope.addProperty("version",value.version());
        envelope.add("discussion",body);
        envelope.addProperty("digest",digest(body));
        return envelope;
    }

    private static String boundedSource(JsonObject envelope) {
        String source=JSON.toJson(envelope);
        if(source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES) throw new IllegalArgumentException("Feedback discussion exceeds bound");
        return source;
    }
    static CircuitFeedbackDiscussion decode(PartsJson.Node node) throws IOException {try {
        if(JSON.toJson(node.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES) throw new IOException("Feedback discussion exceeds bound");ActivityCardCodec.validate(node.value(),Envelope.class);var value=JSON.fromJson(node.value(),Envelope.class);
        var discussion=value.discussion();
        if(!"formula-circuit-feedback-discussion".equals(value.kind()) || discussion==null || value.version()!=discussion.version()
                || !digest(discussion).equals(value.digest())) throw new IOException("Feedback discussion format or checksum differs");
        if(discussion.version()==CircuitFeedbackDiscussion.LEGACY_VERSION) {
            for(var item:node.value().getAsJsonObject().getAsJsonObject("discussion").getAsJsonArray("messages"))
                if(item.getAsJsonObject().has("trial")) throw new IOException("Legacy discussion cannot contain a trial field");
        }
        return discussion;
    }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid feedback discussion",error);}}
    public static CircuitFeedbackDiscussion read(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {return decode(PartsJson.read(stream,MAX_BYTES,24));}}
    public static void write(Path path,CircuitFeedbackDiscussion value) throws IOException {write(path,value,true);}
    public static void write(Path path,CircuitFeedbackDiscussion value,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(value),MAX_BYTES,replace);
    }
}
