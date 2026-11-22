package com.f1car.persistence;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One complete candidate archive and its actual accepted baseline; reads never restore or clear it. */
final class TuningRecoveryFile {
    interface Filesystem extends ArchiveWriter.Filesystem {
        @Override default void write(Path path,byte[] bytes) throws IOException {ArchiveWriter.writeAndForce(path,bytes);}
    }
    private final Path file,lock;private final Filesystem filesystem;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("setup-trials-recovery");
    private TuningStore.Recovery entry;private ArchiveWriter.Stamp expected=ArchiveWriter.ABSENT;private String warning="";private boolean blocked;
    TuningRecoveryFile(Path directory,Filesystem filesystem) {file=directory.resolve("setup-trials-pending.json");lock=directory.resolve("setup-trials-pending.lock");this.filesystem=Objects.requireNonNull(filesystem);reload();}
    TuningStore.Recovery entry() {return entry;}boolean blocked() {return blocked;}String warning() {return warning;}SaveDiagnostics.Failure failure() {return diagnostics.failure();}
    boolean reload() {
        try {byte[] bytes=read();var next=bytes==null?null:TuningRecoveryCodec.decode(bytes);entry=next;expected=stamp(bytes);blocked=false;warning="";return true;}
        catch(IOException | RuntimeException | ReflectiveOperationException error) {blocked=true;warning="普通报告恢复副本无法读取；原文件与已读内容保持，修复后明确重读。";return false;}
    }
    boolean verify(TuningStore.Recovery reviewed) {
        if(blocked || !Objects.equals(entry,reviewed)) return false;
        try {verifyWriteBase();return true;}
        catch(IOException | RuntimeException error) {warning="磁盘报告恢复副本已改变；两边内容保持，明确重读后重新核对。";return false;}
    }
    boolean store(TuningStore.Recovery next) {
        return store(next,ArchiveWriter.NO_CHECK);
    }
    boolean store(TuningStore.Recovery next,ArchiveWriter.ReplacementCheck check) {
        if(blocked) return false;if(Objects.equals(entry,next)) return verify(next);
        if(next!=null && entry!=null) {warning="已有另一份普通报告恢复候选；本次内存与原副本保持，请先明确核对。";return false;}
        diagnostics.begin();
        boolean committed=false;
        try {
            byte[] bytes=TuningRecoveryCodec.encode(next);diagnostics.at(SaveDiagnostics.Stage.PREPARE);Files.createDirectories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Setup recovery is busy");
                verifyWriteBase();
                ArchiveWriter.bytes(file,bytes,TuningRecoveryCodec.MAX_BYTES,"setup-recovery-",diagnostics,filesystem,target->{
                    verifyWriteBase();
                    check.verify(target);
                });
                expected=stamp(bytes);entry=next;warning="";committed=true;
            }
            return true;
        }catch(IOException | RuntimeException error) {
            if(committed) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);warning=blocked?"磁盘恢复副本已改变；原候选保持，请明确重读。":"普通报告恢复副本写入／清理未完成；内存候选保持，请明确重试。";return false;
        }
    }
    private void verifyWriteBase() throws IOException {
        try {
            ArchiveWriter.verifyStamp(file,expected,LinkOption.NOFOLLOW_LINKS);
        } catch(IOException | RuntimeException error) {
            blocked=true;
            throw error;
        }
    }
    private byte[] read() throws IOException {
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) return null;
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid setup recovery path");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {byte[] bytes=input.readNBytes(TuningRecoveryCodec.MAX_BYTES+1);if(bytes.length>TuningRecoveryCodec.MAX_BYTES) throw new IOException("Setup recovery exceeds bound");return bytes;}
    }
    private static ArchiveWriter.Stamp stamp(byte[] bytes) {return bytes==null?ArchiveWriter.ABSENT:ArchiveWriter.stamp(bytes);}
}
