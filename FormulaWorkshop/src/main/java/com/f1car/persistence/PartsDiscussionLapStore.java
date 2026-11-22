package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Independent citations publish after writing; failures keep the same entry in process memory. */
public final class PartsDiscussionLapStore {
    public static final int CAPACITY=8,MAX_BYTES=32_000_000;
    private final Path file;
    private final GuardedArchive archive;
    private List<PartsDiscussionLap.Entry> entries=List.of();
    private PartsDiscussionLap.Entry pending;
    private boolean writable=true,stale;
    private String warning="";
    public PartsDiscussionLapStore(Path directory) {
        file=directory.resolve("discussion-laps.json");
        archive=new GuardedArchive(directory,"discussion-laps",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}public List<PartsDiscussionLap.Entry> entries() {return entries;}public PartsDiscussionLap.Entry pending() {return pending;}public boolean writable() {return writable;}public String warning() {return warning;}
    public PartsDiscussionLap.Entry find(String id) {return entries.stream().filter(e->e.citation().id().equals(id)).findFirst().orElse(null);}
    public boolean save(PartsDiscussionLap value,PartsDiscussionLap.Origin origin) {
        var entry=new PartsDiscussionLap.Entry(value,origin);if(pending!=null && !pending.equals(entry)) {warning="先重试或取消原待存引用；原文保持。";return false;}
        var old=find(value.id());if(old!=null && pending==null) return matchSaved(old,value);
        pending=entry;return retry();
    }
    public boolean retry() {
        if(pending==null) return true;
        if(!writable) {warning="引用库读取受阻；修复后明确重读。";return false;}
        var current=find(pending.citation().id());
        if(current!=null) {
            if(!matchSaved(current,pending.citation())) return false;
            pending=null;
            return true;
        }
        if(entries.size()>=CAPACITY) {
            if(!verifySaved()) return false;
            warning="最多八份装备圈引用；待存保留，请先移除一份再重试。";
            return false;
        }
        try {var next=new ArrayList<>(entries);next.add(pending);write(next);pending=null;return true;}
        catch(IOException | RuntimeException error) {return rejected(error,"装备圈引用尚未保存；同一原文保留在本次进程内，可重试或取消。");}
    }
    public void cancelPending() {pending=null;warning=writable?"":"引用库读取受阻；修复后明确重读。";}
    public boolean remove(PartsDiscussionLap.Entry expected) {
        if(expected==null || expected!=find(expected.citation().id())) {warning="引用已变化，请刷新后重选。";return false;}
        if(!writable) return false;
        try {write(entries.stream().filter(e->e!=expected).toList());return true;}
        catch(IOException | RuntimeException error) {return rejected(error,"移除尚未保存；原引用保持。");}
    }
    private void write(List<PartsDiscussionLap.Entry> next) throws IOException {
        archive.commit(output->{
            try(var writer=EquipmentLapJson.JSON.newJsonWriter(output)) {
                writer.beginObject().name("version").value(1).name("entries").beginArray();
                for(var entry:next) {
                    writer.beginObject().name("origin").value(entry.origin().name());
                    writer.name("citation");EquipmentLapJson.JSON.toJson(PartsDiscussionLapCodec.tree(entry.citation()),writer);
                    writer.endObject();
                }
                writer.endArray().endObject();
            }
        },false);
        entries=List.copyOf(next);stale=false;warning="";
    }
    public boolean reload() {
        if(pending!=null && !stale) {warning="先重试或取消原待存引用，再明确重新读取。";return false;}
        try {
            var loaded=archive.readStream(PartsDiscussionLapStore::readEntries);
            entries=loaded==null?List.of():loaded;
            writable=true;stale=false;warning=pending==null?"":"已重读最新引用库，待存原文可核对后重试。";return true;
        }catch(IOException | RuntimeException error) {writable=false;warning="引用库无法读取；原文件和已读内容保持，修复后明确重读。";return false;}
    }
    private static List<PartsDiscussionLap.Entry> readEntries(InputStream source) throws IOException {
        var next=new ArrayList<PartsDiscussionLap.Entry>();
        var ids=new HashSet<String>();var fields=new HashSet<String>();
        try(var reader=PartsDiscussionTraceCodec.reader(source)) {
            reader.beginObject();
            while(reader.hasNext()) {
                String key=reader.nextName();
                if(!fields.add(key)) throw new IOException("Duplicate field");
                if(key.equals("version")) {
                    if(reader.peek()!=JsonToken.NUMBER) throw new IOException("Invalid version");
                    var number=reader.nextString();
                    if(number.length()>64 || new java.math.BigDecimal(number).intValueExact()!=1) throw new IOException("Invalid version");
                } else if(key.equals("entries")) {
                    reader.beginArray();
                    while(reader.hasNext()) {
                        if(next.size()>=CAPACITY) throw new IOException("Citation archive capacity");
                        var node=new PartsJson.Node(PartsDiscussionLapCodec.element(reader,0,new int[]{0}),"").object("origin","citation");
                        var citation=PartsDiscussionLapCodec.decode(node.child("citation").value());
                        if(!ids.add(citation.id())) throw new IOException("Duplicate citation");
                        next.add(new PartsDiscussionLap.Entry(citation,node.child("origin").enumeration(PartsDiscussionLap.Origin.class)));
                    }
                    reader.endArray();
                } else throw new IOException("Unknown field");
            }
            reader.endObject();
            if(!fields.equals(Set.of("version","entries")) || reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Incomplete or trailing archive");
        }
        return List.copyOf(next);
    }
    private boolean matchSaved(PartsDiscussionLap.Entry old,PartsDiscussionLap value) {
        if(!old.citation().equals(value)) {warning="同身份引用内容不同；已有原文和待存候选保持。";return false;}
        return writable && verifySaved();
    }
    private boolean verifySaved() {
        try {archive.verifyAccepted();stale=false;warning="";return true;}
        catch(IOException | RuntimeException error) {return rejected(error,"无法核对已保存引用。");}
    }
    private boolean rejected(Exception error,String failure) {
        if(error instanceof ArchiveWriter.StaleArchiveException) {
            stale=true;warning="装备圈引用库已由另一窗口更新，请重读后核对。";
        } else warning=failure;
        return false;
    }
}
