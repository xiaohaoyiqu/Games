package com.f1car.persistence;

import com.f1car.model.CarLivery;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Local works and the applied frozen design are committed together, and published only after writing. */
public final class LiveryStore {
    public static final int CAPACITY=16,MAX_BYTES=500000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final String SAVE_FAILED="涂装未保存，原采用外观和草稿保留，修复目标或升级副本位置后可重试。";
    private final Path file;
    private final GuardedArchive archive;
    private List<CarLivery> entries=List.of();
    private CarLivery active;
    private boolean writable=true;
    private String warning="";
    private int archiveVersion=1;
    public LiveryStore(Path directory) {
        file=directory.resolve("liveries.json");
        archive=new GuardedArchive(directory,"liveries",MAX_BYTES,new SaveDiagnostics("liveries"),
                ArchiveWriter.FORCED_FILES,"liveries.pre-regions-v1-");
        reload();
    }
    public Path file() {return file;}
    public List<CarLivery> entries() {return entries;}
    public CarLivery active() {return active;}
    public CarLivery find(String id) {return entries.stream().filter(value->value.id().equals(id)).findFirst().orElse(null);}
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public int archiveVersion() {return archiveVersion;}
    public void reload() {
        try {
            var previous=archive.observedStamp();
            byte[] original=archive.readSource();
            if(writable && previous!=null && previous.equals(archive.observedStamp())) {
                warning="";
                return;
            }
            if(original==null) {
                entries=List.of();
                active=null;
                archiveVersion=1;
            } else {
                var root=PartsJson.read(original,MAX_BYTES,12);
                boolean hasActive=root.value().isJsonObject() && root.value().getAsJsonObject().has("active");
                if(hasActive) root.object("version","entries","active");
                else root.object("version","entries");
                int version=root.child("version").integer(1,2);
                var array=root.child("entries").array(0,CAPACITY);
                var values=new ArrayList<CarLivery>();
                for(int i=0;i<array.size();i++) values.add(LiveryCodec.decode(root.child("entries").index(i)));
                var next=checked(values);
                var nextActive=!hasActive || root.child("active").value().isJsonNull()?null:
                        LiveryCodec.decode(root.child("active"));
                if(version==1 && hasRegions(next,nextActive)) throw new IOException("Region design requires archive two");
                entries=next;
                active=nextActive;
                archiveVersion=version;
            }
            writable=true;
            warning="";
        } catch(IOException | RuntimeException error) {
            writable=false;
            warning="涂装库无法读取，原文件与已读作品保留，修复后重新读取。";
        }
    }
    public boolean save(CarLivery candidate,CarLivery expected) {
        Objects.requireNonNull(candidate);
        var before=find(candidate.id());
        if(candidate.equals(before)) return confirmCurrentArchive();
        if(!Objects.equals(before,expected)) {
            warning="同一作品已变化，请重新查看或另存副本。";
            return false;
        }
        var next=new ArrayList<>(entries);
        next.removeIf(value->value.id().equals(candidate.id()));
        next.add(0,candidate);
        return commit(next,active);
    }

    public boolean receive(CarLivery value) {
        var before=find(value.id());
        if(value.equals(before)) return confirmCurrentArchive();
        if(before!=null) {warning="同一身份的内容不同，已有作品保留；可将收到内容另存副本。";return false;}
        return save(value,null);
    }

    /** Confirms a reviewed work before replacing an editor draft. */
    public boolean confirmSavedWork(CarLivery expected) {
        if(expected==null || !expected.equals(find(expected.id()))) {
            warning="作品已变化，请重新查看。";
            return false;
        }
        return confirmCurrentArchive();
    }

    private boolean confirmCurrentArchive() {
        if(!writable) return false;
        try {
            archive.verifyAccepted();
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error);
        }
    }

    public boolean apply(CarLivery expected,String vehicle) {if(expected==null || !expected.equals(find(expected.id())) || !expected.vehicle().equals(vehicle)) {warning="请先保存当前车型的作品，再采用已保存版本。";return false;}return commit(entries,expected);}
    public boolean reset() {return commit(entries,null);}
    public boolean remove(CarLivery expected) {if(expected==null || !expected.equals(find(expected.id()))) {warning="作品已变化，请重新查看。";return false;}return commit(entries.stream().filter(value->!value.equals(expected)).toList(),active);}
    private static List<CarLivery> checked(List<CarLivery> values) {values=List.copyOf(values);if(values.size()>CAPACITY || values.stream().map(CarLivery::id).distinct().count()!=values.size()) throw new IllegalArgumentException("Livery capacity or identity");return values;}
    private static boolean hasRegions(List<CarLivery> values,CarLivery active) {
        return values.stream().anyMatch(value->value.version()==2) || active!=null && active.version()==2;
    }
    private boolean commit(List<CarLivery> values,CarLivery chosen) {
        if(!writable) return false;
        if(entries.equals(values) && Objects.equals(active,chosen)) return confirmCurrentArchive();
        if(values.size()>CAPACITY) {
            warning="最多十六份作品，请先导出或移除库副本。";
            return false;
        }
        try {
            var next=checked(values);
            int version=Math.max(archiveVersion,hasRegions(next,chosen)?2:1);
            var root=new JsonObject();
            root.addProperty("version",version);
            var array=new JsonArray();
            next.forEach(value->array.add(LiveryCodec.tree(value)));
            root.add("entries",array);
            root.add("active",chosen==null?JsonNull.INSTANCE:LiveryCodec.tree(chosen));
            archive.commit(JSON.toJson(root),archiveVersion==1 && version==2);
            entries=next;
            active=chosen;
            archiveVersion=version;
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error);
        }
    }
    private boolean rejected(Exception error) {
        if(error instanceof ArchiveWriter.StaleArchiveException) {
            writable=false;
            warning="磁盘上的涂装库已变化，请重新读取作品库，再核对本次草稿。";
        } else {
            warning=SAVE_FAILED;
        }
        return false;
    }
}
