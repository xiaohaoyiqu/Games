package com.f1car.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/** Binds an overwrite dialog to the file actually shown to the player. */
public final class ExportDestination {
    private static final long MAX_DIGEST_BYTES=64L*1024*1024;
    private record FileIdentity(Object key,long size,FileTime modified,FileTime created) {
        static FileIdentity read(Path path) throws IOException {
            var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile()) throw new IOException("Export destination is not a regular file");
            return new FileIdentity(attributes.fileKey(),attributes.size(),attributes.lastModifiedTime(),attributes.creationTime());
        }
    }

    private record DirectoryIdentity(Path path,Object key,FileTime created) {
        static DirectoryIdentity read(Path path) throws IOException {
            var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isDirectory()) throw new NotDirectoryException(path.toString());
            // Windows can expose a creation time without a file key. A directory's modification
            // time changes when the export is staged and must not invalidate the review.
            var key=attributes.fileKey();
            var created=key==null && attributes instanceof DosFileAttributes ? attributes.creationTime() : null;
            return new DirectoryIdentity(path,key,created);
        }

        boolean current() throws IOException {
            try {return equals(read(path));}
            catch(NoSuchFileException | NotDirectoryException changed) {return false;}
        }
    }

    private final Path selected,actual;
    private final FileIdentity identity;
    private final ArchiveWriter.Stamp stamp;
    private final DirectoryIdentity existingDirectory;

    private ExportDestination(Path selected,Path actual,FileIdentity identity,ArchiveWriter.Stamp stamp,DirectoryIdentity existingDirectory) {
        this.selected=selected;this.actual=actual;this.identity=identity;this.stamp=stamp;
        this.existingDirectory=existingDirectory;
    }

    public static ExportDestination inspect(Path selection) throws IOException {
        return forWrite(selection,true);
    }

    public static ExportDestination forWrite(Path selection,boolean allowReplacement) throws IOException {
        return forWrite(selection,allowReplacement,MAX_DIGEST_BYTES);
    }

    /** For background exports that can stream the full existing file before asking to replace it. */
    public static ExportDestination forContentWrite(Path selection,boolean allowReplacement) throws IOException {
        return forWrite(selection,allowReplacement,Long.MAX_VALUE);
    }

    private static ExportDestination forWrite(Path selection,boolean allowReplacement,long digestLimit) throws IOException {
        var file=Objects.requireNonNull(selection).toAbsolutePath().normalize();
        if(file.getFileName()==null) throw new IOException("File name is required");
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) {
            var ancestor=existingAncestor(file.getParent());
            var directory=DirectoryIdentity.read(ancestor.toRealPath());
            var actual=directory.path().resolve(ancestor.relativize(file));
            return new ExportDestination(file,actual,null,null,directory);
        }
        if(!allowReplacement) throw new FileAlreadyExistsException(file.toString());
        var real=file.toRealPath();
        var identity=FileIdentity.read(real);
        var stamp=identity.size()<=digestLimit?ArchiveWriter.readStamp(real,identity.size()):null;
        if(!identity.equals(FileIdentity.read(real))) throw new IOException("Export destination changed");
        return new ExportDestination(file,real,identity,stamp,null);
    }

    public Path path() {return selected;}
    public boolean replacing() {return identity!=null;}

    public static Path resolvedPathForExport(Path path) throws IOException {
        var file=path.toAbsolutePath().normalize();
        var existing=existingAncestor(file);
        return existing.toRealPath().resolve(existing.relativize(file));
    }

    private static Path existingAncestor(Path existing) throws IOException {
        while(Files.notExists(existing,LinkOption.NOFOLLOW_LINKS)) {
            existing=existing.getParent();
            if(existing==null) throw new IOException("Cannot resolve the export directory");
        }
        return existing;
    }

    /** Check the reviewed selection against the path about to receive the staged file. */
    public void verifyPublication(Path target) throws IOException {
        var path=target.toAbsolutePath().normalize();
        if(!(path.equals(selected) || path.equals(actual)) || !current())
            throw new ArchiveWriter.StaleArchiveException();
    }

    public boolean current() throws IOException {
        if(identity==null) return Files.notExists(selected,LinkOption.NOFOLLOW_LINKS)
                && existingDirectory.current() && resolvedPathForExport(selected).equals(actual);
        if(Files.notExists(selected,LinkOption.NOFOLLOW_LINKS) || !selected.toRealPath().equals(actual)
                || !identity.equals(FileIdentity.read(actual))) return false;
        if(stamp!=null) {
            try {ArchiveWriter.verifyStamp(actual,stamp,LinkOption.NOFOLLOW_LINKS);}
            catch(ArchiveWriter.StaleArchiveException error) {return false;}
        }
        return identity.equals(FileIdentity.read(actual));
    }
}
