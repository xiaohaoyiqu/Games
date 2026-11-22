package com.f1car.persistence;

import com.f1car.model.EventRoundBrief;
import com.f1car.model.RaceReplay;
import com.f1car.model.ReplayPlayer;
import com.f1car.model.ReplayReviewTimeline;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** The caller thread owns library state. Queued disk writes work from an isolated index snapshot. */
public final class ReplayStore implements AutoCloseable {
    private static final Pattern REPLAY_ID=Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern CONTENT_NAME=Pattern.compile(REPLAY_ID.pattern()+"-[a-f0-9]{64}\\.json");
    public record Entry(String id,String file,String title,String track,long recordedAt,double duration,RaceReplay.End end,RaceReplay.Source source) {
        public Entry { if(id==null || !REPLAY_ID.matcher(id).matches() || file==null || !CONTENT_NAME.matcher(file).matches() || !file.startsWith(id+"-") || title==null || title.length()>80 || track==null || track.length()>80 || recordedAt<0 || !Double.isFinite(duration) || duration<=0 || duration>RaceReplay.MAX_DURATION || end==null || source==null) throw new IllegalArgumentException("Invalid replay index entry"); }
    }
    private static final int INDEX_VERSION=1;
    private record Index(int version,List<Entry> entries) { private Index { if(version!=INDEX_VERSION || entries==null || entries.size()>5) throw new IllegalArgumentException("Invalid replay index");entries=List.copyOf(entries);if(entries.stream().map(Entry::id).distinct().count()!=entries.size()) throw new IllegalArgumentException("Duplicate replay index"); } }
    private static final int MAX_BYTES=ReplayCodec.MAX_BYTES,MAX_INDEX_BYTES=32768;
    private static final int MAX_WAITING_SAVES=4;
    private static final String PLAYBACK_CHANGED="回看条目已变化，请重读回看目录后重新选择。";
    private static final String PLAYBACK_INTERRUPTED="回看读取已中断。";
    private static final String ROUND_MISMATCH="回看末帧或地图与本站结果无法核对。";
    private static final String NEWER_INDEX="回看目录由较新版本创建。请使用支持该版本的游戏，或恢复兼容目录后重读。";
    private static final Index EMPTY=new Index(INDEX_VERSION,List.of());
    private record DiskIndex(Index index,ArchiveWriter.Stamp stamp) {
        boolean contains(Entry entry) {return index!=null && index.entries().contains(entry);}
    }
    private record Pending(RaceReplay replay,Entry previous,Entry row,boolean announced) {}
    private record SaveResult(boolean saved,Index index,ArchiveWriter.Stamp stamp,boolean damaged,String warning) {}
    private record ActiveSave(Pending candidate,Future<SaveResult> result,boolean retry) {}
    private record ReadSelection(String id,Entry saved,RaceReplay pending,boolean savedOnly) {}
    public record PlaybackResult(ReplayPlayer player,String warning) {}
    public record ReviewResult(ReplayReviewTimeline timeline,String warning) {}

    public static final class PlaybackRequest {
        private final ReadSelection selection;
        private final Future<PlaybackResult> result;
        private Entry published;
        private PlaybackRequest(ReadSelection selection,Future<PlaybackResult> result) {
            this.selection=selection;
            this.result=result;
        }
        public String id() {return selection.id();}
    }
    private static final class RetryBatch {
        private final Set<String> waiting;
        private final int count;
        private int saved,failed;
        private String firstFailure="",cleanupWarning="";

        RetryBatch(Set<String> ids) {
            waiting=new LinkedHashSet<>(ids);
            count=waiting.size();
        }

