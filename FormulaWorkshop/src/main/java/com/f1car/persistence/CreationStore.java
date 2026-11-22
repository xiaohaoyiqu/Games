package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Creation briefs and package library; no imported file can install a local driving proof. */
public final class CreationStore {
    private static final java.util.logging.Logger LOG=java.util.logging.Logger.getLogger(CreationStore.class.getName());
    public enum Stage {
        READ("读取"),VALIDATE("内容校验"),ENCODE("编码"),PREPARE("准备保存"),BACKUP("保留备份"),WRITE("写入临时文件"),REPLACE("替换记录文件");
        public final String title;Stage(String title) {this.title=title;}
    }
    public enum ReadState { ABSENT,LOADED,FAILED }
    public record Cause(String type,String message) {}
    public record Failure(Stage stage,List<Cause> causes,int observedBytes,String sha256,boolean prefixOnly) {
        public Failure {Objects.requireNonNull(stage);causes=List.copyOf(causes);if(causes.isEmpty() || causes.size()>4) throw new IllegalArgumentException("Invalid storage failure");}
    }
    public record Status(ReadState readState,boolean pending,int briefs,int reports,int packs,Failure failure) {}
    private record Archive(int version,List<CircuitCommission> briefs,List<CircuitCommission.Report> reports,List<CircuitEventPack.Entry> packs) {
        private Archive {
            if(version<1 || version>4 || briefs==null || briefs.size()>16 || reports==null || reports.size()>24 || packs==null || packs.size()>12) throw new IllegalArgumentException("Invalid creation archive");
            briefs=List.copyOf(briefs);reports=List.copyOf(reports);packs=List.copyOf(packs);
            if(briefs.stream().map(CircuitCommission::id).distinct().count()!=briefs.size() || briefs.stream().map(b->b.template().id()).distinct().count()!=briefs.size() || reports.stream().map(CircuitCommission.Report::receipt).distinct().count()!=reports.size() || packs.stream().map(CircuitEventPack.Entry::id).distinct().count()!=packs.size()) throw new IllegalArgumentException("Duplicate creation entry");
            if(version==1 && packs.stream().anyMatch(pack->pack.original().version()==3)) throw new IllegalArgumentException("Parts packages require creation archive version 2");
            if(version<4 && packs.stream().anyMatch(pack->pack.original().version()==5)) throw new IllegalArgumentException("Section packages require creation archive version 4");
            if(version<3 && packs.stream().anyMatch(pack->pack.original().version()==4)) throw new IllegalArgumentException("Compound packages require creation archive version 3");
        }
    }
    public static final class ConflictReview {
        private final CreationStore owner;
        private final Archive local;
        private final Archive disk;
        private final Archive merged;
        private final GuardedArchive target;
        private final List<String> additions;
        private final List<CircuitEventPack.Entry> reusedImports;

        private ConflictReview(CreationStore owner, Archive local, Archive disk, Archive merged, GuardedArchive target,
                               List<String> additions, List<CircuitEventPack.Entry> reusedImports) {
            this.owner = owner;
            this.local = local;
            this.disk = disk;
            this.merged = merged;
            this.target = target;
            this.additions = List.copyOf(additions);
            this.reusedImports = List.copyOf(reusedImports);
        }

