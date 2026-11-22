package com.f1car.persistence;

import com.f1car.model.CircuitFeedback;
import com.f1car.model.CircuitFeedback.Entry;
import com.f1car.model.CircuitFeedback.LocationSource;
import com.f1car.model.CircuitFeedbackReply;
import com.f1car.model.CircuitFeedbackTrial;
import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent bounded inbox; successful disk writes precede publication to the UI. */
public final class CircuitFeedbackStore {
    public static final int CAPACITY=32,MAX_BYTES=2000000;
    public static final int REPLY_CAPACITY=64,REPLY_MAX_BYTES=8000000;
    private record Archive(int version,List<Entry> entries,List<CircuitFeedbackReply> replies,List<CircuitFeedbackTrial> trials) {
        private Archive {
            if(version<1 || version>3 || version==1 && replies!=null || version==2 && (replies==null || replies.isEmpty())
                    || (version==3)!=(trials!=null)) throw new IllegalArgumentException("Unknown feedback archive");
            entries=checked(entries);
            if(replies!=null) {
                replies=List.copyOf(replies);
                if(replyLimit(replies) || replies.stream().map(CircuitFeedbackReply::id).distinct().count()!=replies.size())
                    throw new IllegalArgumentException("Duplicate identity or too many replies");
                for(var reply:replies) if(entries.stream().noneMatch(reply::matches))
                    throw new IllegalArgumentException("Unknown reply source");
            }
            if(trials!=null) {
                trials=List.copyOf(trials);
                var sources=new HashMap<String,Entry>();
                for(var entry:entries) sources.put(entry.feedback().id(),entry);
                var notes=new HashMap<String,Set<String>>();
                var attempts=new HashSet<String>();
                for(var trial:trials) {
                    if(!trial.belongsTo(sources.get(trial.feedbackId()))
                            || !attempts.add(trial.id())
                            || !notes.computeIfAbsent(trial.feedbackId(),id->new HashSet<>()).add(trial.noteId()))
                        throw new IllegalArgumentException("Unknown or duplicate feedback trial");
                }
            }
        }
    }
    private record LoadedArchive(Archive contents,ArchiveWriter.Stamp stamp) {}
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Path file;
    private final ArchiveWriter.Filesystem filesystem=ArchiveWriter.FORCED_FILES;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("circuit-feedback");
    private List<Entry> entries=List.of();
    private List<CircuitFeedbackReply> replies=List.of();
    private List<CircuitFeedbackTrial> trials=List.of();
    private String warning="";
    private boolean writable=true;
    private int savedVersion=1;
    private ArchiveWriter.Stamp acceptedStamp=ArchiveWriter.ABSENT;
    public CircuitFeedbackStore(Path directory) {file=directory.resolve("circuit-feedback.json");reload();}
    public Path file() {return file;}
    public List<Entry> entries() {return entries;}
    public List<CircuitFeedbackReply> replies() {return replies;}
    public List<CircuitFeedbackReply> replies(String feedbackId) {return replies.stream().filter(reply->reply.feedback().id().equals(feedbackId)).toList();}
    public Entry find(String id) {return find(entries,id);}
    private static Entry find(List<Entry> entries,String id) {
        return entries.stream().filter(row->row.feedback().id().equals(id)).findFirst().orElse(null);
    }
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public CircuitFeedbackTrial trial(String feedbackId,String noteId) {
        return trial(trials,feedbackId,noteId);
    }
    private static CircuitFeedbackTrial trial(List<CircuitFeedbackTrial> trials,String feedbackId,String noteId) {
        return trials.stream().filter(row->row.feedbackId().equals(feedbackId) && row.noteId().equals(noteId))
                .findFirst().orElse(null);
    }

    /** Check the selected record against the last read archive and its current disk contents. */
    public boolean currentTrial(CircuitFeedbackTrial expected) {
        if(expected==null || !writable || !expected.equals(trial(expected.feedbackId(),expected.noteId()))) return false;
        try {
            ArchiveWriter.verifyStamp(file,acceptedStamp);
            return true;
        } catch(IOException error) {
            return false;
        }
    }

