package com.f1car.persistence;

import com.google.gson.Gson;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Cosmetic library text is independent of frozen driving conditions and original imports. */
public final class ContentTextStore {
    private static final int MAX_BYTES = 1_048_576;
    public record Text(String name,String author,String notes) {
        public Text {
            if(name==null || name.isBlank() || name.length()>40 || author==null || author.length()>40 || notes==null || notes.length()>320
                    || name.chars().anyMatch(Character::isISOControl) || author.chars().anyMatch(Character::isISOControl) || notes.chars().anyMatch(c->Character.isISOControl(c) && c!='\n')) throw new IllegalArgumentException("Invalid content text");
        }
        public Text name(String value) { return new Text(value.strip(),author,notes); }
        public Text author(String value) { return new Text(name,value.strip(),notes); }
        public Text notes(String value) { return new Text(name,author,value.strip()); }
    }
    private record Entry(Text defaults,Text value) { private Entry { Objects.requireNonNull(defaults);Objects.requireNonNull(value); } }
    private record Data(int version,Map<String,Entry> entries) {
        private Data { if(version!=1 || entries==null || entries.size()>128 || entries.keySet().stream().anyMatch(k->!k.matches("(activity|pack):[a-f0-9-]{36}"))) throw new IllegalArgumentException("Invalid text archive");entries=Collections.unmodifiableMap(new LinkedHashMap<>(entries)); }
    }
    private record Loaded(ArchiveWriter.Stamp stamp, Data data) {}
    private final Path file;
    interface Filesystem extends ArchiveWriter.Filesystem {
        default void backup(Path source,Path target) throws IOException {Files.copy(source,target,LinkOption.NOFOLLOW_LINKS);}
    }
    private final Filesystem filesystem;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("content-text");
    private final Gson gson=new Gson();
    private Data data=new Data(1,Map.of());
    private ArchiveWriter.Stamp accepted;
    private boolean damaged;
    public String warning="";
    public ContentTextStore(Path directory) {this(directory,new Filesystem() {});}
    ContentTextStore(Path directory,Filesystem filesystem) {
        this.filesystem=Objects.requireNonNull(filesystem);
        file=directory.resolve("content-text.json");
        try {
            var loaded = read();
            accepted = loaded.stamp();
            if (loaded.data() == null) throw new IOException("Invalid text archive");
            data = loaded.data();
        } catch(IOException | RuntimeException error) {damaged=true;warning="名称与备注资料无法读取，原文件已保留。";}
    }
    public Text get(String key,Text defaults) { var entry=data.entries().get(key);return entry==null?defaults:entry.value(); }
    public Text defaults(String key,Text defaults) { var entry=data.entries().get(key);return entry==null?defaults:entry.defaults(); }
    public Path file() {return file;}
    public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}
    public boolean put(String key,Text defaults,Text value) {
        diagnostics.begin();
        var reviewed = data.entries().get(key);
        var replacement = new Entry(defaults(key, defaults), value);
        if (reviewed == null && data.entries().size() >= 128) return full();
        boolean committed = false;
        try {
            var next = putInto(data, key, replacement);
            byte[] bytes = encode(next);
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(file.toAbsolutePath().getParent());
            if (accepted == null) throw new IOException("Cannot verify original text archive");
            try (var channel = ArchiveWriter.openLock(file.resolveSibling("content-text.lock")); var held = channel.tryLock()) {
                if (held == null) throw new IOException("Text archive is busy");
                var stamp = accepted;
                if (damaged) {
                    diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                    preserveOriginal(stamp);
                } else {
                    diagnostics.at(SaveDiagnostics.Stage.REPLACE);
                    var current = read();
                    stamp = current.stamp();
                    if (!accepted.equals(stamp)) {
                        if (current.data() == null) throw new ArchiveWriter.StaleArchiveException();
                        var currentEntry = current.data().entries().get(key);
                        data = current.data();
                        accepted = stamp;
                        if (replacement.equals(currentEntry)) {warning = "";committed = true;return true;}
                        if (!Objects.equals(reviewed, currentEntry)) throw new ArchiveWriter.StaleArchiveException();
                        if (currentEntry == null && data.entries().size() >= 128) return full();
                        next = putInto(data, key, replacement);
                        bytes = encode(next);
                    }
                }
                var checkedStamp = stamp;
                ArchiveWriter.bytes(file, bytes, MAX_BYTES, "content-text-", diagnostics, filesystem,
                        target -> ArchiveWriter.verifyStamp(target, checkedStamp, LinkOption.NOFOLLOW_LINKS));
                data = next;
                accepted = ArchiveWriter.stamp(bytes);
                damaged = false;
                warning = "";
                committed = true;
            }
            return true;
        } catch (ArchiveWriter.StaleArchiveException error) {
            diagnostics.failed(error);
            warning = "磁盘上所选名称或备注已变化，请保留输入并重新打开此项核对。";
            return false;
        } catch(IOException | RuntimeException error) {
            if (committed) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);warning="名称或备注未保存，原内容保留，请重试。";return false;
        }
    }

    private static Data putInto(Data original, String key, Entry entry) {
        var entries = new LinkedHashMap<>(original.entries());
        entries.put(key, entry);
        return new Data(1, entries);
    }

    private byte[] encode(Data next) throws IOException {
        diagnostics.at(SaveDiagnostics.Stage.ENCODE);
        byte[] bytes = gson.toJson(next).getBytes(StandardCharsets.UTF_8);
        diagnostics.encoded(bytes.length);
        if (bytes.length > MAX_BYTES) throw new IOException("Text archive too large");
        return bytes;
    }

    private boolean full() {warning = "名称与备注资料已达 128 项上限。";return false;}

    private Loaded read() throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(file.toAbsolutePath().getParent(), LinkOption.NOFOLLOW_LINKS))
            return new Loaded(ArchiveWriter.ABSENT, new Data(1, Map.of()));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid text archive file");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {bytes = input.readNBytes(MAX_BYTES + 1);}
        if (bytes.length > MAX_BYTES) throw new IOException("Text archive too large");
        var stamp = ArchiveWriter.stamp(bytes);
        try {
            String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return new Loaded(stamp, gson.fromJson(source, Data.class));
        } catch (java.nio.charset.CharacterCodingException | RuntimeException invalid) {
            return new Loaded(stamp, null);
        }
    }

    private void preserveOriginal(ArchiveWriter.Stamp stamp) throws IOException {
        ArchiveWriter.verifyStamp(file, stamp, LinkOption.NOFOLLOW_LINKS);
        Path backup = file.resolveSibling("content-text.backup-" + stamp.sha256() + ".json");
        if (Files.notExists(backup, LinkOption.NOFOLLOW_LINKS)) filesystem.backup(file, backup);
        ArchiveWriter.verifyStamp(backup, stamp, LinkOption.NOFOLLOW_LINKS);
    }
}
