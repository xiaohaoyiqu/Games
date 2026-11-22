package com.f1car.persistence;

import com.f1car.model.GhostChallenge;
import com.f1car.model.GhostLap;
import com.f1car.model.TelemetryLap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Best time, sector times and trajectory are committed together in one atomic file. */
public final class GhostStore {
    public enum Saved { SAVED, UNCHANGED, FAILED }
    public record BestWrite(Saved status,GhostLap published) {}
    public record PendingBest(String trackName,TelemetryLap.Conditions conditions,GhostLap lap) {
        public PendingBest {
            if(trackName==null || trackName.isBlank() || conditions==null || lap==null
                    || !GhostChallenge.key(conditions).equals(lap.key()))
                throw new IllegalArgumentException("Pending best differs from its original conditions");
        }
    }
    private static final int MAX_BYTES=2_500_000;
    private static final int MAX_PENDING=8;
    private static final String NEWER_WARNING="最佳圈文件来自较新版本，原文件已保留。请使用兼容版本，或移走文件后重试。";
    private static final class NewerGhost extends IOException {}
    private record VersionHeader(double version) {}
    private record Archive(GhostLap lap,ArchiveWriter.Stamp stamp) {}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(GhostSectorJson.LIST_TYPE,new GhostSectorJson())
            .registerTypeAdapter(GhostSampleJson.LIST_TYPE,new GhostSampleJson()).create();
    private final Path directory;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("best-ghost");
    private final Map<String,PendingBest> pending=new LinkedHashMap<>();
    private List<PendingBest> pendingView=List.of();
    private String warning="";
    public GhostStore(Path dataDirectory) { directory=dataDirectory.resolve("ghosts"); }
    public String warning() { return warning; }
    public List<PendingBest> pendingBests() {return pendingView;}
    public PendingBest pendingBest(String key) {return pending.get(key);}

    public boolean retainPending(PendingBest candidate) {
        String key=candidate.lap().key();
        var previous=pending.get(key);
        if(previous!=null && previous.lap().duration()<=candidate.lap().duration()) return true;
        if(previous==null && pending.size()==MAX_PENDING) return false;
        pending.put(key,candidate);
        pendingView=List.copyOf(pending.values());
        return true;
    }

    private void clearCoveredPending(GhostLap published) {
        if(published==null) return;
        var held=pending.get(published.key());
        if(held!=null && published.duration()<=held.lap().duration()) {
            pending.remove(published.key());
            pendingView=List.copyOf(pending.values());
        }
    }

    public Path file(String key) {
        try { return directory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))+".json"); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public GhostLap load(String key) {
        warning="";
        try {
            var archive=read(key,file(key));
            if(archive.lap()==null && !ArchiveWriter.ABSENT.equals(archive.stamp())) warnUnreadable();
            clearCoveredPending(archive.lap());
            return archive.lap();
        }catch(NewerGhost error) {
            warning=NEWER_WARNING;
            return null;
        }catch(IOException | RuntimeException error) {
            warnUnreadable();
            return null;
        }
    }
    private void warnUnreadable() {
        warning="最佳圈文件无法读取，原文件已保留；可继续计时挑战。";
    }
    private Archive read(String key,Path file) throws IOException {
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) return new Archive(null,ArchiveWriter.ABSENT);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid ghost file");
        byte[] bytes;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            bytes=input.readNBytes(MAX_BYTES+1);
        }
        if(bytes.length>MAX_BYTES) throw new IOException("Oversized ghost");
        var stamp=ArchiveWriter.stamp(bytes);
        String source;
        try {
            source=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }catch(java.nio.charset.CharacterCodingException invalid) {
            return new Archive(null,stamp);
        }
        try {
            var value=JSON.fromJson(source,GhostLap.class);
            return new Archive(value!=null && key.equals(value.key())?value:null,stamp);
        }catch(RuntimeException invalid) {
            // Newer layouts can fail in the sample decoder before it reaches the root version.
            VersionHeader header=null;
            try {header=JSON.fromJson(source,VersionHeader.class);}
            catch(JsonParseException | NumberFormatException malformed) { /* Preserve the original during repair. */ }
            if(header!=null && header.version()>GhostLap.VERSION) throw new NewerGhost();
            return new Archive(null,stamp);
        }
    }
    public Saved record(GhostLap lap) {return writeBest(lap).status();}

    public BestWrite writeBest(GhostLap lap) {
        Path target=file(lap.key());
        diagnostics.begin();
        BestWrite accepted=null;
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            Files.createDirectories(directory);
            try(var channel=ArchiveWriter.openLock(directory.resolve("archive.lock"));var held=channel.tryLock()) {
                if(held==null) throw new IOException("Ghost archive is busy");
                var previous=read(lap.key(),target);
                if(previous.lap()!=null && lap.duration()>=previous.lap().duration()) {
                    accepted=new BestWrite(Saved.UNCHANGED,previous.lap());
                } else {
                    if(previous.lap()==null && !ArchiveWriter.ABSENT.equals(previous.stamp())) {
                        diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                        String prefix=target.getFileName()+".corrupt-";
                        ArchiveWriter.preserveOriginal(target,previous.stamp(),prefix+previous.stamp().sha256(),prefix,ArchiveWriter.FORCED_FILES);
                    }
                    diagnostics.at(SaveDiagnostics.Stage.ENCODE);
                    ArchiveWriter.utf8(target,MAX_BYTES,"ghost-",diagnostics,ArchiveWriter.FORCED_FILES,
                            file->ArchiveWriter.verifyStamp(file,previous.stamp(),LinkOption.NOFOLLOW_LINKS),writer->JSON.toJson(lap,writer));
                    accepted=new BestWrite(Saved.SAVED,lap);
                }
            }
        }catch(IOException | RuntimeException error) {
            if(accepted==null) {
                diagnostics.failed(error);
                warning=error instanceof NewerGhost?NEWER_WARNING:"最佳圈保存失败，之前的纪录保留。";
                return new BestWrite(Saved.FAILED,null);
            }
            diagnostics.cleanupFailed(error);
        }
        warning="";
        clearCoveredPending(accepted.published());
        return accepted;
    }
}