        void record(boolean published,String message) {
            if(published) {
                saved++;
                if(cleanupWarning.isEmpty()) cleanupWarning=message;
            } else {
                failed++;
                if(firstFailure.isEmpty()) firstFailure=message;
            }
        }
    }
    @FunctionalInterface private interface IndexEdit {Index prepare() throws IOException;}
    interface Filesystem extends ArchiveWriter.Filesystem {
        @Override default void write(Path path,byte[] bytes) throws IOException {ArchiveWriter.writeAndForce(path,bytes);}
        @Override default void backup(Path source,Path target,ArchiveWriter.Stamp expected) throws IOException {
            ArchiveWriter.Filesystem.super.backup(source,target,expected);
            ArchiveWriter.verifyStamp(target,expected,LinkOption.NOFOLLOW_LINKS);
        }
        default ArchiveWriter.Stamp writeReplay(Path temporary,RaceReplay replay) throws IOException {
            return ReplayCodec.write(temporary,replay,MAX_BYTES);
        }
        default InputStream openReplay(Path path) throws IOException {
            return Channels.newInputStream(FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
        }
        default boolean deleteUnusedContent(Path path) throws IOException {return Files.deleteIfExists(path);}
    }
    private static final class IndexChanged extends IOException {
        IndexChanged(Throwable cause) {super("Replay index changed before replacement",cause);}
    }
    private static final class NewerIndex extends IOException {}
    private static final class BackupUnavailable extends IOException {
        BackupUnavailable(Throwable cause) {super("Replay repair backup could not be verified",cause);}
    }
    private final Path directory,indexFile,lockFile;
    private final Filesystem filesystem;
    private final Gson gson=new Gson();
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("replay-index");
    private final SaveDiagnostics contentDiagnostics=new SaveDiagnostics("replay-content");
    private final Map<String,Pending> pending=new LinkedHashMap<>();
    private final Set<String> waiting=new LinkedHashSet<>();
    private ExecutorService saveWorker;
    private ActiveSave activeSave;
    private ThreadPoolExecutor playbackWorker;
    private PlaybackRequest activePlayback;
    private Future<ReplayReviewTimeline> activeReview;
    private RetryBatch retryBatch;
    private String saveNotice="";
    private boolean closed;
    private Index index=EMPTY;
    private List<Entry> visibleEntries;
    private ArchiveWriter.Stamp acceptedStamp;
    private boolean damaged;
    private boolean newerIndex;
    public String warning="";
    public ReplayStore(Path data) {this(data,new Filesystem() {});}
    ReplayStore(Path data,Filesystem filesystem) {
        directory=data.resolve("replays").toAbsolutePath().normalize();
        indexFile=directory.resolve("index.json");lockFile=directory.resolve("index.lock");
        this.filesystem=Objects.requireNonNull(filesystem);
        reload();
    }
    private ReplayStore(ReplayStore library) {
        directory=library.directory;
        indexFile=library.indexFile;
        lockFile=library.lockFile;
        filesystem=library.filesystem;
        index=library.index;
        acceptedStamp=library.acceptedStamp;
        damaged=library.damaged;
        newerIndex=library.newerIndex;
    }
    public boolean reload() {
        if(!canChangeIndex()) return false;
        try {
            if(Files.isRegularFile(directory,LinkOption.NOFOLLOW_LINKS)) {
                if(acceptedStamp==null) acceptedStamp=ArchiveWriter.ABSENT;
                throw new IOException("Replay directory is unavailable");
            }
            var known=acceptedStamp==null?null:new DiskIndex(damaged?null:index,acceptedStamp);
            var disk=readIndex(known);
            newerIndex=false;
            if(disk.index()!=null) {
                if(damaged || !Objects.equals(acceptedStamp,disk.stamp())) {
                    index=disk.index();
                    visibleEntries=null;
                }
                acceptedStamp=disk.stamp();damaged=false;warning="";
                return true;
            }
            if(acceptedStamp==null) {acceptedStamp=disk.stamp();damaged=true;}
        } catch(NewerIndex unsupported) {
            // An existing baseline already prevents a write until those exact bytes return.
            newerIndex=acceptedStamp==null;
            warning=NEWER_INDEX;
            return false;
        } catch(IOException | RuntimeException error) {
            // A failed refresh keeps the last readable list and its original write baseline.
        }
        warning="回看目录无法读取，原文件和已读列表保留。修复后可重读回看目录。";
        return false;
    }
    private DiskIndex readIndex(DiskIndex known) throws IOException {
        BasicFileAttributes attributes;
        try {attributes=Files.readAttributes(indexFile,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException absent) {return new DiskIndex(EMPTY,ArchiveWriter.ABSENT);}
        if(!attributes.isRegularFile() || attributes.size()>MAX_INDEX_BYTES) throw new IOException("Invalid replay index file");
        byte[] bytes;
        try(var input=Files.newInputStream(indexFile,LinkOption.NOFOLLOW_LINKS)) {bytes=input.readNBytes(MAX_INDEX_BYTES+1);}
        if(bytes.length>MAX_INDEX_BYTES) throw new IOException("Replay index too large");
        var stamp=ArchiveWriter.stamp(bytes);
        if(known!=null && stamp.equals(known.stamp())) return known;
        try {
            String source=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            var document=gson.fromJson(source,JsonObject.class);
            var version=document.get("version");
            if(version!=null && version.isJsonPrimitive()
                    && version.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(INDEX_VERSION))>0)
                throw new NewerIndex();
            return new DiskIndex(gson.fromJson(document,Index.class),stamp);
        } catch(java.nio.charset.CharacterCodingException | RuntimeException malformed) {return new DiskIndex(null,stamp);}
    }
    private Entry entry(RaceReplay replay,String file) { return new Entry(replay.id(),file,replay.title(),replay.track().name(),replay.recordedAt(),replay.duration(),replay.end(),replay.source()); }
    /** Immutable library view. A changed index or pending snapshot invalidates the previous view. */
    public List<Entry> entries() {
        if(pending.isEmpty()) return index.entries();
        if(visibleEntries==null) {
            var rows=new ArrayList<Entry>(pending.size()+index.entries().size());
            for(var candidate:pending.values()) rows.add(candidate.row());
            Collections.reverse(rows);
            for(var saved:index.entries()) if(!pending.containsKey(saved.id())) rows.add(saved);
            visibleEntries=List.copyOf(rows);
        }
        return visibleEntries;
    }
    public int pendingCount() { return pending.size(); }
    public boolean isPending(String id) {return pending.containsKey(id);}
    /** Pending rows have no content hash. Their identity follows the actual in-memory recording. */
    public boolean isCurrentPending(Entry row) {
        var candidate=pending.get(row.id());
        return candidate!=null && candidate.row()==row;
    }
    public boolean saving() {return activeSave!=null || retryBatch!=null || !waiting.isEmpty();}
    public boolean isSaving(String id) {
        return waiting.contains(id) || retryBatch!=null && retryBatch.waiting.contains(id)
                || activeSave!=null && activeSave.candidate().replay().id().equals(id);
    }
    public String savingStatus() {
        if(retryBatch==null) return "正在保存回看，可先查看片段。";
        return "正在重试回看 · 已处理 "+(retryBatch.saved+retryBatch.failed)+" / "+retryBatch.count;
    }
    public String takeSaveNotice() {
        String notice=saveNotice;
        saveNotice="";
        return notice;
    }

