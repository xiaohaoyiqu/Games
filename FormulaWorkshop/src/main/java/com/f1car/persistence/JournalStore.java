package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Keeps unsaved rewards in memory and retries the same state without duplicating receipts. */
public final class JournalStore {
    private static final int MAX_BYTES=1_500_000;
    private static final int MAX_PENDING_NOTICES=3_072;
    private final Path directory;
    private GuardedArchive archive;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private DriverJournal.State saved=DriverJournal.State.empty(),pending;
    private record PendingReceipt(DriverJournal.ReceiptGroup group,String id) {}
    // Preserve replay order across groups and recognize retries after state() prunes a receipt.
    private final LinkedHashMap<PendingReceipt,DriverJournal.Notice> pendingNotices=new LinkedHashMap<>();
    private boolean damaged,changedOnDisk,completeNoticeLog=true;
    public String warning="";
    public JournalStore(Path directory) {
        this.directory=directory;
        archive = new GuardedArchive(directory, "driver-journal", MAX_BYTES);
        try {
            saved=read(archive);
        } catch(IOException | RuntimeException error) { saved=DriverJournal.State.empty();damaged=true;warning="履历读取失败，原文件已保留。"; }
    }
    private DriverJournal.State read(GuardedArchive source) throws IOException {
        String content=source.read();
        return content==null?DriverJournal.State.empty():Objects.requireNonNull(gson.fromJson(content,DriverJournal.State.class));
    }
    public DriverJournal.State state() { return pending!=null?pending:saved; }
    public boolean pending() { return pending!=null; }
    public boolean record(DriverJournal.Notice notice) {stage(notice);return flush();}
    public boolean recordAll(List<DriverJournal.Notice> notices) {
        for(var notice:DriverJournal.unrecorded(state(),notices)) stage(notice);
        return flush();
    }
    private void stage(DriverJournal.Notice notice) {
        var key=new PendingReceipt(notice.receiptGroup(),notice.receipt());
        if(pendingNotices.containsKey(key) || pending!=null && saved.recorded(notice)) return;
        var current=state();
        var next=DriverJournal.record(current,notice);
        if(next==current) return;
        pending=next;
        if(pendingNotices.size()==MAX_PENDING_NOTICES) {
            completeNoticeLog=false;
            pendingNotices.remove(pendingNotices.keySet().iterator().next());
        }
        pendingNotices.put(key,notice);
    }
    public boolean look(DriverJournal.Look look) {
        if(damaged || !state().look().equals(look)) pending=DriverJournal.look(state(),look);
        return flush();
    }
    public boolean flush() {
        if(pending==null) return true;
        if(changedOnDisk) return false;
        try {
            if(damaged) {
                archive.acceptMissingSource();
                if(archive.observedStamp()==null) {
                    warning="原履历无法核实，奖励已暂存。请恢复有效的履历后核对，或移走原文件后重试。";
                    return false;
                }
            }
            var next=pending;
            archive.commit(writer->gson.toJson(next,writer),damaged);
            accept(next);
            return true;
        } catch(ArchiveWriter.StaleArchiveException error) {
            changedOnDisk=true;
            warning="磁盘上的驾驶履历已变化，请在履历页核对两份记录。";
            return false;
        } catch(IOException | RuntimeException error) { warning="履历和奖励尚未保存；已暂存在内存，可在驾驶履历重试保存。";return false; }
    }

    private void accept(DriverJournal.State state) {
        saved=state;
        pending=null;
        pendingNotices.clear();
        completeNoticeLog=true;
        damaged=false;
        changedOnDisk=false;
        warning="";
    }

    public boolean conflictReviewAvailable() {return pending!=null && (changedOnDisk || damaged && archive.observedStamp()==null);}

    public static final class ConflictReview {
        private final JournalStore owner;
        private final GuardedArchive source;
        private final DriverJournal.State candidate,disk,merged,withLocalLook;
        private final boolean lookChoice;
        private final List<String> details;

        private ConflictReview(JournalStore owner,GuardedArchive source,DriverJournal.State candidate,DriverJournal.State disk,
                               DriverJournal.State merged,DriverJournal.State withLocalLook,boolean lookChoice,List<String> details) {
            this.owner=owner;this.source=source;this.candidate=candidate;this.disk=disk;
            this.merged=merged;this.withLocalLook=withLocalLook;this.lookChoice=lookChoice;this.details=List.copyOf(details);
        }
        public boolean valid() {return merged!=null;}
        public boolean lookChoice() {return lookChoice;}
        public boolean canUseLocalLook() {return lookChoice && withLocalLook!=null;}
        public List<String> details() {return details;}
    }

