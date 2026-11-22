package com.f1car.persistence;

import com.f1car.model.DriverJournal;
import com.f1car.model.WorkshopReport;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Bounded reports and optional responses; rewards remain in the existing receipt-based journal. */
public final class WorkshopStore {
    private static final int MAX_BYTES=524288,MAX_RECENT=24,MAX_BEST=64,MAX_RECEIPTS=256;
    private record Archive(int version,List<WorkshopReport> recent,Map<String,WorkshopReport> best,List<String> receipts,boolean responses) {
        private Archive {
            if(version!=1 || recent==null || recent.size()>MAX_RECENT || best==null || best.size()>MAX_BEST
                    || receipts==null || receipts.size()>MAX_RECEIPTS) throw new IllegalArgumentException("Invalid workshop archive");
            recent=List.copyOf(recent);best=Collections.unmodifiableMap(new LinkedHashMap<>(best));receipts=List.copyOf(receipts);
            if(recent.stream().map(WorkshopReport::id).distinct().count()!=recent.size() || new HashSet<>(receipts).size()!=receipts.size()) throw new IllegalArgumentException("Duplicate workshop result");
            for(var id:receipts) if(id==null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid workshop receipt");
            for(var row:best.entrySet()) if(row.getValue()==null || !row.getValue().success() || !row.getValue().key().equals(row.getKey())) throw new IllegalArgumentException("Invalid workshop best");
        }
    }
    private final Path directory;
    private GuardedArchive storage;
    private final Gson gson=new Gson();
    private Archive saved=empty(),pending;
    private final Deque<WorkshopReport> stagedReports=new ArrayDeque<>();
    private List<WorkshopReport> acceptedOutsideRecent=List.of();
    private boolean damaged,changedOnDisk,completeReportLog=true;
    public String warning="";
    public WorkshopStore(Path data) {
        directory=data;
        storage=new GuardedArchive(data,"workshop-reports",MAX_BYTES);
        try {
            saved=read(storage);
        } catch(IOException | RuntimeException error) { damaged=true;warning="实验报告无法读取，原文件保留。"; }
    }
    private static Archive empty() {return new Archive(1,List.of(),Map.of(),List.of(),true);}
    private Archive read(GuardedArchive source) throws IOException {
        String content=source.read();
        return content==null?empty():Objects.requireNonNull(gson.fromJson(content,Archive.class));
    }
    private Archive state() { return pending!=null?pending:saved; }
    public List<WorkshopReport> recent() { return state().recent(); }
    public WorkshopReport best(String key) { return state().best().get(key); }
    public boolean pending() { return pending!=null; }
    public boolean responses() { return state().responses(); }
    public boolean responses(boolean enabled) {
        var current=state();
        if(damaged || current.responses()!=enabled) pending=withResponses(current,enabled);
        return !changedOnDisk && flush();
    }

    private static Archive withResponses(Archive current,boolean enabled) {
        return new Archive(1,current.recent(),current.best(),current.receipts(),enabled);
    }

    public boolean record(WorkshopReport report) {
        var current=state();
        if(!current.receipts().contains(report.id())) {
            pending=add(current,report);
            if(stagedReports.size()==MAX_RECEIPTS) {
                stagedReports.removeFirst();
                completeReportLog=false;
            }
            stagedReports.addLast(report);
        }
        return !changedOnDisk && flush();
    }

    private static Archive add(Archive current,WorkshopReport report) {
        var recent=new ArrayList<>(current.recent());
        recent.add(0,report);
        if(recent.size()>MAX_RECENT) recent.remove(recent.size()-1);
        var best=new LinkedHashMap<>(current.best());
        if(report.success()) {
            String key=report.key();
            var old=best.get(key);
            if(old==null || report.grade().ordinal()>old.grade().ordinal()
                    || report.grade()==old.grade() && (report.value()<old.value()
                    || report.value()==old.value() && report.elapsed()<old.elapsed())) best.put(key,report);
        }
        if(best.size()>MAX_BEST) best.remove(best.keySet().iterator().next());
        var receipts=new ArrayList<>(current.receipts());
        receipts.add(report.id());
        if(receipts.size()>MAX_RECEIPTS) receipts.remove(0);
        return new Archive(1,recent,best,receipts,current.responses());
    }
    public boolean flush() {
        if(pending==null) return true;
        try {
            var next=pending;
            storage.commit(writer->gson.toJson(next,writer),damaged);
            accept(next,saved,stagedReports);
            return true;
        } catch (ArchiveWriter.StaleArchiveException error) {
            changedOnDisk=true;
            warning="磁盘实验报告已更新，请核对两份记录。";
            return false;
        } catch(IOException | RuntimeException error) { warning="实验报告暂存于本次运行，可在任务板重试保存。";return false; }
    }

