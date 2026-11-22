package com.f1car.persistence;

import com.f1car.model.CircuitFeedbackDiscussion;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Predicate;

/** Bounded discussion library with a shared lock and a content check before publication. */
public final class CircuitFeedbackDiscussionStore {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final String STALE="磁盘话题已改变，原文与本次草稿保留；请重新读取后再查看合并。";
    private static final String UNPUBLISHED="反馈讨论尚未保存；本次预览与草稿保留，可修复目录或释放容量后重试。";
    public static final int CAPACITY=16,MAX_BYTES=24_000_000;
    private final Path file,lock;
    private final ArchiveWriter.Filesystem filesystem;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("circuit-feedback-discussions");
    private List<CircuitFeedbackDiscussion> entries=List.of();
    private ArchiveWriter.Stamp expected=ArchiveWriter.ABSENT;
    private String warning="";
    private boolean writable=true;

    public CircuitFeedbackDiscussionStore(Path directory) {
        this(directory,ArchiveWriter.FORCED_FILES);
    }

    CircuitFeedbackDiscussionStore(Path directory,ArchiveWriter.Filesystem filesystem) {
        file=directory.resolve("feedback-discussions.json");
        lock=directory.resolve("feedback-discussions.lock");
        this.filesystem=Objects.requireNonNull(filesystem);
        reload();
    }

    public Path file() {return file;}
    public List<CircuitFeedbackDiscussion> entries() {return entries;}
    public CircuitFeedbackDiscussion find(String id) {return find(entries,id);}
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public SaveDiagnostics.Failure lastSaveFailure() {return diagnostics.failure();}
    public boolean currentOnDisk() {
        if(!writable) return false;
        try {ArchiveWriter.verifyStamp(file,expected,LinkOption.NOFOLLOW_LINKS);return true;}
        catch(IOException error) {return false;}
    }

    /** Return the viewed thread if the selection still matches on disk, without adopting disk changes. */
    public CircuitFeedbackDiscussion currentThread(String id,Predicate<CircuitFeedbackDiscussion> matches) {
        var viewed=find(id);
        if(viewed==null || !matches.test(viewed)) return null;
        try {
            ArchiveWriter.verifyStamp(file,expected,LinkOption.NOFOLLOW_LINKS);
            return viewed;
        }catch(ArchiveWriter.StaleArchiveException changed) {
            try {
                var latest=find(decode(read()),id);
                return latest!=null && matches.test(latest)?viewed:null;
            }catch(IOException | RuntimeException unreadable) {
                return null;
            }
        }catch(IOException unreadable) {
            return null;
        }
    }

    public CircuitFeedbackDiscussion.Merge preview(CircuitFeedbackDiscussion incoming) {return CircuitFeedbackDiscussion.merge(find(incoming.id()),incoming);}

    public void reload() {
        try {
            byte[] bytes=read();
            var next=decode(bytes);
            var stamp=bytes==null?ArchiveWriter.ABSENT:ArchiveWriter.stamp(bytes);
            entries=next;
            expected=stamp;
            writable=true;
            warning="";
        }catch(IOException | RuntimeException error) {
            writable=false;
            warning="反馈讨论库无法读取；原文件、已读话题和本次草稿保留，修复后重新读取。";
        }
    }

    private static List<CircuitFeedbackDiscussion> decode(byte[] bytes) throws IOException {
        if(bytes==null) return List.of();
        var root=PartsJson.read(bytes,MAX_BYTES,28).object("version","entries");
        root.child("version").integer(1,1);
        var rows=root.child("entries");
        int count=rows.array(0,CAPACITY).size();
        var loaded=new ArrayList<CircuitFeedbackDiscussion>(count);
        for(int i=0;i<count;i++) loaded.add(CircuitFeedbackDiscussionCodec.decode(rows.index(i)));
        return checked(loaded);
    }

    private static CircuitFeedbackDiscussion find(List<CircuitFeedbackDiscussion> candidates,String id) {
        for(var candidate:candidates) if(candidate.id().equals(id)) return candidate;
        return null;
    }

