package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Rebuilds only accepted event rounds; a failed secondary write never repeats race scoring. */
public final class RivalStore {
    private static final int MAX_BYTES=1048576;
    private final Path directory;
    private GuardedArchive archive;
    private final Gson gson=new Gson();
    private RivalLedger.State saved=RivalLedger.State.empty(),pending;
    private boolean damaged,changedOnDisk;
    public String warning="";
    public RivalStore(Path directory) {
        this.directory=directory;
        archive = new GuardedArchive(directory, "rival-ledger", MAX_BYTES);
        try {
            saved=read(archive);
        } catch(IOException | RuntimeException error) { damaged=true;warning="交手记录无法读取，原文件已保留。"; }
    }
    private RivalLedger.State read(GuardedArchive source) throws IOException {
        String content=source.read();
        return content==null?RivalLedger.State.empty():Objects.requireNonNull(gson.fromJson(content,RivalLedger.State.class));
    }
    public RivalLedger.State state() { return pending!=null?pending:saved; }
    public boolean pending() { return pending!=null; }
    public boolean follow(int driver) { var next=state().follow(driver);if(next!=state()) pending=next;return !changedOnDisk && flush(); }
    public boolean reconcile(EventSeries event,long timestamp,Function<String,TrackLayout> resolve) {
        try { var next=state();for(var meeting:RivalLedger.accepted(event,timestamp,resolve)) next=next.record(meeting);if(next!=state()) pending=next;if(pending==null && !damaged) warning="";return !changedOnDisk && flush(); }
        catch(RuntimeException error) { warning="交手记录与已保存赛事不一致；原记录保留，请检查赛事文件。";return false; }
    }
    public boolean flush() {
        if(pending==null) return true;
        try {
            var next=pending;
            archive.commit(writer->gson.toJson(next,writer),damaged);
            accept(next);
            return true;
        } catch(ArchiveWriter.StaleArchiveException error) {
            changedOnDisk=true;
            warning="磁盘上的交手记录已变化，请核对两份记录。";
            return false;
        }catch(IOException | RuntimeException error) { warning="交手记录暂存在本次运行，请重试保存；已保存的赛事成绩可用于恢复。";return false; }
    }

    private void accept(RivalLedger.State state) {
        saved=state;
        pending=null;
        damaged=false;
        changedOnDisk=false;
        warning="";
    }

    public boolean conflictReviewAvailable() {return pending!=null && changedOnDisk;}

    public static final class ConflictReview {
        private final RivalStore owner;
        private final GuardedArchive source;
        private final RivalLedger.State candidate,disk,merged,localPreference;
        private final List<String> details;

        private ConflictReview(RivalStore owner,GuardedArchive source,RivalLedger.State candidate,
                               RivalLedger.State disk,RivalLedger.State merged,RivalLedger.State localPreference,List<String> details) {
            this.owner=owner;this.source=source;this.candidate=candidate;this.disk=disk;
            this.merged=merged;this.localPreference=localPreference;this.details=List.copyOf(details);
        }
        public boolean valid() {return merged!=null;}
        public boolean followChoice() {return localPreference!=null;}
        public List<String> details() {return details;}
    }

    public ConflictReview reviewConflict() {
        if(!conflictReviewAvailable()) return null;
        var source=new GuardedArchive(directory,"rival-ledger",MAX_BYTES);
        try {
            var disk=read(source);
            var known=new HashSet<String>();
            for(var meeting:saved.meetings()) known.add(meeting.receipt());
            var existing=new HashMap<String,RivalLedger.Meeting>();
            for(var meeting:disk.meetings()) existing.put(meeting.receipt(),meeting);
            var merged=disk;
            int added=0,overlap=0;
            for(var meeting:pending.meetings()) {
                if(known.contains(meeting.receipt())) continue;
                var current=existing.get(meeting.receipt());
                if(current==null) {
                    merged=merged.record(meeting);
                    added++;
                } else if(current.sameFacts(meeting)) overlap++;
                else return new ConflictReview(this,source,pending,disk,null,null,List.of(
                            "同一赛事站次有不同的成绩，暂无法合并。",
                            meeting.eventTitle()+" · 第 "+(meeting.round()+1)+" 站 · "+meeting.trackName(),
                            "磁盘记录和本窗口成绩均保留。"));
            }
            int local=pending.followed();
            boolean localChanged=local!=saved.followed() && local!=disk.followed();
            boolean choose=localChanged && disk.followed()!=saved.followed();
            var details=List.of("本次加入 "+added+" 站 · 已在磁盘 "+overlap+" 站",
                    "合并后保留最近 "+merged.meetings().size()+" 站 · 最多 256 站",
                    "磁盘关注 · "+EventSeries.driverName(disk.followed()),"本窗口关注 · "+EventSeries.driverName(local));
            return new ConflictReview(this,source,pending,disk,localChanged && !choose?merged.follow(local):merged,
                    choose?merged.follow(local):null,details);
        } catch(IOException | RuntimeException error) {
            return new ConflictReview(this,source,pending,null,null,null,
                    List.of("暂时无法读取磁盘交手记录。磁盘档案与本窗口待存内容均保留。"));
        }
    }

    public boolean applyConflict(ConflictReview review,boolean useLocalFollow) {
        if(review==null || review.owner!=this || review.candidate!=pending || !review.valid()
                || useLocalFollow && !review.followChoice() || !conflictReviewAvailable()) {
            warning="交手记录核对已变化，请重新打开。";
            return false;
        }
        var next=useLocalFollow?review.localPreference:review.merged;
        try {
            if(next.equals(review.disk)) review.source.verifyAccepted();
            else review.source.commit(writer->gson.toJson(next,writer),false);
            archive=review.source;
            accept(next);
            return true;
        } catch(ArchiveWriter.StaleArchiveException error) {
            warning="核对后磁盘交手记录又有更新，请重新查看。";
        } catch(IOException | RuntimeException error) {
            warning="交手记录合并后尚未保存，两份记录均已保留。";
        }
        return false;
    }
}
