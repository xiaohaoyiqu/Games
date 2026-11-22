package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;

/** Original player comments only. Local map binding, handling and replies never leave this archive. */
public final class CircuitFeedbackCodec {
    public static final int MAX_BYTES=128000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private record Envelope(String kind,int version,CircuitFeedback feedback,String digest) {}
    private CircuitFeedbackCodec() {}
    public static String digest(CircuitFeedback feedback) {return PartsCatalog.hash(JSON.toJson(feedback));}
    public static String encode(CircuitFeedback feedback) {return JSON.toJson(new Envelope("formula-circuit-feedback",1,feedback,digest(feedback)));}
    public static CircuitFeedback read(Path path) throws IOException {
        try(var stream=Files.newInputStream(path)) {var root=PartsJson.read(stream,MAX_BYTES);ActivityCardCodec.validate(root.value(),Envelope.class);var value=JSON.fromJson(root.value(),Envelope.class);if(!"formula-circuit-feedback".equals(value.kind()) || value.version()!=1 || value.feedback()==null || !digest(value.feedback()).equals(value.digest())) throw new IOException("Feedback format or checksum differs");return value.feedback();}
        catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid circuit feedback",error);}
    }
    public static void write(Path path,CircuitFeedback feedback) throws IOException {write(path,feedback,true);}
    public static void write(Path path,CircuitFeedback feedback,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(feedback),MAX_BYTES,replace);
    }
}