        public List<String> additions() {return additions;}
        public List<CircuitEventPack.Entry> reusedImports() {return reusedImports;}
        public int savedBriefs() {return disk.briefs().size();}
        public int savedReports() {return disk.reports().size();}
        public int savedPacks() {return disk.packs().size();}
    }
    private static final int MAX_BYTES=8388608,PACK_BYTES=524288;
    private final Path file;
    private GuardedArchive storage;
    private final SaveDiagnostics saveDiagnostics = new SaveDiagnostics("creation-workshop");
    private final ArchiveWriter.Filesystem exportFilesystem;
    private final SaveDiagnostics exportDiagnostics=new SaveDiagnostics("circuit-pack-export");
    private final Gson gson=new Gson();
    private Archive saved=new Archive(1,List.of(),List.of(),List.of()),pending;
    private boolean damaged;
    private boolean changedOnDisk;
    private ReadState readState=ReadState.ABSENT;
    private Failure failure;
    private byte[] failureBytes;
    public String warning="";
    public CreationStore(Path directory) {this(directory,new ArchiveWriter.Filesystem() {});}
    CreationStore(Path directory,ArchiveWriter.Filesystem exportFilesystem) {
        this.exportFilesystem=Objects.requireNonNull(exportFilesystem);
        file=directory.resolve("creation-workshop.json");
        storage = new GuardedArchive(directory, "creation-workshop", MAX_BYTES, saveDiagnostics);
        load(false);
    }
    private boolean load(boolean retry) {
        Stage phase=Stage.READ;byte[] bytes=null;
        try {
            bytes = storage.readPrefix();
            if (bytes == null) {
                if (retry) throw new NoSuchFileException(file.getFileName().toString());
                readState = ReadState.ABSENT;
                return true;
            }
            phase=Stage.VALIDATE;accept(readArchive(bytes));return true;
        } catch(IOException | RuntimeException error) {failed(phase,error,bytes);}
        damaged=true;readState=ReadState.FAILED;warning="创作记录无法读取，原文件保留；可查看问题后重新读取。";return false;
    }
    public Status status() {var state=state();return new Status(readState,pending!=null,state.briefs().size(),state.reports().size(),state.packs().size(),failure);}
    public boolean canRetryRead() {return damaged && pending==null;}
    public boolean retryRead() {
        if(pending!=null) {warning="本次创作记录尚未保存，请先重试保存，避免覆盖内存内容。";return false;}
        return !damaged || load(true);
    }

    /** Refresh committed package mappings without replacing pending creation work. */
    public boolean refreshForPackActivation() {
        if(pending!=null) {
            warning="请先保存本次创作记录，再核对赛事包。";
            return false;
        }
        if(!damaged) {
            try {storage.verifyAccepted();return true;}
            catch(IOException | RuntimeException changed) { /* Try a separate read before adopting it. */ }
        }
        var source=new GuardedArchive(file.toAbsolutePath().getParent(),"creation-workshop",MAX_BYTES,saveDiagnostics);
        try {
            byte[] bytes=source.readSource();
            var disk=bytes==null?new Archive(1,List.of(),List.of(),List.of()):readArchive(bytes);
            source.verifyAccepted();
            storage=source;
            accept(disk);
            if(bytes==null) readState=ReadState.ABSENT;
            return true;
        } catch(IOException | RuntimeException error) {
            warning="磁盘创作记录暂时无法核对，请核对后重新选择赛事包。";
            return false;
        }
    }

    private Archive readArchive(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Creation archive too large");
        String source = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        depth(source);
        return Objects.requireNonNull(gson.fromJson(source, Archive.class));
    }

