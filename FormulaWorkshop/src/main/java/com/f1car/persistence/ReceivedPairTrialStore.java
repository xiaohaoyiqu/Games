package com.f1car.persistence;

import com.f1car.model.PairTrialShare;
import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Independent received declarations; never installed, driven or promoted into local experiments. */
public final class ReceivedPairTrialStore {
    public static final int CAPACITY=4,MAX_BYTES=32_050_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Path file;
    private final GuardedArchive archive;
    private List<PairTrialShare> entries=List.of();
    private PairTrialShare pending;
    private boolean writable=true,stale;
    private String warning="";
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("received-pair-trials");
    public ReceivedPairTrialStore(Path directory) {
        file=directory.resolve("received-pair-trials.json");
        archive=new GuardedArchive(directory,"received-pair-trials",MAX_BYTES,diagnostics);
        reload();
    }
    public Path file() {return file;}public List<PairTrialShare> entries() {return entries;}public PairTrialShare pending() {return pending;}public boolean writable() {return writable;}public String warning() {return warning;}public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}
    public PairTrialShare find(String id) {return entries.stream().filter(value->value.id().equals(id)).findFirst().orElse(null);}
    public boolean receive(PairTrialShare value) {
        Objects.requireNonNull(value);if(pending!=null && !pending.equals(value)) {warning="先重试或明确取消原收到待存；同份原文保持。";return false;}
        var old=find(value.id());if(old!=null && pending==null) return matchSaved(old,value);
        pending=value;return retry();
    }
    public boolean retry() {
        if(pending==null) return true;if(!writable) {warning="收到库读取受阻；原候选保留，修复后明确重读。";return false;}
        var old=find(pending.id());
        if(old!=null) {
            if(!matchSaved(old,pending)) return false;
            pending=null;
            return true;
        }
        if(entries.size()>=CAPACITY) {
            if(!verifySaved()) return false;
            warning="最多四份收到四条件报告；原候选保持，请明确移除一份后再重试。";return false;
        }
        var next=new ArrayList<>(entries);next.add(pending);if(!commit(next)) return false;pending=null;return true;
    }
    public void cancelPending() {pending=null;warning=writable?"":"收到库读取受阻；修复后明确重读。";}
    public boolean remove(PairTrialShare expected) {
        if(expected==null || !expected.equals(find(expected.id()))) {warning="收到记录已变化，请重新选择。";return false;}
        if(!commit(entries.stream().filter(value->!value.equals(expected)).toList())) return false;
        if(pending!=null) warning="所选记录已移除；原收到待存仍须明确重试。";return true;
    }
    private boolean commit(List<PairTrialShare> values) {
        if(!writable) return false;
        try {
            archive.commit(output->{
                try(var writer=new JsonWriter(output)) {
                    writer.beginObject().name("version").value(1).name("entries").beginArray();
                    for(var value:values) JSON.toJson(PairTrialShareCodec.tree(value),writer);
                    writer.endArray().endObject();
                }
            },false);
            entries=List.copyOf(values);stale=false;warning="";return true;
        }
        catch(IOException | RuntimeException error) {
            return rejected(error);
        }
    }
    public boolean reload() {
        if(pending!=null && !stale) {warning="原收到待存仍在内存；先重试或明确取消，再重读。";return false;}
        try {
            byte[] source=archive.readSource();
            if(source==null) entries=List.of();
            else {
                var root=new PartsJson.Node(TuningLapStudyCodec.readTree(source,MAX_BYTES,6_100_000),"").object("version","entries");
                root.child("version").integer(1,1);
                var next=new ArrayList<PairTrialShare>();var ids=new HashSet<String>();
                for(var row:root.child("entries").array(0,CAPACITY)) {var value=PairTrialShareCodec.decode(row);if(!ids.add(value.id())) throw new IOException("Duplicate share identity");next.add(value);}
                if(!entries.equals(next)) entries=List.copyOf(next);
            }
            writable=true;stale=false;warning=pending==null?"":"已重读最新收到库，原候选可核对后重试。";return true;
        }
        catch(IOException | RuntimeException error) {writable=false;warning="收到四条件库无法读取；原文件与已读内容保持，修复后明确重读。";return false;}
    }
    private boolean matchSaved(PairTrialShare old,PairTrialShare value) {
        if(!old.equals(value)) {warning="同一分享身份包含不同内容或声明；原收到记录保持。";return false;}
        return writable && verifySaved();
    }
    private boolean verifySaved() {
        try {archive.verifyAccepted();stale=false;warning="";return true;}
        catch(IOException | RuntimeException error) {return rejected(error);}
    }
    private boolean rejected(Exception error) {
        if(error instanceof ArchiveWriter.StaleArchiveException) {
            stale=true;
            warning="收到四条件库已由另一窗口更新，请重新读取，待存候选保留。";
        } else warning="收到报告尚未保存；原库与同份候选在本次进程内保持，可明确重试或取消。";
        return false;
    }
}
