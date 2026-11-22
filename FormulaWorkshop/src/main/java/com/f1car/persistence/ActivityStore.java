package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Local card library and stage checkpoints. Pending writes remain reviewable and retryable. */
public final class ActivityStore {
    private record Archive(int version,List<ActivityCard> cards,ActivityRun active,List<ActivityRun> recent,Map<String,ActivityRun> best) {
        private Archive {
            if(version<1 || version>4 || cards==null || cards.size()>12 || recent==null || recent.size()>8 || best==null || best.size()>32) throw new IllegalArgumentException("Invalid activity archive");
            cards=List.copyOf(cards);recent=List.copyOf(recent);best=Collections.unmodifiableMap(new LinkedHashMap<>(best));
            if(cards.stream().map(ActivityCard::id).distinct().count()!=cards.size() || recent.stream().map(ActivityRun::id).distinct().count()!=recent.size() || recent.stream().anyMatch(r->!r.complete())) throw new IllegalArgumentException("Invalid activity library/history");
            for(var row:best.entrySet()) if(row.getValue()==null || !row.getValue().complete() || !row.getKey().equals(row.getValue().key())) throw new IllegalArgumentException("Invalid activity best");
            if(version==1 && hasParts(cards,active,recent,best)) throw new IllegalArgumentException("Parts activities require archive version 2");
            if(version<4 && hasSections(cards,active,recent,best)) throw new IllegalArgumentException("Sections require archive version 4");
            if(version<3 && hasObjectives(cards,active,recent,best)) throw new IllegalArgumentException("Compound activities require archive version 3");
        }
    }
    private static final int MAX_BYTES=1048576;
    private final Path directory;
    private GuardedArchive archive;
    private final Gson gson=new Gson();
    private Archive saved=new Archive(1,List.of(),null,List.of(),Map.of()),pending;
    private boolean damaged,changedOnDisk;
    public String warning="";
    public ActivityStore(Path directory) {
        this.directory=directory;
        archive = new GuardedArchive(directory, "activity-cards", MAX_BYTES);
        try {
            saved = read(archive);
        } catch(IOException | RuntimeException error) { damaged=true;warning="活动卡无法读取，原文件已保留。"; }
    }
    private Archive read(GuardedArchive source) throws IOException {
        String json=source.read();
        return json==null?new Archive(1,List.of(),null,List.of(),Map.of()):Objects.requireNonNull(gson.fromJson(json,Archive.class));
    }
    private Archive state() { return pending!=null?pending:saved; }
    private static boolean hasParts(List<ActivityCard> cards,ActivityRun active,List<ActivityRun> recent,Map<String,ActivityRun> best) {return cards.stream().anyMatch(c->c.partsRule()!=null) || active!=null && active.card().partsRule()!=null || recent.stream().anyMatch(r->r.card().partsRule()!=null) || best.values().stream().anyMatch(r->r.card().partsRule()!=null);}
    private static boolean hasObjectives(List<ActivityCard> cards,ActivityRun active,List<ActivityRun> recent,Map<String,ActivityRun> best) {return cards.stream().anyMatch(c->c.objectives()!=null) || active!=null && active.card().objectives()!=null || recent.stream().anyMatch(r->r.card().objectives()!=null) || best.values().stream().anyMatch(r->r.card().objectives()!=null);}
    private static boolean hasSections(List<ActivityCard> cards,ActivityRun active,List<ActivityRun> recent,Map<String,ActivityRun> best) {return cards.stream().anyMatch(ActivityCard::hasSections) || active!=null && active.card().hasSections() || recent.stream().anyMatch(r->r.card().hasSections()) || best.values().stream().anyMatch(r->r.card().hasSections());}
    private static Archive archive(List<ActivityCard> cards,ActivityRun active,List<ActivityRun> recent,Map<String,ActivityRun> best) {return new Archive(hasSections(cards,active,recent,best)?4:hasObjectives(cards,active,recent,best)?3:hasParts(cards,active,recent,best)?2:1,cards,active,recent,best);}
    public List<ActivityCard> cards() { return state().cards(); }
    public ActivityRun active() { return state().active(); }
    public List<ActivityRun> recent() { return state().recent(); }
    public List<ActivityRun> savedRecent() { return saved.recent(); }
    public ActivityRun best(String key) { return state().best().get(key); }
    public boolean pending() { return pending!=null; }

