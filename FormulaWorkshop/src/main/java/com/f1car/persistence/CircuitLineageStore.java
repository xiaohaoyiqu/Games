package com.f1car.persistence;

import com.f1car.model.CircuitLineage;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Independent, bounded declarations. Removing a declaration never removes maps or packages. */
public final class CircuitLineageStore {
    public static final int CAPACITY=24,MAX_BYTES=128000;
    private record Archive(int version,List<CircuitLineage> entries) {private Archive {if(version!=1) throw new IllegalArgumentException("Unknown lineage archive");entries=checked(entries);}}
    private final Path file;
    private final GuardedArchive archive;
    private List<CircuitLineage> entries=List.of();private boolean writable=true;private String warning="";
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    public CircuitLineageStore(Path directory) {
        file=directory.resolve("circuit-lineages.json");
        archive=new GuardedArchive(directory,"circuit-lineages",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}
    public List<CircuitLineage> entries() {return entries;}
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public CircuitLineage find(String child) {return entries.stream().filter(value->value.childId().equals(child)).findFirst().orElse(null);}
    public boolean refreshIfChanged() {
        if(writable) {
            try {archive.verifyAccepted();return true;}
            catch(IOException | RuntimeException changed) { /* Adopt a complete, validated read. */ }
        }
        reload();
        return writable;
    }
    public void reload() {
        try {
            byte[] source=archive.readSource();
            if(source==null) entries=List.of();
            else {
                var root=PartsJson.read(source,MAX_BYTES,10);
                ActivityCardCodec.validate(root.value(),Archive.class);
                entries=JSON.fromJson(root.value(),Archive.class).entries();
            }
            writable=true;
            warning="";
        }
        catch(IOException | RuntimeException | ReflectiveOperationException error) {writable=false;warning="修订声明库无法读取；原文件与已读声明保留，修复后重新读取。";}
    }
    public List<String> review(CircuitLineage value,boolean replace) {
        var issues=new ArrayList<String>();var old=find(value.childId());if(old!=null && !old.childDigest().equals(value.childDigest())) issues.add("相同包身份对应不同摘要，请保留原声明并核对文件。");if(old!=null && !old.equals(value) && !replace) issues.add("该包已有不同修订声明，需要明确预览后替换。");var next=new ArrayList<>(entries.stream().filter(row->!row.childId().equals(value.childId())).toList());next.add(value);try {checked(next);}catch(IllegalArgumentException error) {issues.add(error.getMessage());}return List.copyOf(issues);
    }
    public boolean put(CircuitLineage value,boolean replace) {
        var issues=review(value,replace);
        if(!issues.isEmpty()) {warning=String.join(" ",issues);return false;}
        if(value.equals(find(value.childId()))) return verifyAccepted(value);
        var next=new ArrayList<>(entries.stream().filter(row->!row.childId().equals(value.childId())).toList());
        next.add(value);
        return commit(next);
    }
    public boolean verifyAccepted(CircuitLineage expected) {
        if(!writable) return false;
        if(expected==null || !expected.equals(find(expected.childId()))) {
            warning="修订声明已变化，请重新查看。";
            return false;
        }
        try {
            archive.verifyAccepted();
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error);
        }
    }
    public boolean remove(String id) {if(find(id)==null) return false;return commit(entries.stream().filter(value->!value.childId().equals(id)).toList());}
    private boolean commit(List<CircuitLineage> next) {
        if(!writable) return false;
        try {
            var candidate=new Archive(1,next);
            archive.commit(JSON.toJson(candidate),false);
            entries=candidate.entries();
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error);
        }
    }
    private boolean rejected(Exception error) {
        warning=error instanceof ArchiveWriter.StaleArchiveException
                ?"磁盘上的修订声明库已更新，请重新读取后再核对。"
                :"无法核对或保存修订声明，已有声明保留，修复目标后重试。";
        return false;
    }
    private static List<CircuitLineage> checked(List<CircuitLineage> values) {
        values=List.copyOf(values);if(values.size()>CAPACITY) throw new IllegalArgumentException("修订声明最多二十四份，请先移除不再需要的声明。");var byId=new HashMap<String,CircuitLineage>();for(var value:values) if(byId.put(value.childId(),value)!=null) throw new IllegalArgumentException("重复修订声明。");
        for(var value:values) {var visited=new HashSet<String>();var cursor=value;while(cursor!=null) {if(!visited.add(cursor.childId())) throw new IllegalArgumentException("修订声明形成循环，请选择真正的父包。");var next=byId.get(cursor.parent().id());cursor=next!=null && next.childDigest().equals(cursor.parent().digest())?next:null;}}
        return values;
    }
}
