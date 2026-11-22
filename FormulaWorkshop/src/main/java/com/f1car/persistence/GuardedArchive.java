package com.f1car.persistence;

import com.google.gson.Gson;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Protects a loaded archive while its owning store keeps a candidate in memory. */
final class GuardedArchive {
    @FunctionalInterface
    private interface Publication {ArchiveWriter.Stamp write(ArchiveWriter.ReplacementCheck check) throws IOException;}
    @FunctionalInterface
    interface Decoder<T> {T read(InputStream source) throws IOException;}
    private final Path file;
    private final int limit;
    private final String name;
    private final String sourceBackupPrefix;
    private final SaveDiagnostics diagnostics;
    private final ArchiveWriter.Filesystem filesystem;
    private ArchiveWriter.Stamp expected;

    GuardedArchive(Path directory, String name, int limit) {
        this(directory, name, limit, new SaveDiagnostics(name));
    }

    GuardedArchive(Path directory, String name, int limit, SaveDiagnostics diagnostics) {
        this(directory, name, limit, diagnostics, ArchiveWriter.FORCED_FILES, name + ".backup-");
    }

    GuardedArchive(Path directory, String name, int limit, SaveDiagnostics diagnostics,
                   ArchiveWriter.Filesystem filesystem, String sourceBackupPrefix) {
        this.file = directory.toAbsolutePath().resolve(name + ".json");
        this.name = name;
        this.limit = limit;
        this.diagnostics = diagnostics;
        this.filesystem = filesystem;
        this.sourceBackupPrefix = sourceBackupPrefix;
    }

    String read() throws IOException {
        byte[] bytes = readSource();
        return bytes == null ? null : StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    static final class NewerVersionException extends IOException {}

    /** Check the root version before decoding fields whose types may have changed. */
    <T> T readVersioned(Gson gson, Class<T> type, int supported) throws IOException {
        String source = read();
        if (source == null) return null;
        try (var reader = gson.newJsonReader(new StringReader(source))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if ("version".equals(reader.nextName())
                        && (reader.peek() == JsonToken.NUMBER || reader.peek() == JsonToken.STRING)) {
                    String value = reader.nextString();
                    try {
                        if (new BigDecimal(value).compareTo(BigDecimal.valueOf(supported)) > 0)
                            throw new NewerVersionException();
                    } catch (NumberFormatException ignored) {
                        // The settings decoder handles a malformed current version.
                    }
                } else reader.skipValue();
            }
            reader.endObject();
        }
        return Objects.requireNonNull(gson.fromJson(source, type));
    }

    byte[] readSource() throws IOException {
        return readBytes(false);
    }

    /** Allows a store to display bounded read diagnostics for an oversized archive. */
    byte[] readPrefix() throws IOException {
        return readBytes(true);
    }

