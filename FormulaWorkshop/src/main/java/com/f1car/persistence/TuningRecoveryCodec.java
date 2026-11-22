package com.f1car.persistence;

import com.google.gson.*;
import java.io.*;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate, bounded full-archive recovery envelope; ordinary archive versions remain unchanged. */
final class TuningRecoveryCodec {
    static final int MAX_BYTES=2_200_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create(),MAIN=new Gson();
    static byte[] encode(TuningStore.Recovery value) throws IOException {
        var root=new JsonObject();root.addProperty("version",1);root.add("pending",value==null?JsonNull.INSTANCE:JSON.toJsonTree(value));
        RecoveryJson.unicode(root);
        if(value!=null) {bound(value.base());bound(value.candidate());}
        byte[] encoded=bytes(JSON.toJson(root));if(encoded.length>MAX_BYTES) throw new IOException("Setup recovery exceeds bound");return encoded;
    }
    static TuningStore.Recovery decode(byte[] bytes) throws IOException,ReflectiveOperationException {
        var root=new PartsJson.Node(RecoveryJson.read(bytes,MAX_BYTES,250_000),"").object("version","pending");root.child("version").integer(1,1);
        var node=root.child("pending").value();if(node.isJsonNull()) return null;
        RecoveryJson.unicode(node);
        ActivityCardCodec.validate(node,TuningStore.Recovery.class);var value=Objects.requireNonNull(JSON.fromJson(node,TuningStore.Recovery.class));
        if(!RecoveryJson.sameFacts(node,JSON.toJsonTree(value),TuningStore.Recovery.class)) throw new IOException("Recovery facts were not canonical");bound(value.base());bound(value.candidate());return value;
    }
    private static void bound(TuningStore.Archive archive) throws IOException {if(bytes(MAIN.toJson(archive)).length>(archive.version()==1?262144:1048576)) throw new IOException("Setup archive exceeds recovery bound");}
    private static byte[] bytes(String text) throws java.nio.charset.CharacterCodingException {var buffer=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));var result=new byte[buffer.remaining()];buffer.get(result);return result;}
    private TuningRecoveryCodec() {}
}
