package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded immutable discussions. Merge previews must still match the accepted local snapshot. */
public final class PartsDiscussionStore {
    public static final int CAPACITY=16,MAX_BYTES=12000000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final String CONFLICT="讨论库已由另一窗口更新，请重新读取后再预览；已有原文和本次草稿保留。";
    private static final String SAVE_FAILED="讨论未保存，已有原文与本次预览保留，请修复目标后重试。";
    private final Path file;
    private final GuardedArchive archive;
    private List<PartsDiscussion> entries=List.of();
    private boolean writable=true;
    private String warning="";
    public PartsDiscussionStore(Path directory) {
        file=directory.resolve("parts-discussions.json");
        archive=new GuardedArchive(directory,"parts-discussions",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}
    public List<PartsDiscussion> entries() {return entries;}
    public PartsDiscussion find(String id) {return entries.stream().filter(value->value.id().equals(id)).findFirst().orElse(null);}
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public PartsDiscussion.Merge preview(PartsDiscussion incoming) {return PartsDiscussion.merge(find(incoming.id()),incoming);}
    public void reload() {
        try {
            byte[] source=archive.readSource();
            var values=new ArrayList<PartsDiscussion>();
            if(source!=null) {
                var root=PartsJson.read(source,MAX_BYTES,24).object("version","entries");
                root.child("version").integer(1,1);
                var items=root.child("entries").array(0,CAPACITY);
                for(int i=0;i<items.size();i++) values.add(PartsDiscussionCodec.decode(root.child("entries").index(i)));
            }
            entries=checked(values);
            writable=true;
            warning="";
        } catch(IOException | RuntimeException error) {
            writable=false;
            warning="方案讨论无法读取；原文件与已读话题保留，修复后重新读取。";
        }
    }
    public boolean accept(PartsDiscussion.Merge reviewed) {
        if(reviewed==null || !Objects.equals(reviewed.before(),find(reviewed.incoming().id()))) {
            warning="本地讨论已变化，请重新预览新增内容。";
            return false;
        }
        var actual=preview(reviewed.incoming());
        if(!actual.equals(reviewed) || !actual.valid()) {
            warning="讨论背景或留言存在冲突，已有原文保留。";
            return false;
        }
        if(actual.candidate().equals(actual.before())) return verifyAccepted();
        var next=new ArrayList<>(entries);
        next.removeIf(value->value.id().equals(actual.candidate().id()));
        next.add(0,actual.candidate());
        return commit(next);
    }
    public boolean remove(PartsDiscussion expected) {if(expected==null || !expected.equals(find(expected.id()))) {warning="本地讨论已变化，请重新查看。";return false;}return commit(entries.stream().filter(value->!value.equals(expected)).toList());}
    private static List<PartsDiscussion> checked(List<PartsDiscussion> values) {values=List.copyOf(values);if(values.size()>CAPACITY || values.stream().map(PartsDiscussion::id).distinct().count()!=values.size()) throw new IllegalArgumentException("Discussion capacity or identity");return values;}
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
    private boolean commit(List<PartsDiscussion> values) {
        if(!writable) return false;
        if(values.size()>CAPACITY) {
            warning="最多保存十六个话题，请先导出或移除不需要的本地副本。";
            return false;
        }
        try {
            var next=checked(values);
            var root=new JsonObject();
            root.addProperty("version",1);
            var array=new JsonArray();
            for(var value:next) array.add(PartsDiscussionCodec.tree(value));
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
