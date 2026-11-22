package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.util.*;

/** A small metadata index; full trajectories are read only on explicit import/selection/export. */
public final class GhostExchangeStore {
    public static final int MAX_ENTRIES=12,MAX_BYTES=2_500_000;
    private static final int INDEX_BYTES = 64_000;
    private static final Gson JSON=new GsonBuilder().setStrictness(Strictness.STRICT)
            .registerTypeAdapter(GhostSectorJson.LIST_TYPE,new GhostSectorJson())
            .registerTypeAdapter(GhostSampleJson.LIST_TYPE,new GhostSampleJson()).create();
    private record Envelope(int version,String checksum,GhostChallenge challenge) { }
    private record Index(int version,List<Entry> entries) { }
    private record Indexed(List<Entry> entries, ArchiveWriter.Stamp stamp) {}
    private record Loaded(Entry entry,GhostChallenge challenge,ArchiveWriter.Stamp stamp) {}
    public record Entry(String id,TelemetryLap.Conditions conditions,String trackName,String author,String replyTo,double duration,String routeGeometry) {
        public Entry {
            if(id==null || !id.matches("[a-f0-9]{64}") || conditions==null || trackName==null || trackName.isBlank() || trackName.length()>60
                || author==null || author.length()>40 || trackName.codePoints().anyMatch(Character::isISOControl) || author.codePoints().anyMatch(Character::isISOControl)
                || replyTo!=null && !replyTo.matches("[a-f0-9]{64}") || routeGeometry!=null && !routeGeometry.matches("[a-f0-9]{64}")
                || !Double.isFinite(duration) || duration<=0 || duration>GhostLap.MAX_DURATION)
                throw new IllegalArgumentException("Invalid ghost index");
        }
    }
    private final Path directory,indexFile;
    private final SaveDiagnostics indexDiagnostics = new SaveDiagnostics("ghost-exchange-index");
    private List<Entry> entries=List.of();
    private ArchiveWriter.Stamp indexStamp;
    private Loaded loaded;
    private String warning="";
    public GhostExchangeStore(Path data) { directory=data.resolve("ghost-exchange");indexFile=directory.resolve("index.json");reload(); }
    public List<Entry> entries() { return entries; }
    public String warning() { return warning; }
    public void reload() {
        try {
            acceptIndex(readIndex());
            warning = "";
        } catch(IOException | RuntimeException error) { warning="幽灵库索引无法读取，原文件保留；请恢复索引后再导入。"; }
    }

