package com.f1car.persistence;

import com.f1car.model.ExperienceRun;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded local valid attempts. Pending writes remain visible and can be retried explicitly. */
public class ExperienceStore {
    public static final int LIMIT=8*1024*1024;
    private static final int RECENT_LIMIT=4,BEST_LIMIT=24;
    private static final Gson GSON=new Gson();
    private static final Archive EMPTY=new Archive(1,List.of(),List.of());
    private static final int EMPTY_BYTES=GSON.toJson(EMPTY).getBytes(StandardCharsets.UTF_8).length;
    private static final Comparator<ExperienceRun> BY_TIME=Comparator.comparingDouble(ExperienceRun::duration)
            .thenComparingLong(ExperienceRun::recordedAt);
    private static ExperienceRun faster(ExperienceRun a,ExperienceRun b) {return BY_TIME.compare(a,b)<=0?a:b;}

    public record Archive(int version,List<ExperienceRun> recent,List<ExperienceRun> best) {
        public Archive {
            if(version!=1 || recent==null || recent.size()>RECENT_LIMIT || best==null || best.size()>BEST_LIMIT) throw new IllegalArgumentException("Invalid experience archive");
            recent=List.copyOf(recent);best=List.copyOf(best);
            if(recent.stream().map(ExperienceRun::id).distinct().count()!=recent.size() || best.stream().map(ExperienceRun::key).distinct().count()!=best.size()) throw new IllegalArgumentException("Duplicate experience archive entries");
            var byId=new HashMap<String,ExperienceRun>();
            for(var run:concat(recent,best)) { var old=byId.putIfAbsent(run.id(),run);if(old!=null && !old.equals(run)) throw new IllegalArgumentException("Conflicting experience ID"); }
            for(var winner:best) if(recent.stream().anyMatch(run->run.key().equals(winner.key()) && run.duration()<winner.duration())) throw new IllegalArgumentException("Experience best is slower than recent");
            best=best.stream().map(run->byId.get(run.id())).toList();
        }
        public List<ExperienceRun> all() { var result=new LinkedHashMap<String,ExperienceRun>();for(var run:concat(recent,best)) result.put(run.id(),run);return List.copyOf(result.values()); }
        private static List<ExperienceRun> concat(List<ExperienceRun> a,List<ExperienceRun> b) { var result=new ArrayList<>(a);result.addAll(b);return result; }
    }
    private static final class View {
        final Archive archive;
        final Map<String,ExperienceRun> byId=new LinkedHashMap<>();
        final Map<String,ExperienceRun> best=new HashMap<>();

        View(Archive archive) {
            this.archive=archive;
            for(var run:archive.all()) {
                byId.put(run.id(),run);
                best.merge(run.key(),run,ExperienceStore::faster);
            }
        }
    }

    private final Path directory;
    private GuardedArchive storage;
    private View accepted=new View(EMPTY),pending;
    private final List<ExperienceRun> staged=new ArrayList<>();
    private final Map<ExperienceRun,Integer> sizes=new IdentityHashMap<>();
    private boolean damaged,changedOnDisk;
    public String warning="";
    public ExperienceStore(Path directory) {
        this.directory=directory;
        storage=storage();
        try { accepted=read(storage); }
        catch(IOException | RuntimeException error) { damaged=true;warning="分段攻关记录无法读取，原文件已保留。"; }
    }

    private GuardedArchive storage() {
        var filesystem = new ArchiveWriter.Filesystem() {
            @Override public void write(Path path, byte[] bytes) throws IOException {ArchiveWriter.writeAndForce(path, bytes);}
            @Override public void atomic(Path temporary, Path target) throws IOException {ExperienceStore.this.replace(temporary, target);}
        };
        return new GuardedArchive(directory, "circuit-experience", LIMIT, new SaveDiagnostics("circuit-experience"),
                filesystem, "circuit-experience-damaged-");
    }

    private static View read(GuardedArchive source) throws IOException {
        String json=source.read();
        return new View(json==null?EMPTY:Objects.requireNonNull(GSON.fromJson(json,Archive.class)));
    }