    private void accept(Archive next,Archive previous,Collection<WorkshopReport> reports) {
        if(!reports.isEmpty() || !acceptedOutsideRecent.isEmpty()) {
            var visible=new HashSet<String>();
            for(var report:next.recent()) visible.add(report.id());
            var retained=new HashSet<>(next.receipts());
            var extra=new LinkedHashMap<String,WorkshopReport>();
            for(var report:acceptedOutsideRecent) {
                if(retained.contains(report.id()) && !visible.contains(report.id())) extra.put(report.id(),report);
            }
            // A batch can evict reports accepted by another window before they reach the journal.
            for(int i=previous.recent().size()-1;i>=0;i--) {
                var report=previous.recent().get(i);
                if(retained.contains(report.id()) && !visible.contains(report.id())) extra.putIfAbsent(report.id(),report);
            }
            for(var report:reports) {
                if(retained.contains(report.id()) && !visible.contains(report.id())) extra.putIfAbsent(report.id(),report);
            }
            acceptedOutsideRecent=List.copyOf(extra.values());
        }
        saved=next;
        pending=null;
        stagedReports.clear();
        completeReportLog=true;
        damaged=false;
        changedOnDisk=false;
        warning="";
    }

    public boolean conflictReviewAvailable() {return pending!=null && changedOnDisk;}

    public static final class ConflictReview {
        private final WorkshopStore owner;
        private final GuardedArchive source;
        private final Archive candidate,disk,merged;
        private final List<WorkshopReport> additions;
        private final List<String> details;

        private ConflictReview(WorkshopStore owner,GuardedArchive source,Archive candidate,Archive disk,Archive merged,
                               List<WorkshopReport> additions,List<String> details) {
            this.owner=owner;this.source=source;this.candidate=candidate;this.disk=disk;this.merged=merged;
            this.additions=List.copyOf(additions);this.details=List.copyOf(details);
        }
        public boolean valid() {return merged!=null;}
        public List<String> details() {return details;}
    }

    public ConflictReview reviewConflict() {
        if(!conflictReviewAvailable()) return null;
        var source=new GuardedArchive(directory,"workshop-reports",MAX_BYTES);
        if(!completeReportLog) return unavailable(source,"待存报告超过可核对数量，磁盘档案与本窗口待存内容均保留。");
        try {
            var disk=read(source);
            var existing=new HashSet<>(disk.receipts());
            var known=new HashMap<String,WorkshopReport>();
            for(var report:disk.recent()) known.put(report.id(),report);
            for(var report:disk.best().values()) known.putIfAbsent(report.id(),report);
            var merged=disk;
            var additions=new ArrayList<WorkshopReport>();
            int overlaps=0;
            for(var report:stagedReports) {
                if(existing.contains(report.id()) || known.containsKey(report.id())) {
                    var current=known.get(report.id());
                    if(current!=null && !current.equals(report))
                        return unavailable(source,"同一编号有两份不同的实验报告，待存内容和磁盘原文均保留。");
                    overlaps++;
                    continue;
                }
                merged=add(merged,report);
                additions.add(report);
            }
            if(pending.responses()!=saved.responses() && pending.responses()!=merged.responses())
                merged=withResponses(merged,pending.responses());
            return new ConflictReview(this,source,pending,disk,merged,additions,List.of(
                    "新增报告 "+additions.size()+" 份 · 已有记录 "+overlaps+" 份",
                    "合并后最近报告 "+merged.recent().size()+" 份 · 最多 "+MAX_RECENT+" 份",
                    "按原有条件与评定比较最佳报告。",
                    "合并后角色回应 "+(merged.responses()?"开启":"关闭")));
        } catch(IOException | RuntimeException error) {
            return unavailable(source,"暂时无法读取磁盘实验报告，磁盘档案与本窗口待存内容均保留。");
        }
    }

    private ConflictReview unavailable(GuardedArchive source,String detail) {
        return new ConflictReview(this,source,pending,null,null,List.of(),List.of(detail));
    }

    public boolean applyConflict(ConflictReview review) {
        if(review==null || review.owner!=this || review.candidate!=pending || !review.valid() || !conflictReviewAvailable()) {
            warning="实验报告核对已变化，请重新打开。";
            return false;
        }
        var next=review.merged;
        try {
            if(next.equals(review.disk)) review.source.verifyAccepted();
            else review.source.commit(writer->gson.toJson(next,writer),false);
            storage=review.source;
            accept(next,review.disk,review.additions);
            return true;
        } catch(ArchiveWriter.StaleArchiveException error) {
            warning="核对后磁盘实验报告又有更新，请重新查看。";
        } catch(IOException | RuntimeException error) {
            warning="合并后的实验报告尚未保存，两份记录均已保留。";
        }
        return false;
    }

    /** Recover only accepted reports after a crash between the report and journal writes. */
    public boolean reconcile(JournalStore journal) {
        var notices=new ArrayList<DriverJournal.Notice>(acceptedOutsideRecent.size()+saved.recent().size());
        for(var report:acceptedOutsideRecent) notices.add(report.notice());
        for(int i=saved.recent().size()-1;i>=0;i--) notices.add(saved.recent().get(i).notice());
        boolean accepted=journal.recordAll(notices);
        if(accepted) acceptedOutsideRecent=List.of();
        return accepted;
    }
}
