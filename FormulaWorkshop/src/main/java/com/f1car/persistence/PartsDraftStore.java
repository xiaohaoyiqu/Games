package com.f1car.persistence;

import com.f1car.model.PartsWorkbench;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** One recoverable working session. Rendering does no I/O; cooperating writers lock and compare bytes. */
public final class PartsDraftStore {
    public static final int MAX_BYTES=524288,RECOVERY_BYTES=2097152,MAX_BACKUPS=8;
    private record Archive(int version,PartsWorkbench.State state) {
        private Archive {if(version!=1 || state==null) throw new IllegalArgumentException("Invalid parts editing archive");}
    }
    public record Stamp(String sha256,int bytes) {
        public Stamp {if(sha256==null || !sha256.equals("absent") && !sha256.matches("[a-f0-9]{64}") || bytes<0 || bytes>RECOVERY_BYTES) throw new IllegalArgumentException("Invalid draft disk stamp");}
    }
    private static final class Conflict extends IOException {}
    private static final class BackupLimit extends IOException {}
    private final Path file,lock;
    private final Gson gson=new Gson();
    private PartsWorkbench.State saved,pending;
    private Stamp expected=new Stamp("absent",0);
    private boolean blocked,failed,restored;
    private double idle,age;
    private int attempts,writes;
    private String warning="";
    public PartsDraftStore(Path directory) {
        file=directory.resolve("parts-editing.json");lock=directory.resolve("parts-editing.lock");
        try {
            byte[] bytes=read(MAX_BYTES);expected=stamp(bytes);if(bytes==null) return;
            String source=new String(bytes,StandardCharsets.UTF_8);CreationStore.depth(source);
            saved=Objects.requireNonNull(gson.fromJson(source,Archive.class)).state();restored=true;
        } catch(IOException | RuntimeException error) {blocked=true;warning="编辑恢复文件无法读取；原文件保留，自动保存已暂停。";}
    }
    public PartsWorkbench.State saved() {return saved;}
    public boolean pending() {return pending!=null;}
    public boolean needsBackup() {return blocked;}
    public String warning() {return warning;}
    public int writeAttempts() {return attempts;}
    public int successfulWrites() {return writes;}
    public String status() {
        if(blocked) return "编辑恢复已暂停 · 查看并保留两份";
        if(failed) return "本次编辑尚未落盘 · 重试保存";
        if(pending!=null) return "编辑恢复：等待自动保存";
        return saved==null?"编辑恢复：修改后自动保存":restored?"编辑恢复：已恢复上次编辑":"编辑恢复：已自动保存";
    }
    public void capture(PartsWorkbench.State state) {
        Objects.requireNonNull(state);
        if(state.equals(saved)) {pending=null;idle=age=0;if(!blocked) {failed=false;warning="";}return;}
        if(state.equals(pending)) return;
        if(pending==null) age=0;pending=state;idle=0;
    }
    public void tick(double dt) {
        if(!Double.isFinite(dt) || dt<0) throw new IllegalArgumentException("Invalid autosave delta");
        if(pending==null || blocked || failed) return;
        idle+=dt;age+=dt;if(idle>=.5 || age>=2) flush();
    }
    public boolean flush() {return pending==null || !blocked && write(null);}
    public boolean retry() {failed=false;return flush();}
    /** Called only on opening the recovery review, never from render/update. */
    public Stamp inspectDisk() throws IOException {return stamp(read(RECOVERY_BYTES));}
    /** Explicitly preserve the reviewed disk bytes before replacing them with this working session. */
    public boolean backupAndSave(Stamp reviewed,PartsWorkbench.State state) {Objects.requireNonNull(reviewed);pending=Objects.requireNonNull(state);return write(reviewed);}
    private boolean write(Stamp reviewed) {
        attempts++;Path backup=null;
        try {
            Files.createDirectories(file.getParent());
            if(Files.isSymbolicLink(lock)) throw new IOException("Invalid editing lock path");
            try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Editing file is busy");
                byte[] original=read(RECOVERY_BYTES);Stamp actual=stamp(original);
                if(!actual.equals(reviewed==null?expected:reviewed)) throw new Conflict();
                String source=gson.toJson(new Archive(1,pending));byte[] encoded=source.getBytes(StandardCharsets.UTF_8);
                if(encoded.length>MAX_BYTES) throw new IOException("Editing archive exceeds bound");
                if(reviewed!=null && original!=null) {
                    long count;try(var files=Files.list(file.getParent())) {count=files.filter(p->p.getFileName().toString().startsWith("parts-editing.recovery-") && p.getFileName().toString().endsWith(".json")).count();}
                    if(count>=MAX_BACKUPS) throw new BackupLimit();
                    backup=file.resolveSibling("parts-editing.recovery-"+UUID.randomUUID()+".json");Files.write(backup,original,StandardOpenOption.CREATE_NEW);
                }
                PartsStore.write(file,source,MAX_BYTES);
                expected=stamp(encoded);saved=pending;pending=null;blocked=failed=restored=false;warning="";idle=age=0;writes++;return true;
            }
        } catch(Conflict error) {blocked=true;warning="磁盘上的编辑恢复文件已改变；本次编辑保留，请查看恢复选项。";return false;}
        catch(BackupLimit error) {failed=true;warning="已有八份恢复备份；请先另存这些备份，再重试。";return false;}
        catch(IOException | RuntimeException error) {failed=true;warning=backup==null?"本次编辑尚未自动保存；原文件与本次草稿保留，可重试。":"原文件已备份，本次编辑仍未落盘；请检查目录后重试。";return false;}
    }
    private byte[] read(int limit) throws IOException {
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) return null;
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid editing file path");
        try(var input=Files.newInputStream(file)) {byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit) throw new IOException("Editing file exceeds bound");return bytes;}
    }
    private static Stamp stamp(byte[] bytes) {
        if(bytes==null) return new Stamp("absent",0);
        try {return new Stamp(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length);}
        catch(NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
}
