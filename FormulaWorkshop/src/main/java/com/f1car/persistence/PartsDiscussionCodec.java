package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Declaration-only offline discussion. The original plan/evidence envelopes keep their formats. */
public final class PartsDiscussionCodec {
    public static final int MAX_BYTES=1600000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private PartsDiscussionCodec() {}
    private static JsonObject content(PartsDiscussion value) {var result=new JsonObject();result.addProperty("id",value.id());result.addProperty("title",value.title());String plan=PartsPlanShareCodec.encode(value.plan());if(plan.getBytes(StandardCharsets.UTF_8).length>PartsPlanShareCodec.MAX_BYTES) throw new IllegalArgumentException("Discussion plan exceeds bound");result.add("plan",JsonParser.parseString(plan));result.add("evidence",value.evidence()==null?JsonNull.INSTANCE:PartsTrialEvidenceCodec.tree(value.evidence()));result.addProperty("createdAt",value.createdAt());result.add("messages",JSON.toJsonTree(value.messages()));return result;}
    public static String digest(PartsDiscussion value) {return digest(content(value));}
    private static String digest(JsonElement value) {return PartsCatalog.hash(JSON.toJson(PartsTrialEvidenceCodec.canonical(value)));}
    static JsonObject tree(PartsDiscussion value) {var payload=content(value);var root=new JsonObject();root.addProperty("kind","formula-workshop-parts-discussion");root.addProperty("version",1);root.add("content",payload);root.addProperty("digest",digest(payload));if(bytes(root)>MAX_BYTES) throw new IllegalArgumentException("Discussion exceeds size bound");return root;}
    public static String encode(PartsDiscussion value) {return JSON.toJson(tree(value));}
    static PartsDiscussion decode(PartsJson.Node root) throws IOException {try {root.object("kind","version","content","digest");if(!root.child("kind").text(64,true).equals("formula-workshop-parts-discussion")) throw new IOException("Use a parts discussion file");root.child("version").integer(1,1);var body=root.child("content").object("id","title","plan","evidence","createdAt","messages");if(bytes(root.value())>MAX_BYTES || bytes(body.child("plan").value())>PartsPlanShareCodec.MAX_BYTES) throw new IOException("Discussion exceeds bound");var plan=PartsPlanShareCodec.decode(body.child("plan"));var evidence=body.child("evidence").value().isJsonNull()?null:PartsTrialEvidenceCodec.decode(body.child("evidence"));var items=body.child("messages").array(1,PartsDiscussion.CAPACITY);var messages=new ArrayList<PartsDiscussion.Message>();for(var item:items) {ActivityCardCodec.validate(item,PartsDiscussion.Message.class);messages.add(JSON.fromJson(item,PartsDiscussion.Message.class));}ActivityCardCodec.validate(body.child("createdAt").value(),long.class);var result=new PartsDiscussion(1,body.child("id").text(48,true),body.child("title").text(80,true),plan,evidence,body.child("createdAt").value().getAsLong(),messages);if(!digest(result).equals(root.child("digest").text(64,true))) throw new IOException("Discussion checksum differs");return result;}catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid parts discussion",error);}}
    public static PartsDiscussion read(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {return decode(PartsJson.read(stream,MAX_BYTES,18));}}
    public static void write(Path path,PartsDiscussion value) throws IOException {write(path,value,true);}
    public static void write(Path path,PartsDiscussion value,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(value),MAX_BYTES,replace);
    }
    private static int bytes(JsonElement value) {return JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;}
}
