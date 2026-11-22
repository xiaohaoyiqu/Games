package com.f1car.persistence;

import com.f1car.model.PartsDiscussionTrace;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Independent citations never install parts, merge discussions or write provider data as local runs. */
public final class PartsDiscussionTraceStore {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    public static final int CAPACITY=16,MAX_BYTES=32_000_000;
    private final Path file;
    private final GuardedArchive archive;
    private List<PartsDiscussionTrace.Entry> entries=List.of();private boolean writable=true,stale;private String warning="";
    public PartsDiscussionTraceStore(Path directory) {
        file=directory.resolve("discussion-traces.json");
        archive=new GuardedArchive(directory,"discussion-traces",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}
    public List<PartsDiscussionTrace.Entry> entries() {return entries;}
    public PartsDiscussionTrace.Entry find(String id) {return entries.stream().filter(entry->entry.attachment().id().equals(id)).findFirst().orElse(null);}
    public boolean writable() {return writable;}
    public boolean requiresReload() {return stale;}
    public String warning() {return warning;}
    public void reload() {
        try {
            var loaded=archive.readStream(PartsDiscussionTraceStore::readEntries);
            entries=loaded==null?List.of():loaded;
            writable=true;stale=false;warning="";
        }catch(IOException | RuntimeException error) {writable=false;warning="曲线引用库无法读取；保留原文件与已读引用，修复后重新读取。";}
    }
    private static List<PartsDiscussionTrace.Entry> readEntries(InputStream source) throws IOException {
        var loaded=new ArrayList<PartsDiscussionTrace.Entry>();var keys=new HashSet<String>();
        try(var reader=PartsDiscussionTraceCodec.reader(source)) {
            reader.beginObject();
            while(reader.hasNext()) {
                String key=reader.nextName();
                if(!keys.add(key)) throw new IOException("Duplicate citation archive key");
                if(key.equals("version")) {
                    if(reader.peek()!=JsonToken.NUMBER || !reader.nextString().equals("1"))
                        throw new IOException("Invalid citation archive version");
                } else if(key.equals("entries")) {
                    reader.beginArray();
                    while(reader.hasNext()) {
                        if(loaded.size()>=CAPACITY) throw new IOException("Too many citations");
                        var node=new PartsJson.Node(TrialTraceCodec.bounded(reader,0,new int[]{0},28),"").object("origin","attachment");
                        loaded.add(new PartsDiscussionTrace.Entry(PartsDiscussionTraceCodec.decode(node.child("attachment").value()),
                            node.child("origin").enumeration(PartsDiscussionTrace.Origin.class)));
                    }
                    reader.endArray();
                } else throw new IOException("Unknown citation archive key");
            }
            reader.endObject();
            if(!keys.equals(Set.of("version","entries")) || reader.peek()!=JsonToken.END_DOCUMENT)
                throw new IOException("Invalid citation archive structure");
        }
        return checked(loaded);
    }
    public boolean save(PartsDiscussionTrace value,PartsDiscussionTrace.Origin origin) {
        Objects.requireNonNull(value);Objects.requireNonNull(origin);if(!writable) return false;var old=find(value.id());
        if(old!=null) {
            if(!old.attachment().equals(value)) {warning="同身份引用内容不同，原引用保留；请核对独立文件。";return false;}
            try {archive.verifyAccepted();warning="";stale=false;return true;}
            catch(IOException | RuntimeException error) {return rejected(error);}
        }
        var next=new ArrayList<>(entries);next.add(0,new PartsDiscussionTrace.Entry(value,origin));return commit(next);
    }
    public boolean remove(PartsDiscussionTrace.Entry expected) {if(expected==null || !expected.equals(find(expected.attachment().id()))) {warning="引用已变化，请重新查看。";return false;}return commit(entries.stream().filter(entry->!entry.equals(expected)).toList());}
    private static List<PartsDiscussionTrace.Entry> checked(List<PartsDiscussionTrace.Entry> values) {values=List.copyOf(values);if(values.size()>CAPACITY || values.stream().map(entry->entry.attachment().id()).distinct().count()!=values.size()) throw new IllegalArgumentException("Citation archive capacity or identity");return values;}
    private boolean commit(List<PartsDiscussionTrace.Entry> values) {
        if(!writable) return false;
        try {
            var next=checked(values);
            archive.commit(output->{
                try(var writer=JSON.newJsonWriter(output)) {
                    writer.beginObject().name("version").value(1).name("entries").beginArray();
                    for(var entry:next) {
                        writer.beginObject().name("origin").value(entry.origin().name());
                        writer.name("attachment");JSON.toJson(PartsDiscussionTraceCodec.tree(entry.attachment()),writer);
                        writer.endObject();
                    }
                    writer.endArray().endObject();
                }
            },false);
            entries=next;stale=false;warning="";return true;
        } catch(IOException | RuntimeException error) {return rejected(error);}
    }
    private boolean rejected(Exception error) {
        if(error instanceof ArchiveWriter.StaleArchiveException) {
            stale=true;
            warning="曲线引用库已由另一窗口更新，请重读后核对同份引用。";
        } else warning="曲线引用未保存；最多十六份且总文件限 32 MB，本次引用保留，可修复或释放容量后重试。";
        return false;
    }
}
