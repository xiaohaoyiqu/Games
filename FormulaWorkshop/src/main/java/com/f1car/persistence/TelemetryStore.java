package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Recent laps and best lap commit together; failed writes remain available for explicit retry. */
public final class TelemetryStore {
    private static final int MAX_BYTES=24_000_000;
    private static final int MAX_ACCEPTED=3,MAX_PENDING=4;
    private static final int BEST_FIELD_BYTES=",\"best\":".length();
    private static final String NEWER_ARCHIVE="遥测文件由较新版本创建。请恢复兼容档案后核对合并，或移走原文件后重试。";
    private enum SourceFormat { CURRENT, DAMAGED, NEWER }
    private record Archive(TelemetryBook book,ArchiveWriter.Stamp stamp,SourceFormat format) {}
    private record Pending(Archive archive,TelemetryBook localLaps) {}
    public static final class MergeReview {
        private final TelemetryStore owner;
        private final Pending pending;
        private final Archive saved;
        private final TelemetryBook merged;
        private final int trimmedForSize;

        private MergeReview(TelemetryStore owner,Pending pending,Archive saved,TelemetryBook merged,int trimmedForSize) {
            this.owner=owner;
            this.pending=pending;
            this.saved=saved;
            this.merged=merged;
            this.trimmedForSize=trimmedForSize;
        }
        public TelemetryBook saved() {return saved.book();}
        public TelemetryBook local() {return pending.localLaps();}
        public TelemetryBook merged() {return merged;}
        public int trimmedForSize() {return trimmedForSize;}
    }
    private final Path directory;
    private final Gson gson=new Gson();
    private final Map<String,Pending> pending=new LinkedHashMap<>();
    private final Map<String,Archive> accepted=new LinkedHashMap<>(4,.75f,true);
    private final Map<TelemetryLap,Integer> encodedSizes=new IdentityHashMap<>();
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("telemetry");
    private String warning="";
    public TelemetryStore(Path data) { directory=data.resolve("telemetry"); }
    public String warning() { return warning; }
    public int pendingCount() { return pending.size(); }
    public boolean pending(TelemetryLap.Conditions conditions) { return pending.containsKey(conditions.key()); }
    public Path file(TelemetryLap.Conditions conditions) {
        return directory.resolve(fileName(conditions.key())+".json");
    }
    private static String fileName(String key) {
        return HexFormat.of().formatHex(ArchiveWriter.digest().digest(key.getBytes(StandardCharsets.UTF_8)));
    }
    public TelemetryBook load(TelemetryLap.Conditions conditions) {
        var held=pending.get(conditions.key());
        if(held==null) return read(conditions).book();
        warning=held.archive().format()==SourceFormat.NEWER?"正在查看本组待存遥测。"+NEWER_ARCHIVE:"正在查看本组待存遥测。";
        return held.archive().book();
    }
    private Archive read(TelemetryLap.Conditions conditions) {
        String key=conditions.key();
        String name=fileName(key);
        Path path=directory.resolve(name+".json");
        warning="";
        ArchiveWriter.Stamp observed=null;
        var cached=accepted.get(key);
        try {
            if(cached!=null) {
                try {
                    ArchiveWriter.verifyStamp(path,cached.stamp(),LinkOption.NOFOLLOW_LINKS);
                    return cached;
                } catch(ArchiveWriter.StaleArchiveException changed) {
                    // A same-size file can still contain another window's lap.
                }
            }
            var source=new GuardedArchive(directory,name,MAX_BYTES);
            var book=source.readStream(this::decode);
            observed=source.observedStamp();
            if(ArchiveWriter.ABSENT.equals(observed)) {
                accepted.remove(key);
                return new Archive(TelemetryBook.empty(conditions),observed,SourceFormat.CURRENT);
            }
            if(book==null) {
                // A newer layout may fail to decode before the reader reaches the root version.
                var original=observed;
                observed=null;
                var newer=source.readStream(TelemetryStore::hasNewerVersion);
                if(!original.equals(source.observedStamp())) throw new IOException("Telemetry changed during version check");
                if(Boolean.TRUE.equals(newer)) {
                    warning=NEWER_ARCHIVE;
                    return new Archive(TelemetryBook.empty(conditions),null,SourceFormat.NEWER);
                }
                observed=original;
                throw new IOException("Invalid telemetry archive");
            }
            if(!book.conditions().equals(conditions)) throw new IOException("Unexpected telemetry conditions");
            var archive=new Archive(book,observed,SourceFormat.CURRENT);
            remember(key,archive);
            return archive;
        }catch(IOException | RuntimeException error) {
            warning="遥测文件无法读取，原文件保留；可继续计时。";
            var stamp=observed!=null?observed:cached==null?null:cached.stamp();
            return new Archive(TelemetryBook.empty(conditions),stamp,observed!=null?SourceFormat.DAMAGED:SourceFormat.CURRENT);
        }
    }

