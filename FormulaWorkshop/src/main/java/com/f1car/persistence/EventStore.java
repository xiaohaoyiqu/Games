package com.f1car.persistence;

import com.f1car.model.EventPendingResult;
import com.f1car.model.EventSeries;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Separate, bounded event checkpoint. Failed writes leave the accepted state unchanged. */
public final class EventStore {
    private static final int MAX_BYTES=262144;
    public enum RecoveryCleanup { CLEARED, MAIN_UNVERIFIED, RECOVERY_RETAINED }
    interface Filesystem extends ArchiveWriter.Filesystem {
        @Override default void write(Path path,byte[] bytes) throws IOException {
            ArchiveWriter.writeAndForce(path,bytes);
        }
    }
    private static final class BaseChanged extends IOException {
        BaseChanged() {super("Event base no longer matches");}
        BaseChanged(Throwable cause) {super("Event base cannot be verified",cause);}
    }

    private final Path file,lock;
    private final Filesystem filesystem;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("race-event");
    private final Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private EventSeries current;
    private ArchiveWriter.Stamp accepted;
    private boolean damaged;
    public String warning="";
    public EventStore(Path directory) {this(directory,new Filesystem() {});}
    EventStore(Path directory,Filesystem filesystem) {
        file=directory.resolve("race-event.json").toAbsolutePath().normalize();
        lock=file.resolveSibling("race-event.lock");
        this.filesystem=Objects.requireNonNull(filesystem);
        reload();
    }
    public EventSeries current() { return current; }
    /** Tracks the last accepted file, including an empty slot or a readable damaged file. */
    public BooleanSupplier watchRevision() {
        var reviewed=accepted;
        return ()->reviewed!=null && reviewed.equals(accepted);
    }
    public SaveDiagnostics.Failure failure() {return diagnostics.failure();}
    public boolean reload() {
        accepted=null;
        try {
            byte[] bytes=readMain(file);
            accepted=ArchiveWriter.stamp(bytes);
            current=new EventSeries(decode(bytes));
        } catch(NoSuchFileException absent) {
            accepted=ArchiveWriter.ABSENT;
            current=null;
        } catch(IOException | RuntimeException error) {
            damaged=true;
            warning=accepted==null?"无法核对赛事主档；请检查文件与读取上限后重读。":
                    "赛事进度无法读取，原文件及已读进度保留；修复后可重读，确认新建时会备份。";
            return false;
        }
        damaged=false;
        warning="";
        return true;
    }
    private static byte[] readMain(Path path) throws IOException {
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile()) throw new IOException("Invalid event save path");
        if(attributes.size()>MAX_BYTES) throw new IOException("Event save too large");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES) throw new IOException("Event save too large");
            return bytes;
        }
    }
    private EventSeries.State decode(byte[] bytes) throws IOException {
        String source=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        var json=com.google.gson.JsonParser.parseString(source).getAsJsonObject();if(!json.has("eventId")) json.addProperty("eventId",java.util.UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString());
        return gson.fromJson(json,EventSeries.State.class);
    }
    /** Check the last read revision, including absence or a readable damaged original. */
    public boolean matchesCurrentFile() {
        try {verifyAccepted(file,accepted);return true;}catch(BaseChanged changed) {return false;}
    }

    /** Explicit operation-time check for the expected event state on disk. */
    public boolean matchesSaved(EventSeries.State expected) {
        if(expected==null) return false;
        try {return expected.equals(decode(readMain(file)));}catch(IOException | RuntimeException error) {return false;}
    }
    public boolean save(EventSeries next) {
        return save(next,null);
    }

    /** Commit an already reviewed change while holding the same lock used by other event writes. */
    public boolean saveIfCurrent(EventSeries.State expected,EventSeries next) {
        return save(next,Objects.requireNonNull(expected));
    }

    /** Keep the main write lock until the exact, published recovery is replaced by its empty marker. */
    public RecoveryCleanup clearPublishedRecovery(EventPendingResult reviewed,EventRecoveryStore recovery) {
        var candidate=Objects.requireNonNull(reviewed).candidate();
        if(!file.getParent().equals(recovery.archiveDirectory()) || damaged
                || current==null || !candidate.equals(current.state())) return RecoveryCleanup.MAIN_UNVERIFIED;
        boolean cleared=false;
        var source=accepted;
        try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
            if(held==null) return RecoveryCleanup.MAIN_UNVERIFIED;
            verifyAccepted(file,source);
            cleared=recovery.clear(reviewed,target->verifyAccepted(file,source));
            if(!cleared) verifyAccepted(file,source);
        } catch(IOException | RuntimeException error) {
            if(!cleared) return RecoveryCleanup.MAIN_UNVERIFIED;
        }
        return cleared?RecoveryCleanup.CLEARED:RecoveryCleanup.RECOVERY_RETAINED;
    }

    private boolean save(EventSeries next,EventSeries.State expected) {
        if(next==null) throw new IllegalArgumentException("Missing event checkpoint");
        diagnostics.begin();
        boolean committed=false;
        var source=accepted;
        try {
            var encoded=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(gson.toJson(next.state())));
            byte[] bytes=new byte[encoded.remaining()];
            encoded.get(bytes);
            diagnostics.encoded(bytes.length);
            if(bytes.length>MAX_BYTES) throw new IOException("Event save too large");

            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(file.getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Event archive is busy");
                if(expected!=null && (damaged || current==null || !expected.equals(current.state()))) throw new BaseChanged();
                ArchiveWriter.ReplacementCheck check=target->verifyAccepted(target,source);
                check.verify(file);
                if(damaged && !ArchiveWriter.ABSENT.equals(source)) {
                    diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                    var backup=file.resolveSibling("race-event.backup-"+source.sha256()+".json");
                    if(Files.notExists(backup,LinkOption.NOFOLLOW_LINKS)) {
                        ArchiveWriter.backup(file,backup,source,MAX_BYTES,"race-event-backup-",
                                new SaveDiagnostics("race-event-backup"));
                    }
                    ArchiveWriter.verifyStamp(backup,source,LinkOption.NOFOLLOW_LINKS);
                }
                var revision=ArchiveWriter.stamp(bytes);
                ArchiveWriter.bytes(file,bytes,MAX_BYTES,"race-event-",diagnostics,filesystem,check);
                current=next;
                accepted=revision;
                damaged=false;
                warning="";
                committed=true;
            }
            return true;
        } catch(IOException | RuntimeException error) {
            if(committed) {
                diagnostics.cleanupFailed(error);
                return true;
            }
            diagnostics.failed(error);
            warning=error instanceof BaseChanged?"赛事主档已改变或无法核对；请明确重读后处理。":
                    "赛事进度保存失败，请检查用户目录；尚未推进阶段，可重试保存。";
            return false;
        }
    }

    private static void verifyAccepted(Path path,ArchiveWriter.Stamp source) throws BaseChanged {
        if(source==null) throw new BaseChanged();
        try {ArchiveWriter.verifyStamp(path,source,LinkOption.NOFOLLOW_LINKS);}
        catch(IOException | RuntimeException error) {throw new BaseChanged(error);}
    }
}
