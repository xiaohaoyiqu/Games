package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Separate sidecar leaves setup-trials and all exchange codecs unchanged. */
public final class TuningTraceStore {
    public static final int MAX_BYTES=32_000_000;
    private record Archive(int version,List<TuningTrace> traces) {
        private Archive {
            if(traces==null || traces.size()>48 || version!=format(traces)) throw new IllegalArgumentException("Invalid trace archive");
            traces=List.copyOf(traces);
            if(traces.stream().map(TuningTrace::report).distinct().count()>8 || traces.stream().map(t->t.report()+":"+t.index()).distinct().count()!=traces.size()) throw new IllegalArgumentException("Duplicate or excessive trace reports");
        }
    }
    private record DiskArchive(Archive archive,ArchiveWriter.Stamp stamp) {}
    public record Recovery(TuningExperiment.Snapshot reportBase,TuningExperiment.Snapshot report,TuningPurpose purpose,TuningTrace base,TuningTrace candidate) {
        public Recovery {
            Objects.requireNonNull(report);Objects.requireNonNull(candidate);
            if(!candidate.matches(report,candidate.index()) || purpose!=null && !purpose.matches(report) || base!=null && (!base.report().equals(candidate.report()) || base.index()!=candidate.index())) throw new IllegalArgumentException("Trace recovery source differs");
            if(reportBase!=null && (!reportBase.id().equals(report.id()) || reportBase.createdAt()!=report.createdAt() || !reportBase.conditions().equals(report.conditions()) || !reportBase.a().equals(report.a()) || !reportBase.b().equals(report.b()) || !reportBase.order().equals(report.order()) || reportBase.runs().size()>report.runs().size() || reportBase.failedAttempts()>report.failedAttempts())) throw new IllegalArgumentException("Trace report baseline differs");
            if(reportBase!=null) for(int i=0;i<reportBase.runs().size();i++) if(i!=candidate.index() && !reportBase.runs().get(i).equals(report.runs().get(i))) throw new IllegalArgumentException("Unrelated report run changed");
            if(reportBase!=null) {
                int count=reportBase.runs().size();if(report.runs().size()>count+1 || report.runs().size()==count+1 && candidate.index()!=count) throw new IllegalArgumentException("Recovery report skips original groups");
                if(candidate.index()<count) {var before=reportBase.runs().get(candidate.index());var after=report.runs().get(candidate.index());if(!before.equals(after) && after.attempt()<=before.attempt()) throw new IllegalArgumentException("Recovery report rewinds an original attempt");}
            }
        }
        public boolean reportAvailable(List<TuningExperiment.Snapshot> reports) {return reports.stream().anyMatch(value->candidate.matches(value,candidate.index()));}
        public boolean reportBaseMatches(List<TuningExperiment.Snapshot> reports) {return Objects.equals(reportBase,reports.stream().filter(value->value.id().equals(report.id())).findFirst().orElse(null));}
    }
    public enum Relation {READY,ALREADY_SAVED,CONFLICT,REPORT_MISSING}
    interface Filesystem extends ArchiveWriter.Filesystem {
        @Override default void write(Path path,byte[] bytes) throws IOException {ArchiveWriter.writeAndForce(path,bytes);}
    }
    private final Path file,lock;
    private final Gson gson=new Gson();
    private Archive archive=new Archive(1,List.of());
    private Archive accepted=archive;
    private final Filesystem filesystem;
    private final TuningTraceRecoveryFile recoveryFile;
    private final SaveDiagnostics diagnostics;
    private SaveDiagnostics.Timing saveTiming;
    private Recovery pendingRecovery;private List<TuningExperiment.Snapshot> pendingReports=List.of();
    private boolean pending,damaged;
    private long revision;
    public String warning="";
    public TuningTraceStore(Path directory) {this(directory,new Filesystem() {},new TuningTraceRecoveryFile.Filesystem() {});}
    TuningTraceStore(Path directory,Filesystem filesystem,TuningTraceRecoveryFile.Filesystem recoveryFilesystem) {
        this(directory,filesystem,recoveryFilesystem,System::nanoTime);
    }
    TuningTraceStore(Path directory,Filesystem filesystem,TuningTraceRecoveryFile.Filesystem recoveryFilesystem,LongSupplier nanoTime) {
        file=directory.resolve("trial-traces.json");lock=directory.resolve("trial-traces.lock");this.filesystem=Objects.requireNonNull(filesystem);
        diagnostics=new SaveDiagnostics("trial-traces",nanoTime);
        recoveryFile=new TuningTraceRecoveryFile(directory,recoveryFilesystem,nanoTime);reload();
    }
    public Path file() {return file;}public boolean damaged() {return damaged;}public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}
    public SaveDiagnostics.Timing saveTiming() {return saveTiming;}
    public SaveDiagnostics.Timing recoveryWriteTiming() {return recoveryFile.writeTiming();}
    public SaveDiagnostics.Timing recoveryClearTiming() {return recoveryFile.clearTiming();}
    public Recovery recovery() {return recoveryFile.entry();}public Recovery pendingRecovery() {return pendingRecovery;}
    public boolean recoveryBlocked() {return recoveryFile.blocked();}public String recoveryWarning() {return recoveryFile.warning();}public SaveDiagnostics.Failure recoveryFailure() {return recoveryFile.failure();}
    public boolean recoveryNeedsAttention() {return recoveryBlocked() || recovery()!=null;}
    public boolean pending() {return pending;}
    public long revision() {return revision;}
    private static int format(List<TuningTrace> traces) {return traces.stream().mapToInt(TuningTrace::version).max().orElse(1);}
    public TuningTrace find(TuningExperiment.Snapshot report,int index) {
        return archive.traces().stream().filter(t->t.matches(report,index)).findFirst().orElse(null);
    }
    public FrozenTuningTraces freeze(TuningExperiment.Snapshot report) {
        var values=new ArrayList<TuningTrace>();for(int i=0;i<report.runs().size();i++) {var value=find(report,i);if(value!=null) values.add(value);}return new FrozenTuningTraces(report,values);
    }
    public boolean current(FrozenTuningTraces frozen) {return frozen!=null && freeze(frozen.report()).equals(frozen);}
    public boolean record(List<TuningExperiment.Snapshot> reports,TuningTrace added) {
        return stage(reports,added,null,null) && retry();
    }
    public boolean stage(List<TuningExperiment.Snapshot> reports,TuningTrace added,TuningPurpose purpose,TuningExperiment.Snapshot reportBase) {
        if(reports==null || reports.size()>8 || added!=null && reports.stream().noneMatch(r->added.matches(r,added.index()))) throw new IllegalArgumentException("Trace has no matching retained report");
        if(pendingRecovery!=null && (added!=null && !pendingRecovery.candidate().equals(added) || !pendingRecovery.reportAvailable(reports))) {warning="已有另一份原曲线待存；原候选与副本保持，请先处理。";return false;}
        var nextRecovery=pendingRecovery;if(added!=null && nextRecovery==null) {var report=reports.stream().filter(value->added.matches(value,added.index())).findFirst().orElseThrow();nextRecovery=new Recovery(reportBase,report,purpose,at(accepted,added.report(),added.index()),added);}
        var updated=merge(archive,reports,added);
        if(!updated.equals(archive)) {archive=updated;pending=true;revision++;}
        if(pending) {pendingReports=List.copyOf(reports);pendingRecovery=nextRecovery;}
        return checkpoint();
    }
    private static Archive merge(Archive original,List<TuningExperiment.Snapshot> reports,TuningTrace added) {var rows=new ArrayList<TuningTrace>();if(added!=null) rows.add(added);for(var old:original.traces()) if((added==null || !old.report().equals(added.report()) || old.index()!=added.index()) && reports.stream().anyMatch(report->old.matches(report,old.index()))) rows.add(old);return new Archive(format(rows),rows);}
    private static TuningTrace at(Archive value,String report,int index) {return value.traces().stream().filter(t->t.report().equals(report) && t.index()==index).findFirst().orElse(null);}
    public TuningTrace acceptedTrace(Recovery value) {return value==null?null:at(accepted,value.candidate().report(),value.candidate().index());}
    public Relation relation(Recovery value,List<TuningExperiment.Snapshot> reports) {if(value==null || !value.reportAvailable(reports)) return Relation.REPORT_MISSING;var actual=acceptedTrace(value);return value.candidate().equals(actual)?Relation.ALREADY_SAVED:Objects.equals(value.base(),actual)?Relation.READY:Relation.CONFLICT;}
    public boolean checkpoint() {if(!pending) return true;if(pendingRecovery==null) {if(recoveryNeedsAttention()) {warning="先明确处理已读原曲线副本，再保存库清理。";return false;}return true;}if(recoveryFile.store(pendingRecovery)) return true;warning="原曲线尚未发布；先保存同份恢复副本。";return false;}
    public boolean retry() {
        return retry(pendingReports);
    }
    public boolean retry(List<TuningExperiment.Snapshot> reports) {
        return retry(reports,ArchiveWriter.NO_CHECK);
    }
    boolean retry(List<TuningExperiment.Snapshot> reports,ArchiveWriter.ReplacementCheck reportCheck) {
        if(!pending) return true;
        if(pendingRecovery!=null && !pendingRecovery.reportAvailable(reports)) {
            warning="尚无匹配这份原曲线的已存报告；先处理报告，再明确保存曲线。";
            return false;
        }
        if(!checkpoint()) return false;
        diagnostics.begin();
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Trace archive is busy");
                DiskArchive disk;
                try(var work=diagnostics.measure(SaveDiagnostics.Work.READ_CURRENT)) {disk=readDisk(damaged);}
                var current=disk.archive();
                var expectedStamp=disk.stamp();
                if(current!=null) {
                    if(pendingRecovery!=null) {
                        var candidate=pendingRecovery.candidate();
                        var actual=at(current,candidate.report(),candidate.index());
                        if(candidate.equals(actual)) {
                            try(var work=diagnostics.measure(SaveDiagnostics.Work.VERIFY)) {reportCheck.verify(file);}
                            archive=current;
                            revision++;
                            return finishSave(expectedStamp,reportCheck);
                        }
                        if(!Objects.equals(pendingRecovery.base(),actual)) {
                            warning="原曲线所在报告／组号已存内容改变；两份保持，不自动覆盖。";
                            return false;
                        }
                        boolean archiveChanged=!accepted.equals(current);
                        if(archiveChanged && current.traces().stream().anyMatch(trace->!Objects.equals(trace,at(accepted,trace.report(),trace.index()))
                                && reports.stream().noneMatch(report->trace.matches(report,trace.index())))) {
                            warning="曲线库有新的原次与当前已读报告不对应；请重读报告后重试，原库与待存候选保持。";
                            return false;
                        }
                        if(archiveChanged || !pendingReports.equals(reports)) {
                            archive=merge(current,reports,candidate);
                            accepted=current;
                            pendingReports=List.copyOf(reports);
                            revision++;
                        }
                    } else if(!accepted.equals(current)) {
                        warning="曲线库已变化；原库与本次清理保持，请重新核对。";
                        return false;
                    }
                    damaged=false;
                }
                diagnostics.at(SaveDiagnostics.Stage.ENCODE);
                byte[] bytes;
                try(var work=diagnostics.measure(SaveDiagnostics.Work.ENCODE)) {bytes=gson.toJson(archive).getBytes(StandardCharsets.UTF_8);}
                int limit=archive.version()==1?16_000_000:MAX_BYTES;
                if(bytes.length>limit) throw new IOException("Trace archive exceeds bound");
                diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                try(var work=diagnostics.measure(SaveDiagnostics.Work.BACKUP)) {backupIfNeeded(disk);}
                ArchiveWriter.bytes(file,bytes,limit,"trial-traces-",diagnostics,filesystem,
                        target->verifyPublication(target,expectedStamp,reportCheck));
                return finishSave(ArchiveWriter.stamp(bytes),reportCheck);
            }
        } catch(IOException | RuntimeException error) {
            if(!pending) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);
            warning=diagnostics.failure().stage()==SaveDiagnostics.Stage.BACKUP
                    ?"原曲线备份无法写入或核对。待存曲线已保留，请检查备份文件和磁盘空间后重试。"
                    :"连续记录尚未保存；本次候选与可用恢复副本保持，请明确重试。";
            return false;
        } finally {
            finishTiming();
        }
    }
    private void finishTiming() {
        var timing=diagnostics.finish();
        if(timing!=null) saveTiming=timing;
    }
    private void backupIfNeeded(DiskArchive disk) throws IOException {
        if(ArchiveWriter.ABSENT.equals(disk.stamp())) return;
        String firstName=null;
        if(disk.archive()!=null) {
            int version=disk.archive().version();
            if(version==1 && archive.version()==2) firstName="trial-traces.pre-positions-v1.json";
            else if(version<3 && archive.version()==3) firstName="trial-traces.pre-exact-v"+version+".json";
            else return;
        }
        ArchiveWriter.preserveOriginal(file,disk.stamp(),firstName,"trial-traces.backup-",filesystem);
    }
    private boolean finishSave(ArchiveWriter.Stamp publishedStamp,ArchiveWriter.ReplacementCheck reportCheck) {
        boolean captured=pendingRecovery!=null;
        accepted=archive;
        pending=false;
        damaged=false;
        pendingRecovery=null;
        pendingReports=List.of();
        warning="";
        finishTiming();
        if(captured && !recoveryFile.store(null,target->verifyPublication(file,publishedStamp,reportCheck)))
            warning="原曲线已保存，但副本尚未清理；请明确核对已存原次后清理。";
        return true;
    }
    private static void verifyPublication(Path path,ArchiveWriter.Stamp expected,ArchiveWriter.ReplacementCheck reportCheck) throws IOException {
        ArchiveWriter.verifyStamp(path,expected,LinkOption.NOFOLLOW_LINKS);
        reportCheck.verify(path);
    }
    private DiskArchive readDisk(boolean allowDamaged) throws IOException {
        try {
            var raw=ArchiveWriter.readSource(file,MAX_BYTES,allowDamaged);
            var bytes=raw.bytes();
            var fingerprint=raw.stamp();
            if(bytes==null) return new DiskArchive(null,fingerprint);
            try {
                String source=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                CreationStore.depth(source);
                var value=Objects.requireNonNull(gson.fromJson(source,Archive.class));
                if(value.version()==1 && bytes.length>16_000_000) throw new IOException("Legacy trace archive exceeds bound");
                return new DiskArchive(value,fingerprint);
            } catch(IOException | RuntimeException error) {
                if(!allowDamaged) throw error;
                return new DiskArchive(null,fingerprint);
            }
        } catch(NoSuchFileException absent) {return new DiskArchive(new Archive(1,List.of()),ArchiveWriter.ABSENT);}
    }
    private void accept(Archive disk) {archive=disk;accepted=disk;damaged=false;revision++;warning="";}
    public boolean reload() {if(pending) {warning="原曲线仍在内存待存，不重读覆盖。";return false;}try {accept(readDisk(false).archive());return true;}catch(IOException | RuntimeException error) {damaged=true;warning="连续记录无法读取，原文件与已读内容保留。";return false;}}
    public boolean reloadRecovery() {return recoveryFile.reload();}public boolean verifyRecovery(Recovery value) {return value!=null && recoveryFile.verify(value);}
    public boolean restoreRecovery(Recovery value,List<TuningExperiment.Snapshot> reports) {if(pending) {warning="已有内存原曲线待存；先保存或明确取消，再处理磁盘副本。";return false;}if(!verifyRecovery(value) || !reload()) return false;if(relation(value,reports)!=Relation.READY) {warning="当前报告或已存原曲线与恢复基准不符；原文保持。";return false;}archive=merge(accepted,reports,value.candidate());pendingReports=List.copyOf(reports);pendingRecovery=value;pending=true;revision++;warning="已恢复原曲线为待存，尚未发布；请另点保存。";return true;}
    public boolean acknowledgeRecovery(Recovery value,List<TuningExperiment.Snapshot> reports) {
        return acknowledgeRecovery(value,reports,ArchiveWriter.NO_CHECK);
    }
    boolean acknowledgeRecovery(Recovery value,List<TuningExperiment.Snapshot> reports,
            ArchiveWriter.ReplacementCheck reportCheck) {
        if(pending) {warning="先处理本次内存原曲线，再确认磁盘副本。";return false;}
        if(!verifyRecovery(value)) return false;
        boolean cleared=false;
        try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
            if(held==null) throw new IOException("Trace archive is busy");
            var disk=readDisk(false);
            accept(disk.archive());
            if(relation(value,reports)!=Relation.ALREADY_SAVED) {warning="尚不能确认匹配报告与原曲线均已保存；副本保持。";return false;}
            cleared=recoveryFile.store(null,target->verifyPublication(file,disk.stamp(),reportCheck));
            if(!cleared) return false;
        } catch(IOException | RuntimeException error) {
            if(!cleared) {warning="原曲线主库无法核对，请重试或重读。";return false;}
            diagnostics.cleanupFailed(error);
        }
        warning="仅清理已确认保存的原曲线副本。";return true;
    }
    public boolean discardRecovery(Recovery value) {if(pending) {warning="内存原曲线仍待存；请先保存或明确取消。";return false;}if(!verifyRecovery(value) || !recoveryFile.store(null)) return false;warning="已明确放弃所选原曲线副本，已存资料保持。";return true;}
    public boolean cancelPending(Recovery value) {if(!pending || !Objects.equals(value,pendingRecovery) || value==null) {warning="所选内存原曲线已变化，请重新核对。";return false;}if(recovery()!=null && !value.equals(recovery())) {archive=accepted;pending=false;pendingRecovery=null;pendingReports=List.of();revision++;warning="仅取消内存原曲线，另一份磁盘副本保持。";return true;}if(!recoveryFile.store(null)) return false;archive=accepted;pending=false;pendingRecovery=null;pendingReports=List.of();revision++;warning="已明确取消原曲线待存，已存曲线保持。";return true;}
}
