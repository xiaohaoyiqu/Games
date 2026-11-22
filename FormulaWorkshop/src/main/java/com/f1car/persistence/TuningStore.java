package com.f1car.persistence;

import com.f1car.model.TuningExperiment;
import com.f1car.model.TuningPurpose;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded local reports; a failed write retains the old disk data and current in-memory report. */
public final class TuningStore {
    private static final int LEGACY_BYTES=262144,MAX_BYTES=1048576;
    public record Archive(int version,List<TuningExperiment.Snapshot> reports,Map<String,TuningPurpose> purposes) {
        public Archive {
            if(version<1 || version>3 || reports==null || reports.size()>8 || version==1 && purposes!=null || version>=2 && (purposes==null || purposes.isEmpty() || purposes.size()>8) || purposes!=null && version!=(purposes.values().stream().anyMatch(p->p.version()==2)?3:2)) throw new IllegalArgumentException("Invalid setup archive");reports=List.copyOf(reports);if(reports.stream().map(TuningExperiment.Snapshot::id).distinct().count()!=reports.size()) throw new IllegalArgumentException("Duplicate setup report");
            if(purposes!=null) {purposes=Map.copyOf(purposes);for(var entry:purposes.entrySet()) {var report=reports.stream().filter(r->r.id().equals(entry.getKey())).findFirst().orElseThrow(()->new IllegalArgumentException("Missing purpose report"));if(!entry.getValue().matches(report)) throw new IllegalArgumentException("Trial purpose conditions differ");}}
        }
    }
    public enum Relation {READY,ALREADY_SAVED,CONFLICT}
    public record Recovery(Archive base,Archive candidate) {
        public Recovery {Objects.requireNonNull(base);Objects.requireNonNull(candidate);if(candidate.reports().isEmpty()) throw new IllegalArgumentException("Missing candidate reports");}
        public Relation relation(Archive current) {return candidate.equals(current)?Relation.ALREADY_SAVED:base.equals(current)?Relation.READY:Relation.CONFLICT;}
    }
    private record DiskArchive(Archive archive,ArchiveWriter.Stamp stamp) {}
    private final Path file,lock;
    interface Filesystem extends ArchiveWriter.Filesystem {
        @Override default void write(Path path,byte[] bytes) throws IOException {ArchiveWriter.writeAndForce(path,bytes);}
    }
    private final Filesystem filesystem;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("setup-trials");
    private final Gson gson=new Gson();
    private Archive archive=new Archive(1,List.of(),null);
    private Archive accepted=archive;
    private final TuningRecoveryFile recoveryFile;
    private boolean damaged,pending;
    public String warning="";
    public TuningStore(Path directory) {this(directory,new Filesystem() {},new TuningRecoveryFile.Filesystem() {});}
    TuningStore(Path directory,Filesystem filesystem) {this(directory,filesystem,new TuningRecoveryFile.Filesystem() {});}
    TuningStore(Path directory,Filesystem filesystem,TuningRecoveryFile.Filesystem recoveryFilesystem) {
        this.filesystem=Objects.requireNonNull(filesystem);
        file=directory.resolve("setup-trials.json");
        lock=file.resolveSibling("setup-trials.lock");
        recoveryFile=new TuningRecoveryFile(directory,recoveryFilesystem);reload();
    }
    public Path file() {return file;}
    public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}
    public List<TuningExperiment.Snapshot> reports() { return archive.reports(); }
    public boolean pending() { return pending; }
    public Archive accepted() {return accepted;}public Archive candidate() {return pending?archive:null;}public boolean damaged() {return damaged;}
    public Recovery recovery() {return recoveryFile.entry();}public boolean recoveryBlocked() {return recoveryFile.blocked();}
    public String recoveryWarning() {return recoveryFile.warning();}public SaveDiagnostics.Failure recoveryFailure() {return recoveryFile.failure();}
    public boolean recoveryNeedsAttention() {return recoveryBlocked() || recovery()!=null;}
    public TuningPurpose purpose(String id) {return archive.purposes()==null?null:archive.purposes().get(id);}
    public boolean save(TuningExperiment.Snapshot report) {
        return save(report,purpose(report.id()));
    }
    public boolean save(TuningExperiment.Snapshot report,TuningPurpose purpose) {
        stage(report,purpose);return retry();
    }
    /** Prepare the complete report candidate without publishing; raw sampling can be secured first. */
    public void stage(TuningExperiment.Snapshot report,TuningPurpose purpose) {
        Objects.requireNonNull(report);if(purpose!=null && !purpose.matches(report)) throw new IllegalArgumentException("Purpose does not match report");
        var reports=new ArrayList<TuningExperiment.Snapshot>();reports.add(report);archive.reports().stream().filter(old->!old.id().equals(report.id())).limit(7).forEach(reports::add);var purposes=new HashMap<String,TuningPurpose>();for(var value:reports) {var old=purpose(value.id());if(old!=null) purposes.put(value.id(),old);}if(purpose!=null) purposes.put(report.id(),purpose);else purposes.remove(report.id());var next=new Archive(purposes.isEmpty()?1:purposes.values().stream().anyMatch(p->p.version()==2)?3:2,reports,purposes.isEmpty()?null:purposes);archive=next;pending=true;
    }
    public boolean retry() {
        if(!pending) return true;
        diagnostics.begin();
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(file.toAbsolutePath().getParent());
            diagnostics.at(SaveDiagnostics.Stage.ENCODE);
            byte[] bytes=gson.toJson(archive).getBytes(StandardCharsets.UTF_8);
            diagnostics.encoded(bytes.length);
            int limit=archive.version()==1?LEGACY_BYTES:MAX_BYTES;
            if(bytes.length>limit) throw new IOException("Setup archive exceeds bound");
            if(!recoveryFile.store(new Recovery(accepted,archive))) {warning="报告尚未发布；先处理并保存同份恢复副本。";return false;}
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Setup archive is busy");
                var disk=readArchive(damaged);
                Archive current=disk.archive();
                if(archive.equals(current)) return finishSave(disk.stamp());
                if(current!=null && !accepted.equals(current)) {
                    warning="正式报告与原待存基准不同；两份内容保持，请重新核对。";
                    return false;
                }
                diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                backupBeforeSave(disk);
                ArchiveWriter.bytes(file,bytes,limit,"setup-trials-",diagnostics,filesystem,
                        target->ArchiveWriter.verifyStamp(target,disk.stamp(),LinkOption.NOFOLLOW_LINKS));
                return finishSave(ArchiveWriter.stamp(bytes));
            }
        } catch(IOException | RuntimeException error) {
            if(!pending) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);
            warning=diagnostics.failure().stage()==SaveDiagnostics.Stage.BACKUP
                    ?"旧报告备份无法写入或核对。待存报告已保留，请检查备份文件和磁盘空间后重试。"
                    :"试车报告暂存于本次运行；请在试车中心重试保存。";
            return false;
        }
    }
    private void backupBeforeSave(DiskArchive disk) throws IOException {
        if(ArchiveWriter.ABSENT.equals(disk.stamp())) return;
        String firstName=null;
        if(disk.archive()!=null) {
            int version=disk.archive().version();
            if(version==1 && archive.version()==2) firstName="setup-trials.pre-purpose-v1.json";
            else if(version<3 && archive.version()==3) firstName="setup-trials.pre-fitted-purpose-v"+version+".json";
            else return;
        }
        ArchiveWriter.preserveOriginal(file,disk.stamp(),firstName,"setup-trials.backup-",filesystem);
    }
    private boolean finishSave(ArchiveWriter.Stamp stamp) {
        accepted=archive;pending=false;damaged=false;warning="";
        if(!recoveryFile.store(null,target->ArchiveWriter.verifyStamp(file,stamp,LinkOption.NOFOLLOW_LINKS)))
            warning="报告已保存，但恢复副本尚未清理；请在恢复页核对已存内容后明确清理。";
        return true;
    }
    private DiskArchive readArchive(boolean allowDamaged) throws IOException {
        try {
            var raw=ArchiveWriter.readSource(file,MAX_BYTES,allowDamaged);
            var bytes=raw.bytes();
            var stamp=raw.stamp();
            if(bytes==null) return new DiskArchive(null,stamp);
            try {
                String source=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                CreationStore.depth(source);
                var value=Objects.requireNonNull(gson.fromJson(source,Archive.class));
                if(value.version()==1 && bytes.length>LEGACY_BYTES) throw new IOException("Legacy setup archive too large");
                return new DiskArchive(value,stamp);
            } catch(IOException | RuntimeException error) {
                if(!allowDamaged) throw error;
                return new DiskArchive(null,stamp);
            }
        } catch(NoSuchFileException absent) {return new DiskArchive(new Archive(1,List.of(),null),ArchiveWriter.ABSENT);}
    }
    public boolean reload() {
        if(pending) {warning="报告候选仍在内存，请先保存或明确取消，不重读覆盖。";return false;}
        try {archive=readArchive(false).archive();accepted=archive;damaged=false;warning="";return true;}
        catch(IOException | RuntimeException error) {damaged=true;warning="试车报告无法读取，原文件与已读内容保留。";return false;}
    }
    public boolean reloadRecovery() {return recoveryFile.reload();}
    public boolean restoreRecovery(Recovery reviewed) {
        if(pending) {warning="已有内存报告待存；先保存或明确取消，再处理磁盘副本。";return false;}
        if(reviewed==null || !recoveryFile.verify(reviewed) || !reload()) return false;
        if(reviewed.relation(accepted)!=Relation.READY) {warning="恢复副本与当前已存基准不符，或候选已保存；原文保持，请重新核对。";return false;}
        archive=reviewed.candidate();pending=true;warning="已恢复为待存，尚未写正式报告；请再次明确重试保存。";return true;
    }
    public boolean acknowledgeRecovery(Recovery reviewed) {
        if(pending) {warning="已有内存报告待存；先保存或明确取消，再确认磁盘副本。";return false;}
        if(reviewed==null || !recoveryFile.verify(reviewed)) return false;
        boolean cleared=withSavedArchive((current,check)->{
            if(!reviewed.candidate().equals(current)) {
                warning="当前正式报告与恢复候选不同，原副本保持。";
                return false;
            }
            return recoveryFile.store(null,check);
        });
        if(cleared) warning="已确认完整报告库保存，仅清理了恢复副本。";
        return cleared;
    }

    /** Publish pending curves against the actual saved reports under the report write lock. */
    public boolean saveTraces(TuningTraceStore traces) {
        requireSameDirectory(traces);
        if(!traces.pending()) return true;
        return withSavedArchive((current,check)->traces.retry(current.reports(),check));
    }

    public boolean acknowledgeTraceRecovery(TuningTraceStore traces,TuningTraceStore.Recovery reviewed) {
        requireSameDirectory(traces);
        return withSavedArchive((current,check)->traces.acknowledgeRecovery(reviewed,current.reports(),check));
    }

    private void requireSameDirectory(TuningTraceStore traces) {
        if(!file.toAbsolutePath().normalize().getParent().equals(traces.file().toAbsolutePath().normalize().getParent()))
            throw new IllegalArgumentException("Reports and curves must share a data directory");
    }

    @FunctionalInterface
    private interface SavedArchiveAction {
        boolean apply(Archive current,ArchiveWriter.ReplacementCheck check);
    }

    private boolean withSavedArchive(SavedArchiveAction action) {
        if(pending) {warning="请先处理内存中的报告候选。";return false;}
        boolean completed=false;
        try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
            if(held==null) throw new IOException("Setup archive is busy");
            var disk=readArchive(false);
            accepted=archive=disk.archive();
            damaged=false;
            if(!recoveryNeedsAttention()) warning="";
            completed=action.apply(accepted,target->ArchiveWriter.verifyStamp(file,disk.stamp(),LinkOption.NOFOLLOW_LINKS));
        } catch(IOException | RuntimeException error) {
            if(completed) return true;
            warning="正式报告暂时无法核对，恢复副本保持，请重读或重试。";
            return false;
        }
        return completed;
    }
    public boolean discardRecovery(Recovery reviewed) {
        if(pending) {warning="已有内存报告待存；请先处理内存候选，不直接移除其磁盘副本。";return false;}
        if(reviewed==null || !recoveryFile.verify(reviewed) || !recoveryFile.store(null)) return false;
        warning="已明确放弃所选恢复副本，正式报告保持。";return true;
    }
    public boolean cancelPending() {
        if(!pending) return true;var expected=new Recovery(accepted,archive);
        if(recovery()!=null && !expected.equals(recovery())) {archive=accepted;pending=false;warning="仅取消本次内存候选；另一份恢复副本保持，请明确核对。";return true;}
        if(!recoveryFile.store(null)) {warning="恢复副本尚未清理；保留本次内存候选，请修复后重试取消。";return false;}
        archive=accepted;pending=false;warning="已明确取消本次待存，正式报告保持。";return true;
    }
}
