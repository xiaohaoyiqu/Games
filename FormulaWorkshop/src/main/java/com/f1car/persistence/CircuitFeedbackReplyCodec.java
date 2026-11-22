package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;

/** Complete source-and-answer exchange. Decoding does not adopt local handling or maps. */
public final class CircuitFeedbackReplyCodec {
    public static final int MAX_BYTES=256000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private record Envelope(String kind,int version,CircuitFeedbackReply reply,String digest) {}
    private CircuitFeedbackReplyCodec() {}
    public static String digest(CircuitFeedbackReply reply) {return PartsCatalog.hash(JSON.toJson(reply));}
    public static String encode(CircuitFeedbackReply reply) {return JSON.toJson(new Envelope("formula-circuit-feedback-reply",1,reply,digest(reply)));}
    public static CircuitFeedbackReply read(Path path) throws IOException {
        try(var stream=Files.newInputStream(path)) {var root=PartsJson.read(stream,MAX_BYTES);ActivityCardCodec.validate(root.value(),Envelope.class);var envelope=JSON.fromJson(root.value(),Envelope.class);if(!"formula-circuit-feedback-reply".equals(envelope.kind()) || envelope.version()!=1 || envelope.reply()==null || !digest(envelope.reply()).equals(envelope.digest())) throw new IOException("Reply format or checksum differs");return envelope.reply();}
        catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid feedback reply",error);}
    }
    public static void write(Path path,CircuitFeedbackReply reply) throws IOException {write(path,reply,true);}
    public static void write(Path path,CircuitFeedbackReply reply,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(reply),MAX_BYTES,replace);
    }
}