    public ConflictReview reviewConflict() {
        if(!conflictReviewAvailable()) return null;
        var source=new GuardedArchive(directory,"driver-journal",MAX_BYTES);
        if(!completeNoticeLog) return new ConflictReview(this,source,pending,null,null,null,false,
                List.of("本窗口待存收据超过可核对数量。两份履历和内存中的奖励均保留。"));
        try {
            var disk=read(source);
            var merged=disk;
            var additions=DriverJournal.unrecorded(disk,List.copyOf(pendingNotices.values()));
            for(var notice:additions) merged=DriverJournal.record(merged,notice);
            var details=new ArrayList<String>();
            details.add("本次加入 "+additions.size()+" 项记录 · 已有收据 "+(pendingNotices.size()-additions.size())+" 项");
            details.add("磁盘外观 · "+describe(disk.look()));
            details.add("本窗口外观 · "+describe(pending.look()));
            boolean changedLook=!pending.look().equals(saved.look()) && !pending.look().equals(disk.look());
            if(!changedLook) return new ConflictReview(this,source,pending,disk,merged,null,false,details);

            var local=merged.unlocked(pending.look().pattern())?DriverJournal.look(merged,pending.look()):null;
            boolean choose=local==null || !disk.look().equals(saved.look());
            if(local==null) details.add("合并后的履历目前缺少本窗口所选图案的解锁记录。");
            else if(choose) details.add("两边都改了外观，请选择保留哪一份。");
            return new ConflictReview(this,source,pending,disk,choose?merged:local,local,choose,details);
        } catch(IOException | RuntimeException error) {
            return new ConflictReview(this,source,pending,null,null,null,false,
                    List.of("暂时无法读取磁盘履历。请恢复有效的履历后重新核对，或移走原文件后重试。本窗口的奖励已保留。"));
        }
    }

    public boolean applyConflict(ConflictReview review,boolean useLocalLook) {
        if(review==null || review.owner!=this || review.candidate!=pending || !review.valid()
                || useLocalLook && !review.canUseLocalLook() || !conflictReviewAvailable()) {
            warning="履历核对已变化，请重新打开。";
            return false;
        }
        var next=useLocalLook?review.withLocalLook:review.merged;
        try {
            if(next.equals(review.disk)) review.source.verifyAccepted();
            else review.source.commit(writer->gson.toJson(next,writer),false);
            archive=review.source;
            accept(next);
            return true;
        } catch(ArchiveWriter.StaleArchiveException error) {
            warning="核对后磁盘履历又有更新，请重新查看。";
        } catch(IOException | RuntimeException error) {
            warning="合并后的履历尚未保存，两份记录均已保留。";
        }
        return false;
    }

    private static String describe(DriverJournal.Look look) {return "车号 "+look.number()+" · "+look.pattern().title;}
    /** Replays only accepted event checkpoints after a crash between the two independent saves. */
    public boolean reconcile(EventSeries event,long timestamp) {
        return reconcile(event,timestamp,id->TrackCatalog.require(id).layout());
    }
    public boolean reconcile(EventSeries event,long timestamp,java.util.function.Function<String,TrackLayout> resolve) {
        if(event==null) return true;
        var state=event.state();
        var notices=new ArrayList<DriverJournal.Notice>(state.results().size()+1);
        for(int round=0;round<state.results().size();round++) {
            var track=state.calendar().get(round);var result=state.results().get(round);
            var player=result.race().stream().filter(row->row.driver()==0).findFirst().orElseThrow();
            String name;
            try { name=resolve.apply(track.trackId()).name; } catch(IllegalArgumentException error) { name=track.trackId(); }
            int place=result.race().indexOf(player)+1;
            notices.add(new DriverJournal.Notice(state.eventId()+":round:"+round,timestamp,DriverJournal.Activity.EVENT_RACE,
                track.trackId()+":"+track.geometry()+":"+state.physicsVersion(),name,event.title()+" · 第 "+(round+1)+" 站 · "+(player.finished()?"P"+place:"未完赛"),
                player.finished(),player.finished()?player.total():0,"",ChallengeBook.Medal.NONE,place));
        }
        if(state.phase()==EventSeries.Phase.COMPLETE) {
            var track=state.calendar().get(state.round());int place=event.placing(0);
            long same=event.standings().stream().filter(row->event.placing(row.driver())==place).count();
            notices.add(new DriverJournal.Notice(state.eventId()+":complete",timestamp,DriverJournal.Activity.EVENT_COMPLETE,
                track.trackId()+":"+track.geometry()+":"+state.physicsVersion(),event.title(),event.title()+" · "+(same>1?"并列 ":"")+"P"+place,
                state.results().stream().anyMatch(r->r.race().stream().anyMatch(row->row.driver()==0 && row.finished())),0,"",ChallengeBook.Medal.NONE,place));
        }
        return recordAll(notices);
    }
}
