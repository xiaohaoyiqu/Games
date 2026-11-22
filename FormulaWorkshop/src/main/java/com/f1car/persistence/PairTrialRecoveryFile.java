package com.f1car.persistence;

import com.f1car.model.PairExperiment;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One complete candidate plus its actual accepted base. No publication or automatic recovery on load. */
final class PairTrialRecoveryFile {
    static final int MAX_BYTES=16_010_000;
    interface Filesystem extends ArchiveWriter.Filesystem {
        @Override default void write(Path path,byte[] bytes) throws IOException {
            ArchiveWriter.writeAndForce(path,bytes);
        }
    }
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Path file,lock;private final Filesystem filesystem;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("pair-trials-recovery");
    private PairTrialStore.Recovery entry;
    private ArchiveWriter.Stamp expected=ArchiveWriter.ABSENT;
    private boolean blocked;private String warning="";
    PairTrialRecoveryFile(Path directory) {this(directory,new Filesystem() {});}
    PairTrialRecoveryFile(Path directory,Filesystem filesystem) {
        file=directory.resolve("pair-trials-pending.json");lock=directory.resolve("pair-trials-pending.lock");this.filesystem=Objects.requireNonNull(filesystem);reload();
    }
    Path file() {return file;}PairTrialStore.Recovery entry() {return entry;}boolean blocked() {return blocked;}
    String warning() {return warning;}SaveDiagnostics.Failure failure() {return diagnostics.failure();}
    boolean reload() {
        try {
            byte[] bytes=read();PairTrialStore.Recovery next=null;
            if(bytes!=null) {
                var root=new PartsJson.Node(TuningLapStudyCodec.readTree(bytes,MAX_BYTES,6_100_000),"").object("version","pending");
                root.child("version").integer(1,1);var pending=root.child("pending");
                if(!pending.value().isJsonNull()) {
                    pending.object("base","candidate");var base=snapshot(pending.child("base").value(),true);var candidate=snapshot(pending.child("candidate").value(),false);
                    next=new PairTrialStore.Recovery(base,candidate);
                }
            }
            expected=bytes==null?ArchiveWriter.ABSENT:ArchiveWriter.stamp(bytes);
            entry=next;blocked=false;warning="";return true;
        }catch(IOException | RuntimeException | ReflectiveOperationException error) {
            blocked=true;warning="四条件恢复文件无法读取；原文件和已读候选保持，修复后明确重读。";return false;
        }
    }
    private static PairExperiment.Snapshot snapshot(JsonElement node,boolean nullable) throws IOException,ReflectiveOperationException {
        if(node.isJsonNull()) {if(nullable) return null;throw new IOException("Missing recovery candidate");}
        if(bytes(node).length>PairTrialStore.ENTRY_BYTES) throw new IOException("Recovery report exceeds bound");
        ActivityCardCodec.validate(node,PairExperiment.Snapshot.class);return Objects.requireNonNull(JSON.fromJson(node,PairExperiment.Snapshot.class));
    }
    boolean verify(PairTrialStore.Recovery reviewed) {
        if(blocked || !Objects.equals(entry,reviewed)) return false;
        try {ArchiveWriter.verifyStamp(file,expected,LinkOption.NOFOLLOW_LINKS);return true;}
        catch(IOException | RuntimeException error) {blocked=true;warning="磁盘恢复副本已改变；保留两边内容，明确重读后重新核对。";return false;}
    }
    boolean store(PairTrialStore.Recovery value) {
        if(blocked) return false;
        if(Objects.equals(entry,value)) return verify(value);
        if(value!=null && entry!=null) {warning="已有另一份恢复候选；明确核对原副本后再继续，本次候选保持。";return false;}
        diagnostics.begin();
        boolean published=false;
        try {
            var root=new JsonObject();root.addProperty("version",1);root.add("pending",value==null?JsonNull.INSTANCE:JSON.toJsonTree(value));
            if(value!=null) {if(bytes(JSON.toJsonTree(value.candidate())).length>PairTrialStore.ENTRY_BYTES || value.base()!=null && bytes(JSON.toJsonTree(value.base())).length>PairTrialStore.ENTRY_BYTES) throw new IOException("Recovery report exceeds bound");}
            byte[] encoded=bytes(root);if(encoded.length>MAX_BYTES) throw new IOException("Recovery exceeds bound");
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);Files.createDirectories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Recovery is busy");
                ArchiveWriter.bytes(file,encoded,MAX_BYTES,"pair-recovery-",diagnostics,filesystem,
                    path->ArchiveWriter.verifyStamp(path,expected,LinkOption.NOFOLLOW_LINKS));
                published=true;
                expected=ArchiveWriter.stamp(encoded);entry=value;warning="";return true;
            }
        }catch(IOException | RuntimeException error) {
            if(published) {
                diagnostics.cleanupFailed(error);
                return true;
            }
            if(error instanceof ArchiveWriter.StaleArchiveException) blocked=true;
            diagnostics.failed(error);
            warning=blocked?"磁盘恢复副本已改变；原候选保留，明确重读后重新核对。":"恢复副本尚未写入；本次候选仍在内存，修复后明确重试。";
            return false;
        }
    }
    private byte[] read() throws IOException {
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) return null;
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid recovery path");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {byte[] value=input.readNBytes(MAX_BYTES+1);if(value.length>MAX_BYTES) throw new IOException("Recovery exceeds bound");return value;}
    }
    private static byte[] bytes(JsonElement value) throws java.nio.charset.CharacterCodingException {
        var buffer=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(JSON.toJson(value)));byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;
    }
}
