package com.f1car.persistence;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Same-directory replacement. Cleanup never changes an already successful commit or masks its primary error. */
final class ArchiveWriter {
    @FunctionalInterface
    interface ReplacementCheck {void verify(Path target) throws IOException;}
    @FunctionalInterface
    interface TextWriter {void write(Writer writer) throws IOException;}
    @FunctionalInterface
    private interface StreamWriter {void write(BoundedOutput output) throws IOException;}
    @FunctionalInterface
    private interface Staging<T> {T write(Path path) throws IOException;}

    interface Filesystem {
        default void backup(Path source,Path target,Stamp expected) throws IOException {
            ArchiveWriter.backup(source,target,expected,expected.bytes(),"archive-backup-",new SaveDiagnostics("archive-backup"));
        }
        default void directories(Path path) throws IOException {Files.createDirectories(path);}
        default Path temporary(Path directory,String prefix) throws IOException {return Files.createTempFile(directory,prefix,".tmp");}
        default void write(Path path,byte[] bytes) throws IOException {
            Files.write(path,bytes,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS);
        }
        default void atomic(Path source,Path target) throws IOException {Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        default void replace(Path source,Path target) throws IOException {Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
        default void cleanup(Path path) throws IOException {Files.deleteIfExists(path);}
    }
    private static final Filesystem FILES=new Filesystem() {};
    static final Filesystem FORCED_FILES=new Filesystem() {
        @Override public void write(Path path,byte[] bytes) throws IOException {writeAndForce(path,bytes);}
    };
    static final Filesystem NEW_FILES=new Filesystem() {
        @Override public void write(Path path,byte[] bytes) throws IOException {writeAndForce(path,bytes);}
        @Override public void atomic(Path source,Path target) throws IOException {Files.move(source,target);}
        @Override public void replace(Path source,Path target) throws IOException {Files.move(source,target);}
    };
    static final ReplacementCheck NO_CHECK=target->{};
    record Stamp(long bytes,String sha256) {}
    record Source(byte[] bytes,Stamp stamp) {}
    static final Stamp ABSENT=new Stamp(-1,null);
    static final class StaleArchiveException extends IOException {
        StaleArchiveException() {super("Archive changed before replacement");}
        StaleArchiveException(IOException cause) {super("Archive changed before replacement",cause);}
    }

    static FileChannel openLock(Path path) throws IOException {
        if(Files.isSymbolicLink(path) || Files.exists(path,LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid archive lock");
        return FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    }

    static Stamp stamp(byte[] bytes) {return new Stamp(bytes.length,HexFormat.of().formatHex(digest().digest(bytes)));}

    /** Oversized originals can be identified for repair without allocating their contents. */
    static Source readSource(Path file,int limit,boolean allowOversized) throws IOException {
        try(var channel=Files.newByteChannel(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            long size=channel.size();
            var input=Channels.newInputStream(channel);
            if(size>limit) {
                if(!allowOversized) throw new IOException("Archive exceeds size limit");
                return new Source(null,readStamp(input,size));
            }
            byte[] bytes=readExact(input,(int)size);
            return new Source(bytes,stamp(bytes));
        }
    }

    /** A user-selected map or pack may be a link to a regular file. Measure the opened target. */
    static byte[] readImport(Path selected,int limit,String tooLarge) throws IOException {
        checkReadInterrupted();
        Path file=selected.toRealPath();
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("请选择普通文件。");
        try(var channel=Files.newByteChannel(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            long size=channel.size();
            if(size>limit) throw new IOException(tooLarge);
            return readExact(Channels.newInputStream(channel),(int)size);
        }
    }

    static byte[] readExact(InputStream input,int size) throws IOException {
        checkReadInterrupted();
        byte[] bytes=new byte[size];
        int offset=0;
        while(offset<size) {
            int count=input.read(bytes,offset,Math.min(8192,size-offset));
            checkReadInterrupted();
            if(count<0) throw new StaleArchiveException();
            offset+=count;
        }
        int extra=input.read();
        checkReadInterrupted();
        if(extra!=-1) throw new StaleArchiveException();
        return bytes;
    }

    private static void checkReadInterrupted() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("File read interrupted");
    }

    static Stamp readStamp(Path path,long size) throws IOException {
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            return readStamp(input,size);
        }
    }

    static Stamp readStamp(InputStream input,long size) throws IOException {
        if(size<0) throw new StaleArchiveException();
        var hash=digest();
        byte[] buffer=new byte[8192];
        long remaining=size;
        while(remaining>0) {
            if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("File digest interrupted");
            int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
            if(count<0) throw new StaleArchiveException();
            hash.update(buffer,0,count);
            remaining-=count;
        }
        if(input.read()!=-1) throw new StaleArchiveException();
        return new Stamp(size,HexFormat.of().formatHex(hash.digest()));
    }

    static void verifyStamp(Path path,Stamp expected,LinkOption... options) throws IOException {
        BasicFileAttributes attributes;
        try {attributes=Files.readAttributes(path,BasicFileAttributes.class,options);}
        catch(NoSuchFileException absent) {
            if(ABSENT.equals(expected)) return;
            throw new StaleArchiveException(absent);
        }
        if(!attributes.isRegularFile()) throw new IOException("Invalid archive file");
        if(attributes.size()!=expected.bytes()) throw new StaleArchiveException();
        try(var input=Files.newInputStream(path,options)) {verifyContents(input,expected);}
        catch(NoSuchFileException absent) {throw new StaleArchiveException(absent);}
    }

    static void verifyContents(InputStream input,Stamp expected) throws IOException {
        if(!expected.equals(readStamp(input,expected.bytes())))
            throw new StaleArchiveException();
    }

    static MessageDigest digest() {
        try {return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }

    static void writeAndForce(Path path,byte[] bytes) throws IOException {
        try(var channel=FileChannel.open(path,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)) {
            var buffer=ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    static void text(Path path,String text,int limit,String prefix,SaveDiagnostics diagnostics) throws IOException {diagnostics.at(SaveDiagnostics.Stage.ENCODE);try {bytes(path,text.getBytes(StandardCharsets.UTF_8),limit,prefix,diagnostics,FILES);}catch(IOException | RuntimeException error) {diagnostics.failed(error);throw error;}}
    /** Preserve Files.writeString's strict encoding contract for setup libraries and exports. */
    static void strictText(Path path,String text,int limit,String prefix,SaveDiagnostics diagnostics) throws IOException {
        strictText(path,text,limit,prefix,diagnostics,FILES);
    }
    static void strictText(Path path,String text,int limit,String prefix,SaveDiagnostics diagnostics,Filesystem filesystem) throws IOException {
        diagnostics.at(SaveDiagnostics.Stage.ENCODE);
        try {bytes(path,encodeStrict(text),limit,prefix,diagnostics,filesystem);}
        catch(IOException | RuntimeException error) {diagnostics.failed(error);throw error;}
    }
    static byte[] encodeStrict(String text) throws CharacterCodingException {
        var encoded=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
        var content=new byte[encoded.remaining()];
        encoded.get(content);
        return content;
    }

    /** Count the same strict UTF-8 encoding used for streaming writes without holding the encoded text. */
    static int utf8Size(TextWriter source) throws IOException {
        class Counter extends OutputStream {
            int bytes;
            @Override public void write(int value) {bytes++;}
            @Override public void write(byte[] buffer,int offset,int length) {bytes+=length;}
        }
        var count=new Counter();
        try(var writer=new BufferedWriter(new OutputStreamWriter(count,StandardCharsets.UTF_8.newEncoder()))) {
            source.write(writer);
        }
        return count.bytes;
    }
    static void bytes(Path path,byte[] bytes,int limit,String prefix,SaveDiagnostics diagnostics) throws IOException {bytes(path,bytes,limit,prefix,diagnostics,FILES);}
    static void bytes(Path path,byte[] bytes,int limit,String prefix,SaveDiagnostics diagnostics,Filesystem filesystem) throws IOException {
        bytes(path,bytes,limit,prefix,diagnostics,filesystem,NO_CHECK);
    }
    static void bytes(Path path,byte[] bytes,int limit,String prefix,SaveDiagnostics diagnostics,ReplacementCheck check) throws IOException {
        bytes(path,bytes,limit,prefix,diagnostics,FILES,check);
    }

    static void bytes(Path path,byte[] bytes,int limit,String prefix,SaveDiagnostics diagnostics,Filesystem filesystem,
            ReplacementCheck check) throws IOException {
        diagnostics.at(SaveDiagnostics.Stage.ENCODE);
        diagnostics.encoded(bytes.length);
        if(bytes.length>limit) {
            var failure=new IOException("Archive exceeds size bound");
            diagnostics.failed(failure);
            throw failure;
        }
        publish(path,prefix,diagnostics,filesystem,check,temporary->{filesystem.write(temporary,bytes);return null;});
    }

    static Stamp utf8(Path path,int limit,String prefix,SaveDiagnostics diagnostics,Filesystem filesystem,
                      ReplacementCheck check,TextWriter source) throws IOException {
        return stream(path,limit,prefix,diagnostics,filesystem,check,null,output->{
            try(var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8.newEncoder()))) {
                source.write(writer);
            }
        });
    }

    static void backup(Path source,Path destination,Stamp expected,long limit,String prefix,SaveDiagnostics diagnostics) throws IOException {
        if(expected.bytes()<0 || expected.bytes()>limit || !Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))
            throw new StaleArchiveException();
        stream(destination,limit,prefix,diagnostics,NEW_FILES,NO_CHECK,expected,output->{
            try(var input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)) {
                var buffer=new byte[8192];
                while(output.bytes<expected.bytes()) {
                    int length=(int)Math.min(buffer.length,expected.bytes()-output.bytes);
                    int count=input.read(buffer,0,length);
                    if(count<0) throw new StaleArchiveException();
                    output.write(buffer,0,count);
                }
                if(input.read()!=-1) throw new StaleArchiveException();
            } catch(NoSuchFileException missing) {
                throw new StaleArchiveException(missing);
            }
        });
    }

    /** Preserve the first migration copy and also secure the current original if its bytes differ. */
    static void preserveOriginal(Path file,Stamp expected,String firstName,String backupPrefix,Filesystem filesystem) throws IOException {
        if(firstName!=null) {
            Path first=file.resolveSibling(firstName);
            if(Files.notExists(first,LinkOption.NOFOLLOW_LINKS)) {
                filesystem.backup(file,first,expected);
                return;
            }
            try {
                verifyStamp(first,expected,LinkOption.NOFOLLOW_LINKS);
                return;
            } catch(StaleArchiveException different) {
                // An earlier upgrade may have preserved another valid original under this name.
            }
        }
        Path backup=file.resolveSibling(backupPrefix+expected.sha256()+".json");
        if(Files.notExists(backup,LinkOption.NOFOLLOW_LINKS)) filesystem.backup(file,backup,expected);
        else verifyStamp(backup,expected,LinkOption.NOFOLLOW_LINKS);
    }

    private static Stamp stream(Path path,long limit,String prefix,SaveDiagnostics diagnostics,Filesystem filesystem,
                                ReplacementCheck check,Stamp expected,StreamWriter source) throws IOException {
        return publish(path,prefix,diagnostics,filesystem,check,temporary->{
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)) {
                var output=new BoundedOutput(channel,limit);
                source.write(output);
                // A raw backup may exceed the range of the existing encoded-size diagnostic.
                if(output.bytes<=Integer.MAX_VALUE) diagnostics.encoded((int)output.bytes);
                var stamp=output.stamp();
                if(expected!=null && !expected.equals(stamp)) throw new StaleArchiveException();
                channel.force(true);
                return stamp;
            }
        });
    }