    public boolean accept(CircuitFeedbackDiscussion.Merge reviewed) {
        if(reviewed==null || !Objects.equals(reviewed.before(),find(reviewed.incoming().id()))) {
            warning="本地话题已变化，请重新预览合并。";
            return false;
        }
        var actual=preview(reviewed.incoming());
        if(!actual.equals(reviewed) || !actual.valid()) {
            warning="原文、背景或回复关系存在冲突，已有话题保留。";
            return false;
        }
        if(actual.candidate().equals(actual.before())) return acceptUnchanged();
        var next=new ArrayList<>(entries);
        next.removeIf(value->value.id().equals(actual.candidate().id()));
        next.add(0,actual.candidate());
        return commit(next);
    }

    private boolean acceptUnchanged() {
        if(!writable) return false;
        try {
            ArchiveWriter.verifyStamp(file,expected,LinkOption.NOFOLLOW_LINKS);
            warning="";
            return true;
        }catch(ArchiveWriter.StaleArchiveException changed) {
            writable=false;
            warning=STALE;
        }catch(IOException | RuntimeException unreadable) {
            warning="无法核对已存话题，原文和本次草稿保留。";
        }
        return false;
    }

    public boolean remove(CircuitFeedbackDiscussion expected) {
        if(expected==null || !expected.equals(find(expected.id()))) {
            warning="话题已变化，请重新查看。";
            return false;
        }
        return commit(entries.stream().filter(value->!value.equals(expected)).toList());
    }

    private static List<CircuitFeedbackDiscussion> checked(List<CircuitFeedbackDiscussion> values) {
        var copy=List.copyOf(values);
        if(copy.size()>CAPACITY || copy.stream().map(CircuitFeedbackDiscussion::id).distinct().count()!=copy.size())
            throw new IllegalArgumentException("Feedback discussion capacity or identity");
        return copy;
    }

    private boolean commit(List<CircuitFeedbackDiscussion> values) {
        if(!writable) return false;
        if(values.size()>CAPACITY) {
            warning="反馈讨论库最多保存 "+CAPACITY+" 个话题。本次草稿保留，可先导出并移除一个话题。";
            return false;
        }
        diagnostics.begin();
        boolean published=false;
        try {
            var next=checked(values);
            var root=new JsonObject();
            root.addProperty("version",1);
            var rows=new JsonArray();
            for(var value:next) rows.add(CircuitFeedbackDiscussionCodec.tree(value));
            root.add("entries",rows);
            byte[] encoded=JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
            diagnostics.encoded(encoded.length);
            if(encoded.length>MAX_BYTES) throw new IOException("Discussion archive exceeds size bound");
            var newStamp=ArchiveWriter.stamp(encoded);
            var previous=expected;
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Discussion archive is busy");
                ArchiveWriter.bytes(file,encoded,MAX_BYTES,"feedback-discussions-",diagnostics,filesystem,
                        target->ArchiveWriter.verifyStamp(target,previous,LinkOption.NOFOLLOW_LINKS));
                entries=next;
                expected=newStamp;
                warning="";
                published=true;
            }
            return true;
        }catch(ArchiveWriter.StaleArchiveException changed) {
            diagnostics.failed(changed);
            writable=false;
            warning=STALE;
        }catch(IOException | RuntimeException error) {
            if(published) {
                diagnostics.cleanupFailed(error);
                return true;
            }
            diagnostics.failed(error);
            warning=UNPUBLISHED;
        }
        return false;
    }

    private byte[] read() throws IOException {
        try {
            var attrs=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attrs.isRegularFile() || attrs.size()>MAX_BYTES) throw new IOException("Invalid discussion archive");
            try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=stream.readNBytes(MAX_BYTES+1);
                if(bytes.length>MAX_BYTES) throw new IOException("Discussion archive exceeds size bound");
                return bytes;
            }
        }catch(NoSuchFileException missing) {
            return null;
        }
    }
}