    private View current() {return pending==null?accepted:pending;}
    public List<ExperienceRun> recent() {return current().archive.recent();}
    public List<ExperienceRun> savedRecent() {return accepted.archive.recent();}
    public List<ExperienceRun> best() {return current().archive.best();}
    public ExperienceRun best(String key) {return current().best.get(key);}
    public ExperienceRun savedBest(String key) {return accepted.best.get(key);}
    public boolean saved(ExperienceRun run) {return run!=null && run.equals(accepted.byId.get(run.id()));}
    public boolean dirty() {return pending!=null;}

    public boolean record(ExperienceRun run) {
        Objects.requireNonNull(run);
        var old=current().byId.get(run.id());
        if(old==null) old=accepted.byId.get(run.id());
        if(old!=null) {
            if(!old.equals(run)) throw new IllegalArgumentException("Conflicting experience ID");
            return flush();
        }
        View next;
        try {next=including(current(),List.of(run),sizes);}
        catch(RuntimeException error) {
            keepSizes();
            warning="本次分段记录无法编码，已有记录保留。";
            return false;
        }
        if(next==null) {
            keepSizes();
            warning="本次分段记录超过存储上限，已有记录保留。";
            return false;
        }
        pending=next;
        staged.add(run);
        staged.removeIf(value->!next.byId.containsKey(value.id()));
        keepSizes();
        return flush();
    }

    private static View including(View base,List<ExperienceRun> additions,Map<ExperienceRun,Integer> sizes) {
        if(additions.isEmpty()) return base;
        var recent=new ArrayList<>(base.archive.recent());
        var winners=new HashMap<>(base.best);
        var bestByUse=new LinkedHashMap<String,ExperienceRun>();
        for(var run:base.archive.best()) bestByUse.put(run.key(),run);
        for(var run:additions) {
            recent.add(0,run);
            String key=run.key();
            var winner=winners.merge(key,run,ExperienceStore::faster);
            bestByUse.remove(key);
            bestByUse.put(key,winner);
        }
        // Retain contenders until all additions have selected their condition's best.
        if(recent.size()>RECENT_LIMIT) recent.subList(RECENT_LIMIT,recent.size()).clear();
        var best=new ArrayList<>(bestByUse.values());
        if(best.size()>BEST_LIMIT) best.subList(0,best.size()-BEST_LIMIT).clear();

        // The empty encoding includes both array brackets. Each extra entry adds its UTF-8 size and possibly a comma.
        int bytes=EMPTY_BYTES+recent.size()+best.size()-2;
        for(var entry:recent) bytes+=sizes.computeIfAbsent(entry,ExperienceStore::measure);
        for(var entry:best) bytes+=sizes.computeIfAbsent(entry,ExperienceStore::measure);
        while(bytes>LIMIT) {
            if(best.size()>1) bytes-=1+sizes.get(best.remove(0));
            else if(recent.size()>1) bytes-=1+sizes.get(recent.remove(recent.size()-1));
            else return null;
        }
        return new View(new Archive(1,recent,best));
    }

    private static int measure(ExperienceRun run) {
        try {
            return ArchiveWriter.utf8Size(writer->GSON.toJson(run,writer));
        } catch(IOException error) {throw new IllegalArgumentException("Invalid experience encoding",error);}
    }

    private void keepSizes() {
        var retained=Collections.newSetFromMap(new IdentityHashMap<ExperienceRun,Boolean>());
        retained.addAll(accepted.archive.recent());
        retained.addAll(accepted.archive.best());
        if(pending!=null) {
            retained.addAll(pending.archive.recent());
            retained.addAll(pending.archive.best());
        }
        sizes.keySet().retainAll(retained);
    }

    public boolean flush() {
        if(!dirty()) return true;
        if(changedOnDisk) return false;
        try {
            if(damaged) {
                storage.acceptMissingSource();
                if(storage.observedStamp()==null) {
                    warning="原分段成绩无法核实，本次记录已保留。请恢复有效的成绩档后核对，或移走原文件后重试。";
                    return false;
                }
            }
            publish(storage,pending,damaged);
            accept(pending);
            return true;
        } catch (ArchiveWriter.StaleArchiveException error) {
            changedOnDisk=true;
            warning="磁盘分段成绩已更新，请核对两份记录。";
            return false;
        } catch(IOException | RuntimeException error) { warning="分段攻关记录尚未保存，当前进程保留数据，可重试保存。";return false; }
    }

