package com.f1car.persistence;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One bounded original trace and its frozen report context; independent of exchange/main archive versions. */
final class TuningTraceRecoveryCodec {
    static final int MAX_BYTES=6_000_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    static byte[] encode(TuningTraceStore.Recovery value) throws IOException {
        var root=new JsonObject();root.addProperty("version",1);root.add("pending",value==null?JsonNull.INSTANCE:JSON.toJsonTree(value));RecoveryJson.unicode(root);
        var buffer=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(JSON.toJson(root)));if(buffer.remaining()>MAX_BYTES) throw new IOException("Trace recovery exceeds bound");var bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;
    }
    static TuningTraceStore.Recovery decode(byte[] bytes) throws IOException,ReflectiveOperationException {
        var root=new PartsJson.Node(RecoveryJson.read(bytes,MAX_BYTES,240_000),"").object("version","pending");root.child("version").integer(1,1);var node=root.child("pending").value();if(node.isJsonNull()) return null;
        RecoveryJson.unicode(node);ActivityCardCodec.validate(node,TuningTraceStore.Recovery.class);
        var value=Objects.requireNonNull(JSON.fromJson(node,TuningTraceStore.Recovery.class));if(!RecoveryJson.sameFacts(node,JSON.toJsonTree(value),TuningTraceStore.Recovery.class)) throw new IOException("Trace recovery facts were not canonical");return value;
    }
    private TuningTraceRecoveryCodec() {}
}