    private TelemetryBook decode(InputStream input) throws IOException {
        try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder())) {
            var value=gson.fromJson(reader,TelemetryBook.class);
            if(value==null) input.transferTo(OutputStream.nullOutputStream());
            return value;
        } catch(IOException | RuntimeException error) {
            // Fingerprint the entire invalid source from the same stream for the guarded backup.
            input.transferTo(OutputStream.nullOutputStream());
            return null;
        }
    }

    /** Only the archive and its lap records own format versions. Skip samples and extension fields. */
    private static boolean hasNewerVersion(InputStream input) throws IOException {
        try(var json=new JsonReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder()))) {
            return newerObject(json,true);
        } catch(IOException | IllegalStateException malformed) {
            return false;
        } finally {
            input.transferTo(OutputStream.nullOutputStream());
        }
    }

    private static boolean newerObject(JsonReader json,boolean archive) throws IOException {
        if(json.peek()!=JsonToken.BEGIN_OBJECT) {json.skipValue();return false;}
        json.beginObject();
        while(json.hasNext()) {
            String field=json.nextName();
            var token=json.peek();
            if(field.equals("version") && (token==JsonToken.NUMBER || token==JsonToken.STRING)) {
                String value=json.nextString();
                int supported=archive?TelemetryBook.VERSION:TelemetryLap.VERSION;
                try {if(Double.parseDouble(value)>supported) return true;}
                catch(NumberFormatException malformed) { /* Other records may still declare a newer version. */ }
            } else if(archive && field.equals("best")) {
                if(newerObject(json,false)) return true;
            } else if(archive && field.equals("recent") && token==JsonToken.BEGIN_ARRAY) {
                json.beginArray();
                while(json.hasNext()) if(newerObject(json,false)) return true;
                json.endArray();
            } else json.skipValue();
        }
        json.endObject();
        return false;
    }
    private void remember(String key,Archive archive) {
        accepted.put(key,archive);
        if(accepted.size()>MAX_ACCEPTED) accepted.remove(accepted.keySet().iterator().next());
    }
    public boolean record(TelemetryLap lap) {
        String key=lap.conditions().key();
        var held=pending.get(key);
        Archive previous;
        TelemetryBook book;
        var local=held==null?TelemetryBook.empty(lap.conditions()):held.localLaps();
        if(held!=null) {
            previous=held.archive();
            book=previous.book();
        }else {
            previous=read(lap.conditions());
            var cached=accepted.get(key);
            book=!warning.isEmpty() && previous.format()!=SourceFormat.NEWER && cached!=null?cached.book():previous.book();
        }
        var candidate=book.add(lap);
        if(candidate==book) {
            if(held!=null) return save(previous,false);
            // A duplicate in the last readable copy still needs publishing if the disk read failed.
            if(warning.isEmpty()) return true;
        }
        if(held==null && pending.size()>=MAX_PENDING) {
            warning="待存遥测库已满，本次遥测未加入。可先在驾驶分析重试待存记录。";
            return false;
        }
        var next=new Archive(candidate,previous.stamp(),previous.format());
        pending.put(key,new Pending(next,local.add(lap)));
        return save(next,false);
    }
    private boolean save(Archive archive,boolean merging) {
        var conditions=archive.book().conditions();
        String key=conditions.key();
        Path target=file(conditions);
        diagnostics.begin();
        try {
            if(archive.format()==SourceFormat.NEWER && !Files.notExists(target,LinkOption.NOFOLLOW_LINKS)) {
                warning=NEWER_ARCHIVE;
                return false;
            }
            diagnostics.at(SaveDiagnostics.Stage.ENCODE);
            var book=merging?archive.book():fit(archive.book());
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            Files.createDirectories(directory);
            try(var channel=ArchiveWriter.openLock(directory.resolve("archive.lock"));var held=channel.tryLock()) {
                if(held==null) throw new IOException("Telemetry is busy");
                var previous=archive.stamp()==null && Files.notExists(target,LinkOption.NOFOLLOW_LINKS)
                        ?ArchiveWriter.ABSENT:archive.stamp();
                if(previous==null) throw new IOException("Cannot verify existing telemetry");
                if(archive.format()==SourceFormat.DAMAGED) preserveDamaged(target,previous);
                var stamp=ArchiveWriter.utf8(target,MAX_BYTES,"telemetry-",diagnostics,ArchiveWriter.FORCED_FILES,
                        path->ArchiveWriter.verifyStamp(path,previous,LinkOption.NOFOLLOW_LINKS),
                        writer->gson.toJson(book,writer));
                pending.remove(key);
                remember(key,new Archive(book,stamp,SourceFormat.CURRENT));
                warning="";
            }
            return true;
        }catch(IOException | RuntimeException error) {
            if(!pending.containsKey(key)) {
                diagnostics.cleanupFailed(error);
                return true;
            }
            diagnostics.failed(error);
            if(error instanceof ArchiveWriter.StaleArchiveException) {
                if(merging) return reviewChanged();
                warning="已存遥测已变化，本次待存记录保留。";
            } else warning="遥测尚未保存，可在驾驶分析中重试；旧文件保留。";
            return false;
        } finally {keepEncodedSizes();}
    }

    private TelemetryBook fit(TelemetryBook book) throws IOException {
        if(book.best()==null) return book;
        int bytes=ArchiveWriter.utf8Size(writer->gson.toJson(TelemetryBook.empty(book.conditions()),writer));
        // The empty archive has the recent array brackets and omits its null best field.
        bytes+=BEST_FIELD_BYTES+encodedSize(book.best());
        int kept=0;
        for(var lap:book.recent()) {
            int added=encodedSize(lap)+(kept==0?0:1);
            if(added>MAX_BYTES-bytes) break;
            bytes+=added;
            kept++;
        }
        if(bytes>MAX_BYTES || kept==0 && !book.recent().isEmpty())
            throw new IOException("Newest telemetry and best lap exceed archive size");
        if(kept==book.recent().size()) return book;
        return new TelemetryBook(book.version(),book.conditions(),book.recent().subList(0,kept),book.best());
    }

    private int encodedSize(TelemetryLap lap) throws IOException {
        var cached=encodedSizes.get(lap);
        if(cached!=null) return cached;
        int bytes=ArchiveWriter.utf8Size(writer->gson.toJson(lap,writer));
        encodedSizes.put(lap,bytes);
        return bytes;
    }

    private void keepEncodedSizes() {
        var retained=Collections.newSetFromMap(new IdentityHashMap<TelemetryLap,Boolean>());
        for(var archive:accepted.values()) rememberLaps(retained,archive.book());
        for(var value:pending.values()) {
            rememberLaps(retained,value.archive().book());
            rememberLaps(retained,value.localLaps());
        }
        encodedSizes.keySet().retainAll(retained);
    }

    private static void rememberLaps(Set<TelemetryLap> retained,TelemetryBook book) {
        retained.addAll(book.recent());
        if(book.best()!=null) retained.add(book.best());
    }

    private void preserveDamaged(Path target,ArchiveWriter.Stamp previous) throws IOException {
        diagnostics.at(SaveDiagnostics.Stage.BACKUP);
        ArchiveWriter.verifyStamp(target,previous,LinkOption.NOFOLLOW_LINKS);
        String prefix=target.getFileName()+".backup-";
        ArchiveWriter.preserveOriginal(target,previous,prefix+previous.sha256(),prefix,ArchiveWriter.FORCED_FILES);
    }
    public boolean retry(TelemetryBook displayed) {
        var held=pending.get(displayed.conditions().key());
        if(held==null || held.archive().book()!=displayed) {
            warning="这组待存遥测已变化，请重新查看。";
            return false;
        }
        return save(held.archive(),false);
    }

    public boolean retry() {
        String firstFailure="";
        for(var value:List.copyOf(pending.values())) {
            if(!save(value.archive(),false) && firstFailure.isEmpty()) firstFailure=warning;
        }
        if(!firstFailure.isEmpty()) warning=firstFailure;
        return firstFailure.isEmpty();
    }
    public MergeReview reviewMerge(TelemetryLap.Conditions conditions) {
        var held=pending.get(conditions.key());
        if(held==null) {
            warning="当前条件没有待存遥测。";
            return null;
        }
        var saved=read(conditions);
        if(!warning.isEmpty()) return null;
        if(Objects.equals(saved.stamp(),held.archive().stamp())) {
            warning="已存遥测仍是原版本，请直接重试保存。";
            return null;
        }
        TelemetryBook combined;
        try {
            combined=merge(saved.book(),held.localLaps());
        }catch(IllegalArgumentException conflict) {
            warning="有同一编号的遥测圈内容不同，已存与待存都保留。";
            return null;
        }
        try {
            var result=fit(combined);
            return new MergeReview(this,held,saved,result,combined.recent().size()-result.recent().size());
        }catch(IOException | RuntimeException error) {
            warning="合并遥测无法写入档案，已存与待存都保留。";
            return null;
        }finally {keepEncodedSizes();}
    }

    public boolean commitMerge(MergeReview reviewed) {
        if(reviewed==null) return false;
        if(reviewed.owner!=this || pending.get(reviewed.saved().conditions().key())!=reviewed.pending) return reviewChanged();
        return save(new Archive(reviewed.merged,reviewed.saved.stamp(),SourceFormat.CURRENT),true);
    }

    private boolean reviewChanged() {
        warning="已存或待存遥测已变化，请重新核对后合并。";
        return false;
    }
    private static TelemetryBook merge(TelemetryBook saved,TelemetryBook local) {
        var identities=new HashMap<String,TelemetryLap>();
        for(var book:List.of(saved,local)) {
            for(var lap:book.all()) {
                var old=identities.putIfAbsent(lap.id(),lap);
                if(old!=null && !old.equals(lap)) throw new IllegalArgumentException("Conflicting telemetry identity");
            }
        }
        var recent=new LinkedHashMap<String,TelemetryLap>();
        for(var book:List.of(saved,local)) {
            for(var lap:book.recent()) recent.putIfAbsent(lap.id(),lap);
        }
        var ordered=recent.values().stream().sorted(Comparator.comparingLong(TelemetryLap::recordedAt).reversed()).limit(6).toList();
        var best=saved.best();
        if(local.best()!=null && (best==null || local.best().duration()<best.duration())) best=local.best();
        return new TelemetryBook(TelemetryBook.VERSION,saved.conditions(),ordered,best);
    }
    public void exportCsv(TelemetryLap lap,Path target) throws IOException {
        exportCsv(lap,target,false);
    }
    public void exportCsv(TelemetryLap lap,Path target,boolean replace) throws IOException {
        if(replace) {
            exportReviewedCsv(lap,target,ExportDestination.inspect(target));
            return;
        }
        writeCsvFile(lap,target,false,ArchiveWriter.NO_CHECK);
    }
    public void exportReviewedCsv(TelemetryLap lap,Path target,ExportDestination reviewed) throws IOException {
        writeCsvFile(lap,target.toAbsolutePath().normalize(),reviewed.replacing(),reviewed::verifyPublication);
    }
    private static void writeCsvFile(TelemetryLap lap,Path target,boolean replace,ArchiveWriter.ReplacementCheck check) throws IOException {
        var diagnostics=new SaveDiagnostics("telemetry-export");
        diagnostics.begin();
        ArchiveWriter.utf8(target,MAX_BYTES,"telemetry-",diagnostics,
                replace?ArchiveWriter.FORCED_FILES:ArchiveWriter.NEW_FILES,check,
                writer->writeCsv(lap,writer));
    }

    private static void writeCsv(TelemetryLap lap,Writer writer) throws IOException {
        writer.write("time_s,distance_m,speed_kmh,throttle,brake,steering_command,x_m,z_m,yaw_rad,front_compression_mm,rear_compression_mm,front_steer_deg,yaw_rate_deg_s\n");
        var rows=new Formatter(writer,Locale.ROOT);
        for(var sample:lap.samples()) {
            var driving=sample.driving();
            rows.format("%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",sample.time(),sample.distance(),
                    driving.speed()*3.6,driving.throttle(),driving.brake(),driving.steer(),sample.x(),sample.z(),sample.yaw());
            var observation=sample.observation();
            if(observation==null) writer.write(",,,,");
            else rows.format(",%.6f,%.6f,%.6f,%.6f",observation.frontCompression()*1000,observation.rearCompression()*1000,
                    Math.toDegrees(observation.frontSteer()),Math.toDegrees(observation.yawRate()));
            if(rows.ioException()!=null) throw rows.ioException();
            writer.write('\n');
        }
    }
}
