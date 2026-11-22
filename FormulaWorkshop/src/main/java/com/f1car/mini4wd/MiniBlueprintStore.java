package com.f1car.mini4wd;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Six independent complete-car snapshots, separate from the active assembly and track records. */
public final class MiniBlueprintStore {
    public static final int SLOTS=6;
    private static final int MAX_BYTES = 64_000;
    public record Blueprint(String name,MiniSetup setup) {
        public Blueprint {
            if(name==null || name.isBlank() || name.length()>40 || name.codePoints().anyMatch(Character::isISOControl)
                || setup==null || !setup.ready()) throw new IllegalArgumentException("Invalid complete-car blueprint");
            name=name.strip();
        }
    }
    private record Document(int version,List<Blueprint> slots) { }
    private static final Gson JSON=new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();
    private final Path file, lock;
    private List<Blueprint> slots=new ArrayList<>(Collections.nCopies(SLOTS,null));
    private boolean writable=true;
    private String warning="";
    public MiniBlueprintStore(Path data) {
        file=data.resolve("mini4wd/blueprints.json");
        lock = file.resolveSibling("blueprints.lock");
        reload();
    }

    public void reload() {
        try {
            slots = decode(read());
            writable = true;
            warning = "";
        } catch(IOException | RuntimeException error) { writable=false;warning="装配蓝图无法读取，原文件保留；请恢复文件后再保存。"; }
    }

    private byte[] read() throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid blueprint file");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Blueprint size limit");
            return bytes;
        }
    }

    private static List<Blueprint> decode(byte[] bytes) throws IOException {
        if (bytes == null) return new ArrayList<>(Collections.nCopies(SLOTS, null));
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {if (++depth > 8) throw new IOException("Blueprint nesting limit");}
            else if (c == '}' || c == ']') depth--;
        }
        var document = JSON.fromJson(text, Document.class);
        if (document == null || document.version() != 1 || document.slots() == null || document.slots().size() != SLOTS)
            throw new IOException("Invalid blueprints");
        // Blueprint snapshots require every current setup field. Profile defaults are only for migration.
        if (!JSON.toJsonTree(document).equals(JsonParser.parseString(text))) throw new IOException("Noncanonical blueprint data");
        return new ArrayList<>(document.slots());
    }
    public Blueprint get(int slot) { return slots.get(slot); }
    public String warning() { return warning; }
    public boolean matchesSaved(int slot, Blueprint expected) {
        if (expected == null || !expected.equals(get(slot))) return false;
        try {
            if (expected.equals(decode(read()).get(slot))) return true;
            warning = "所选蓝图位已由另一窗口更新，请重新读取并核对。";
        } catch (IOException | RuntimeException error) {
            warning = "无法核对所选蓝图，原文件和当前车辆保留。";
        }
        return false;
    }

    public boolean save(int slot,String name,MiniSetup setup) {
        Objects.checkIndex(slot, SLOTS);
        var blueprint = new Blueprint(name, setup);
        if (!writable) return false;
        boolean committed = false;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid blueprint lock");
            try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) throw new IOException("Blueprint library is busy");
                byte[] accepted = read();
                var latest = decode(accepted);
                if (!Objects.equals(slots.get(slot), latest.get(slot)) && !blueprint.equals(latest.get(slot))) {
                    warning = "该蓝图位已在另一窗口更新，请重新读取并核对。";
                    return false;
                }
                if (!blueprint.equals(latest.get(slot))) {
                    latest.set(slot, blueprint);
                    publish(accepted, latest);
                }
                slots = latest;
                warning = "";
                committed = true;
            }
            return true;
        } catch(IOException | RuntimeException error) {
            if (committed) return true;
            warning="蓝图保存失败，原蓝图保留。";return false;
        }
    }

    private void publish(byte[] accepted, List<Blueprint> latest) throws IOException {
        byte[] bytes = JSON.toJson(new Document(1, latest)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Blueprint size limit");
        Path temporary = Files.createTempFile(file.getParent(), "blueprints-", ".tmp");
        try {
            try (var writer = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) writer.write(buffer);
                writer.force(true);
            }
            if (!Arrays.equals(accepted, read())) throw new IOException("Blueprint library changed before replacement");
            try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch (AtomicMoveNotSupportedException error) {
                if (!Arrays.equals(accepted, read())) throw new IOException("Blueprint library changed before fallback replacement");
                Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {Files.deleteIfExists(temporary);}
            catch (IOException ignored) { }
        }
    }
}