    private Indexed readIndex() throws IOException {
        if (Files.notExists(indexFile, LinkOption.NOFOLLOW_LINKS)) return new Indexed(List.of(), ArchiveWriter.ABSENT);
        byte[] bytes = read(indexFile, INDEX_BYTES, LinkOption.NOFOLLOW_LINKS);
        var stamp = ArchiveWriter.stamp(bytes);
        if (stamp.equals(indexStamp)) return new Indexed(entries, stamp);
        try {
            var index = JSON.fromJson(decode(bytes), Index.class);
            if (index == null || index.version() != 1 || index.entries() == null || index.entries().size() > MAX_ENTRIES)
                throw new IOException("Invalid index");
            var values = List.copyOf(index.entries());
            var ids = new HashSet<String>();
            for (var entry : values) if (!ids.add(entry.id())) throw new IOException("Duplicate ghost entry");
            return new Indexed(values, stamp);
        } catch (RuntimeException error) {
            throw new IOException("Invalid ghost index", error);
        }
    }
    public static String id(GhostChallenge challenge) {
        var digest=ArchiveWriter.digest();
        try(var writer=new BufferedWriter(new OutputStreamWriter(
                new DigestOutputStream(OutputStream.nullOutputStream(),digest),StandardCharsets.UTF_8))) {
            JSON.toJson(challenge,writer);
        } catch(IOException error) {throw new UncheckedIOException(error);}
        return HexFormat.of().formatHex(digest.digest());
    }
    public static GhostChallenge readFile(Path path) throws IOException {
        return readEnvelope(read(path,MAX_BYTES)).challenge();
    }
    private static Envelope readEnvelope(byte[] bytes) throws IOException {
        try {
            var value=JSON.fromJson(decode(bytes),Envelope.class);
            if(value==null || value.version()!=1 || value.challenge()==null || !id(value.challenge()).equals(value.checksum())) throw new IOException("Ghost checksum or version mismatch");
            return value;
        } catch(RuntimeException error) { throw new IOException("Invalid ghost file",error); }
    }
    public Entry importFile(Path path) throws IOException {
        var value=readEnvelope(read(path,MAX_BYTES));
        var challenge=value.challenge();
        var entry=entry(challenge,value.checksum());
        Files.createDirectories(directory);
        try (var channel = ArchiveWriter.openLock(directory.resolve("index.lock")); var held = lock(channel)) {
            var current = readIndex();
            acceptIndex(current);
            var previous = find(entry.id());
            if (previous != null && !previous.equals(entry)) throw new IOException("Ghost index mismatch");
            if (previous == null && entries.size() >= MAX_ENTRIES) throw new IOException("Ghost library is full (12)");
            var stored = file(entry.id());
            if (Files.notExists(stored, LinkOption.NOFOLLOW_LINKS)) writeEnvelope(value,stored,false);
            else readStored(entry);
            if (previous != null) {
                warning = "";
                return previous;
            }
            var next = new ArrayList<>(entries);
            next.add(entry);
            publishIndex(current, next);
            warning = "";
            return entry;
        }
    }
    public GhostChallenge load(String id) throws IOException {
        acceptIndex(readIndex());
        var entry=find(id);
        if (entry == null) throw new IOException("Ghost not in library");
        if(loaded!=null && loaded.entry().equals(entry)) {
            try {
                ArchiveWriter.verifyStamp(file(id),loaded.stamp(),LinkOption.NOFOLLOW_LINKS);
                return loaded.challenge();
            } catch(ArchiveWriter.StaleArchiveException changed) {
                loaded=null;
            }
        }
        loaded=readStored(entry);
        return loaded.challenge();
    }
    public Entry find(String id) {
        for (var entry : entries) if (entry.id().equals(id)) return entry;
        return null;
    }
    private Loaded readStored(Entry entry) throws IOException {
        byte[] bytes=read(file(entry.id()),MAX_BYTES,LinkOption.NOFOLLOW_LINKS);
        var value=readEnvelope(bytes);
        if(!entry.equals(entry(value.challenge(),value.checksum()))) throw new IOException("Ghost index mismatch");
        return new Loaded(entry,value.challenge(),ArchiveWriter.stamp(bytes));
    }
    private void acceptIndex(Indexed index) {
        var next = index.entries();
        if(loaded!=null && !next.contains(loaded.entry())) loaded=null;
        if(!entries.equals(next)) entries=next;
        indexStamp=index.stamp();
    }
    public void remove(String id) throws IOException {
        var reviewed = find(id);
        if (reviewed == null) {
            acceptIndex(readIndex());
            if(find(id)!=null) throw new IOException("Selected ghost changed");
            warning="这份幽灵已不在库中。";
            return;
        }
        try (var channel = ArchiveWriter.openLock(directory.resolve("index.lock")); var held = lock(channel)) {
            var current = readIndex();
            acceptIndex(current);
            var onDisk = find(id);
            if (onDisk == null) {warning="这份幽灵已不在库中。";return;}
            if (!reviewed.equals(onDisk)) throw new IOException("Selected ghost changed");
            var next = entries.stream().filter(entry -> !entry.id().equals(id)).toList();
            publishIndex(current, next);
            warning = "";
            var stored=file(id);
            try {
                if(Files.notExists(stored,LinkOption.NOFOLLOW_LINKS)) return;
                if(!Files.isRegularFile(stored,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid ghost content path");
                ArchiveWriter.verifyStamp(indexFile,indexStamp,LinkOption.NOFOLLOW_LINKS);
                Files.deleteIfExists(stored);
            } catch (IOException | SecurityException error) {warning = "已移出索引，旧轨迹文件未能清理。";}
        }
    }

    private static FileLock lock(FileChannel channel) throws IOException {
        try {
            var held = channel.tryLock();
            if (held == null) throw new IOException("Ghost library is busy");
            return held;
        } catch (OverlappingFileLockException busy) {
            throw new IOException("Ghost library is busy", busy);
        }
    }

    private void publishIndex(Indexed current, List<Entry> next) throws IOException {
        byte[] bytes = JSON.toJson(new Index(1, next)).getBytes(StandardCharsets.UTF_8);
        indexDiagnostics.begin();
        ArchiveWriter.bytes(indexFile, bytes, INDEX_BYTES, "ghost-index-", indexDiagnostics, ArchiveWriter.FORCED_FILES,
                target -> ArchiveWriter.verifyStamp(target, current.stamp(), LinkOption.NOFOLLOW_LINKS));
        acceptIndex(new Indexed(List.copyOf(next), ArchiveWriter.stamp(bytes)));
    }
    public static void writeFile(GhostChallenge challenge,Path path) throws IOException {
        writeFile(challenge,path,true);
    }
    public static void writeFile(GhostChallenge challenge,Path path,boolean replace) throws IOException {
        writeEnvelope(new Envelope(1,id(challenge),challenge),path,replace);
    }
    public static void writeFile(GhostChallenge challenge,Path path,ExportDestination destination) throws IOException {
        writeEnvelope(new Envelope(1,id(challenge),challenge),path,destination.replacing(),destination::verifyPublication);
    }
    private static void writeEnvelope(Envelope envelope,Path path,boolean replace) throws IOException {
        writeEnvelope(envelope,path,replace,ArchiveWriter.NO_CHECK);
    }
    private static void writeEnvelope(Envelope envelope,Path path,boolean replace,ArchiveWriter.ReplacementCheck check) throws IOException {
        var diagnostics=new SaveDiagnostics("ghost-exchange");
        diagnostics.begin();
        try {
            ArchiveWriter.utf8(path,MAX_BYTES,"ghost-exchange-",diagnostics,
                    replace?ArchiveWriter.FORCED_FILES:ArchiveWriter.NEW_FILES,check,writer->JSON.toJson(envelope,writer));
        } catch(JsonIOException error) {
            if(error.getCause() instanceof IOException cause) throw cause;
            throw error;
        }
    }
    private static Entry entry(GhostChallenge c,String id) { return new Entry(id,c.conditions(),c.trackName(),c.author(),c.replyTo(),c.lap().duration(),c.routeGeometry()); }
    private Path file(String id) { return directory.resolve(id+".json"); }
    private static byte[] read(Path path,int limit,LinkOption... options) throws IOException {
        if(!Files.isRegularFile(path,options)) throw new IOException("Choose a ghost file");
        byte[] bytes;
        try(var input=Files.newInputStream(path,options)) { bytes=input.readNBytes(limit+1); }
        if(bytes.length>limit) throw new IOException("Oversized ghost data");
        return bytes;
    }
    private static String decode(byte[] bytes) throws IOException {
        String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) { if(escaped) escaped=false;else if(c=='\\') escaped=true;else if(c=='\"') quoted=false; }
            else if(c=='\"') quoted=true;
            else if(c=='{' || c=='[') { if(++depth>24) throw new IOException("Ghost nesting limit"); }
            else if(c=='}' || c==']') depth--;
        }
        return text;
    }
}