    /** Verify only the selected note, its binding and any selected trial, leaving the accepted archive alone. */
    public boolean currentLocation(LocationSource source,CircuitFeedbackTrial expectedTrial) {
        if(!writable || !matchesLocation(entries,trials,source,expectedTrial)) return false;
        try {
            ArchiveWriter.verifyStamp(file,acceptedStamp);
            return true;
        } catch(ArchiveWriter.StaleArchiveException changed) {
            try {
                var latest=readArchive().contents();
                return matchesLocation(latest.entries(),latest.trials(),source,expectedTrial);
            } catch(IOException | RuntimeException | ReflectiveOperationException unreadable) {
                return false;
            }
        } catch(IOException | RuntimeException unreadable) {
            return false;
        }
    }
    private static boolean matchesLocation(List<Entry> entries,List<CircuitFeedbackTrial> trials,LocationSource source,
                                           CircuitFeedbackTrial expectedTrial) {
        if(source==null || !source.matches(find(entries,source.entry().feedback().id()))) return false;
        return expectedTrial==null || expectedTrial.matches(source) && trials!=null
                && expectedTrial.equals(trial(trials,expectedTrial.feedbackId(),expectedTrial.noteId()));
    }

    public void reload() {
        try {
            var loaded=readArchive();
            accept(loaded.contents(),loaded.stamp());
        } catch(IOException | RuntimeException | ReflectiveOperationException error) {
            writable=false;
            warning="反馈库无法读取；原文件和已读内容保留，修复后重新读取。";
        }
    }

    private LoadedArchive readArchive() throws IOException,ReflectiveOperationException {
        try(var stream=Files.newInputStream(file)) {
            byte[] bytes=stream.readNBytes(REPLY_MAX_BYTES+1);
            var tree=PartsJson.read(new ByteArrayInputStream(bytes),REPLY_MAX_BYTES).value().getAsJsonObject().deepCopy();
            int version=tree.get("version").getAsInt();
            if(version==1 && !tree.has("replies")) tree.add("replies",JsonNull.INSTANCE);
            if(version<3 && !tree.has("trials")) tree.add("trials",JsonNull.INSTANCE);
            ActivityCardCodec.validate(tree,Archive.class);
            var archive=JSON.fromJson(tree,Archive.class);
            if(archive.version()==1 && bytes.length>MAX_BYTES) throw new IOException("Legacy feedback archive too large");
            return new LoadedArchive(archive,ArchiveWriter.stamp(bytes));
        } catch(NoSuchFileException absent) {
            return new LoadedArchive(new Archive(1,List.of(),null,null),ArchiveWriter.ABSENT);
        }
    }

    private void accept(Archive archive,ArchiveWriter.Stamp stamp) {
        entries=archive.entries();
        replies=archive.replies()==null?List.of():archive.replies();
        trials=archive.trials()==null?List.of():archive.trials();
        savedVersion=archive.version();
        acceptedStamp=stamp;
        warning="";
        writable=true;
    }
    public boolean receive(CircuitFeedback feedback) {var old=find(feedback.id());if(old!=null) {if(!old.feedback().equals(feedback)) {warning="相同反馈身份含不同原文，请核对文件；已有意见保留。";return false;}if(writable) warning="";return writable;}var next=new ArrayList<>(entries);next.add(Entry.received(feedback));return commit(next);}
    public boolean update(Entry expected,Entry next) {if(expected==null || next==null || !expected.equals(find(expected.feedback().id())) || !expected.feedback().equals(next.feedback())) {warning="反馈或处理记录已变化，请重新查看。";return false;}if(expected.equals(next)) {if(writable) warning="";return writable;}var rows=new ArrayList<>(entries);rows.set(rows.indexOf(expected),next);return commit(rows);}
    public boolean receiveReply(CircuitFeedbackReply reply) {
        var duplicate=replies.stream().filter(old->old.id().equals(reply.id())).findFirst().orElse(null);if(duplicate!=null) {if(!duplicate.equals(reply)) {warning="相同答复身份含不同内容，已有答复保留。";return false;}if(writable) warning="";return writable;}
        var source=find(reply.feedback().id());if(source!=null && !reply.matches(source)) {warning="答复所指原文与本地不同，请核对源反馈；已有内容保留。";return false;}
        var next=new ArrayList<>(entries);if(source==null) next.add(Entry.received(reply.feedback()));var received=new ArrayList<>(replies);received.add(reply);return commit(next,received);
    }
    public boolean removeReply(CircuitFeedbackReply expected) {if(expected==null || !replies.contains(expected)) {warning="答复已变化，请重新查看。";return false;}return commit(entries,replies.stream().filter(reply->!reply.equals(expected)).toList());}
    public boolean remove(Entry expected) {
        if(expected==null || !expected.equals(find(expected.feedback().id()))) {warning="反馈已变化，请重新查看。";return false;}
        return commit(entries.stream().filter(row->!row.equals(expected)).toList(),
                replies.stream().filter(reply->!reply.matches(expected)).toList(),
                trials.stream().filter(trial->!trial.belongsTo(expected)).toList());
    }

