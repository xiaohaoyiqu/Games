package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Portable parent declaration plus a v1-v5 event package; no trusted local mapping. */
public final class CircuitEditionCodec {
    public static final int MAX_BYTES=600000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    public record Shared(CircuitEventPack pack,CircuitLineage lineage) {public Shared {Objects.requireNonNull(pack);if(lineage!=null) lineage.validateChild(pack);}}
    private record Envelope(String kind,int version,CircuitEventPack pack,CircuitLineage lineage,String digest) {}
    private CircuitEditionCodec() {}
    private static String digest(Shared shared) {return PartsCatalog.hash(shared.pack().id()+"\n"+shared.pack().digest()+"\n"+JSON.toJson(shared.lineage()));}
    public static Shared read(Path path,CreationStore legacy) throws IOException {
        byte[] bytes=ArchiveWriter.readImport(path,MAX_BYTES,"Edition exceeds size bound");
        String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        CreationStore.depth(source);
        try {
            var root=JsonParser.parseString(source).getAsJsonObject();
            var kind=root.get("kind");
            if(kind==null || !kind.isJsonPrimitive() || !kind.getAsString().equals("formula-circuit-edition"))
                return new Shared(legacy.decodePack(bytes.length,source,root),null);
            var strict=PartsJson.readDecoded(source,12);
            ActivityCardCodec.validate(strict.value(),Envelope.class);
            var envelope=JSON.fromJson(strict.value(),Envelope.class);
            if(envelope.version()!=1 || envelope.lineage()==null) throw new IOException("Unknown or incomplete edition");
            var shared=new Shared(envelope.pack(),envelope.lineage());
            size(shared);
            if(!digest(shared).equals(envelope.digest())) throw new IOException("Edition checksum differs");
            return shared;
        } catch(RuntimeException | ReflectiveOperationException error) {
            throw new IOException("Invalid circuit edition",error);
        }
    }
    private static void size(Shared shared) throws IOException {if(ActivityCardCodec.legacyPackage(shared.pack(),JSON).getBytes(StandardCharsets.UTF_8).length>524288) throw new IOException("Embedded package exceeds 512 KiB");}
    public static void write(Path path,Shared shared,CreationStore legacy) throws IOException {
        write(path,shared,legacy,true);
    }
    public static void write(Path path,Shared shared,CreationStore legacy,boolean replace) throws IOException {
        write(path,shared,legacy,ExportDestination.forWrite(path,replace));
    }
    public static void write(Path path,Shared shared,CreationStore legacy,ExportDestination destination) throws IOException {
        if(shared.lineage()==null) {
            legacy.exportPack(shared.pack(),path,destination);
            return;
        }
        size(shared);
        var envelope=new Envelope("formula-circuit-edition",1,shared.pack(),shared.lineage(),digest(shared));
        String source=JSON.toJson(ActivityCardCodec.legacyPackageJson(envelope,JSON));
        PartsStore.writeExport(legacy.exportTarget(path),source,MAX_BYTES,destination.replacing(),destination::verifyPublication);
    }
}
