package com.f1car.persistence;

import com.f1car.model.EventPendingResult;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** One recoverable finished event stage. Reading never publishes or clears a result. */
public final class EventRecoveryStore {
    interface Filesystem extends ArchiveWriter.Filesystem {
        @Override default void write(Path path,byte[] bytes) throws IOException {
            ArchiveWriter.writeAndForce(path,bytes);
        }
    }
    private final Path file,lock;private final Filesystem filesystem;private final SaveDiagnostics diagnostics=new SaveDiagnostics("event-result-recovery");
    private EventPendingResult entry;private String expected="absent",warning="";private boolean blocked;
    public EventRecoveryStore(Path directory) {this(directory,new Filesystem() {});}
    EventRecoveryStore(Path directory,Filesystem filesystem) {file=directory.resolve("race-event-pending.json");lock=directory.resolve("race-event-pending.lock");this.filesystem=Objects.requireNonNull(filesystem);reload();}
    public EventPendingResult entry() {return entry;}public boolean blocked() {return blocked;}public String warning() {return warning;}public SaveDiagnostics.Failure failure() {return diagnostics.failure();}
    Path archiveDirectory() {return file.toAbsolutePath().normalize().getParent();}
    public boolean needsAttention() {return blocked || entry!=null;}
    public boolean reload() {
        try {byte[] bytes=read();var next=bytes==null?null:EventRecoveryCodec.decode(bytes);expected=stamp(bytes);entry=next;blocked=false;warning="";return true;}
        catch(IOException | RuntimeException | ReflectiveOperationException error) {blocked=true;warning="赛事恢复副本无法读取；原文件和已读结果保持，修复后明确重读。";return false;}
    }
    public boolean verify(EventPendingResult reviewed) {
        if(blocked) return false;if(!Objects.equals(entry,reviewed)) {warning="所选赛事恢复副本已变化；当前内容保持，请重新核对。";return false;}
        try {if(!stamp(read()).equals(expected)) throw new IOException("Event recovery changed");return true;}
        catch(IOException | RuntimeException error) {blocked=true;warning="磁盘赛事恢复副本已改变；两边内容保持，明确重读后重新核对。";return false;}
    }
    public boolean capture(EventPendingResult value) {
        Objects.requireNonNull(value);if(blocked) return false;if(value.equals(entry)) return verify(value);
        if(entry!=null) {warning="已有另一份赛事恢复副本；原副本与本次结果都保持，请先核对处理。";return false;}return write(value,ArchiveWriter.NO_CHECK);
    }
    public boolean clear(EventPendingResult reviewed) {return clear(reviewed,ArchiveWriter.NO_CHECK);}
    boolean clear(EventPendingResult reviewed,ArchiveWriter.ReplacementCheck check) {
        return reviewed!=null && verify(reviewed) && write(null,check);
    }
    private boolean write(EventPendingResult next,ArchiveWriter.ReplacementCheck check) {
        if(blocked) return false;diagnostics.begin();
        boolean committed=false;
        try {
            byte[] bytes=EventRecoveryCodec.encode(next);diagnostics.at(SaveDiagnostics.Stage.PREPARE);Files.createDirectories(file.toAbsolutePath().getParent());
            if(Files.isSymbolicLink(lock) || Files.exists(lock,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid event recovery lock");
            try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Event recovery is busy");
                verifyWriteBase();
                ArchiveWriter.bytes(file,bytes,EventRecoveryCodec.MAX_BYTES,"event-recovery-",diagnostics,filesystem,target->{
                    verifyWriteBase();
                    check.verify(target);
                });
                expected=stamp(bytes);entry=next;warning="";committed=true;
            }
            return true;
        }catch(IOException | RuntimeException error) {
            if(committed) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);warning=blocked?"磁盘赛事恢复副本已改变；请明确重读，本次结果保持。":"赛事恢复副本写入／清理失败；原副本与本次内存结果保持，请明确重试。";return false;
        }
    }
    private void verifyWriteBase() throws IOException {
        try {
            if(!stamp(read()).equals(expected)) throw new IOException("Event recovery changed");
        } catch(IOException | RuntimeException error) {
            blocked=true;
            throw error;
        }
    }
    private byte[] read() throws IOException {
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) return null;if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid event recovery path");
        try(var input=Files.newInputStream(file)) {byte[] bytes=input.readNBytes(EventRecoveryCodec.MAX_BYTES+1);if(bytes.length>EventRecoveryCodec.MAX_BYTES) throw new IOException("Event recovery exceeds bound");return bytes;}
    }
    private static String stamp(byte[] bytes) {if(bytes==null) return "absent";try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}}
}
