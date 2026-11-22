package com.f1car.mini4wd;

import com.f1car.mini4wd.MiniTrack.Kind;
import com.f1car.mini4wd.MiniTrack.Piece;
import com.f1car.persistence.ExportDestination;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class MiniTrackStore {
    private static final int MAX_BYTES=256_000;
    private record Cell(int column, int row, Kind kind, int rotation) {}
    private record Document(int version, String name, List<Cell> pieces, List<MiniShape.Parameters> shapes) {}
    public record MapData(String name, MiniTrack track) {}
    record Revision(String digest, boolean damaged) {}
    record Slot(MapData map, Revision revision) {}
    static final class ChangedSlotException extends IOException {
        ChangedSlotException() {super("The saved map slot changed");}
    }

    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();
    private final Path directory;

    MiniTrackStore(Path profileDirectory) { directory = profileDirectory.resolve("mini4wd"); }

    MiniTrack load(int slot) throws IOException {
        MapData map = loadMap(slot);
        return map == null ? null : map.track;
    }

    MapData loadMap(int slot) throws IOException {
        var source = slot(slot);
        if (source.revision().damaged()) throw new IOException("Damaged map slot");
        return source.map();
    }

    Slot slot(int number) throws IOException {
        byte[] bytes = slotBytes(path(number));
        String digest = digest(bytes);
        if (bytes == null) return new Slot(null, new Revision(digest, false));
        try {
            return new Slot(decode(text(bytes)), new Revision(digest, false));
        } catch (IOException | RuntimeException error) {
            return new Slot(null, new Revision(digest, true));
        }
    }

    public MapData read(Path path) throws IOException {
        if(!Files.isRegularFile(path)) throw new IOException("Choose a track file");
        try(var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES) throw new IOException("Track file is too large");
            return decode(text(bytes));
        }
    }

    public MapData decode(String json) throws IOException {
        if (json.length() > MAX_BYTES) throw new IOException("Track file is too large");
        Document document;
        try { document = gson.fromJson(json, Document.class); }
        catch (RuntimeException e) { throw new IOException("Invalid track JSON", e); }
        if (document == null || document.version < 1 || document.version > 3 || document.pieces == null || document.pieces.size() > MiniTrack.SIZE) {
            throw new IOException("Invalid track format");
        }
        Piece[] pieces = new Piece[MiniTrack.SIZE];
        for (Cell cell : document.pieces) {
            if (cell == null || cell.kind == null || cell.rotation < 0 || cell.rotation > 3) throw new IOException("Invalid track piece");
            int index = MiniTrack.cell(cell.column, cell.row);
            if (index < 0 || pieces[index] != null) throw new IOException("Invalid track coordinates");
            pieces[index] = new Piece(cell.kind, cell.rotation);
        }
        List<MiniShape> shapes = List.of();
        if (document.shapes != null) {
            if (document.shapes.size() > 256) throw new IOException("Too many track shapes");
            shapes = new ArrayList<>(document.shapes.size());
            try {
                for (MiniShape.Parameters parameters : document.shapes) {
                    MiniShape shape = new MiniShape(parameters);
                    if (!shape.fits()) throw new IOException("Track shape is outside the board");
                    shapes.add(shape);
                }
            } catch (IllegalArgumentException | NullPointerException e) { throw new IOException("Invalid track shape", e); }
        }
        return new MapData(cleanName(document.name), MiniTrack.fromPieces(pieces,shapes));
    }

    void save(int slot, MiniTrack track) throws IOException {
        save(slot, track, "自制地图 " + (slot + 1));
    }

    void save(int slot, MiniTrack track, String name) throws IOException {
        write(path(slot), track, name);
    }

    Revision save(int number, MiniTrack track, String name, Revision expected) throws IOException {
        if (expected == null) throw new IOException("Cannot verify the original map slot");
        Path file = path(number).toAbsolutePath();
        Path lock = file.resolveSibling("track-" + (number + 1) + ".lock");
        Files.createDirectories(file.getParent());
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid track lock");
        byte[] candidate = encode(track, name).getBytes(StandardCharsets.UTF_8);
        var revision = new Revision(digest(candidate), false);
        boolean published = false;
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var held = channel.tryLock()) {
            if (held == null) throw new IOException("Track slot is busy");
            byte[] previous = verify(file, expected);
            if (expected.damaged() && previous != null) {
                Path backup = file.resolveSibling("track-" + (number + 1) + ".backup-" + expected.digest() + ".json");
                if (Files.notExists(backup, LinkOption.NOFOLLOW_LINKS)) publish(backup, previous, false, null);
                verify(backup, expected);
            }
            publish(file, candidate, previous != null, target -> verify(target,expected));
            published = true;
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("Track slot is busy", busy);
        } catch (IOException error) {
            if (!published) throw error;
        }
        return revision;
    }

    public String encode(MiniTrack track, String name) {
        List<Cell> pieces = new ArrayList<>();
        for (int i = 0; i < MiniTrack.SIZE; i++) {
            Piece piece = track.piece(i);
            if (piece != null) pieces.add(new Cell(MiniTrack.column(i), MiniTrack.row(i), piece.kind(), piece.rotation()));
        }
        return gson.toJson(new Document(3, cleanName(name), pieces, track.shapes().stream().map(MiniShape::parameters).toList()));
    }

    public void write(Path destination, MiniTrack track, String name) throws IOException {
        write(destination,track,name,true);
    }

    void write(Path destination,MiniTrack track,String name,boolean replace) throws IOException {
        write(destination,track,name,ExportDestination.forWrite(destination,replace));
    }

    void write(Path destination,MiniTrack track,String name,ExportDestination reviewed) throws IOException {
        destination=destination.toAbsolutePath().normalize();
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Choose a regular track file");
        byte[] data=encode(track,name).getBytes(StandardCharsets.UTF_8);
        publish(destination, data, reviewed.replacing(), reviewed::verifyPublication);
    }

    @FunctionalInterface
    private interface PublicationCheck {void verify(Path destination) throws IOException;}

    private static void publish(Path destination, byte[] data, boolean replace, PublicationCheck check) throws IOException {
        if(data.length>MAX_BYTES) throw new IOException("Track file is too large");
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "track-", ".tmp");
        try {
            try (var writer = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) writer.write(buffer);
                writer.force(true);
            }
            if (check != null) check.verify(destination);
            if(replace) {
                try { Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException e) {
                    if (check != null) check.verify(destination);
                    Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);
                }
            } else Files.move(temporary,destination);
        } finally {
            try {Files.deleteIfExists(temporary);}
            catch(IOException ignored) { }
        }
    }

    private static byte[] verify(Path file, Revision expected) throws IOException {
        byte[] bytes = slotBytes(file);
        if (!digest(bytes).equals(expected.digest())) throw new ChangedSlotException();
        return bytes;
    }

    private static byte[] slotBytes(Path file) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid map slot file");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Track file is too large");
            return bytes;
        }
    }

    private static String text(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String digest(byte[] bytes) {
        if (bytes == null) return "absent";
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch (NoSuchAlgorithmException unavailable) {throw new IllegalStateException(unavailable);}
    }

    public static String cleanName(String name) {
        if (name == null || name.isBlank()) return "未命名地图";
        name = name.strip().replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank()) return "未命名地图";
        return name.substring(0, name.offsetByCodePoints(0, Math.min(24, name.codePointCount(0, name.length()))));
    }

    private Path path(int slot) { return directory.resolve("track-" + (slot + 1) + ".json"); }
}