    /** Update the read view before starting or resuming an activity. */
    public boolean refreshBeforeLaunch() {
        if(pending()) return false;
        try {
            try {
                archive.verifyAccepted();
            } catch(ArchiveWriter.StaleArchiveException changed) {
                var source=new GuardedArchive(directory,"activity-cards",MAX_BYTES);
                var latest=read(source);
                source.verifyAccepted();
                archive=source;
                accept(latest);
            }
            return true;
        } catch(IOException | RuntimeException error) {
            return false;
        }
    }

    public boolean put(ActivityCard card) {
        Objects.requireNonNull(card);var state=state();var cards=new ArrayList<>(state.cards());int index=-1;for(int i=0;i<cards.size();i++) if(cards.get(i).id().equals(card.id())) index=i;
        if(index<0) { if(cards.size()==12) { warning="活动卡已满十二张，请先删除一张。";return false; }cards.add(card); }else cards.set(index,card);
        pending=archive(cards,state.active(),state.recent(),state.best());return flush();
    }
    public boolean remove(String id) { var state=state();var cards=state.cards().stream().filter(c->!c.id().equals(id)).toList();pending=archive(cards,state.active(),state.recent(),state.best());return flush(); }
    public boolean begin(ActivityCard card,String controls,int livery,long time) {
        return begin(card,controls,livery,time,null);
    }
    public boolean begin(ActivityCard card,String controls,int livery,long time,PartsBuild entry) {
        if(pending()) { warning="请先保存暂存的活动卡与成绩。";return false; }
        if(!state().cards().contains(card)) throw new IllegalArgumentException("Save the activity card before starting");
        pending=archive(state().cards(),ActivityRun.begin(card,controls,livery,time,entry),state().recent(),state().best());return flush();
    }
    public boolean record(String runId,ActivityRun.Result result) {
        var state=state();if(state.active()==null || !state.active().id().equals(runId)) throw new IllegalStateException("Activity attempt belongs to another run");
        var next=state.active().accept(result);if(next==state.active()) return flush();
        pending=withRecordedResult(state,next,state.cards());return flush();
    }
    public boolean flush() {
        if(pending==null) return true;
        try {
            archive.commit(gson.toJson(pending),damaged,migrationBackup(saved,pending));
            accept(pending);
            return true;
        } catch(ArchiveWriter.StaleArchiveException error) {
            changedOnDisk=true;
            warning="磁盘上的活动卡或成绩已变化。请核对最新档案与本次待存内容。";
            return false;
        } catch(IOException | RuntimeException error) { warning="活动卡或本段成绩尚未保存；本次运行保留数据，请重试保存后继续。";return false; }
    }

    public static final class ConflictReview {
        private final ActivityStore owner;
        private final Archive candidate,disk,merged;
        private final GuardedArchive source;
        private final List<String> details;
        private final boolean keepDisk;

        private ConflictReview(ActivityStore owner,Archive candidate,Archive disk,Archive merged,
                               GuardedArchive source,List<String> details,boolean keepDisk) {
            this.owner=owner;this.candidate=candidate;this.disk=disk;this.merged=merged;this.source=source;
            this.details=List.copyOf(details);
            this.keepDisk=keepDisk;
        }

        public boolean valid() {return merged!=null;}
        public boolean canKeepDisk() {return keepDisk;}
        public List<String> details() {return details;}
    }

    public boolean conflictReviewAvailable() {return pending!=null && changedOnDisk && !damaged;}