    private static final class BoundedOutput extends OutputStream {
        private final FileChannel channel;
        private final long limit;
        private final MessageDigest hash=digest();
        private long bytes;
        BoundedOutput(FileChannel channel,long limit) {this.channel=channel;this.limit=limit;}

        @Override public void write(int value) throws IOException {write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] contents,int offset,int length) throws IOException {
            Objects.checkFromIndexSize(offset,length,contents.length);
            if(length>limit-bytes) throw new IOException("Archive exceeds size bound");
            var buffer=ByteBuffer.wrap(contents,offset,length);
            while(buffer.hasRemaining()) channel.write(buffer);
            hash.update(contents,offset,length);
            bytes+=length;
        }

        Stamp stamp() {
            return new Stamp(bytes,HexFormat.of().formatHex(hash.digest()));
        }
    }

    private static <T> T publish(Path path,String prefix,SaveDiagnostics diagnostics,Filesystem filesystem,
                                 ReplacementCheck check,Staging<T> staging) throws IOException {
        Path temporary=null;Throwable primary=null;
        try {
            var target=path.toAbsolutePath();diagnostics.at(SaveDiagnostics.Stage.PREPARE);filesystem.directories(target.getParent());
            diagnostics.at(SaveDiagnostics.Stage.CREATE_TEMP);temporary=filesystem.temporary(target.getParent(),prefix);
            diagnostics.at(SaveDiagnostics.Stage.WRITE_TEMP);
            T staged;
            try(var work=diagnostics.measure(SaveDiagnostics.Work.WRITE_TEMP)) {staged=staging.write(temporary);}
            replacePrepared(temporary,target,diagnostics,filesystem,check);
            return staged;
        }catch(IOException | RuntimeException error) {primary=error;diagnostics.failed(error);throw error;}
        finally {if(temporary!=null) try(var work=diagnostics.measure(SaveDiagnostics.Work.DELETE_TEMP)) {filesystem.cleanup(temporary);}catch(IOException | RuntimeException cleanup) {if(primary!=null && primary!=cleanup) primary.addSuppressed(cleanup);diagnostics.cleanupFailed(cleanup);}}
    }

    static void replacePrepared(Path temporary,Path target,SaveDiagnostics diagnostics,Filesystem filesystem,
                                ReplacementCheck check) throws IOException {
        diagnostics.at(SaveDiagnostics.Stage.REPLACE);
        try(var work=diagnostics.measure(SaveDiagnostics.Work.VERIFY)) {check.verify(target);}
        try(var work=diagnostics.measure(SaveDiagnostics.Work.MOVE)) {filesystem.atomic(temporary,target);}
        catch(AtomicMoveNotSupportedException unsupported) {
            diagnostics.at(SaveDiagnostics.Stage.FALLBACK_REPLACE);
            try(var work=diagnostics.measure(SaveDiagnostics.Work.VERIFY)) {check.verify(target);}
            try(var work=diagnostics.measure(SaveDiagnostics.Work.FALLBACK_MOVE)) {filesystem.replace(temporary,target);}
        }
        diagnostics.replaced();
    }
}
