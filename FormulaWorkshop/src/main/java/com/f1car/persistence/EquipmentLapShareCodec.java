package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** One declared provider and one complete original lap, with corruption detection and strict bounds. */
public final class EquipmentLapShareCodec {
    public static final int MAX_BYTES=4_100_000;
    private static final Gson JSON=EquipmentLapJson.JSON;
    private EquipmentLapShareCodec() {}
    static JsonObject tree(EquipmentLapShare value) {
        var body=JSON.toJsonTree(value).getAsJsonObject();var root=new JsonObject();root.addProperty("kind","formula-workshop-equipment-lap");root.addProperty("version",1);root.add("content",body);root.addProperty("digest",digest(body));
        try {
            if (EquipmentLapJson.encodedBytes(root) > MAX_BYTES
                    || EquipmentLapJson.encodedBytes(body.get("recording")) > EquipmentLapStore.ENTRY_BYTES)
                throw new IllegalArgumentException("Equipment lap share exceeds bound");
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot encode equipment lap share", error);
        }
        return root;
    }
    private static String digest(JsonElement value) {return PartsTrialEvidenceCodec.normalizedDigest(value);}
    public static String encode(EquipmentLapShare value) {return JSON.toJson(tree(value));}
    static EquipmentLapShare decode(JsonElement value) throws IOException {
        try {
            if(EquipmentLapJson.encodedBytes(value)>MAX_BYTES) throw new IOException("Equipment lap share exceeds bound");var root=new PartsJson.Node(value,"").object("kind","version","content","digest");
            if(!root.child("kind").text(64,true).equals("formula-workshop-equipment-lap")) throw new IOException("Wrong equipment lap kind");root.child("version").integer(1,1);
            var body=root.child("content").object("version","author","notes","recording");body.child("version").integer(1,1);
            if(!root.child("digest").text(64,true).equals(digest(body.value()))) throw new IOException("Equipment lap checksum differs");
            ActivityCardCodec.validate(body.child("author").value(),String.class);ActivityCardCodec.validate(body.child("notes").value(),String.class);
            var recording=EquipmentLapJson.decode(body.child("recording").value());return new EquipmentLapShare(1,body.child("author").value().getAsString(),body.child("notes").value().getAsString(),recording);
        }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid equipment lap share",error);}
    }
    public static EquipmentLapShare read(Path path) throws IOException {
        try(var input=Files.newInputStream(path)) {
            byte[] data=input.readNBytes(MAX_BYTES+1);if(data.length>MAX_BYTES) throw new IOException("Equipment lap share exceeds bound");
            try (var reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8.newDecoder()))) {
                reader.setStrictness(Strictness.STRICT);
                var value = EquipmentLapJson.element(reader, 0, new int[]{0});
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing equipment lap content");
                return decode(value);
            }
        }catch(RuntimeException error) {throw new IOException("Invalid equipment lap file",error);}
    }
    public static void write(Path path,EquipmentLapShare value,boolean replace) throws IOException {
        String source=encode(value);if(replace) {PartsStore.write(path,source,MAX_BYTES);return;}
        var diagnostics=new SaveDiagnostics("equipment-lap-export");diagnostics.begin();
        ArchiveWriter.bytes(path,source.getBytes(StandardCharsets.UTF_8),MAX_BYTES,"equipment-lap-export-",diagnostics,new ArchiveWriter.Filesystem() {
            @Override public void atomic(Path from,Path to) throws IOException {Files.move(from,to);}
        });
    }
}