    private boolean canChangeIndex() {
        if(closed) {
            warning="回看库已关闭。";
            return false;
        }
        if(!saving()) return true;
        warning="回看正在保存，写入结束后可管理回看目录。";
        return false;
    }

    public boolean discardPending(String id) {
        if(isSaving(id)) {
            warning="这条回看正在保存，写入结束后可管理该片段。";
            return false;
        }
        if(pending.remove(id)==null) return false;
        visibleEntries=null;
        if(pending.isEmpty() && !damaged) warning=newerIndex?NEWER_INDEX:"";
        return true;
    }
    public Entry savedEntry(String id) {
        for(var row:index.entries()) if(row.id().equals(id)) return row;
        return null;
    }
    public boolean contains(String id) {return isPending(id) || savedEntry(id)!=null;}
    public RaceReplay load(String id) {return load(id,null);}

    public RaceReplay load(String id,EventRoundBrief round) {
        var selection=selectPlayback(id,round);
        if(selection==null) return null;
        try {
            var replay=selection.pending();
            if(replay==null) replay=readReplay(selection.saved());
            if(round!=null && !round.matchesReplay(replay)) {
                warning=ROUND_MISMATCH;
                return null;
            }
            if(selection.saved()!=null) {
                replay.track().resolve();
                warning="";
            }
            return replay;
        } catch(NoSuchFileException absent) {
            warning=saving()?"回看目录正在更新，请稍后查看列表。":"回看数据已不在本机回看库。";
            return null;
        } catch(IOException | RuntimeException error) { warning="回看数据或地图无法校验，原文件保留。";return null; }
    }

    private RaceReplay readReplay(Entry saved) throws IOException {
        Path path=directory.resolve(saved.file());
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile() || attributes.size()>MAX_BYTES) throw new IOException("Invalid replay content file");
        var hash=ArchiveWriter.digest();
        RaceReplay replay;
        try(var stream=new DigestInputStream(filesystem.openReplay(path),hash)) {
            replay=ReplayCodec.decode(stream);
        }
        if(!saved.file().equals(saved.id()+"-"+HexFormat.of().formatHex(hash.digest())+".json"))
            throw new IOException("Replay content mismatch");
        if(!entry(replay,saved.file()).equals(saved)) throw new IOException("Replay index entry does not match its content");
        return replay;
    }

    /** Freezes the selected entry. Reading, verification and playback setup stay off the caller thread. */
    public PlaybackRequest requestPlayback(String id,EventRoundBrief round) {
        return requestPlayback(id,round,false);
    }

    public PlaybackRequest requestPlayback(String id,EventRoundBrief round,boolean prepareMarkers) {
        cancelPlayback();
        if(closed || id==null) return null;
        var selection=selectPlayback(id,round);
        return selection==null?null:startPlayback(selection,round,prepareMarkers);
    }

    public PlaybackResult loadPlayback(String id,EventRoundBrief round) {
        if(closed) return new PlaybackResult(null,"回看库已关闭。");
        var selection=selectPlayback(id,round);
        if(selection==null) return new PlaybackResult(null,"所选回看已不在本机回看库。");
        return preparePlayback(selection,round,false,playbackIndex(selection));
    }