    public ConflictReview reviewConflict() {
        if(!conflictReviewAvailable()) return null;
        var source=new GuardedArchive(directory,"activity-cards",MAX_BYTES);
        var details=new ArrayList<String>();
        Archive disk=null;
        ActivityRun.Result localResult=null;
        try {
            disk=read(source);
            details.add("磁盘活动卡 "+disk.cards().size()+" 张，已完成 "+disk.recent().size()+" 份。");
            details.add("磁盘进度 · "+describeRun(disk.active()));
            details.add("本窗口进度 · "+describeRun(pending.active()));
            addLastResult(details,"磁盘",disk.active());
            addLastResult(details,"本窗口",pending.active());
            localResult=recordedResult(saved.active(),pending.active());
            var cards=mergeCards(saved.cards(),pending.cards(),disk.cards(),details);
            var successAfterRetry=acceptAfterConcurrentFailure(saved.active(),localResult,disk.active());
            Archive merged;
            if(successAfterRetry!=null) {
                details.add("磁盘仅新增了本项失败试跑。可保留试跑次数并接受本窗口的成功结果。");
                merged=withRecordedResult(disk,successAfterRetry,cards);
            } else if(canArchiveCompletion(saved.active(),pending.active(),disk.active())) {
                merged=archiveCompletion(cards,disk,pending.active(),details);
            } else {
                var active=mergeValue(saved.active(),pending.active(),disk.active(),"组合进度",details);
                var recent=mergeValue(saved.recent(),pending.recent(),disk.recent(),"最近完成的组合",details);
                var best=mergeValue(saved.best(),pending.best(),disk.best(),"各条件最佳成绩",details);
                merged=cards.equals(disk.cards()) && Objects.equals(active,disk.active())
                    && recent.equals(disk.recent()) && best.equals(disk.best())?disk:archive(cards,active,recent,best);
            }
            return new ConflictReview(this,pending,disk,merged,source,details,false);
        } catch(IOException | RuntimeException error) {
            details.add("两份内容暂时无法合并，目前保留磁盘原档和本次候选。");
            boolean keepDisk=disk!=null && !ArchiveWriter.ABSENT.equals(source.observedStamp())
                    && pending.cards().equals(saved.cards()) && localResult!=null;
            if(keepDisk) details.add("可明确采用已核对的磁盘进度，放弃本窗口这一次待存结果。");
            return new ConflictReview(this,pending,disk,null,source,details,keepDisk);
        }
    }

    public boolean applyConflict(ConflictReview review) {
        if(!currentReview(review) || !review.valid()) {
            warning="活动保存核对已变化，请重新打开。";
            return false;
        }
        try {
            if(review.merged.equals(review.disk)) review.source.verifyAccepted();
            else review.source.commit(gson.toJson(review.merged),false,migrationBackup(review.disk,review.merged));
            archive=review.source;
            accept(review.merged);
            return true;
        } catch(ArchiveWriter.StaleArchiveException error) {warning="核对后磁盘又有更新，请重新查看。";}
        catch(IOException | RuntimeException error) {warning="核对后的活动档案尚未保存；原档与候选保留。";}
        return false;
    }

    public boolean keepDiskAfterResultConflict(ConflictReview review) {
        if(!currentReview(review) || !review.canKeepDisk()) {
            warning="活动成绩核对已变化，请重新打开。";
            return false;
        }
        try {
            review.source.verifyAccepted();
            archive=review.source;
            accept(review.disk);
            return true;
        } catch(IOException | RuntimeException error) {
            warning="磁盘进度已变化或无法重新核对，本次待存成绩保留。";
            return false;
        }
    }

    private boolean currentReview(ConflictReview review) {
        return review!=null && review.owner==this && review.candidate==pending && conflictReviewAvailable();
    }

    private void accept(Archive accepted) {
        saved=accepted;pending=null;damaged=false;changedOnDisk=false;warning="";
    }

    private static String describeRun(ActivityRun run) {
        return run==null?"没有活动":run.card().name()+" · "+run.stage()+"/"+run.card().stages().size()
            +" 项 · "+run.attempts()+" 次试跑";
    }

    private static void addLastResult(List<String> details,String label,ActivityRun run) {
        if(run==null || run.last()==null) return;
        var result=run.last();
        details.add(label+"最近试跑 · 第 "+(result.stage()+1)+" 项 · "+(result.success()?"成功":"失败")
                +" · "+PracticeSession.format(result.elapsed())+"\n"+result.detail());
    }

    private static ActivityRun.Result recordedResult(ActivityRun base,ActivityRun candidate) {
        if(base==null || base.complete() || candidate==null || !base.id().equals(candidate.id())) return null;
        var result=candidate.last();
        if(result==null || result.stage()!=base.stage() || candidate.attempts()!=base.attempts()+1) return null;
        return base.accept(result).equals(candidate)?result:null;
    }

    private static ActivityRun acceptAfterConcurrentFailure(ActivityRun base,ActivityRun.Result result,ActivityRun disk) {
        if(result==null || !result.success() || disk==null || disk.last()==null || disk.last().success()
                || disk.attempts()<=base.attempts() || !disk.results().equals(base.results())
                || !disk.id().equals(base.id()) || !disk.card().equals(base.card()) || disk.createdAt()!=base.createdAt()
                || disk.livery()!=base.livery() || !disk.controls().equals(base.controls()) || !Objects.equals(disk.entry(),base.entry())) return null;
        return disk.accept(result);
    }