    private static void publish(GuardedArchive storage,View value,boolean damaged) throws IOException {
        storage.commit(writer->GSON.toJson(value.archive,writer),damaged);
    }

    private void accept(View value) {
        accepted=value;
        pending=null;
        staged.clear();
        keepSizes();
        damaged=false;
        changedOnDisk=false;
        warning="";
    }

    public boolean conflictReviewAvailable() {
        return pending!=null && (changedOnDisk || damaged && storage.observedStamp()==null);
    }

    public static final class ConflictReview {
        private final ExperienceStore owner;
        private final GuardedArchive source;
        private final View local,disk,merged;
        private final Map<ExperienceRun,Integer> measured;
        private final List<String> details;

        private ConflictReview(ExperienceStore owner,GuardedArchive source,View local,View disk,View merged,
                               Map<ExperienceRun,Integer> measured,List<String> details) {
            this.owner=owner;this.source=source;this.local=local;this.disk=disk;this.merged=merged;
            this.measured=measured;
            this.details=List.copyOf(details);
        }
        public boolean valid() {return merged!=null;}
        public List<String> details() {return details;}
    }

    public ConflictReview reviewConflict() {
        if(!conflictReviewAvailable()) return null;
        var source=storage();
        try {
            var disk=read(source);
            var additions=new ArrayList<ExperienceRun>(staged.size());
            var measured=new IdentityHashMap<>(sizes);
            int duplicates=0;
            for(var run:staged) {
                var saved=disk.byId.get(run.id());
                if(saved!=null) {
                    if(!saved.equals(run)) return unavailable(source,"同一编号对应不同的分段记录，两份原记录均保留。");
                    duplicates++;
                    continue;
                }
                additions.add(run);
            }
            var result=including(disk,additions,measured);
            if(result==null) return unavailable(source,"合并成绩超过存储上限，两份原记录均保留。");
            measured.keySet().removeIf(run->result.byId.get(run.id())!=run);
            long dropped=staged.stream().filter(run->!result.byId.containsKey(run.id())).count();
            long lostDiskBests=disk.best.entrySet().stream().filter(entry->{
                var winner=result.best.get(entry.getKey());
                return winner==null || winner.duration()>entry.getValue().duration();
            }).count();
            return new ConflictReview(this,source,pending,disk,result,measured,List.of(
                    "本窗口待存 "+staged.size()+" 次 · 磁盘已有 "+duplicates+" 次相同记录",
                    "合并后保留最近 "+result.archive.recent().size()+" 次 · 最佳 "+result.archive.best().size()+" 组条件",
                    "受容量限制，未进入合并档案的待存记录 "+dropped+" 次 · 未保留原磁盘最佳用时 "+lostDiskBests+" 组"));
        } catch(IOException | RuntimeException error) {
            return unavailable(source,"无法读取磁盘分段成绩。请恢复有效的成绩档后重新核对，或移走原文件后重试。本窗口的待存记录已保留。");
        }
    }

    private ConflictReview unavailable(GuardedArchive source,String detail) {
        return new ConflictReview(this,source,pending,null,null,Map.of(),List.of(detail));
    }

    public boolean applyConflict(ConflictReview review) {
        if(review==null || review.owner!=this || review.local!=pending || !review.valid() || !conflictReviewAvailable()) {
            warning="分段成绩核对已失效，请重新打开。";
            return false;
        }
        try {
            if(review.merged==review.disk) review.source.verifyAccepted();
            else publish(review.source,review.merged,false);
            storage=review.source;
            sizes.putAll(review.measured);
            accept(review.merged);
            return true;
        } catch(ArchiveWriter.StaleArchiveException error) {
            warning="核对后磁盘分段成绩又有更新，请重新查看。";
        } catch(IOException | RuntimeException error) {
            warning="合并成绩尚未保存，两份原记录均已保留。";
        }
        return false;
    }

    protected void replace(Path temporary,Path target) throws IOException {
        try { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException error) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
    }
}