    /** A library button refers to the snapshot shown when it was drawn, even if an ID is reused. */
    public PlaybackRequest requestListedPlayback(Entry expected) {
        cancelPlayback();
        if(closed || expected==null) return null;
        var candidate=pending.get(expected.id());
        if(candidate!=null) {
            if(candidate.row()!=expected) return null;
            return startPlayback(new ReadSelection(expected.id(),null,candidate.replay(),false),null,false);
        }
        if(!expected.equals(savedEntry(expected.id()))) return null;
        return startPlayback(new ReadSelection(expected.id(),expected,null,false),null,false);
    }

    private ReadSelection selectPlayback(String id,EventRoundBrief round) {
        var candidate=pending.get(id);
        if(candidate!=null && (round==null || round.matchesReplay(candidate.replay())))
            return new ReadSelection(id,null,candidate.replay(),false);
        var saved=savedEntry(id);
        if(saved!=null) return new ReadSelection(id,saved,null,round!=null);
        // A sole pending entry is still checked against the round during playback setup.
        return candidate==null?null:new ReadSelection(id,null,candidate.replay(),false);
    }

    /** Open the reviewed disk version even when a pending snapshot is shown for the same ID. */
    public PlaybackRequest requestSavedPlayback(Entry expected) {
        cancelPlayback();
        if(closed || expected==null || !expected.equals(savedEntry(expected.id()))) return null;
        return startPlayback(new ReadSelection(expected.id(),expected,null,true),null,false);
    }

    private PlaybackRequest startPlayback(ReadSelection selection,EventRoundBrief round,boolean prepareMarkers) {
        cancelReviewTimeline();
        var known=playbackIndex(selection);
        try {
            activePlayback=new PlaybackRequest(selection,playbackWorker().submit(()->preparePlayback(selection,round,prepareMarkers,known)));
            return activePlayback;
        } catch(RejectedExecutionException | SecurityException unavailable) {
            return null;
        }
    }

    private DiskIndex playbackIndex(ReadSelection selection) {
        return selection.saved()==null?null:new DiskIndex(index,acceptedStamp);
    }

