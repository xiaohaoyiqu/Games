package com.f1car.persistence;

import com.f1car.model.PairExperiment;
import com.f1car.model.PairTrialRevision;
import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Independent four-condition reports; write success publishes memory, failure retains the exact candidate. */
public final class PairTrialStore {
    public static final int CAPACITY=4,ENTRY_BYTES=8_000_000,HISTORY_CAPACITY=4,HISTORY_BYTES=16_000_000,MAX_BYTES=48_200_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Path file;private List<PairExperiment.Snapshot> reports=List.of();private PairExperiment.Snapshot pending,pendingBase;private boolean writable=true;private String warning="";
    private final PairTrialRecoveryFile recoveryFile;
    private List<PairTrialRevision> history=List.of();private int format=1;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("pair-trials");
    private final GuardedArchive archive;
    public record Recovery(PairExperiment.Snapshot base,PairExperiment.Snapshot candidate) {
        public Recovery {Objects.requireNonNull(candidate);if(candidate.equals(base) || !validUpdate(base,candidate)) throw new IllegalArgumentException("Invalid recovery base or candidate");}
    }
    public PairTrialStore(Path directory) {this(directory,new PairTrialRecoveryFile.Filesystem() {});}
    PairTrialStore(Path directory,PairTrialRecoveryFile.Filesystem recoveryFilesystem) {
        file=directory.resolve("pair-trials.json");
        archive=new GuardedArchive(directory,"pair-trials",MAX_BYTES,diagnostics);
        readReports();
        recoveryFile=new PairTrialRecoveryFile(directory,recoveryFilesystem);
    }
    public Path file() {return file;}public List<PairExperiment.Snapshot> reports() {return reports;}public PairExperiment.Snapshot pending() {return pending;}public String warning() {return warning+(recoveryFile.warning().isEmpty()?"":(warning.isEmpty()?"":" ")+recoveryFile.warning());}public boolean writable() {return writable;}
    public Path recoveryPath() {return recoveryFile.file();}public Recovery recovery() {return recoveryFile.entry();}public boolean recoveryBlocked() {return recoveryFile.blocked();}
    public boolean recoveryReviewNeeded() {return pending==null && (recovery()!=null || recoveryBlocked());}
    public SaveDiagnostics.Failure recoveryFailure() {return recoveryFile.failure();}
    public boolean pendingRecoverable() {var saved=recovery();return pending!=null && saved!=null && !recoveryBlocked() && Objects.equals(pendingBase,saved.base()) && pending.equals(saved.candidate());}
    public List<PairTrialRevision> history() {return history;}
    public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}
    public PairExperiment.Snapshot find(String id) {return reports.stream().filter(report->report.id().equals(id)).findFirst().orElse(null);}
    public boolean save(PairExperiment.Snapshot value) {
        Objects.requireNonNull(value);if(pending!=null && !pending.equals(value)) {warning="先重试或明确取消原待存四条件报告；原候选保持。";return false;}
        if(pending==null && recoveryReviewNeeded()) {warning="先查看重开恢复副本，明确恢复为待存或放弃，再保存新报告。";return false;}
        var old=find(value.id());if(!validUpdate(old,value)) {warning="同身份四条件报告背景冲突、版本过期或替换缺少新试次；原记录保持。";return false;}
        if(value.equals(old)) return verifySaved();
        if(pending==null) {pending=value;pendingBase=old;}return retry();
    }
    private static boolean validUpdate(PairExperiment.Snapshot old,PairExperiment.Snapshot value) {
        if(old==null) return true;
        if(!PairTrialRevision.sameSource(old,value) || value.lastAttempt()<old.lastAttempt() || value.runs().size()<old.runs().size() || value.failedAttempts()<old.failedAttempts() || value.lastAttempt()==old.lastAttempt() && !value.equals(old)) return false;
        try {PairTrialRevision.capture(old,value);return true;}catch(IllegalArgumentException error) {return false;}
    }
    public boolean retry() {
        if(pending==null) return !recoveryReviewNeeded();
        if(!recoveryFile.store(new Recovery(pendingBase,pending))) {warning="报告尚未发布；先保存同份恢复副本。";return false;}
        if(!readReports()) return false;var actual=find(pending.id());
        if(pending.equals(actual)) return finishPending();
        if(!Objects.equals(pendingBase,actual)) {warning="已保存的同身份报告与原待存基准不同；两份保持，请取消后重新核对，不自动覆盖。";return false;}
        if(find(pending.id())==null && reports.size()>=CAPACITY) {warning="最多四份四条件报告；原待存保持，请先移除一份。";return false;}
        var next=new ArrayList<PairExperiment.Snapshot>();next.add(pending);reports.stream().filter(report->!report.id().equals(pending.id())).forEach(next::add);
        var revisions=new ArrayList<>(history);var old=find(pending.id());var revision=old==null?null:PairTrialRevision.capture(old,pending);
        if(revision!=null) {if(revisions.size()>=HISTORY_CAPACITY) {warning="重测历史最多四份；原待存保持，请先明确移除一份历史，再重试。";return false;}revisions.add(0,revision);}
        if(!commit(next,revisions,revision==null?format:2)) return false;return finishPending();
    }
    private boolean finishPending() {
        pending=pendingBase=null;
        if(!recoveryFile.store(null)) warning="报告已保存，但恢复副本尚未清理；请查看恢复页确认已存内容，不会重复发布。";
        return true;
    }
    public boolean cancelPending() {
        if(pending==null) return true;
        if(recovery()!=null && !new Recovery(pendingBase,pending).equals(recovery())) {pending=pendingBase=null;warning="仅取消本次内存候选；另一份磁盘恢复副本保持，请查看。";return true;}
        if(!recoveryFile.store(null)) {warning="尚未完成取消；恢复副本与原候选保持，修复后明确重试取消。";return false;}
        pending=pendingBase=null;warning=writable?"":"报告库读取受阻，修复后明确重读。";return true;
    }
    public boolean reloadRecovery() {
        boolean loaded=recoveryFile.reload();if(loaded) warning=pending==null?"":"已重读恢复文件；内存原候选保持，未自动替换或发布。";return loaded;
    }
    public boolean restoreRecovery(Recovery reviewed) {
        if(pending!=null || reviewed==null || !recoveryFile.verify(reviewed)) return false;
        if(!readReports()) return false;var current=find(reviewed.candidate().id());
        if(reviewed.candidate().equals(current)) {warning="这份候选已经保存；请明确确认并清理恢复副本，不重复发布。";return false;}
        if(!Objects.equals(reviewed.base(),current)) {warning="恢复副本的原基准与当前已存报告不同；原文保留，不能直接覆盖。";return false;}
        pending=reviewed.candidate();pendingBase=reviewed.base();warning="已恢复为待存，尚未发布；请再次明确重试保存。";return true;
    }
    public boolean discardRecovery(Recovery reviewed) {
        if(pending!=null || reviewed==null || !recoveryFile.verify(reviewed)) return false;
        if(!recoveryFile.store(null)) return false;warning="已明确放弃恢复副本；已存报告保持。";return true;
    }
    public boolean acknowledgeRecovery(Recovery reviewed) {
        if(pending!=null || reviewed==null || !recoveryFile.verify(reviewed) || !readReports()) return false;
        if(!reviewed.candidate().equals(find(reviewed.candidate().id()))) {warning="当前报告已变化，不能确认这份候选已保存；请重新核对。";return false;}
        if(!recoveryFile.store(null)) return false;warning="已确认报告保存，恢复副本已清理；没有重复发布。";return true;
    }
    public boolean remove(PairExperiment.Snapshot expected) {
        if(expected==null || !expected.equals(find(expected.id())) || pending!=null && pending.id().equals(expected.id())) {warning="报告已变化或仍有同身份待存，请先处理原候选。";return false;}
        return commit(reports.stream().filter(report->!report.equals(expected)).toList(),history.stream().filter(row->!row.before().id().equals(expected.id())).toList(),format);
    }
    public boolean removeHistory(PairTrialRevision expected) {
        if(expected==null || !history.contains(expected)) {warning="所选重测历史已变化，请重新选择。";return false;}
        if(!commit(reports,history.stream().filter(row->!row.equals(expected)).toList(),format)) return false;
        if(pending!=null) warning="历史已移除；原待存候选仍在，请明确重试保存。";return true;
    }
    public boolean reload() {
        if(pending!=null) {warning="原四条件待存仍在内存中，先重试或明确取消。";return false;}
        return readReports();
    }
    private boolean readReports() {
        try {
            byte[] source=archive.readSource();
            if(source==null) {reports=List.of();history=List.of();format=1;writable=true;warning="";return true;}
            var root=new PartsJson.Node(TuningLapStudyCodec.readTree(source,MAX_BYTES,6_100_000),"");int version=root.child("version").integer(1,2);root.object(version==1?new String[]{"version","reports"}:new String[]{"version","reports","history"});var values=root.child("reports").array(0,CAPACITY);var next=new ArrayList<PairExperiment.Snapshot>();var ids=new HashSet<String>();
            for(var value:values) {if(encoded(value)>ENTRY_BYTES) throw new IOException("Pair report exceeds bound");ActivityCardCodec.validate(value,PairExperiment.Snapshot.class);var report=Objects.requireNonNull(JSON.fromJson(value,PairExperiment.Snapshot.class));if(!ids.add(report.id())) throw new IOException("Duplicate pair report identity");next.add(report);}
            var revisions=new ArrayList<PairTrialRevision>();var revisionIds=new HashSet<String>();long historyBytes=2;
            if(version==2) for(var value:root.child("history").array(0,HISTORY_CAPACITY)) {int bytes=encoded(value);if(bytes>ENTRY_BYTES+4096 || (historyBytes+=bytes+1)>HISTORY_BYTES) throw new IOException("Retest history exceeds bound");ActivityCardCodec.validate(value,PairTrialRevision.class);var row=Objects.requireNonNull(JSON.fromJson(value,PairTrialRevision.class));if(!revisionIds.add(row.id()) || next.stream().noneMatch(row::matches)) throw new IOException("Retest history source differs");revisions.add(row);}
            if(!reports.equals(next)) reports=List.copyOf(next);if(!history.equals(revisions)) history=List.copyOf(revisions);format=version;writable=true;warning="";return true;
        }catch(IOException | RuntimeException | ReflectiveOperationException error) {writable=false;warning="四条件报告库无法读取；原文件与已读内容保持，修复后明确重读。";return false;}
    }
    private static int encoded(JsonElement value) throws java.nio.charset.CharacterCodingException {return java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(JSON.toJson(value))).remaining();}
    private boolean commit(List<PairExperiment.Snapshot> values,List<PairTrialRevision> revisions,int nextFormat) {
        if(!writable) return false;
        diagnostics.begin();
        try {
            diagnostics.at(SaveDiagnostics.Stage.ENCODE);
            if(values.size()>CAPACITY || revisions.size()>HISTORY_CAPACITY || nextFormat<1 || nextFormat>2 || nextFormat==1 && !revisions.isEmpty()) throw new IOException("Too many pair reports or history entries");
            archive.commit(output->writeReports(output,values,revisions,nextFormat),false,
                format==1 && nextFormat==2?"pair-trials.pre-history-v1.json":null);
            reports=List.copyOf(values);history=List.copyOf(revisions);format=nextFormat;warning="";return true;
        }catch(IOException | RuntimeException error) {
            diagnostics.failed(error);
            return rejected(error);
        }
    }
    private static void writeReports(Writer output,List<PairExperiment.Snapshot> values,List<PairTrialRevision> revisions,int version) throws IOException {
        try(var writer=new JsonWriter(output)) {
            writer.beginObject().name("version").value(version).name("reports").beginArray();
            for(var value:values) {
                var tree=JSON.toJsonTree(value);
                if(encoded(tree)>ENTRY_BYTES) throw new IOException("Pair report exceeds bound");
                JSON.toJson(tree,writer);
            }
            writer.endArray();
            if(version==2) {
                writer.name("history").beginArray();
                long historyBytes=2;var ids=new HashSet<String>();
                for(var row:revisions) {
                    if(!ids.add(row.id()) || values.stream().noneMatch(row::matches)) throw new IOException("History source differs");
                    var tree=JSON.toJsonTree(row);int bytes=encoded(tree);
                    if(bytes>ENTRY_BYTES+4096 || (historyBytes+=bytes+1)>HISTORY_BYTES) throw new IOException("History byte budget is full");
                    JSON.toJson(tree,writer);
                }
                writer.endArray();
            }
            writer.endObject();
        }
    }
    private boolean verifySaved() {
        if(!writable) return false;
        try {archive.verifyAccepted();warning="";return true;}
        catch(IOException | RuntimeException error) {return rejected(error);}
    }
    private boolean rejected(Exception error) {
        warning=error instanceof ArchiveWriter.StaleArchiveException
            ?"四条件报告或历史已由另一窗口更新；请重新核对，待存报告可在重试时重读。"
            :"四条件报告或历史未保存；原报告、历史与同份待存保持，检查容量或目标后明确重试。";
        return false;
    }
}
