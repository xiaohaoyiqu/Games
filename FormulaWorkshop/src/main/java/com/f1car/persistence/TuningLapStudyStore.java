package com.f1car.persistence;

import com.f1car.model.TuningLapStudy;
import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Independent collection. Received studies never become local reports or lap measurements. */
public final class TuningLapStudyStore {
    public static final int CAPACITY=4,MAX_BYTES=60_000_000;
    public enum Origin {LOCAL("本机制作的对照"),RECEIVED("收到的提供者对照");public final String title;Origin(String title) {this.title=title;}}
    public record Entry(TuningLapStudy study,Origin origin) {public Entry {Objects.requireNonNull(study);Objects.requireNonNull(origin);}}
    public record Review(List<Entry> before,TuningLapStudy incoming,Origin origin,List<String> issues) {
        public Review {before=List.copyOf(before);Objects.requireNonNull(incoming);Objects.requireNonNull(origin);issues=List.copyOf(issues);}
        public boolean valid() {return issues.isEmpty();}
    }
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Path file;
    private final GuardedArchive archive;
    private List<Entry> entries=List.of();
    private Entry pending;
    private boolean writable=true,stale;
    private String warning="";
    private int diskVersion=1;
    public TuningLapStudyStore(Path directory) {
        file=directory.resolve("tuning-lap-studies.json");
        archive=new GuardedArchive(directory,"tuning-lap-studies",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}public List<Entry> entries() {return entries;}public Entry pending() {return pending;}public boolean writable() {return writable;}public String warning() {return warning;}
    public Entry find(String id) {return entries.stream().filter(e->e.study().id().equals(id)).findFirst().orElse(null);}
    public Review review(TuningLapStudy value,Origin origin) {
        Objects.requireNonNull(value);Objects.requireNonNull(origin);var issues=new ArrayList<String>();var old=find(value.id());
        if(old!=null && !old.study().equals(value)) issues.add("同身份对照正文冲突；已有原文保持。");
        if(pending!=null && !pending.equals(new Entry(value,origin))) issues.add("先重试或取消上一份待存对照，原候选保持。");
        if(old==null && entries.size()>=CAPACITY) issues.add("最多四份完整对照；请先导出或移除一份。");
        return new Review(entries,value,origin,issues);
    }
    public boolean accept(Review reviewed) {
        if(reviewed==null || !entries.equals(reviewed.before())) {warning="对照记录库已变化，请重新预览。";return false;}
        var actual=review(reviewed.incoming(),reviewed.origin());if(!actual.equals(reviewed) || !actual.valid()) {warning="对照预览存在冲突，已有原文与候选保持。";return false;}
        if(!writable) return false;
        if(find(reviewed.incoming().id())!=null && pending==null) return verifySaved();
        pending=new Entry(reviewed.incoming(),reviewed.origin());return retry();
    }
    public boolean retry() {
        if(pending==null) return true;if(!writable) {warning="记录库读取受阻；修复文件后明确重读。";return false;}
        var saved=find(pending.study().id());
        if(saved!=null) {
            if(!saved.study().equals(pending.study())) {warning="同身份对照原文冲突，保留已存记录和待存候选。";return false;}
            if(!verifySaved()) return false;
            pending=null;
            return true;
        }
        if(entries.size()>=CAPACITY) {
            if(!verifySaved()) return false;
            warning="最多四份完整对照；原待存保持，请先移除一份。";return false;
        }
        var next=new ArrayList<>(entries);next.add(0,pending);if(!commit(next)) return false;pending=null;return true;
    }
    public void cancelPending() {pending=null;warning=writable?"":"记录库读取受阻；修复文件后明确重读。";}
    public boolean remove(Entry expected) {
        if(expected==null || !expected.equals(find(expected.study().id()))) {warning="对照已变化，请重新查看。";return false;}
        return commit(entries.stream().filter(e->!e.equals(expected)).toList());
    }
    public boolean reload() {
        if(pending!=null && !stale) {warning="原待存对照仍在内存中，先重试或明确取消。";return false;}
        try {
            byte[] source=archive.readSource();
            if(source==null) {
                entries=List.of();diskVersion=1;
            } else {
                var root=new PartsJson.Node(TuningLapStudyCodec.readTree(source,MAX_BYTES,6_100_000),"").object("version","entries");int version=root.child("version").integer(1,2);var array=root.child("entries").array(0,CAPACITY);var next=new ArrayList<Entry>();var ids=new HashSet<String>();
                for(int i=0;i<array.size();i++) {var node=root.child("entries").index(i).object("study","origin");var value=TuningLapStudyCodec.decode(node.child("study").value());if(version<TuningLapStudyCodec.format(value) || !ids.add(value.id())) throw new IOException("Duplicate study identity or curve version differs");next.add(new Entry(value,Origin.valueOf(node.child("origin").text(16,true))));}
                if(!entries.equals(next)) entries=List.copyOf(next);
                diskVersion=version;
            }
            writable=true;stale=false;warning=pending==null?"":"已重读最新对照库，待存候选可核对后重试。";return true;
        }
        catch(IOException | RuntimeException error) {writable=false;warning="对照记录库无法读取；原文件与已读内容保持，修复后明确重读。";return false;}
    }
    private boolean commit(List<Entry> values) {
        if(!writable) return false;
        try {
            int version=Math.max(diskVersion,values.stream().mapToInt(entry->TuningLapStudyCodec.format(entry.study())).max().orElse(1));
            var ids=new HashSet<String>();
            if(values.size()>CAPACITY) throw new IOException("Too many studies");
            archive.commit(output->{
                try(var writer=new JsonWriter(output)) {
                    writer.beginObject().name("version").value(version).name("entries").beginArray();
                    for(var entry:values) {
                        if(!ids.add(entry.study().id())) throw new IOException("Duplicate study identity");
                        writer.beginObject().name("study");JSON.toJson(TuningLapStudyCodec.tree(entry.study()),writer);
                        writer.name("origin").value(entry.origin().name()).endObject();
                    }
                    writer.endArray().endObject();
                }
            },false,diskVersion==1 && version==2?"tuning-lap-studies.pre-local-curves-v1.json":null);
            entries=List.copyOf(values);diskVersion=version;stale=false;warning="";return true;
        }catch(IOException | RuntimeException error) {return rejected(error);}
    }
    private boolean verifySaved() {
        try {archive.verifyAccepted();stale=false;warning="";return true;}
        catch(IOException | RuntimeException error) {return rejected(error);}
    }
    private boolean rejected(Exception error) {
        if(error instanceof ArchiveWriter.StaleArchiveException) {
            stale=true;
            warning="对照库已由另一窗口更新，请重新读取，待存候选保留。";
        } else warning="完整对照未保存；原记录与待存候选保持，请检查容量、来源文本或目标后明确重试。";
        return false;
    }
}
