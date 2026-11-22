package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Strict bounded vector-only declarations. No images, file references, script or XML import. */
public final class LiveryCodec {
    public static final int MAX_BYTES=40000;
    private static final Gson JSON=new Gson();
    private LiveryCodec() {}
    static JsonObject body(CarLivery value) {var body=JSON.toJsonTree(value).getAsJsonObject();if(value.version()==1) for(var layer:body.getAsJsonArray("layers")) layer.getAsJsonObject().remove("target");return body;}
    static JsonObject tree(CarLivery value) {var root=new JsonObject();root.addProperty("kind","formula-workshop-livery");root.addProperty("version",value.version());root.add("content",body(value));return root;}
    public static String encode(CarLivery value) {return JSON.toJson(tree(value));}
    static CarLivery decode(PartsJson.Node root) throws IOException {
        root.object("kind","version","content");if(!root.child("kind").text(64,true).equals("formula-workshop-livery")) throw new IOException("Use a livery file");int version=root.child("version").integer(1,2);var value=content(root.child("content"));if(value.version()!=version) throw new IOException("Livery envelope and content versions differ");return value;
    }
    static CarLivery content(PartsJson.Node c) throws IOException {try {c.object("version","id","vehicle","name","author","notes","sourceId","layers");int version=c.child("version").integer(1,2);var array=c.child("layers").array(0,CarLivery.CAPACITY);var layers=new ArrayList<CarLivery.Layer>();for(int i=0;i<array.size();i++) {var l=c.child("layers").index(i);if(version==1) l.object("shape","x","y","width","height","rotation","rgb","opacity","mirror");else l.object("shape","x","y","width","height","rotation","rgb","opacity","mirror","target");layers.add(new CarLivery.Layer(l.child("shape").enumeration(CarLivery.Shape.class),l.child("x").integer(-400,400),l.child("y").integer(-400,400),l.child("width").integer(20,900),l.child("height").integer(20,900),l.child("rotation").integer(-180,179),l.child("rgb").integer(0,0xffffff),l.child("opacity").integer(10,100),l.child("mirror").integer(0,1),version==1?CarLivery.Target.ALL:l.child("target").enumeration(CarLivery.Target.class)));}return new CarLivery(version,c.child("id").text(48,true),c.child("vehicle").token(),c.child("name").text(40,true),c.child("author").text(40,false),c.child("notes").text(320,false),c.child("sourceId").text(48,false),layers);}catch(IllegalArgumentException error) {throw new IOException("Invalid livery design",error);}}
    public static CarLivery read(InputStream stream) throws IOException {return decode(PartsJson.read(stream,MAX_BYTES,8));}
    public static CarLivery read(Path path) throws IOException {
        Path file=path.toRealPath();
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("请选择普通涂装文件。");
        try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {return read(stream);}
    }
    public static void write(Path path,CarLivery value) throws IOException {write(path,value,false);}
    public static void write(Path path,CarLivery value,boolean replace) throws IOException {
        write(path,value,ExportDestination.forContentWrite(path,replace));
    }
    public static void write(Path path,CarLivery value,ExportDestination reviewed) throws IOException {
        if(Files.isSymbolicLink(path)) throw new IOException("Choose an independent livery file");
        var diagnostics=new SaveDiagnostics("livery-export");
        diagnostics.begin();
        ArchiveWriter.utf8(path,MAX_BYTES,"livery-export-",diagnostics,
                reviewed.replacing()?ArchiveWriter.FORCED_FILES:ArchiveWriter.NEW_FILES,reviewed::verifyPublication,
                writer->writer.write(encode(value)));
    }
}