    public boolean saveTrial(LocationSource source,CircuitFeedbackTrial value,CircuitFeedbackTrial expected) {
        if(source==null || value==null || !value.matches(source) || !source.matches(find(value.feedbackId()))
                || !Objects.equals(expected,trial(value.feedbackId(),value.noteId()))) {
            warning="反馈对应或已存试驾已变化，请重新核对。";
            return false;
        }
        var next=new ArrayList<>(trials);
        next.remove(expected);
        next.add(value);
        return commit(entries,replies,next);
    }

    public boolean removeTrial(CircuitFeedbackTrial expected) {
        if(expected==null || !expected.equals(trial(expected.feedbackId(),expected.noteId()))) {
            warning="已存试驾已变化，请重新查看。";
            return false;
        }
        return commit(entries,replies,trials.stream().filter(value->!value.equals(expected)).toList());
    }

    private boolean commit(List<Entry> next) {return commit(next,replies);}
    private boolean commit(List<Entry> next,List<CircuitFeedbackReply> received) {return commit(next,received,trials);}

    private boolean commit(List<Entry> next,List<CircuitFeedbackReply> received,List<CircuitFeedbackTrial> localTrials) {
        if(!writable) return false;
        diagnostics.begin();
        boolean published=false;
        try {
            int version=savedVersion==3 || !localTrials.isEmpty()?3:received.isEmpty()?1:2;
            var archive=new Archive(version,next,received.isEmpty()?null:received,version==3?localTrials:null);
            var tree=JSON.toJsonTree(archive).getAsJsonObject();
            if(version==1) tree.remove("replies");
            if(version<3) tree.remove("trials");
            byte[] encoded=JSON.toJson(tree).getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(file.resolveSibling("circuit-feedback.lock"));var held=channel.tryLock()) {
                if(held==null) throw new IOException("Feedback archive is busy");
                ArchiveWriter.verifyStamp(file,acceptedStamp);
                backupBeforeUpgrade(version);
                ArchiveWriter.bytes(file,encoded,version==1?MAX_BYTES:REPLY_MAX_BYTES,"circuit-feedback-",diagnostics,
                        filesystem,target->ArchiveWriter.verifyStamp(target,acceptedStamp));
                accept(archive,ArchiveWriter.stamp(encoded));
                published=true;
            }
            return true;
        } catch(IOException | RuntimeException error) {
            if(published) return true;
            warning=next.size()>CAPACITY?"反馈库已满三十二份；请先导出或移除不再需要的意见。"
                    :replyLimit(received)?"答复最多六十四份、每份反馈八份；请先导出或移除不再需要的答复。"
                    :"反馈库未保存；请重新读取核对磁盘版本，或检查目标和备份路径后重试。";
            return false;
        }
    }

    private void backupBeforeUpgrade(int version) throws IOException {
        if(version<=savedVersion || ArchiveWriter.ABSENT.equals(acceptedStamp)) return;
        var backup=file.resolveSibling(version==2?"circuit-feedback.pre-replies-v1.json"
                :"circuit-feedback.pre-author-trials-v"+savedVersion+".json");
        if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(backup,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Legacy feedback backup path is invalid");
        if(Files.notExists(backup)) Files.copy(file,backup);
    }
    private static boolean replyLimit(List<CircuitFeedbackReply> values) {if(values.size()>REPLY_CAPACITY) return true;var counts=new HashMap<String,Integer>();for(var value:values) if(counts.merge(value.feedback().id(),1,Integer::sum)>8) return true;return false;}
    private static List<Entry> checked(List<Entry> values) {values=List.copyOf(values);if(values.size()>CAPACITY || values.stream().map(row->row.feedback().id()).distinct().count()!=values.size()) throw new IllegalArgumentException("Feedback capacity or duplicate identity");return values;}
}
