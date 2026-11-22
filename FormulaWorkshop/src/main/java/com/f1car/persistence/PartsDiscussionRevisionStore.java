package com.f1car.persistence;

import com.f1car.model.PartsDiscussionRevision;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded, independent claims. No discussion merge, plan adoption or measurement writes. */
public final class PartsDiscussionRevisionStore {
    public static final int CAPACITY=8,MAX_BYTES=16000000;
    public enum Origin {LOCAL("本机明确声明"),RECEIVED("收到的提供者声明");public final String title;Origin(String title) {this.title=title;}}
    public record Entry(PartsDiscussionRevision revision,Origin origin) {public Entry {Objects.requireNonNull(revision);Objects.requireNonNull(origin);}}
    public record Review(List<Entry> before,PartsDiscussionRevision incoming,Origin origin,List<String> issues) {
        public Review {before=List.copyOf(before);Objects.requireNonNull(incoming);Objects.requireNonNull(origin);issues=List.copyOf(issues);}
        public boolean valid() {return issues.isEmpty();}
    }
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Path file;
    private final GuardedArchive archive;
    private List<Entry> entries=List.of();private boolean writable=true;private String warning="";
    public PartsDiscussionRevisionStore(Path directory) {
        file=directory.resolve("parts-discussion-revisions.json");
        archive=new GuardedArchive(directory,"parts-discussion-revisions",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}public List<Entry> entries() {return entries;}public boolean writable() {return writable;}public String warning() {return warning;}
    public Entry find(String id) {return entries.stream().filter(row->row.revision().id().equals(id)).findFirst().orElse(null);}
    public Review review(PartsDiscussionRevision value,Origin origin) {
        var issues=new ArrayList<String>();var old=find(value.id());
        if(old!=null && !old.revision().equals(value)) issues.add("同身份修订声明正文冲突，已有原文保留。");
        if(old==null) {var next=new ArrayList<>(entries);next.add(new Entry(value,origin));try {checked(next);}catch(IllegalArgumentException error) {issues.add(error.getMessage());}}
        return new Review(entries,value,origin,issues);
    }
    public boolean accept(Review reviewed) {
        if(reviewed==null || !entries.equals(reviewed.before())) {warning="修订声明库已变化，请重新预览。";return false;}
        var actual=review(reviewed.incoming(),reviewed.origin());
        if(!actual.equals(reviewed) || !actual.valid()) {warning="修订关系存在冲突，请查看预览，已有声明保留。";return false;}
        if(!writable) return false;
        if(find(actual.incoming().id())!=null) {
            try {archive.verifyAccepted();warning="";return true;}
            catch(IOException | RuntimeException error) {return rejected(error);}
        }
        var next=new ArrayList<>(entries);next.add(0,new Entry(actual.incoming(),actual.origin()));return commit(next);
    }
    public boolean remove(Entry expected) {
        if(expected==null || !expected.equals(find(expected.revision().id()))) {warning="声明已变化，请重新查看。";return false;}
        return commit(entries.stream().filter(row->!row.equals(expected)).toList());
    }
    public void reload() {
        try {
            var bytes=archive.readSource();
            if(bytes==null) {entries=List.of();writable=true;warning="";return;}
            var root=PartsJson.read(bytes,MAX_BYTES,32).object("version","entries");root.child("version").integer(1,1);
            var items=root.child("entries").array(0,CAPACITY);var next=new ArrayList<Entry>();
            for(int i=0;i<items.size();i++) {var item=root.child("entries").index(i).object("revision","origin");next.add(new Entry(PartsDiscussionRevisionCodec.decode(item.child("revision")),Origin.valueOf(item.child("origin").text(16,true))));}
            entries=checked(next);writable=true;warning="";
        }catch(IOException | RuntimeException error) {writable=false;warning="修订声明库无法读取；原文件与已读声明保留，修复后重新读取。";}
    }
    private static List<Entry> checked(List<Entry> values) {
        values=List.copyOf(values);if(values.size()>CAPACITY) throw new IllegalArgumentException("最多保存八份完整修订声明，请先导出或移除本地副本。");
        PartsDiscussionRevision.checked(values.stream().map(Entry::revision).toList());return values;
    }
    private boolean commit(List<Entry> values) {
        if(!writable) return false;
        try {
            var next=checked(values);
            archive.commit(output->{
                try(var writer=JSON.newJsonWriter(output)) {
                    writer.beginObject().name("version").value(1).name("entries").beginArray();
                    for(var value:next) {
                        writer.beginObject().name("revision");JSON.toJson(PartsDiscussionRevisionCodec.tree(value.revision()),writer);
                        writer.name("origin").value(value.origin().name()).endObject();
                    }
                    writer.endArray().endObject();
                }
            },false);
            entries=next;warning="";return true;
        }catch(IOException | RuntimeException error) {return rejected(error);}
    }
    private boolean rejected(Exception error) {
        warning=error instanceof ArchiveWriter.StaleArchiveException?"修订声明库已由另一窗口更新，请重读并重新核对。"
            :"修订声明未保存；已有原文与本次候选保留，修复目标后重试。";
        return false;
    }
}