    private void accept(Archive value) {
        saved = value;
        pending = null;
        damaged = changedOnDisk = false;
        readState = ReadState.LOADED;
        failure = null;
        failureBytes = null;
        warning = "";
    }
    /** Explicit diagnostic access only: render/status never copies, hashes or serializes file bytes. */
    public byte[] failureBytes() {return failureBytes==null?null:failureBytes.clone();}
    public String storageLabel() {return pending!=null?"本次记录待保存 · 查看":readState==ReadState.FAILED?"记录未能载入 · 查看":readState==ReadState.ABSENT?"尚无创作记录文件 · 查看":"创作记录已落盘 · 查看";}
    private void failed(Stage phase,Throwable error,byte[] observed) {
        var causes=new ArrayList<Cause>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null && causes.size()<4 && seen.add(cause);cause=cause.getCause()) {
            String message=Objects.toString(cause.getMessage(),"");causes.add(new Cause(cause.getClass().getName(),message.substring(0,Math.min(240,message.length()))));
        }
        String sha=null;if(observed!=null) try {sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(observed));}catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
        failure=new Failure(phase,causes,observed==null?-1:observed.length,sha,observed!=null && observed.length>MAX_BYTES);failureBytes=observed;
        try {LOG.warning("Creation archive failed at "+phase+"; observedBytes="+failure.observedBytes()+"; sha256="+sha+"; causes="+causes.toString().replace('\n',' ').replace('\r',' '));}catch(RuntimeException loggingFailure) { /* Storage recovery must remain available even when a log handler fails. */ }
    }
    private Archive state() { return pending!=null?pending:saved; }
    private static Archive archive(List<CircuitCommission> briefs,List<CircuitCommission.Report> reports,List<CircuitEventPack.Entry> packs) {return new Archive(packs.stream().anyMatch(pack->pack.original().version()==5)?4:packs.stream().anyMatch(pack->pack.original().version()==4)?3:packs.stream().anyMatch(pack->pack.original().version()==3)?2:1,briefs,reports,packs);}
    public List<CircuitCommission> briefs() { return state().briefs(); }
    public CircuitCommission commission(String documentId) { return state().briefs().stream().filter(b->b.template().id().equals(documentId)).findFirst().orElse(null); }
    public List<CircuitCommission.Report> reports() { return state().reports(); }
    public List<CircuitEventPack.Entry> packs() { return state().packs(); }
    public CircuitEventPack.Entry pack(String id) { return state().packs().stream().filter(p->p.id().equals(id)).findFirst().orElse(null); }
    public boolean pending() { return pending!=null; }
    public boolean begin(CircuitCommission brief) {
        var state=state();if(state.briefs().stream().anyMatch(b->b.id().equals(brief.id()))) return flush();if(state.briefs().size()==16) { warning="委托已满十六份，请先放弃不再继续的委托。";return false; }
        var briefs=new ArrayList<>(state.briefs());briefs.add(brief);pending=archive(briefs,state.reports(),state.packs());return flush();
    }
    public boolean abandon(String documentId) { var s=state();pending=archive(s.briefs().stream().filter(b->!b.template().id().equals(documentId)).toList(),s.reports(),s.packs());return flush(); }
    public boolean record(CircuitCommission.Report report) {
        var s=state();if(s.reports().stream().anyMatch(r->r.receipt().equals(report.receipt()))) return flush();var reports=new ArrayList<>(s.reports());reports.add(0,report);while(reports.size()>24) reports.remove(reports.size()-1);pending=archive(s.briefs(),reports,s.packs());return flush();
    }
    public boolean put(CircuitEventPack.Entry entry) {
        var s=state();var old=pack(entry.id());if(old!=null) { if(!old.original().digest().equals(entry.original().digest())) throw new IllegalArgumentException("Conflicting circuit package identity");return flush(); }
        if(s.packs().size()==12) { warning="赛事包已满十二份，请先删除不需要的包；地图草稿另行保留。";return false; }
        var packs=new ArrayList<>(s.packs());packs.add(entry);pending=archive(s.briefs(),s.reports(),packs);return flush();
    }
    public boolean remove(String id) { var s=state();pending=archive(s.briefs(),s.reports(),s.packs().stream().filter(p->!p.id().equals(id)).toList());return flush(); }
    public boolean flush() {
        if(pending==null) return true;
        saveDiagnostics.begin();
        try {
            if(damaged) storage.acceptMissingSource();
            storage.commit(gson.toJson(pending), damaged, migrationBackup(saved, pending));
            accept(pending);
            return true;
        }catch(IOException | RuntimeException error) {
            var saveFailure = saveDiagnostics.failure();
            var phase = saveFailure == null ? Stage.ENCODE : switch (saveFailure.stage()) {
                case ENCODE -> Stage.ENCODE;
                case PREPARE -> Stage.PREPARE;
                case BACKUP -> Stage.BACKUP;
                case CREATE_TEMP, WRITE_TEMP -> Stage.WRITE;
                case REPLACE, FALLBACK_REPLACE, CLEANUP -> Stage.REPLACE;
            };
            failed(phase,error,null);
            changedOnDisk |= error instanceof ArchiveWriter.StaleArchiveException;
            warning = changedOnDisk
                    ? "磁盘上的创作记录已变化。磁盘记录已保留，本次改动暂存在当前窗口。"
                    : "创作记录或赛事包暂存在本次运行，请重试保存。";
            return false;
        }
    }
    public boolean conflictReviewAvailable() {return pending != null && changedOnDisk && !damaged;}

    /** Prepare an append-only merge, reusing the saved local identities of an identical imported package. */
    public ConflictReview reviewConflict() {
        if (!conflictReviewAvailable()) return null;
        var diskFile = new GuardedArchive(file.toAbsolutePath().getParent(), "creation-workshop", MAX_BYTES, saveDiagnostics);
        try {
            byte[] bytes = diskFile.readSource();
            if (bytes == null) throw new IOException("Missing current archive");
            var disk = readArchive(bytes);
            var additions = new ArrayList<String>();
            var reusedImports = new ArrayList<CircuitEventPack.Entry>();
            var briefs = appendReviewed(saved.briefs(), pending.briefs(), disk.briefs(), CircuitCommission::id,
                     value -> "制图委托 · " + value.kind().title + " · " + value.template().name(), false, additions,
                     (local, stored) -> false);
            var reports = appendReviewed(saved.reports(), pending.reports(), disk.reports(), CircuitCommission.Report::receipt,
                     value -> "完成报告 · " + value.kind().title, true, additions, (local, stored) -> false);
            var packs = appendReviewed(saved.packs(), pending.packs(), disk.packs(), CircuitEventPack.Entry::id,
                     value -> "赛事包 · " + value.original().title(), false, additions, (local, stored) -> {
                         if (!local.imported() || !local.original().equals(stored.original())) return false;
                         reusedImports.add(stored);
                         return true;
                     });
            var merged = additions.isEmpty() ? disk : archive(briefs, reports, packs);
            return new ConflictReview(this, pending, disk, merged, diskFile, additions, reusedImports);
        } catch (IllegalArgumentException error) {
            warning = "当前两份记录含移除、同身份异内容，或合并后超出容量。磁盘记录和本次改动均已保留。";
        } catch (IOException | RuntimeException error) {
            warning = "磁盘创作记录暂时无法核对。磁盘记录和本次改动均已保留。";
        }
        return null;
    }

    public boolean saveConflict(ConflictReview review) {
        if (review == null || review.owner != this || review.local != pending || !conflictReviewAvailable()) {
            warning = "本次创作内容已变化，请重新查看两份记录。";
            return false;
        }
        try {
            if (review.additions.isEmpty()) review.target.verifyAccepted();
            else review.target.commit(gson.toJson(review.merged), false, migrationBackup(review.disk, review.merged));
            storage = review.target;
            accept(review.merged);
            return true;
        } catch (ArchiveWriter.StaleArchiveException error) {
            warning = "磁盘创作记录在核对后又有更新，请重新查看两份记录。";
        } catch (IOException | RuntimeException error) {
            warning = "合并结果尚未保存，磁盘记录和本次改动均已保留，可重新查看后重试。";
        }
        return false;
    }

    private static <T> List<T> appendReviewed(List<T> base, List<T> local, List<T> disk, Function<T, String> id,
                                             Function<T, String> label, boolean newestFirst, List<String> additions,
                                             BiPredicate<T,T> reuseStored) {
        if (!local.containsAll(base)) throw new IllegalArgumentException("Local removals or replacements need inspection");
        var merged = new ArrayList<T>(disk);
        var byId = new HashMap<String, T>();
        disk.forEach(value -> byId.put(id.apply(value), value));
        int prefix = 0;
        for (T value : local) {
            if (base.contains(value)) continue;
            T stored = byId.get(id.apply(value));
            if (value.equals(stored)) continue;
            if (stored != null) {
                if (reuseStored.test(value, stored)) continue;
                throw new IllegalArgumentException("Different entries share an identity");
            }
            if (newestFirst) merged.add(prefix++, value);
            else merged.add(value);
            additions.add(label.apply(value));
        }
        return merged;
    }

    private static String migrationBackup(Archive base, Archive candidate) {
        int before = base.version();
        int after = candidate.version();
        if (after <= before) return null;
        return switch (after) {
            case 2 -> "creation-workshop.pre-parts-v1.json";
            case 3 -> "creation-workshop.pre-compound-v" + before + ".json";
            case 4 -> "creation-workshop.pre-sections-v" + before + ".json";
            default -> null;
        };
    }
    public CircuitEventPack readPack(Path path) throws IOException {
        return decodePack(ArchiveWriter.readImport(path,PACK_BYTES,"Circuit package exceeds 512 KiB"));
    }
    CircuitEventPack decodePack(byte[] bytes) throws IOException {
        if(bytes.length>PACK_BYTES) throw new IOException("Circuit package exceeds 512 KiB");
        String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        depth(source);
        try {return decodePack(bytes.length,source,JsonParser.parseString(source).getAsJsonObject());}
        catch(RuntimeException error) {throw new IOException("Invalid circuit package",error);}
    }
    CircuitEventPack decodePack(int byteCount,String source,JsonObject root) throws IOException {
        if(byteCount>PACK_BYTES) throw new IOException("Circuit package exceeds 512 KiB");
        try {
            if(root.has("version") && root.get("version").getAsInt()>=3) {
                // Newer formats still need the original text to detect duplicate fields.
                var strict=PartsJson.readDecoded(source,10).value();
                ActivityCardCodec.validate(strict,CircuitEventPack.class);
                return Objects.requireNonNull(gson.fromJson(strict,CircuitEventPack.class));
            }
            return Objects.requireNonNull(gson.fromJson(source,CircuitEventPack.class));
        } catch(RuntimeException | ReflectiveOperationException error) {
            throw new IOException("Invalid circuit package",error);
        }
    }
    public void exportPack(CircuitEventPack pack,Path path) throws IOException {
        exportPack(pack, path, true);
    }
    public void exportPack(CircuitEventPack pack, Path path, boolean replace) throws IOException {
        exportPack(pack,path,replace,null);
    }
    public void exportPack(CircuitEventPack pack,Path path,ExportDestination destination) throws IOException {
        exportPack(pack,path,destination.replacing(),destination);
    }
    private void exportPack(CircuitEventPack pack,Path path,boolean replace,ExportDestination destination) throws IOException {
        exportDiagnostics.begin();
        try {
            exportDiagnostics.at(SaveDiagnostics.Stage.PREPARE);Path target=exportTarget(path);
            var reviewed=destination==null ? ExportDestination.forWrite(target,replace) : destination;
            exportDiagnostics.at(SaveDiagnostics.Stage.ENCODE);byte[] bytes=ActivityCardCodec.legacyPackage(pack,pack.version()>=3?new GsonBuilder().serializeNulls().create():gson).getBytes(StandardCharsets.UTF_8);
            ArchiveWriter.bytes(target,bytes,PACK_BYTES,"circuit-event-",exportDiagnostics,
                    reviewed.replacing() ? exportFilesystem : ArchiveWriter.NEW_FILES,reviewed::verifyPublication);
        }catch(IOException | RuntimeException error) {exportDiagnostics.failed(error);throw error;}
    }
    /** Last actual package write only; independent of creation-library saves and caller path checks. */
    public SaveDiagnostics.Failure exportFailure() {return exportDiagnostics.failure();}
    Path exportTarget(Path path) throws IOException {var target=path.toAbsolutePath().normalize();if(target.equals(file.toAbsolutePath().normalize()) || Files.exists(target) && Files.exists(file) && Files.isSameFile(target,file)) throw new IOException("Choose an independent package file");return target;}
    static void depth(String source) throws IOException {
        int depth=0;boolean quoted=false,escaped=false;for(int i=0;i<source.length();i++) { char c=source.charAt(i);if(quoted) { if(escaped) escaped=false;else if(c=='\\') escaped=true;else if(c=='"') quoted=false; }else if(c=='"') quoted=true;else if(c=='{' || c=='[') { if(++depth>32) throw new IOException("Creation JSON nesting exceeds bound"); }else if(c=='}' || c==']') { if(--depth<0) throw new IOException("Unbalanced creation JSON"); } }
        if(depth!=0 || quoted) throw new IOException("Incomplete creation JSON");
    }
}