    private ThreadPoolExecutor playbackWorker() {
        if(playbackWorker==null) {
            playbackWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task->{
                var worker=new Thread(task,"formula-replay-read");
                worker.setDaemon(true);
                return worker;
            });
        }
        return playbackWorker;
    }

    /** Prepare markers from the immutable recording already open in the player. */
    public boolean requestReviewTimeline(RaceReplay replay) {
        if(closed) return false;
        cancelReviewTimeline();
        try {
            activeReview=playbackWorker().submit(()->ReplayReviewTimeline.prepare(replay,Thread.currentThread()::isInterrupted));
            return true;
        } catch(RejectedExecutionException | SecurityException unavailable) {
            return false;
        }
    }

    public ReviewResult pollReviewTimeline() {
        var request=activeReview;
        if(request==null || !request.isDone()) return null;
        activeReview=null;
        try {return new ReviewResult(request.get(),"");}
        catch(InterruptedException error) {Thread.currentThread().interrupt();return new ReviewResult(null,PLAYBACK_INTERRUPTED);}
        catch(ExecutionException | CancellationException error) {return new ReviewResult(null,"回看标记未能整理，请重新打开标记。");}
    }

    public void cancelReviewTimeline() {
        if(activeReview==null) return;
        activeReview.cancel(true);
        activeReview=null;
        playbackWorker.purge();
    }

    private PlaybackResult preparePlayback(ReadSelection selection,EventRoundBrief round,boolean prepareMarkers,DiskIndex known) {
        var worker=Thread.currentThread();
        if(worker.isInterrupted()) return new PlaybackResult(null,PLAYBACK_INTERRUPTED);
        var saved=selection.saved();
        if(saved==null) return decodePlayback(selection,round,prepareMarkers);
        try {
            var before=readIndex(known);
            if(!before.contains(saved)) return new PlaybackResult(null,PLAYBACK_CHANGED);
            var result=decodePlayback(selection,round,prepareMarkers);
            if(worker.isInterrupted()) return new PlaybackResult(null,PLAYBACK_INTERRUPTED);
            // If a replacement removed the old file during the read, report the changed entry.
            return readIndex(before).contains(saved)?result:new PlaybackResult(null,PLAYBACK_CHANGED);
        } catch(NewerIndex unsupported) {
            return new PlaybackResult(null,NEWER_INDEX);
        } catch(IOException | SecurityException unavailable) {
            return new PlaybackResult(null,"回看目录暂时无法核对，请重读后再查看。");
        }
    }

    private PlaybackResult decodePlayback(ReadSelection selection,EventRoundBrief round,boolean prepareMarkers) {
        RaceReplay replay;
        try {replay=selection.pending()==null?readReplay(selection.saved()):selection.pending();}
        catch(NoSuchFileException absent) {return new PlaybackResult(null,"回看数据已不在本机回看库。");}
        catch(IOException | RuntimeException error) {return new PlaybackResult(null,"回看数据无法校验，原文件保留。");}
        var worker=Thread.currentThread();
        if(worker.isInterrupted()) return new PlaybackResult(null,PLAYBACK_INTERRUPTED);
        ReplayPlayer player;
        try {player=new ReplayPlayer(replay,round);}
        catch(IllegalArgumentException error) {
            return new PlaybackResult(null,round==null?"回看地图无法重建，原文件保留。":ROUND_MISMATCH);
        }
        try {
            if(prepareMarkers) player.reviewTimeline(worker::isInterrupted);
        } catch(CancellationException cancelled) {
            return new PlaybackResult(null,PLAYBACK_INTERRUPTED);
        }
        if(worker.isInterrupted()) return new PlaybackResult(null,PLAYBACK_INTERRUPTED);
        return new PlaybackResult(player,"");
    }

    public void cancelPlayback() {
        if(activePlayback==null) return;
        activePlayback.result.cancel(true);
        activePlayback=null;
        playbackWorker.purge();
    }

    public PlaybackResult pollPlayback(PlaybackRequest request) {
        if(request==null || request!=activePlayback || !request.result.isDone()) return null;
        activePlayback=null;
        var selected=request.selection;
        var candidate=pending.get(selected.id());
        var accepted=selected.saved()==null?request.published:selected.saved();
        boolean current=selected.savedOnly()?selected.saved().equals(savedEntry(selected.id()))
                :candidate==null?accepted!=null && accepted.equals(savedEntry(selected.id()))
                :candidate.replay()==selected.pending();
        if(!current) return new PlaybackResult(null,PLAYBACK_CHANGED);
        try {return request.result.get();}
        catch(InterruptedException error) {Thread.currentThread().interrupt();return new PlaybackResult(null,PLAYBACK_INTERRUPTED);}
        catch(ExecutionException | CancellationException error) {return new PlaybackResult(null,"回看尚未打开，请重新选择。");}
    }
    public boolean save(RaceReplay replay) {
        if(!canChangeIndex()) return false;
        var candidate=remember(replay,false);
        boolean saved=publish(candidate);
        if(saved) acknowledgePlaybackPublication(candidate);
        return saved;
    }

    private void acknowledgePlaybackPublication(Pending candidate) {
        if(activePlayback!=null && activePlayback.selection.pending()==candidate.replay())
            activePlayback.published=savedEntry(candidate.replay().id());
    }

    private Pending remember(RaceReplay replay,boolean announce) {
        var previous=pending.get(replay.id());
        boolean sameReplay=previous!=null && previous.replay()==replay;
        boolean announced=announce || previous!=null && previous.announced();
        if(sameReplay && previous.announced()==announced) return previous;
        var row=sameReplay?previous.row():entry(replay,replay.id()+"-"+"0".repeat(64)+".json");
        var candidate=new Pending(replay,previous==null?savedEntry(replay.id()):previous.previous(),row,announced);
        pending.put(replay.id(),candidate);
        if(!sameReplay) visibleEntries=null;
        return candidate;
    }

    /** Make the replay immediately viewable and schedule a background save. */
    public boolean queueSave(RaceReplay replay) {
        return queueSave(replay,false);
    }

    public boolean queueManualSave(RaceReplay replay) {
        return queueSave(replay,true);
    }

    private boolean queueSave(RaceReplay replay,boolean announce) {
        if(closed) return canChangeIndex();
        remember(replay,announce);
        if(newerIndex) {warning=NEWER_INDEX;return false;}
        String id=replay.id();
        if(activeSave!=null && activeSave.candidate().replay()==replay) {
            // The latest request is already being written, including when it supersedes a queued version.
            waiting.remove(id);
            return true;
        }
        if(retryBatch!=null && retryBatch.waiting.contains(id)) return true;
        if(!waiting.contains(id) && waiting.size()>=MAX_WAITING_SAVES) {
            warning="回看写入队列已满，本次片段已留在待存列表。";
            return false;
        }
        waiting.add(id);
        startNextSave();
        return isSaving(id);
    }

    /** Schedule one attempt per pending replay ID, using its latest snapshot when writing begins. */
    public boolean queueRetry() {
        if(!canChangeIndex()) return false;
        if(newerIndex) {warning=NEWER_INDEX;return false;}
        if(pending.isEmpty()) {
            warning="没有待存回看。";
            return false;
        }
        warning="";
        retryBatch=new RetryBatch(pending.keySet());
        startNextSave();
        return retryBatch!=null;
    }

    private void startNextSave() {
        if(activeSave!=null) return;
        boolean retry=retryBatch!=null;
        var next=(retry?retryBatch.waiting:waiting).iterator();
        if(!next.hasNext()) return;
        var candidate=pending.get(next.next());
        next.remove();
        if(saveWorker==null) {
            saveWorker=Executors.newSingleThreadExecutor(task->{
                var worker=new Thread(task,"formula-replay-save");
                worker.setDaemon(true);
                return worker;
            });
        }
        var disk=new ReplayStore(this);
        try {
            activeSave=new ActiveSave(candidate,saveWorker.submit(()->{
                boolean saved=disk.publish(candidate);
                return new SaveResult(saved,disk.index,disk.acceptedStamp,disk.damaged,disk.warning);
            }),retry);
        } catch(RejectedExecutionException | SecurityException unavailable) {
            waiting.clear();
            retryBatch=null;
            warning="无法启动后台保存，片段仍在待存列表。";
        }
    }

    /** Collect a finished save without waiting for disk I/O. */
    public boolean pollSave() {
        if(activeSave==null || !activeSave.result().isDone()) return false;
        try {
            collectSave();
            return true;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void collectSave() throws InterruptedException {
        var candidate=activeSave.candidate();
        boolean retry=activeSave.retry();
        boolean published=false;
        String message;
        try {
            var result=activeSave.result().get();
            published=result.saved();
            message=result.warning();
            if(published) {
                acceptIndex(result.index(),true);
                acceptedStamp=result.stamp();
                damaged=result.damaged();
                String id=candidate.replay().id();
                var latest=pending.get(id);
                if(latest!=null && latest.replay()==candidate.replay()) {
                    pending.remove(id);
                    acknowledgePlaybackPublication(candidate);
                    if(latest.announced() && !retry) saveNotice="回看片段已保存。";
                } else if(latest!=null) pending.put(id,new Pending(latest.replay(),savedEntry(id),latest.row(),latest.announced()));
            }
        } catch(ExecutionException failure) {
            message="后台保存中断，本次片段仍在待存列表。";
        }
        if(retry) recordRetry(published,message);
        else warning=message;
        activeSave=null;
        startNextSave();
    }

    private void recordRetry(boolean published,String message) {
        var batch=retryBatch;
        batch.record(published,message);
        warning="";
        if(!batch.waiting.isEmpty()) return;
        if(batch.failed>0) warning="本批重试已保存 "+batch.saved+" 份，"+batch.failed+" 份仍待存。"+batch.firstFailure;
        else {
            warning=batch.cleanupWarning;
            saveNotice="本批重试已保存 "+batch.saved+" 份回看。";
        }
        retryBatch=null;
    }

    /** On orderly shutdown, finish the work already queued and leave failed entries pending. */
    @Override public void close() {
        cancelPlayback();
        cancelReviewTimeline();
        if(playbackWorker!=null) playbackWorker.shutdownNow();
        boolean interrupted=false;
        while(activeSave!=null) {
            try {collectSave();}
            catch(InterruptedException error) {interrupted=true;}
        }
        closed=true;
        if(saveWorker!=null) saveWorker.shutdown();
        if(interrupted) Thread.currentThread().interrupt();
    }
    private boolean publish(Pending candidate) {
        var replay=candidate.replay();
        String failure="回看尚未保存，暂存于本次运行；可在回看列表重试。";
        contentDiagnostics.begin();
        Path temporary=null;
        try {
            contentDiagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(directory);
            verifyIndex(indexFile,acceptedStamp);
            contentDiagnostics.at(SaveDiagnostics.Stage.CREATE_TEMP);
            var staged=filesystem.temporary(directory,"replay-content-");
            temporary=staged;
            contentDiagnostics.at(SaveDiagnostics.Stage.WRITE_TEMP);
            ArchiveWriter.Stamp stamp;
            try(var work=contentDiagnostics.measure(SaveDiagnostics.Work.WRITE_TEMP)) {stamp=filesystem.writeReplay(staged,replay);}
            contentDiagnostics.encoded((int)stamp.bytes());
            var replacement=entry(replay,replay.id()+"-"+stamp.sha256()+".json");
            return commit(()->{
                var current=savedEntry(replay.id());
                if(!Objects.equals(candidate.previous(),current) && !replacement.equals(current)) {
                    warning=current==null?"该回看的已存版本已变化，原条目已移除，待存片段保留。"
                            :"该回看的已存版本已变化，待存片段保留。可在回看列表分别查看两份内容。";
                    return null;
                }
                publishContent(replacement,staged,stamp);
                var rows=new ArrayList<Entry>();rows.add(replacement);
                index.entries().stream().filter(row->!row.id().equals(replay.id())).limit(4).forEach(rows::add);
                return new Index(INDEX_VERSION,rows);
            },Set.of(replay.id()),true,failure);
        } catch(IOException | RuntimeException error) {
            contentDiagnostics.failed(error);
            warning=operationFailure(error,failure);
            return false;
        } finally {
            if(temporary!=null) {
                try(var work=contentDiagnostics.measure(SaveDiagnostics.Work.DELETE_TEMP)) {filesystem.cleanup(temporary);}
                catch(IOException | RuntimeException cleanup) {contentDiagnostics.cleanupFailed(cleanup);}
            }
        }
    }
    private void publishContent(Entry entry,Path staged,ArchiveWriter.Stamp stamp) throws IOException {
        Path file=directory.resolve(entry.file());
        contentDiagnostics.at(SaveDiagnostics.Stage.REPLACE);
        try {
            ArchiveWriter.Stamp previous;
            try(var work=contentDiagnostics.measure(SaveDiagnostics.Work.VERIFY)) {
                previous=contentStamp(file);
            }
            if(stamp.equals(previous)) return;
            if(!ArchiveWriter.ABSENT.equals(previous))
                backup(file,entry.file()+".backup-"+previous.sha256(),previous,MAX_BYTES,contentDiagnostics);
            ArchiveWriter.replacePrepared(staged,file,contentDiagnostics,filesystem,
                    target->ArchiveWriter.verifyStamp(target,previous,LinkOption.NOFOLLOW_LINKS));
        } catch(IOException | RuntimeException error) {
            contentDiagnostics.failed(error);
            throw error;
        }
    }
    private static ArchiveWriter.Stamp contentStamp(Path file) throws IOException {
        BasicFileAttributes attributes;
        try {attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException absent) {return ArchiveWriter.ABSENT;}
        if(!attributes.isRegularFile() || attributes.size()>MAX_BYTES) throw new IOException("Invalid replay content path");
        return ArchiveWriter.readStamp(file,attributes.size());
    }
    private void backup(Path file,String name,ArchiveWriter.Stamp stamp,int limit,SaveDiagnostics diagnostics) throws IOException {
        diagnostics.at(SaveDiagnostics.Stage.BACKUP);
        if(stamp.bytes()<0 || stamp.bytes()>limit) throw new IOException("Replay backup exceeds size limit");
        try {
            ArchiveWriter.preserveOriginal(file,stamp,name,file.getFileName()+".repair-backup-",filesystem);
        } catch(IOException | SecurityException unavailable) {throw new BackupUnavailable(unavailable);}
    }
    public boolean retry() {
        if(!canChangeIndex()) return false;
        String firstFailure=null;
        for(var id:List.copyOf(pending.keySet())) {
            var candidate=pending.get(id);
            if(publish(candidate)) acknowledgePlaybackPublication(candidate);
            else if(firstFailure==null) firstFailure=warning;
        }
        if(firstFailure!=null) warning=firstFailure;
        return firstFailure==null;
    }
    public boolean delete(String id) { return deleteEntries(Set.of(id)); }
    public boolean clear() { var ids=new HashSet<>(pending.keySet());index.entries().forEach(entry->ids.add(entry.id()));return deleteEntries(ids); }
    private boolean deleteEntries(Set<String> ids) {
        if(!canChangeIndex()) return false;
        return commit(()->new Index(INDEX_VERSION,index.entries().stream().filter(entry->!ids.contains(entry.id())).toList()),ids,false,
                "回看清理尚未保存，原列表和文件保留。");
    }
    private boolean commit(IndexEdit edit,Set<String> completed,boolean repair,String failure) {
        diagnostics.begin();
        boolean committed=false;
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(directory);
            try(var channel=ArchiveWriter.openLock(lockFile);var held=channel.tryLock()) {
                if(held==null) throw new OverlappingFileLockException();
                var baseline=acceptedStamp;
                verifyIndex(indexFile,baseline);
                if(damaged && !repair) {warning="回看目录损坏，请先修复后重读。";return false;}
                var next=edit.prepare();
                if(next==null) return false;
                if(repair && !damaged && next.equals(index)) {
                    verifyIndex(indexFile,baseline);
                    completed.forEach(pending::remove);
                    visibleEntries=null;
                    warning="";
                    committed=true;
                    return true;
                }
                if(damaged) backup(indexFile,"index.backup-"+baseline.sha256()+".json",baseline,MAX_INDEX_BYTES,diagnostics);
                byte[] bytes=gson.toJson(next).getBytes(StandardCharsets.UTF_8);
                ArchiveWriter.bytes(indexFile,bytes,MAX_INDEX_BYTES,"replay-index-",diagnostics,filesystem,
                        target->verifyIndex(target,baseline));
                var old=index;acceptIndex(next,repair);acceptedStamp=ArchiveWriter.stamp(bytes);damaged=false;
                completed.forEach(pending::remove);warning="";committed=true;
                for(var entry:old.entries()) if(next.entries().stream().noneMatch(kept->kept.file().equals(entry.file()))) {
                    verifyIndex(indexFile,acceptedStamp);
                    var file=directory.resolve(entry.file());
                    try {
                        var attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                        if(!attributes.isRegularFile()) throw new IOException("Invalid superseded replay content path");
                        filesystem.cleanup(file);
                    } catch(NoSuchFileException absent) {
                        // The unreferenced content was already removed.
                    } catch(IOException | SecurityException error) {
                        diagnostics.cleanupFailed(error);
                        warning="回看目录已保存，部分清理未完成。";
                    }
                }
            }
            return true;
        } catch(IOException | RuntimeException error) {
            if(committed) {diagnostics.cleanupFailed(error);warning="回看目录已保存，部分清理未完成。";return true;}
            diagnostics.failed(error);
            warning=operationFailure(error,failure);
            return false;
        }
    }
    private void acceptIndex(Index next,boolean publishedReplay) {
        var previous=index;
        index=next;
        visibleEntries=null;
        if(!publishedReplay || pending.isEmpty()) return;
        // Publishing a sixth replay can evict a version on which another local save is based.
        for(var old:previous.entries()) {
            var candidate=pending.get(old.id());
            if(candidate!=null && old.equals(candidate.previous()) && savedEntry(old.id())==null)
                pending.put(old.id(),new Pending(candidate.replay(),null,candidate.row(),candidate.announced()));
        }
    }
    private void verifyIndex(Path target,ArchiveWriter.Stamp expected) throws IOException {
        if(newerIndex) throw new NewerIndex();
        try {
            if(expected==null) throw new IOException("Replay index has no readable baseline");
            ArchiveWriter.verifyStamp(target,expected,LinkOption.NOFOLLOW_LINKS);
        } catch(IOException error) {throw new IndexChanged(error);}
    }
    private static String operationFailure(Exception error,String fallback) {
        if(error instanceof BackupUnavailable) return "回看修复备份未能写入或校验。请检查备份文件和目录权限，再重试保存。";
        if(error instanceof NewerIndex) return NEWER_INDEX;
        if(error instanceof IndexChanged) return "磁盘回看目录已变化或无法核对。请先重读回看目录，待存内容保留。";
        if(error instanceof OverlappingFileLockException) return "回看目录正在被另一操作使用，请稍后重试。";
        return fallback;
    }
    /** Only unreferenced app-owned content files; backups and unrelated files are retained. */
    public int cleanupUnused() {
        if(!canChangeIndex()) return -1;
        if(damaged || !pending.isEmpty()) {warning="请先恢复索引或处理待存回看，再清理遗留文件。";return -1;}
        int removed=0,failed=0;
        try {
            if(Files.notExists(directory,LinkOption.NOFOLLOW_LINKS)) {verifyIndex(indexFile,acceptedStamp);warning="";return 0;}
            try(var channel=ArchiveWriter.openLock(lockFile);var held=channel.tryLock()) {
                if(held==null) throw new OverlappingFileLockException();
                var baseline=acceptedStamp;
                verifyIndex(indexFile,baseline);
                var kept=new HashSet<String>();index.entries().forEach(entry->kept.add(entry.file()));
                try(var paths=Files.newDirectoryStream(directory)) {
                    for(var path:paths) {
                        String name=path.getFileName().toString();
                        if(!kept.contains(name) && CONTENT_NAME.matcher(name).matches() && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {
                            verifyIndex(indexFile,baseline);
                            try {if(filesystem.deleteUnusedContent(path)) removed++;}
                            catch(IOException | SecurityException unavailable) {failed++;}
                        }
                    }
                }
                if(failed>0) {
                    warning="已清理 "+removed+" 个遗留回放文件，"+failed+" 个未能删除，可稍后重试。";
                    return -1;
                }
                warning="";return removed;
            }
        } catch(IOException | RuntimeException error) {
            String reason=operationFailure(error,"遗留回看清理中断，请检查目录权限。");
            warning=removed==0 && failed==0?reason:"已清理 "+removed+" 个遗留回放文件，"+failed+" 个删除失败。"+reason;
            return -1;
        }
    }
}
