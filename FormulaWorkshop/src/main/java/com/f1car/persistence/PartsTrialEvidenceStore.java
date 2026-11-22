package com.f1car.persistence;

import com.f1car.model.*;
import com.f1car.model.PartsTrialEvidence.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Sixteen immutable report snapshots, separate from local driving history and named-plan formats. */
public final class PartsTrialEvidenceStore {
    public static final int CAPACITY=16,MAX_BYTES=8000000;
    private record Row(Origin origin,String localPlanId,JsonElement evidence) {}
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final String CONFLICT="方案试车记录库已由另一窗口更新，请重新读取后再查看，已有内容保留。";
    private static final String SAVE_FAILED="方案试车记录未保存，已有内容保留，请修复目标后重试。";
    private final Path file;
    private final GuardedArchive archive;
    private List<Entry> entries=List.of();
    private boolean writable=true;
    private String warning="";
    public PartsTrialEvidenceStore(Path directory) {
        file=directory.resolve("parts-trial-evidence.json");
        archive=new GuardedArchive(directory,"parts-trial-evidence",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}
    public List<Entry> entries() {return entries;}
    public Entry find(String id) {return entries.stream().filter(row->row.evidence().id().equals(id)).findFirst().orElse(null);}
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public void reload() {
        try {
            byte[] source=archive.readSource();
            var rows=new ArrayList<Entry>();
            if(source!=null) {
                var root=PartsJson.read(source,MAX_BYTES,18).object("version","entries");
                root.child("version").integer(1,1);
                var values=root.child("entries").array(0,CAPACITY);
                for(int i=0;i<values.size();i++) {
                    var row=root.child("entries").index(i).object("origin","localPlanId","evidence");
                    var link=row.child("localPlanId").value();
                    ActivityCardCodec.validate(link,String.class);
                    rows.add(new Entry(PartsTrialEvidenceCodec.decode(row.child("evidence")),
                            row.child("origin").enumeration(Origin.class),link.isJsonNull()?null:link.getAsString()));
                }
            }
            entries=checked(rows);
            writable=true;
            warning="";
        } catch(IOException | RuntimeException | ReflectiveOperationException error) {
            writable=false;
            warning="方案试车记录无法读取；原文件与已读内容保留，修复后重新读取。";
        }
    }
    public boolean receive(PartsTrialEvidence value) {return put(new Entry(value,Origin.RECEIVED,null));}
    public boolean capture(PartsTrialEvidence value,PartsPlan local) {return put(new Entry(value,Origin.LOCAL,null).link(local));}
    private boolean put(Entry value) {
        var old=find(value.evidence().id());
        if(old!=null) {
            if(old.evidence().equals(value.evidence())) return verifyAccepted();
            warning="同身份试车记录包含不同内容，已有记录保留。";
            return false;
        }
        var next=new ArrayList<>(entries);
        next.add(value);
        return commit(next);
    }
    public boolean link(Entry expected,PartsPlan plan) {
        if(!current(expected)) return false;
        Entry linked;
        try {
            linked=expected.link(plan);
        } catch(IllegalArgumentException error) {
            warning="命名卡的部件或参数不对应这份记录，已有关系保留。";
            return false;
        }
        if(linked.equals(expected)) return verifyAccepted();
        var next=new ArrayList<>(entries);
        next.set(next.indexOf(expected),linked);
        return commit(next);
    }
    public boolean remove(Entry expected) {return current(expected) && commit(entries.stream().filter(row->!row.equals(expected)).toList());}
    private boolean current(Entry expected) {if(expected==null || !expected.equals(find(expected.evidence().id()))) {warning="试车记录或关联已改变，请重新查看。";return false;}return true;}
    private static List<Entry> checked(List<Entry> values) {values=List.copyOf(values);if(values.size()>CAPACITY || values.stream().map(row->row.evidence().id()).distinct().count()!=values.size()) throw new IllegalArgumentException("Evidence capacity or duplicate identity");return values;}
    private boolean verifyAccepted() {
        if(!writable) return false;
        try {
            archive.verifyAccepted();
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            warning=error instanceof ArchiveWriter.StaleArchiveException?CONFLICT:SAVE_FAILED;
            return false;
        }
    }
    private boolean commit(List<Entry> values) {
        if(!writable) return false;
        if(values.size()>CAPACITY) {
            warning="最多收录十六份方案试车记录，请先导出或移除不需要的记录。";
            return false;
        }
        try {
            var next=checked(values);
            var root=new JsonObject();
            root.addProperty("version",1);
            var array=new JsonArray();
            for(var value:next) array.add(JSON.toJsonTree(new Row(value.origin(),value.localPlanId(),PartsTrialEvidenceCodec.tree(value.evidence()))));
            root.add("entries",array);
            archive.commit(JSON.toJson(root),false);
            entries=next;
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            warning=error instanceof ArchiveWriter.StaleArchiveException?CONFLICT:SAVE_FAILED;
            return false;
        }
    }
}
