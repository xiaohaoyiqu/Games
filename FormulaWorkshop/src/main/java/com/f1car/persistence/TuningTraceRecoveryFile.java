package com.f1car.persistence;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Explicit original-trace recovery; content stamps and a cooperating lock guard sidecar replacement. */
final class TuningTraceRecoveryFile {
    interface Filesystem extends ArchiveWriter.Filesystem {
        @Override default void write(Path path,byte[] bytes) throws IOException {ArchiveWriter.writeAndForce(path,bytes);}
    }
    private final Path file,lock;private final Filesystem filesystem;private final SaveDiagnostics diagnostics;
    private SaveDiagnostics.Timing writeTiming,clearTiming;
    private TuningTraceStore.Recovery entry;private ArchiveWriter.Stamp expected=ArchiveWriter.ABSENT;private String warning="";private boolean blocked;
    TuningTraceRecoveryFile(Path directory,Filesystem filesystem,LongSupplier nanoTime) {file=directory.resolve("trial-traces-pending.json");lock=directory.resolve("trial-traces-pending.lock");this.filesystem=Objects.requireNonNull(filesystem);diagnostics=new SaveDiagnostics("trial-traces-recovery",nanoTime);reload();}
    TuningTraceStore.Recovery entry() {return entry;}boolean blocked() {return blocked;}String warning() {return warning;}SaveDiagnostics.Failure failure() {return diagnostics.failure();}
    SaveDiagnostics.Timing writeTiming() {return writeTiming;}
    SaveDiagnostics.Timing clearTiming() {return clearTiming;}
    boolean reload() {try {byte[] bytes=read();var next=bytes==null?null:TuningTraceRecoveryCodec.decode(bytes);entry=next;expected=stamp(bytes);blocked=false;warning="";return true;}catch(IOException | RuntimeException | ReflectiveOperationException error) {blocked=true;warning="原曲线恢复副本无法读取；原文件与已读内容保持，请修复后明确重读。";return false;}}
    boolean verify(TuningTraceStore.Recovery value) {
        if(blocked || !Objects.equals(entry,value)) return false;
        try {verifyWriteBase();return true;}
        catch(IOException | RuntimeException error) {warning="磁盘原曲线副本已变化；两边内容保持，请明确重读。";return false;}
    }
    boolean store(TuningTraceStore.Recovery next) {
        return store(next,ArchiveWriter.NO_CHECK);
    }
    boolean store(TuningTraceStore.Recovery next,ArchiveWriter.ReplacementCheck check) {
        if(blocked) return false;if(Objects.equals(entry,next)) return verify(next);if(next!=null && entry!=null) {warning="已有另一份原曲线副本；本次内存与原副本保持，请先核对。";return false;}diagnostics.begin();
        boolean committed=false;
        try {
            byte[] bytes;
            try(var work=diagnostics.measure(SaveDiagnostics.Work.ENCODE)) {bytes=TuningTraceRecoveryCodec.encode(next);}
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);Files.createDirectories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Trace recovery busy");
                try(var work=diagnostics.measure(SaveDiagnostics.Work.VERIFY)) {verifyWriteBase();}
                ArchiveWriter.bytes(file,bytes,TuningTraceRecoveryCodec.MAX_BYTES,"trace-recovery-",diagnostics,filesystem,target->{
                    verifyWriteBase();
                    check.verify(target);
                });
                expected=stamp(bytes);entry=next;warning="";committed=true;
            }
            return true;
        }catch(IOException | RuntimeException error) {
            if(committed) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);warning=blocked?"磁盘原曲线副本已变化，请明确重读；候选保持。":"原曲线恢复副本保存／清理未完成；本次候选保持，请明确重试。";return false;
        } finally {
            if(next==null) clearTiming=diagnostics.finish();
            else writeTiming=diagnostics.finish();
        }
    }
    private void verifyWriteBase() throws IOException {
        try {
            ArchiveWriter.verifyStamp(file,expected,LinkOption.NOFOLLOW_LINKS);
        } catch(IOException | RuntimeException error) {
            blocked=true;throw error;
        }
    }
    private byte[] read() throws IOException {if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) return null;if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid trace recovery path");try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {byte[] bytes=input.readNBytes(TuningTraceRecoveryCodec.MAX_BYTES+1);if(bytes.length>TuningTraceRecoveryCodec.MAX_BYTES) throw new IOException("Trace recovery exceeds bound");return bytes;}}
    private static ArchiveWriter.Stamp stamp(byte[] bytes) {return bytes==null?ArchiveWriter.ABSENT:ArchiveWriter.stamp(bytes);}
}
