package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;

/** Strict named-plan exchange alongside the unchanged legacy draft bundle format. */
public final class PartsPlanShareCodec {
    public static final int MAX_BYTES=600000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private PartsPlanShareCodec() {}
    private static JsonObject content(PartsPlanShare share) {var bundle=JsonParser.parseString(PartsPackCodec.bundle(new PartsPackCodec.Bundle(new PartsPackCodec.Saved(share.plan().name(),share.plan().build()),share.packs()))).getAsJsonObject();var root=new JsonObject();root.add("plan",JSON.toJsonTree(share.plan()));root.addProperty("author",share.author());root.add("trail",JSON.toJsonTree(share.trail()));root.add("packs",bundle.get("packs"));return root;}
    public static String digest(PartsPlanShare share) {return PartsCatalog.hash(JSON.toJson(content(share)));}
    public static String encode(PartsPlanShare share) {var root=new JsonObject();root.addProperty("kind","formula-workshop-parts-plan");root.addProperty("version",1);root.add("content",content(share));root.addProperty("digest",digest(share));return JSON.toJson(root);}
    public static PartsPlanShare read(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {return decode(PartsJson.read(stream,MAX_BYTES));}}
    static PartsPlanShare decode(PartsJson.Node root) throws IOException {try {root.object("kind","version","content","digest");if(!root.child("kind").text(64,true).equals("formula-workshop-parts-plan")) throw new IOException("Use a portable named parts plan");root.child("version").integer(1,1);var body=root.child("content").object("plan","author","trail","packs");ActivityCardCodec.validate(body.child("plan").value(),PartsPlan.class);ActivityCardCodec.validate(body.child("trail").value(),PartsPlanShare.Trail.class);var share=new PartsPlanShare(JSON.fromJson(body.child("plan").value(),PartsPlan.class),body.child("author").text(40,true),JSON.fromJson(body.child("trail").value(),PartsPlanShare.Trail.class),PartsPackCodec.packs(body.child("packs")));if(!digest(share).equals(root.child("digest").text(64,true))) throw new IOException("Shared plan checksum differs");return share;}catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid portable parts plan",error);}}
    public static void write(Path path,PartsPlanShare share) throws IOException {write(path,share,true);}
    public static void write(Path path,PartsPlanShare share,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(share),MAX_BYTES,replace);
    }
}