    private byte[] readBytes(boolean allowOversizedPrefix) throws IOException {
        expected = null;
        if (absent()) {
            expected = ArchiveWriter.ABSENT;
            return null;
        }
        var attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile()) throw new IOException("Invalid archive file");
        if(!allowOversizedPrefix && attributes.size()>limit) throw new IOException("Archive exceeds size bound");
        byte[] bytes;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            bytes=input.readNBytes(limit+1);
        }
        if(bytes.length<=limit) expected=ArchiveWriter.stamp(bytes);
        else if(!allowOversizedPrefix) throw new IOException("Archive exceeds size bound");
        return bytes;
    }

    <T> T readStream(Decoder<T> decoder) throws IOException {
        expected = null;
        if (absent()) {
            expected = ArchiveWriter.ABSENT;
            return null;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid archive file");
        if (Files.size(file) > limit) throw new IOException("Archive exceeds size bound");
        T value;
        ArchiveWriter.Stamp accepted;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var stream = new ObservedInput(input, limit);
            value = decoder.read(stream);
            if (stream.read() != -1) throw new IOException("Archive decoder did not consume the whole file");
            accepted = stream.stamp();
        }
        expected = accepted;
        return value;
    }

    /** Fingerprint from the most recent complete read, including a decoder's explicit invalid result. */
    ArchiveWriter.Stamp observedStamp() {return expected;}

    private boolean absent() {
        return Files.notExists(file,LinkOption.NOFOLLOW_LINKS)
            || Files.isRegularFile(file.getParent(),LinkOption.NOFOLLOW_LINKS);
    }

    /** The surrounding archive read owns the file, even when a decoder closes its own reader. */
    private static final class ObservedInput extends InputStream {
        private final InputStream source;
        private final int maximum;
        private final MessageDigest hash = ArchiveWriter.digest();
        private int consumed;
        private IOException failure;

        ObservedInput(InputStream source, int maximum) {
            this.source = source;
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            try {
                int value = source.read();
                if (value >= 0) {
                    advance(1);
                    hash.update((byte)value);
                }
                return value;
            } catch(IOException error) {
                failure=error;
                throw error;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) return 0;
            try {
                int count = source.read(buffer, offset, (int)Math.min(length, (long)maximum - consumed + 1));
                if (count > 0) {
                    advance(count);
                    hash.update(buffer, offset, count);
                }
                return count;
            } catch(IOException error) {
                failure=error;
                throw error;
            }
        }

        @Override
        public void close() {
            // The owning archive checks for EOF even after a decoder closes its reader.
        }

        private void advance(int count) throws IOException {
            if (count > maximum - consumed) throw new IOException("Archive exceeds size bound");
            consumed += count;
        }

        ArchiveWriter.Stamp stamp() throws IOException {
            if(failure!=null) throw failure;
            return new ArchiveWriter.Stamp(consumed, HexFormat.of().formatHex(hash.digest()));
        }
    }

    void verifyAccepted() throws IOException {
        if (expected == null) throw new IOException("The original archive could not be verified");
        ArchiveWriter.verifyStamp(file, expected, LinkOption.NOFOLLOW_LINKS);
    }

    /** A removed source allows a pending edit to be published as a new file. */
    boolean acceptMissingSource() {
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        expected = ArchiveWriter.ABSENT;
        return true;
    }

    void commit(String text, boolean preserveSource, String... migrationBackups) throws IOException {
        commit(text.getBytes(StandardCharsets.UTF_8), preserveSource, migrationBackups);
    }

    void commit(byte[] bytes, boolean preserveSource, String... migrationBackups) throws IOException {
        if (bytes.length > limit) {
            diagnostics.begin();
            diagnostics.encoded(bytes.length);
            var failure=new IOException("Archive exceeds size bound");
            diagnostics.failed(failure);
            throw failure;
        }
        commit(preserveSource,check->{
            ArchiveWriter.bytes(file,bytes,limit,name+"-",diagnostics,filesystem,check);
            return ArchiveWriter.stamp(bytes);
        },migrationBackups);
    }

    void commit(ArchiveWriter.TextWriter text, boolean preserveSource, String... migrationBackups) throws IOException {
        commit(preserveSource,check->ArchiveWriter.utf8(file,limit,name+"-",diagnostics,filesystem,check,text),migrationBackups);
    }

    private void commit(boolean preserveSource,Publication publication,String... migrationBackups) throws IOException {
        diagnostics.begin();
        boolean published = false;
        try {
            if (expected == null) throw new IOException("The original archive could not be verified");
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            Files.createDirectories(file.getParent());
            var lockFile = file.resolveSibling(name + ".lock");
            try (var channel = ArchiveWriter.openLock(lockFile); var held = channel.tryLock()) {
                if (held == null) throw new IOException("Archive is busy");
                if (!ArchiveWriter.ABSENT.equals(expected) && preserveSource) {
                    diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                    backup(sourceBackupPrefix + expected.sha256() + ".json", true);
                } else if (!ArchiveWriter.ABSENT.equals(expected)) {
                    for (String migrationBackup : migrationBackups) {
                        if (migrationBackup == null) continue;
                        diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                        backup(migrationBackup, false);
                    }
                }
                var stamp=publication.write(path -> ArchiveWriter.verifyStamp(path, expected, LinkOption.NOFOLLOW_LINKS));
                published = true;
                expected = stamp;
            }
        } catch (IOException | RuntimeException error) {
            if (published) {
                diagnostics.cleanupFailed(error);
                return;
            }
            diagnostics.failed(error);
            throw error;
        }
    }

    private void backup(String name, boolean verifyExisting) throws IOException {
        Path backup = file.resolveSibling(name);
        if (Files.notExists(backup, LinkOption.NOFOLLOW_LINKS)) {
            ArchiveWriter.backup(file, backup, expected, limit, this.name + "-backup-", new SaveDiagnostics(this.name + "-backup"));
        } else if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid backup file");
        }
        if (verifyExisting) {
            try {
                ArchiveWriter.verifyStamp(backup, expected, LinkOption.NOFOLLOW_LINKS);
            } catch (ArchiveWriter.StaleArchiveException mismatch) {
                throw new IOException("Backup differs from the original archive", mismatch);
            }
        }
    }

}
