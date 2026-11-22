package com.f1car.persistence;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One bounded local working copy, with cooperative locking and reviewed conflict backups. */
public final class EditorDraftStore<T> {
    public static final int MAX_BYTES=2_000_000,RECOVERY_BYTES=4_000_000,MAX_BACKUPS=8;
    public interface Codec<T> {JsonElement encode(T value);T decode(JsonElement value) throws IOException;}
    public record Stamp(String digest,int bytes) {}
    private final Path file,lock;
    private final String name;
    private final SaveDiagnostics diagnostics;
    private final Class<T> type;
    private final Codec<T> codec;
    private final int maxBytes,recoveryBytes,maximumDepth;
    private final Gson gson=new GsonBuilder().serializeNulls().create();
    private T current,saved;
    private Stamp expected=new Stamp("absent",0);
    private boolean pending,blocked,failed,restored;
    private double idle,age;
    private String warning="";
    public EditorDraftStore(Path directory,String name,Class<T> type) {
        this(directory,name,Objects.requireNonNull(type),null,MAX_BYTES,RECOVERY_BYTES,18);
    }
    public EditorDraftStore(Path directory,String name,Codec<T> codec,int maxBytes,int recoveryBytes,int maximumDepth) {
        this(directory,name,null,Objects.requireNonNull(codec),maxBytes,recoveryBytes,maximumDepth);
    }
    private EditorDraftStore(Path directory,String name,Class<T> type,Codec<T> codec,int maxBytes,int recoveryBytes,int maximumDepth) {
        if(!name.matches("[a-z][a-z-]{0,60}")) throw new IllegalArgumentException("Invalid editor draft name");
        if(maxBytes<1 || maxBytes>8_000_000 || recoveryBytes<maxBytes || recoveryBytes>16_000_000 || maximumDepth<18 || maximumDepth>32) throw new IllegalArgumentException("Invalid bounded draft limits");
        this.type=type;this.codec=codec;this.maxBytes=maxBytes;this.recoveryBytes=recoveryBytes;this.maximumDepth=maximumDepth;
        this.name = name;
        diagnostics = new SaveDiagnostics(name);
        file=directory.resolve(name+".json");lock=directory.resolve(name+".lock");
        try {
            byte[] bytes=read(maxBytes);expected=stamp(bytes);if(bytes==null) return;
            String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JsonObject root;
            try(var reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(source))) {
                reader.setStrictness(Strictness.STRICT);
                root=TrialTraceCodec.bounded(reader,0,new int[]{0},maximumDepth,true).getAsJsonObject();
                if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("Trailing draft content");
            }
            if(!root.keySet().equals(Set.of("version","draft")) || !root.get("version").toString().equals("1")) throw new IOException("Invalid draft format");
            if(codec==null) {ActivityCardCodec.validate(root.get("draft"),type);saved=current=gson.fromJson(root.get("draft"),type);}
            else saved=current=root.get("draft").isJsonNull()?null:codec.decode(root.get("draft"));
            restored=true;
        } catch(IOException | RuntimeException | ReflectiveOperationException error) {blocked=true;warning="恢复文件无法读取；原文件保留，自动保存暂停。";}
    }
    public T current() {return current;}
    public Path file() {return file;}
    public boolean pending() {return pending;}
    public boolean needsBackup() {return blocked;}
    public String warning() {return warning;}
    public String status() {return blocked?"恢复暂停 · 查看并保留两份":failed?"草稿未落盘 · 重试":pending?"草稿等待保存":restored?"已恢复上次草稿":"草稿恢复已保存";}
    public void capture(T value) {
        if(Objects.equals(current,value)) return;
        if(!pending) age=0;current=value;pending=!Objects.equals(saved,current);idle=0;
        if(!pending && !blocked) {failed=false;warning="";}
    }
    public void tick(double dt) {
        if(!Double.isFinite(dt) || dt<0) throw new IllegalArgumentException("Invalid draft delta");
        if(!pending || blocked || failed) return;idle+=dt;age+=dt;if(idle>=.5 || age>=2) flush();
    }
    public boolean flush() {return !pending || !blocked && write(null,null);}
    public boolean retry() {failed=false;return flush();}
    public Stamp inspectDisk() throws IOException {return stamp(read(recoveryBytes));}
    public boolean backupAndSave(Stamp reviewed,T value) {Objects.requireNonNull(reviewed);current=value;pending=true;return write(reviewed,null);}
    boolean commitAfter(T replacement,BooleanSupplier publishRelated) {
        Objects.requireNonNull(publishRelated);
        capture(replacement);
        return pending && !blocked && write(null,publishRelated);
    }
    private boolean write(Stamp reviewed,BooleanSupplier publishRelated) {
        diagnostics.begin();
        boolean published = false;
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            Files.createDirectories(file.getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Draft is busy");
                byte[] original=read(recoveryBytes);var actual=stamp(original);
                if(!actual.equals(reviewed==null?expected:reviewed)) throw new ArchiveWriter.StaleArchiveException();
                var disk = original == null ? ArchiveWriter.ABSENT : new ArchiveWriter.Stamp(actual.bytes(), actual.digest());
                diagnostics.at(SaveDiagnostics.Stage.ENCODE);
                var root=new JsonObject();root.addProperty("version",1);root.add("draft",codec==null?gson.toJsonTree(current,type):current==null?JsonNull.INSTANCE:codec.encode(current));
                byte[] encoded=gson.toJson(root).getBytes(StandardCharsets.UTF_8);
                if(encoded.length>maxBytes) throw new IOException("Draft exceeds bound");
                if(reviewed!=null && original!=null) {
                    diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                    backup(original, disk);
                }
                boolean[] relatedSaved={false};
                ArchiveWriter.bytes(file, encoded, maxBytes, name + "-", diagnostics, ArchiveWriter.FORCED_FILES,
                        target -> {
                            ArchiveWriter.verifyStamp(target,disk,LinkOption.NOFOLLOW_LINKS);
                            if(publishRelated!=null && !relatedSaved[0]) {
                                if(!publishRelated.getAsBoolean()) throw new IOException("Related save failed");
                                relatedSaved[0]=true;
                                ArchiveWriter.verifyStamp(target,disk,LinkOption.NOFOLLOW_LINKS);
                            }
                        });
                published = true;
                expected=stamp(encoded);saved=current;pending=blocked=failed=restored=false;warning="";idle=age=0;
            }
            return true;
        } catch (ArchiveWriter.StaleArchiveException changed) {
            diagnostics.failed(changed);
            blocked = true;
            warning = "磁盘草稿已改变；本次编辑保留，请查看并保留两份。";
            return false;
        } catch(IOException | RuntimeException error) {
            if (published) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);
            failed=true;warning="草稿未落盘，本次编辑保留；请检查目录或恢复备份后重试。";return false;
        }
    }

    private void backup(byte[] original, ArchiveWriter.Stamp disk) throws IOException {
        String prefix = name + ".recovery-";
        Path backup = file.resolveSibling(prefix + disk.sha256() + ".json");
        if (Files.notExists(backup, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(file.getParent())) {
                if (files.filter(path -> path.getFileName().toString().startsWith(prefix)).count() >= MAX_BACKUPS)
                    throw new IOException("Recovery backup limit reached");
            }
            ArchiveWriter.bytes(backup, original, recoveryBytes, name + "-recovery-", new SaveDiagnostics(name + "-recovery"),
                    ArchiveWriter.NEW_FILES);
        }
        ArchiveWriter.verifyStamp(backup, disk, LinkOption.NOFOLLOW_LINKS);
    }
    private byte[] read(int bound) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid draft path");
        try(var input=Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {byte[] bytes=input.readNBytes(bound+1);if(bytes.length>bound) throw new IOException("Draft exceeds read bound");return bytes;}
        catch(NoSuchFileException missing) {return null;}
    }
    private static Stamp stamp(byte[] bytes) {
        if(bytes==null) return new Stamp("absent",0);
        var stamp = ArchiveWriter.stamp(bytes);
        return new Stamp(stamp.sha256(), bytes.length);
    }
}
