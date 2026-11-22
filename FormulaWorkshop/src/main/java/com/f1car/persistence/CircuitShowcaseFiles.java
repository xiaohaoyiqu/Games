package com.f1car.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Independent atomic presentation exports. These are never read as maps or installed as content. */
public final class CircuitShowcaseFiles {
    public static final int PNG_BYTES=8*1024*1024,TEXT_BYTES=32000;
    private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    private CircuitShowcaseFiles() {}
    public static void png(Path path,byte[] encoded) throws IOException {
        png(path,encoded,true);
    }
    public static void png(Path path,byte[] encoded,boolean replace) throws IOException {
        png(path,encoded,replace,ArchiveWriter.NO_CHECK);
    }
    public static void png(Path path,byte[] encoded,ExportDestination destination) throws IOException {
        png(path,encoded,destination.replacing(),destination::verifyPublication);
    }
    private static void png(Path path,byte[] encoded,boolean replace,ArchiveWriter.ReplacementCheck check) throws IOException {
        extension(path,".png");
        if(encoded==null || encoded.length>PNG_BYTES || encoded.length<PNG.length
                || !Arrays.equals(PNG,Arrays.copyOf(encoded,PNG.length))) throw new IOException("Invalid generated PNG");
        ArchiveWriter.bytes(path,encoded,PNG_BYTES,"circuit-showcase-",new SaveDiagnostics("circuit-showcase"),
                replace?ArchiveWriter.FORCED_FILES:ArchiveWriter.NEW_FILES,check);
    }
    public static void text(Path path,String value) throws IOException {
        text(path,value,true);
    }
    public static void text(Path path,String value,boolean replace) throws IOException {
        extension(path,".txt");
        PartsStore.writeExport(path,value,TEXT_BYTES,replace);
    }
    public static void text(Path path,String value,ExportDestination destination) throws IOException {
        extension(path,".txt");
        PartsStore.writeExport(path,value,TEXT_BYTES,destination.replacing(),destination::verifyPublication);
    }
    public static void extension(Path path,String suffix) throws IOException {if(path.getFileName()==null || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix) || Files.isSymbolicLink(path) || Files.isDirectory(path)) throw new IOException("Choose an independent "+suffix+" file");}
}