    private static Archive withRecordedResult(Archive state,ActivityRun next,List<ActivityCard> cards) {
        if(!next.complete()) return archive(cards,next,state.recent(),state.best());
        var recent=new ArrayList<>(state.recent());
        var best=new LinkedHashMap<>(state.best());
        addCompleted(next,recent,best);
        return archive(cards,next,recent,best);
    }

    private static boolean canArchiveCompletion(ActivityRun before,ActivityRun candidate,ActivityRun activeOnDisk) {
        return before!=null && !before.complete() && candidate!=null && candidate.complete()
            && before.id().equals(candidate.id()) && activeOnDisk!=null && !activeOnDisk.id().equals(candidate.id());
    }

    private static Archive archiveCompletion(List<ActivityCard> cards,Archive disk,ActivityRun completed,List<String> details) {
        var history=new ArrayList<>(disk.recent());
        var best=new LinkedHashMap<>(disk.best());
        if(history.stream().anyMatch(value->value.id().equals(completed.id()) && !value.equals(completed))
                || best.values().stream().anyMatch(value->value.id().equals(completed.id()) && !value.equals(completed))) {
            details.add("磁盘已存有同一组合编号的不同成绩。");
            throw new IllegalArgumentException("Different completed activity with the same identity");
        }
        details.add("保留磁盘上的当前组合，只将本窗口的完整成绩收录到最近完成。");
        var oldLast=history.size()==8?history.get(history.size()-1):null;
        String oldestKey=best.size()==32?best.keySet().iterator().next():null;
        if(!history.contains(completed)) addCompleted(completed,history,best);
        if(oldLast!=null && !history.contains(oldLast))
            details.add("最近记录已满，本次保存会移出最早一份 · "+describeRun(oldLast));
        if(oldestKey!=null && !best.containsKey(oldestKey))
            details.add("最佳记录已满，本次保存会移出最早收录的一项。");
        if(cards.equals(disk.cards()) && history.equals(disk.recent()) && best.equals(disk.best())) return disk;
        return archive(cards,disk.active(),history,best);
    }

    private static void addCompleted(ActivityRun run,List<ActivityRun> recent,LinkedHashMap<String,ActivityRun> best) {
        recent.removeIf(value->value.id().equals(run.id()));
        recent.add(0,run);
        while(recent.size()>8) recent.remove(recent.size()-1);
        String key=run.key();
        var old=best.get(key);
        if(old==null || run.total()<old.total()) best.put(key,run);
        while(best.size()>32) best.remove(best.keySet().iterator().next());
    }

    private static List<ActivityCard> mergeCards(List<ActivityCard> base,List<ActivityCard> local,List<ActivityCard> disk,
                                                 List<String> details) {
        var merged=new LinkedHashMap<String,ActivityCard>();
        disk.forEach(card->merged.put(card.id(),card));
        var original=new HashMap<String,ActivityCard>();
        base.forEach(card->original.put(card.id(),card));
        var changed=new LinkedHashMap<String,ActivityCard>();
        local.forEach(card->changed.put(card.id(),card));
        base.forEach(card->changed.putIfAbsent(card.id(),null));
        for(var entry:changed.entrySet()) {
            String id=entry.getKey();
            var before=original.get(id);
            var next=entry.getValue();
            if(Objects.equals(before,next)) continue;
            String label="活动卡 · "+(next==null?before.name():next.name());
            var accepted=mergeValue(before,next,merged.get(id),label,details);
            if(accepted==null) merged.remove(id);
            else merged.put(id,accepted);
        }
        if(merged.size()>12) {
            details.add("合并后将超过十二张卡，请先处理容量。");
            throw new IllegalArgumentException("Activity capacity");
        }
        return List.copyOf(merged.values());
    }

    private static <T> T mergeValue(T base,T local,T disk,String label,List<String> details) {
        if(Objects.equals(base,local)) return disk;
        if(Objects.equals(local,disk)) {
            details.add("磁盘已保存相同内容 · "+label);
            return disk;
        }
        if(Objects.equals(base,disk)) {
            details.add("可提交本次改动 · "+label);
            return local;
        }
        details.add("两边均有不同改动 · "+label);
        throw new IllegalArgumentException("Different activity changes at the same destination");
    }

    private static String migrationBackup(Archive saved,Archive pending) {
        int before = saved.version();
        int after = pending.version();
        if (after <= before) return null;
        return switch (after) {
            case 2 -> "activity-cards.pre-parts-v1.json";
            case 3 -> "activity-cards.pre-objectives-v" + before + ".json";
            case 4 -> "activity-cards.pre-sections-v" + before + ".json";
            default -> null;
        };
    }
}
