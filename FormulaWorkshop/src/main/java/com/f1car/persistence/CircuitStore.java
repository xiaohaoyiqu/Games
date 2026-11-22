package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-profile drafts and validated circuits. Imports never carry trusted driving results. */
public final class CircuitStore {
    public static final int HISTORY_LIMIT=12,REVIEW_LIMIT=12;
    public static final int PRACTICE_SLOT_LIMIT=4,PRACTICE_NAME_LIMIT=24;
    public record Revision(String id,long savedAt,CircuitDocument document) {
        public Revision { if(id==null || !id.matches("[a-f0-9]{32}") || savedAt<0 || document==null) throw new IllegalArgumentException("Invalid circuit revision"); }
    }
    public record ObservationRetest(Integer markerIndex,CircuitObservationRetest.Result result) {
        public ObservationRetest {
            if(markerIndex==null || markerIndex<0 || result==null) throw new IllegalArgumentException("Invalid saved observation retest");
        }
    }

    public record Review(long savedAt,String physicsVersion,CircuitDriveReview.Snapshot snapshot,List<ObservationRetest> observationRetests) {
        public Review(long savedAt,String physicsVersion,CircuitDriveReview.Snapshot snapshot) {
            this(savedAt,physicsVersion,snapshot,null);
        }
        public Review {
            if(savedAt<0 || physicsVersion==null || physicsVersion.isBlank() || physicsVersion.length()>120
                    || snapshot==null || snapshot.status()==CircuitDriveReview.Status.RUNNING)
                throw new IllegalArgumentException("Invalid saved drive review");
            if(observationRetests!=null) {
                observationRetests=List.copyOf(observationRetests);
                if(observationRetests.size()>snapshot.markers().size()
                        || observationRetests.stream().anyMatch(retest->retest.markerIndex()>=snapshot.markers().size())
                        || observationRetests.stream().map(ObservationRetest::markerIndex).distinct().count()!=observationRetests.size())
                    throw new IllegalArgumentException("Invalid saved observation retests");
                if(observationRetests.isEmpty()) observationRetests=null;
            }
        }
        public List<ObservationRetest> retests() {return observationRetests==null?List.of():observationRetests;}
        public CircuitObservationRetest.Result retest(int markerIndex) {
            for(var retest:retests()) if(retest.markerIndex()==markerIndex) return retest.result();
            return null;
        }
    }
    public record PracticePreset(String driveKey,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,Integer split) {
        public PracticePreset(String driveKey,CircuitSectionPractice.Range range,CircuitSectionPractice.Start start) {
            this(driveKey,range,start,null);
        }
        public PracticePreset {
            if(driveKey==null || !driveKey.matches("[a-f0-9]{64}") || range==null || start==null)
                throw new IllegalArgumentException("Invalid circuit practice preset");
            if(split!=null && (range.splitCount()<2 || split<1 || split>range.splitCount()))
                throw new IllegalArgumentException("Invalid circuit practice split");
        }
        public boolean matches(CircuitDocument document) {return driveKey.equals(document.driveKey());}
        public int focusedSplit() {return split==null?0:split;}
        public CircuitSectionPractice preview(TrackLayout track) {return new CircuitSectionPractice(track,range,start,focusedSplit());}
    }
    public record PracticeSlot(int number,String name,PracticePreset preset) {
        public PracticeSlot(int number,PracticePreset preset) {this(number,defaultPracticeName(number),preset);}
        public PracticeSlot {
            if(!validPracticeSlot(number) || name==null || name.isBlank() || name.length()>PRACTICE_NAME_LIMIT
                    || name.chars().anyMatch(Character::isISOControl) || preset==null)
                throw new IllegalArgumentException("Invalid circuit practice slot");
        }
    }
    public record Saved(CircuitDocument document,CircuitTrial.Proof proof,List<Revision> history,List<Review> reviews,
                        Integer reviewLimit,@SerializedName("practicePreset") PracticePreset legacyPracticePreset,
                        List<PracticeSlot> practiceSlots) {
        public Saved(CircuitDocument document,CircuitTrial.Proof proof) { this(document,proof,List.of(),List.of(),null,null,null); }
        public Saved(CircuitDocument document,CircuitTrial.Proof proof,List<Revision> history,List<Review> reviews) { this(document,proof,history,reviews,null,null,null); }
        public Saved(CircuitDocument document,CircuitTrial.Proof proof,List<Revision> history,List<Review> reviews,Integer reviewLimit) {
            this(document,proof,history,reviews,reviewLimit,null,null);
        }
        public Saved(CircuitDocument document,CircuitTrial.Proof proof,List<Revision> history,List<Review> reviews,
                     Integer reviewLimit,PracticePreset practicePreset) {
            this(document,proof,history,reviews,reviewLimit,practicePreset,null);
        }
        public Saved {
            Objects.requireNonNull(document);if(proof!=null && !proof.documentKey().equals(document.driveKey())) proof=null;
            history=history==null?List.of():List.copyOf(history);reviews=reviews==null?List.of():List.copyOf(reviews);
            String identity=document.id();
            int capacity=reviewLimit==null?REVIEW_LIMIT:reviewLimit;
            if(!List.of(4,8,12).contains(capacity) || history.size()>HISTORY_LIMIT || reviews.size()>capacity || history.stream().anyMatch(revision->!revision.document().id().equals(identity)) || history.stream().map(Revision::id).distinct().count()!=history.size()) throw new IllegalArgumentException("Invalid circuit archive");
            if(practiceSlots==null && legacyPracticePreset!=null)
                practiceSlots=List.of(new PracticeSlot(1,defaultPracticeName(1),legacyPracticePreset));
            legacyPracticePreset=null;
            if(practiceSlots!=null) {
                if(practiceSlots.size()>PRACTICE_SLOT_LIMIT || practiceSlots.stream().anyMatch(Objects::isNull)
                        || practiceSlots.stream().map(PracticeSlot::number).distinct().count()!=practiceSlots.size())
                    throw new IllegalArgumentException("Invalid circuit practice slots");
                practiceSlots=practiceSlots.isEmpty()?null:practiceSlots.stream().sorted(Comparator.comparingInt(PracticeSlot::number)).toList();
            }
        }
        public int reviewCapacity() { return reviewLimit==null?REVIEW_LIMIT:reviewLimit; }
        public CircuitDocument drivingVersion(String driveKey) {
            if(document.driveKey().equals(driveKey)) return document;
            for(int index=history.size()-1;index>=0;index--) {
                var candidate=history.get(index).document();
                if(candidate.driveKey().equals(driveKey)) return candidate;
            }
            return null;
        }
        /** Recover a matching road for read-only telemetry even when the current draft is unfinished. */
        public TrackLayout trackForTelemetry(TelemetryLap.Conditions source) {
            if(!document.id().equals(source.trackId())) return null;
            var current=matchingTelemetryTrack(document,source);
            if(current!=null) return current;
            for(int index=history.size()-1;index>=0;index--) {
                var revision=history.get(index).document();
                if(revision.equals(document)) continue;
                var match=matchingTelemetryTrack(revision,source);
                if(match!=null) return match;
            }
            return null;
        }
        private static TrackLayout matchingTelemetryTrack(CircuitDocument document,TelemetryLap.Conditions source) {
            if(document.anchors().size()<4) return null;
            try {
                var layout=document.track();
                return source.matchesTrack(layout)?layout:null;
            } catch(IllegalArgumentException unfinished) {
                return null;
            }
        }
        public List<PracticeSlot> slots() {return practiceSlots==null?List.of():practiceSlots;}
        public PracticeSlot practiceSlot(int number) {
            return slots().stream().filter(slot->slot.number()==number).findFirst().orElse(null);
        }
        public PracticePreset practicePreset() {
            var first=practiceSlot(1);
            return first==null?null:first.preset();
        }
    }
    private record Data(int version,List<Saved> maps) {
        private Data(List<Saved> maps) {this(versionFor(maps),maps);}
        private Data {
            if(maps==null || maps.size()>MAX_MAPS || version!=versionFor(maps))
                throw new IllegalArgumentException("Invalid circuit library");
            maps=List.copyOf(maps);if(maps.stream().map(s->s.document().id()).distinct().count()!=maps.size()) throw new IllegalArgumentException("Duplicate circuit identity");
        }
        private static int versionFor(List<Saved> maps) {
            if(maps.stream().flatMap(saved->saved.reviews().stream()).anyMatch(review->!review.retests().isEmpty())) return 3;
            return maps.stream().flatMap(saved->saved.slots().stream()).anyMatch(slot->slot.preset().split()!=null)?2:1;
        }
        Optional<Saved> find(String id) {return maps.stream().filter(saved->saved.document().id().equals(id)).findFirst();}
    }
    public record DraftComparison(Saved accepted,Saved disk,boolean libraryMissing) {}
    private record DiskArchive(Data data,ArchiveWriter.Stamp stamp) {}
    private record PlayableList(String physics,List<TrackCatalog.Entry> entries) {}
    private record TelemetryRoad(Saved archive,String geometry,float length,TrackLayout track) {}
    private record DiskTelemetryRoad(ArchiveWriter.Stamp stamp,String id,String geometry,float length,TrackLayout track) {
        boolean matches(TelemetryLap.Conditions source) {
            return id.equals(source.trackId()) && geometry.equals(source.geometry()) && length==source.length();
        }
    }

    private static final class PlayableMap {
        private final CircuitInspector.DraftInspection inspection=new CircuitInspector.DraftInspection();
        private CircuitDocument document;
        private TrackCatalog.Entry entry;

        TrackCatalog.Entry forDocument(CircuitDocument current) {
            if(current.equals(document)) return entry;
            var report=inspection.inspect(current);
            TrackCatalog.Entry next=null;
            if(report.ready()) {
                var catalog=TrackCatalog.forLayout(report.track());
                var description=current.metadata().description();
                next=description.isBlank()?catalog:new TrackCatalog.Entry(catalog.layout(),description,catalog.theme(),
                        catalog.scenerySeed(),catalog.flowStart(),catalog.flowEnd(),catalog.sections());
            }
            document=current;
            entry=next;
            return entry;
        }
    }

    private static final int MAX_MAPS=32,MAX_BYTES=2_000_000,IMPORT_BYTES=CircuitPracticeCodec.MAX_BYTES;
    private final Path file,lock;
    private final ArchiveWriter.Filesystem filesystem;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private Data data=new Data(1,List.of());
    private final Map<String,PlayableMap> playableMaps=new HashMap<>();
    private PlayableList playableList;
    private TelemetryRoad telemetryRoad;
    private DiskTelemetryRoad diskTelemetryRoad;
    private ArchiveWriter.Stamp acceptedStamp;
    private boolean damaged;
    private boolean needsRefresh;
    public String warning="";
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("custom-circuits");
    public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}
    public CircuitStore(Path directory) {this(directory,ArchiveWriter.FORCED_FILES);}
    CircuitStore(Path directory,ArchiveWriter.Filesystem filesystem) {
        file=directory.resolve("custom-circuits.json");
        lock=directory.resolve("custom-circuits.lock");
        this.filesystem=Objects.requireNonNull(filesystem);
        try {
            var disk=readArchive(true);
            if(disk.data()!=null) accept(disk.data(),disk.stamp());
            else {
                acceptedStamp=disk.stamp();
                damaged=true;
                warning="自制地图资料库无法读取，原文件保留；替换前会备份。";
            }
        } catch(IOException | RuntimeException error) {
            needsRefresh=true;
            warning="暂时无法读取地图资料库，请重读后再保存。";
        }
    }
    public boolean needsRefresh() {return needsRefresh;}

    /** Reads the selected disk draft without adopting the archive or clearing the save conflict. */
    public DraftComparison inspectDraftComparison(String id) throws IOException {
        var disk=readArchive(false);
        if(disk.data()==null) throw new IOException("Invalid circuit archive");
        return new DraftComparison(data.find(id).orElse(null),disk.data().find(id).orElse(null),
                ArchiveWriter.ABSENT.equals(disk.stamp()));
    }

    /** Recheck the loaded map library before using saved maps. */
    public boolean refreshIfChanged(String protectedDraftId) {
        if(damaged) return reloadFromDisk(protectedDraftId);
        try {
            verifyBase(file,acceptedStamp);
            if(needsRefresh) {
                needsRefresh=false;
                warning="";
            }
            return true;
        } catch(IOException | RuntimeException changed) {
            return reloadFromDisk(protectedDraftId);
        }
    }

    /** Refresh the library without changing the disk draft that a live editor is based on. */
    public boolean reload(String protectedDraftId) {
        if(!refreshIfChanged(protectedDraftId)) return false;
        warning="";
        return true;
    }

    private boolean reloadFromDisk(String protectedDraftId) {
        try {
            var disk=readArchive(false);
            if(disk.data()==null) throw new IOException("Invalid circuit archive");
            if(protectedDraftId!=null && !sameDraft(data.find(protectedDraftId).orElse(null),disk.data().find(protectedDraftId).orElse(null))) {
                needsRefresh=true;
                warning="磁盘上的当前草稿或验证已变化。编辑内容保留，请先回编辑画布核对。";
                return false;
            }
            accept(disk.data(),disk.stamp());
            return true;
        } catch(IOException | RuntimeException error) {
            needsRefresh=true;
            warning="暂时无法重读地图资料库，已读内容和当前编辑保留。";
            return false;
        }
    }

    private static boolean sameDraft(Saved before,Saved after) {
        return before==after || before!=null && after!=null
                && before.document().equals(after.document()) && Objects.equals(before.proof(),after.proof());
    }

    private static boolean sameRoadInputs(Saved before,Saved after) {
        return before!=null && after!=null
                && before.document().equals(after.document()) && before.history().equals(after.history());
    }

    private static boolean samePlayableInputs(List<Saved> before,List<Saved> after) {
        if(before.size()!=after.size()) return false;
        for(int index=0;index<before.size();index++) {
            if(!sameDraft(before.get(index),after.get(index))) return false;
        }
        return true;
    }

    private DiskArchive readArchive(boolean stampOversize) throws IOException {
        try {
            var source=ArchiveWriter.readSource(file,MAX_BYTES,stampOversize);
            var bytes=source.bytes();
            var stamp=source.stamp();
            if(bytes==null) return new DiskArchive(null,stamp);
            try {
                var text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                return new DiskArchive(gson.fromJson(text,Data.class),stamp);
            } catch(IOException | RuntimeException damagedContent) {
                return new DiskArchive(null,stamp);
            }
        } catch(NoSuchFileException absent) {
            return new DiskArchive(new Data(1,List.of()),ArchiveWriter.ABSENT);
        }
    }

    private synchronized void accept(Data accepted,ArchiveWriter.Stamp stamp) {
        // History, observations and practice presets do not participate in the track list.
        if(playableList!=null && !samePlayableInputs(data.maps(),accepted.maps())) playableList=null;
        if(!playableMaps.isEmpty()) {
            var withProof=new HashSet<String>();
            for(var saved:accepted.maps()) if(saved.proof()!=null) withProof.add(saved.document().id());
            playableMaps.keySet().retainAll(withProof);
        }
        if(telemetryRoad!=null) {
            var last=telemetryRoad;
            var before=last.archive();
            var after=accepted.find(before.document().id()).orElse(null);
            if(after!=before) telemetryRoad=sameRoadInputs(before,after)
                    ?new TelemetryRoad(after,last.geometry(),last.length(),last.track()):null;
        }
        data=accepted;
        acceptedStamp=stamp;
        diskTelemetryRoad=null;
        damaged=false;
        needsRefresh=false;
        warning="";
    }
    public List<Saved> all() { return data.maps(); }
    public Optional<Saved> find(String id) {return data.find(id);}

    /** Reuse one road lookup for the same recorded geometry and accepted map archive. */
    public synchronized TrackLayout trackForTelemetry(TelemetryLap.Conditions source) {
        var archive=data.find(source.trackId()).orElse(null);
        if(archive==null) return null;
        var last=telemetryRoad;
        if(last!=null && last.archive()==archive && last.length()==source.length() && last.geometry().equals(source.geometry()))
            return last.track();
        var track=archive.trackForTelemetry(source);
        telemetryRoad=new TelemetryRoad(archive,source.geometry(),source.length(),track);
        return track;
    }

    /** Check the recorded road against the current file without adopting its map archive. */
    public synchronized TrackLayout currentTelemetryTrack(TelemetryLap.Conditions source) {
        if(damaged || acceptedStamp==null) return null;
        var cached=diskTelemetryRoad;
        boolean reuse=cached!=null && cached.matches(source);
        try {
            ArchiveWriter.verifyStamp(file,reuse?cached.stamp():acceptedStamp,LinkOption.NOFOLLOW_LINKS);
            return reuse?cached.track():trackForTelemetry(source);
        } catch(ArchiveWriter.StaleArchiveException changed) {
            diskTelemetryRoad=null;
            try {
                var disk=readArchive(false);
                var current=disk.data()==null?null:disk.data().find(source.trackId()).orElse(null);
                var viewed=data.find(source.trackId()).orElse(null);
                TrackLayout track=null;
                if(current!=null)
                    track=sameRoadInputs(viewed,current)?trackForTelemetry(source):current.trackForTelemetry(source);
                diskTelemetryRoad=new DiskTelemetryRoad(disk.stamp(),source.trackId(),source.geometry(),source.length(),track);
                return track;
            } catch(IOException | RuntimeException unreadable) {
                return null;
            }
        } catch(IOException unreadable) {
            diskTelemetryRoad=null;
            return null;
        }
    }

    public synchronized List<TrackCatalog.Entry> playable(String physicsVersion) {
        if(playableList!=null && Objects.equals(playableList.physics(),physicsVersion)) return playableList.entries();
        var result=new ArrayList<TrackCatalog.Entry>();
        for(var saved:data.maps()) {
            var entry=playable(saved,physicsVersion);
            if(entry!=null) result.add(entry);
        }
        playableList=new PlayableList(physicsVersion,List.copyOf(result));
        return playableList.entries();
    }

    public synchronized TrackCatalog.Entry playable(String id,String physicsVersion) {
        if(playableList!=null && Objects.equals(playableList.physics(),physicsVersion)) {
            for(var entry:playableList.entries()) if(entry.id().equals(id)) return entry;
            return null;
        }
        return playable(data.find(id).orElse(null),physicsVersion);
    }

    private TrackCatalog.Entry playable(Saved saved,String physicsVersion) {
        if(saved==null || saved.proof()==null || !saved.proof().matches(saved.document(),physicsVersion)) return null;
        var document=saved.document();
        return playableMaps.computeIfAbsent(document.id(),key->new PlayableMap()).forDocument(document);
    }
    public boolean save(CircuitDocument document,CircuitTrial.Proof proof) {
        return save(document,proof,null);
    }
    public boolean savePracticePreset(CircuitDocument document,CircuitTrial.Proof proof,CircuitSectionPractice.Range range,
                                      CircuitSectionPractice.Start start) {
        var previous=find(document.id()).orElse(null);
        return savePracticeSlot(document,proof,1,previous==null?null:previous.practiceSlot(1),range,start);
    }
    public boolean savePracticeSlot(CircuitDocument document,CircuitTrial.Proof proof,int number,PracticeSlot expected,
                                    CircuitSectionPractice.Range range,CircuitSectionPractice.Start start) {
        return savePracticeSlot(document,proof,number,expected,range,start,null);
    }

    public boolean savePracticeSlot(CircuitDocument document,CircuitTrial.Proof proof,int number,PracticeSlot expected,
                                    CircuitSectionPractice.Range range,CircuitSectionPractice.Start start,Integer split) {
        if(!validPracticeSlot(number)) {warning="请选择有效的练习预设槽位。";return false;}
        var previous=find(document.id()).orElse(null);
        var slot=previous==null?null:previous.practiceSlot(number);
        if(!Objects.equals(slot,expected)) return practiceSlotChanged();
        var preset=checkedPracticePreset(document,range,start,split);
        if(preset==null) return false;
        var replacement=slot==null?new PracticeSlot(number,preset):new PracticeSlot(number,slot.name(),preset);
        return save(document,proof,updatedSlots(previous,number,replacement));
    }

    private PracticePreset checkedPracticePreset(CircuitDocument document,CircuitSectionPractice.Range range,
                                                CircuitSectionPractice.Start start,Integer split) {
        if(range==null || start==null) {warning="请先确认区间和发车设置。";return null;}
        try {
            var preset=new PracticePreset(document.driveKey(),range,start,split);
            var practice=preset.preview(document.track());
            if(practice.spawnBlocked()) {warning="发车点被场景占用，请调整助跑距离、区间起点或布景。";return null;}
            return preset;
        } catch(IllegalArgumentException error) {
            warning="当前路线或助跑距离无法用于区间试驾，请调整后再保存。";
            return null;
        }
    }
    private boolean save(CircuitDocument document,CircuitTrial.Proof proof,List<PracticeSlot> changedSlots) {
        var records=new ArrayList<>(data.maps());
        putDraft(records,document,proof,changedSlots);
        return writeDrafts(records);
    }
    private void putDraft(List<Saved> records,CircuitDocument document,CircuitTrial.Proof proof,List<PracticeSlot> changedSlots) {
        int index=-1;
        for(int i=0;i<records.size();i++) if(records.get(i).document().id().equals(document.id())) { index=i;break; }
        var previous=index<0?null:records.get(index);var history=new ArrayList<Revision>(previous==null?List.of():previous.history());
        if(previous!=null && history.isEmpty()) history.add(revision(previous.document()));
        if(history.isEmpty() || !history.get(history.size()-1).document().equals(document)) history.add(revision(document));
        while(history.size()>HISTORY_LIMIT) history.remove(0);
        var presets=changedSlots;
        if(presets==null && previous!=null) presets=previous.slots();
        var saved=new Saved(document,proof,history,previous==null?List.of():previous.reviews(),previous==null?null:previous.reviewLimit(),null,presets);
        if(index<0) records.add(saved);else records.set(index,saved);
    }
    private boolean writeDrafts(List<Saved> records) {
        if(records.size()>MAX_MAPS) { warning="自制地图槽位已满，请先在编辑器中删除不需要的草稿。";return false; }
        return write(new Data(records));
    }
    private Revision revision(CircuitDocument document) { return new Revision(UUID.randomUUID().toString().replace("-",""),System.currentTimeMillis(),document); }

    public CircuitDocument saveDraftCopy(CircuitDocument working,String name,PracticeSlot preview) {
        CircuitDocument copy;
        try {
            if(name==null) throw new IllegalArgumentException("Missing draft name");
            copy=working.importedCopy().renamed(name);
        } catch(IllegalArgumentException error) {
            warning="副本名称需为 1–60 个字符，且不能包含控制字符。";
            return null;
        }
        String workingKey=working.driveKey();
        if(preview!=null) {
            var preset=preview.preset();
            if(!workingKey.equals(preset.driveKey())) {warning="区间预览已过期，请回到当前草稿重新核对。";return null;}
            if(checkedPracticePreset(working,preset.range(),preset.start(),preset.split())==null) return null;
        }

        var slots=new ArrayList<PracticeSlot>();
        String copyKey=copy.driveKey();
        for(var slot:find(working.id()).map(Saved::slots).orElse(List.of())) {
            if(workingKey.equals(slot.preset().driveKey()) && (preview==null || preview.number()!=slot.number()))
                slots.add(copySlot(slot,copyKey));
        }
        if(preview!=null) slots.add(copySlot(preview,copyKey));
        var saved=new Saved(copy,null,List.of(revision(copy)),List.of(),null,null,slots);
        return write(()->prepareCopy(saved))?copy:null;
    }

    private static PracticeSlot copySlot(PracticeSlot source,String driveKey) {
        var preset=source.preset();
        return new PracticeSlot(source.number(),source.name(),new PracticePreset(driveKey,preset.range(),preset.start(),preset.split()));
    }

    private ArchiveChange prepareCopy(Saved copy) throws IOException {
        var disk=readArchive(true);
        if(disk.data()==null || ArchiveWriter.ABSENT.equals(disk.stamp()) && !ArchiveWriter.ABSENT.equals(acceptedStamp)) {
            needsRefresh=true;
            warning="无法确认最新地图资料库，当前编辑保留。请先重读资料库。";
            return null;
        }
        if(disk.data().maps().size()>=MAX_MAPS) {
            warning="磁盘上的地图槽位已满，当前编辑保留。仍可导出当前草稿。";
            return null;
        }
        if(disk.data().find(copy.document().id()).isPresent()) {
            warning="副本编号冲突，请重试另存。";
            return null;
        }
        var records=new ArrayList<>(disk.data().maps());
        records.add(copy);
        return new ArchiveChange(new Data(records),disk.stamp(),false);
    }

    /** All newly imported maps enter as unqualified drafts in a single library write. */
    public boolean installUnverified(List<CircuitDocument> documents) {
        if(documents==null || documents.isEmpty() || documents.size()>8 || documents.stream().anyMatch(Objects::isNull) || documents.stream().map(CircuitDocument::id).distinct().count()!=documents.size() || documents.stream().anyMatch(document->find(document.id()).isPresent())) { warning="导入地图身份重复或无效，已有草稿保留。";return false; }
        if(data.maps().size()+documents.size()>MAX_MAPS) { warning="地图槽位不足，请为整份包腾出足够位置。";return false; }
        var records=new ArrayList<>(data.maps());for(var document:documents) records.add(new Saved(document,null,List.of(revision(document)),List.of()));return writeDrafts(records);
    }
    public boolean saveReview(String id,CircuitDriveReview.Snapshot snapshot,String physicsVersion) {
        var existing=find(id).orElse(null);
        if(existing==null || snapshot==null || snapshot.status()==CircuitDriveReview.Status.RUNNING) {
            warning="试跑记录与当前或历史地图版本不匹配，记录尚未保存。";
            return false;
        }
        if(!existing.reviews().isEmpty()) {
            var last=existing.reviews().get(existing.reviews().size()-1);
            if(last.snapshot().equals(snapshot) && last.physicsVersion().equals(physicsVersion)) return confirmSavedReview();
        }
        if(existing.drivingVersion(snapshot.documentKey())==null) {
            warning="试跑记录与当前或历史地图版本不匹配，记录尚未保存。";
            return false;
        }
        var reviews=new ArrayList<>(existing.reviews());reviews.add(new Review(System.currentTimeMillis(),physicsVersion,snapshot));while(reviews.size()>existing.reviewCapacity()) reviews.remove(0);
        return replaceArchive(existing,reviews,existing.reviewLimit());
    }

    private boolean confirmSavedReview() {
        try {
            verifyBase(file,acceptedStamp);
            needsRefresh=false;
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            needsRefresh=true;
            warning="磁盘上的试跑记录暂时无法核对，待存记录保留。请重读地图资料库。";
            return false;
        }
    }
    public CircuitObservationRetest.Result observationRetest(CircuitObservationRetest.Source source) {
        var review=observationReview(data,source);
        return review==null?null:review.retest(source.markerIndex());
    }

    /** Check the original observation and, when supplied, the exact archived retest without adopting disk changes. */
    public boolean currentObservation(CircuitObservationRetest.Source source,CircuitObservationRetest.Result expectedRetest) {
        if(!matchesObservation(data,source,expectedRetest) || damaged || acceptedStamp==null) return false;
        try {
            ArchiveWriter.verifyStamp(file,acceptedStamp,LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch(ArchiveWriter.StaleArchiveException changed) {
            try {
                var disk=readArchive(false);
                if(matchesObservation(disk.data(),source,expectedRetest)) return true;
            } catch(IOException | RuntimeException unreadable) {
                // Keep the loaded records available for review until an explicit reload succeeds.
            }
        } catch(IOException | RuntimeException unreadable) {
            // The selected source could not be verified on disk.
        }
        needsRefresh=true;
        return false;
    }

    private static boolean matchesObservation(Data archive,CircuitObservationRetest.Source source,CircuitObservationRetest.Result expectedRetest) {
        var review=observationReview(archive,source);
        return review!=null && (expectedRetest==null || expectedRetest.equals(review.retest(source.markerIndex())));
    }

    private static Review observationReview(Data archive,CircuitObservationRetest.Source source) {
        if(archive==null || source==null || source.savedAt()==CircuitObservationRetest.Source.PENDING) return null;
        var saved=archive.find(source.mapId()).orElse(null);
        if(saved==null) return null;
        int index=observationReviewIndex(saved.reviews(),source);
        return index<0?null:saved.reviews().get(index);
    }

    public boolean replaceObservationRetest(CircuitObservationRetest.Source source,CircuitObservationRetest.Result expected,
                                            CircuitObservationRetest.Result replacement) {
        var saved=source==null?null:find(source.mapId()).orElse(null);
        int index=saved==null?-1:observationReviewIndex(saved.reviews(),source);
        if(index<0) {
            warning="原试跑记录尚未保存、已移除或无法唯一定位。";
            return false;
        }
        var original=saved.reviews().get(index);
        if(!Objects.equals(original.retest(source.markerIndex()),expected)) {
            warning="已存复测已变化，请重新打开详情后确认。";
            return false;
        }
        var retests=new ArrayList<>(original.retests());
        retests.removeIf(retest->retest.markerIndex()==source.markerIndex());
        if(replacement!=null) retests.add(new ObservationRetest(source.markerIndex(),replacement));
        var reviews=new ArrayList<>(saved.reviews());
        reviews.set(index,new Review(original.savedAt(),original.physicsVersion(),original.snapshot(),retests));
        return replaceArchive(saved,reviews,saved.reviewLimit());
    }

    public static int observationReviewIndex(List<Review> reviews,CircuitObservationRetest.Source source) {
        int index=-1;
        for(int i=0;i<reviews.size();i++) {
            var review=reviews.get(i);
            if(!source.matchesSaved(review.savedAt(),review.physicsVersion(),review.snapshot())) continue;
            if(index>=0) return -1;
            index=i;
        }
        return index;
    }
    public boolean reviewCapacity(String id,int capacity) {
        if(!List.of(4,8,12).contains(capacity)) throw new IllegalArgumentException("Unsupported review capacity");var saved=find(id).orElse(null);if(saved==null) return false;
        var reviews=saved.reviews();return replaceArchive(saved,reviews.subList(Math.max(0,reviews.size()-capacity),reviews.size()),capacity);
    }
    public boolean deleteReview(String id,Review review) {
        var saved=find(id).orElse(null);if(saved==null) return false;var reviews=new ArrayList<>(saved.reviews());if(!reviews.remove(review)) return false;return replaceArchive(saved,reviews,saved.reviewLimit());
    }
    public boolean clearReviews(String id) { var saved=find(id).orElse(null);return saved!=null && replaceArchive(saved,List.of(),saved.reviewLimit()); }
    private boolean replaceArchive(Saved saved,List<Review> reviews,Integer capacity) {
        return replaceArchive(saved,new Saved(saved.document(),saved.proof(),saved.history(),reviews,capacity,null,saved.slots()));
    }
    public boolean clearPracticePreset(String id,PracticePreset expected) {
        var saved=find(id).orElse(null);
        if(saved==null || expected==null || !expected.equals(saved.practicePreset())) return practiceSlotChanged();
        return replacePracticeSlot(saved,1,null);
    }
    public boolean clearPracticeSlot(String id,PracticeSlot expected) {
        var saved=find(id).orElse(null);
        if(saved==null || expected==null || !expected.equals(saved.practiceSlot(expected.number()))) return practiceSlotChanged();
        return replacePracticeSlot(saved,expected.number(),null);
    }
    public boolean renamePracticeSlot(String id,PracticeSlot expected,String name) {
        var saved=find(id).orElse(null);
        if(saved==null || expected==null || !expected.equals(saved.practiceSlot(expected.number()))) return practiceSlotChanged();
        PracticeSlot renamed;
        try {
            renamed=new PracticeSlot(expected.number(),name==null?null:name.strip(),expected.preset());
        } catch(IllegalArgumentException error) {
            warning="预设名称需为 1–"+PRACTICE_NAME_LIMIT+" 个字符，且不能包含控制字符。";
            return false;
        }
        if(renamed.equals(expected)) {warning="";return true;}
        return replacePracticeSlot(saved,expected.number(),renamed);
    }
    private boolean replacePracticeSlot(Saved saved,int number,PracticeSlot replacement) {
        var slots=updatedSlots(saved,number,replacement);
        return replaceArchive(saved,new Saved(saved.document(),saved.proof(),saved.history(),saved.reviews(),saved.reviewLimit(),null,slots));
    }
    private boolean practiceSlotChanged() {
        warning="练习预设已变化，请重新打开预设列表。";
        return false;
    }
    private static List<PracticeSlot> updatedSlots(Saved saved,int number,PracticeSlot replacement) {
        var slots=new ArrayList<PracticeSlot>();
        if(saved!=null) for(var slot:saved.slots()) if(slot.number()!=number) slots.add(slot);
        if(replacement!=null) slots.add(replacement);
        return slots;
    }
    private static boolean validPracticeSlot(int number) {return number>=1 && number<=PRACTICE_SLOT_LIMIT;}
    private static String defaultPracticeName(int number) {return "练习 "+number;}
    private boolean replaceArchive(Saved saved,Saved replacement) {
        var records=new ArrayList<>(data.maps());records.set(records.indexOf(saved),replacement);return writeDrafts(records);
    }
    public boolean delete(String id) {
        var records=new ArrayList<>(data.maps());records.removeIf(saved->saved.document().id().equals(id));return writeDrafts(records);
    }
    private record ArchiveChange(Data next,ArchiveWriter.Stamp base,boolean backup) {}
    @FunctionalInterface
    private interface PrepareWrite {ArchiveChange prepare() throws IOException;}

    private boolean write(Data next) {
        return write(()->{
            verifyBase(file,acceptedStamp);
            needsRefresh=false;
            return new ArchiveChange(next,acceptedStamp,damaged);
        });
    }

    private boolean write(PrepareWrite action) {
        diagnostics.begin();
        boolean published=false;
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            Files.createDirectories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Circuit archive is busy");
                var change=action.prepare();
                if(change==null) return false;
                // Preparation has already verified the on-disk base while holding the archive lock.
                if(!change.backup() && !ArchiveWriter.ABSENT.equals(change.base())
                        && change.base().equals(acceptedStamp) && change.next().equals(data)) {
                    warning="";
                    return true;
                }
                diagnostics.at(SaveDiagnostics.Stage.ENCODE);
                byte[] encoded=ArchiveWriter.encodeStrict(gson.toJson(change.next()));
                var stamp=ArchiveWriter.stamp(encoded);
                ArchiveWriter.bytes(file,encoded,MAX_BYTES,"custom-circuits-",diagnostics,filesystem,target->{
                    if(change.backup()) preserveDamagedArchive(change.base());
                    verifyBase(target,change.base());
                });
                accept(change.next(),stamp);
                published=true;
            }
            return true;
        } catch(IOException | RuntimeException error) {
            if(published) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);
            warning=needsRefresh?"磁盘资料库与已读基准不一致或暂时无法核对。编辑内容保留，请重读地图资料库。"
                    :diagnostics.failure().stage()==SaveDiagnostics.Stage.BACKUP?"原地图资料库的备份无法写入或核对。请检查备份文件和磁盘空间后重试。"
                    :diagnostics.failure().encodedBytes()>MAX_BYTES?"地图资料库达到 2 MB 上限。请通过记录管理减少已存试跑记录后重试。"
                    :"地图尚未保存，请检查用户目录或等另一个窗口完成后重试。";
            return false;
        }
    }

    private void preserveDamagedArchive(ArchiveWriter.Stamp original) throws IOException {
        var backup=file.resolveSibling("custom-circuits.backup-"+original.sha256()+".json");
        try {
            if(Files.notExists(backup,LinkOption.NOFOLLOW_LINKS)) {
                try {
                    ArchiveWriter.backup(file,backup,original,original.bytes(),"custom-circuits-backup-",
                            new SaveDiagnostics("custom-circuits-backup"));
                } catch(ArchiveWriter.StaleArchiveException changed) {
                    needsRefresh=true;
                    throw changed;
                }
            } else {
                ArchiveWriter.verifyStamp(backup,original,LinkOption.NOFOLLOW_LINKS);
            }
        } catch(IOException | RuntimeException error) {
            diagnostics.at(SaveDiagnostics.Stage.BACKUP);
            throw error;
        }
    }

    private void verifyBase(Path path,ArchiveWriter.Stamp base) throws IOException {
        try {
            if(base==null) throw new IOException("Circuit archive must be read before saving");
            ArchiveWriter.verifyStamp(path,base,LinkOption.NOFOLLOW_LINKS);
        } catch(IOException error) {
            needsRefresh=true;
            throw error;
        }
    }
    public record ImportedDraft(CircuitDocument document,PracticeSlot slot) {
        public ImportedDraft {
            Objects.requireNonNull(document);
            if(slot!=null && !slot.preset().matches(document)) throw new IllegalArgumentException("Practice slot belongs to another map");
        }
    }

    public CircuitDocument importFile(Path path) throws IOException {
        return readImport(path,false).document();
    }

    public ImportedDraft importDraftFile(Path path) throws IOException {
        return readImport(path,true);
    }

    private ImportedDraft readImport(Path path,boolean allowPractice) throws IOException {
        byte[] bytes=ArchiveWriter.readImport(path,IMPORT_BYTES,"地图文件过大");
        try {
            var object=CircuitPracticeCodec.readTree(bytes);
            if(allowPractice) {
                var format=object.get("format");
                if(format!=null && format.isJsonPrimitive() && CircuitPracticeCodec.FORMAT.equals(format.getAsString())) {
                    var file=CircuitPracticeCodec.decode(object);
                    var local=file.document().importedCopy();
                    return new ImportedDraft(local,copySlot(file.slot(),local.driveKey()));
                }
            }
            // Version 1 maps predate the optional card and may carry editor-specific fields.
            if(!object.has("card")) object.add("card",JsonNull.INSTANCE);
            ActivityCardCodec.validate(object,CircuitDocument.class,true);
            var local=gson.fromJson(object,CircuitDocument.class).importedCopy();
            return new ImportedDraft(local,null);
        } catch(RuntimeException | ReflectiveOperationException error) {
            throw new IOException("不是有效的方程式地图文件",error);
        }
    }

    public boolean saveImportedPractice(ImportedDraft draft) {
        return saveImportedPractice(draft,null,null);
    }

    /** Keep the editor's dirty map and the new practice draft in one archive replacement. */
    public boolean saveImportedPractice(ImportedDraft draft,CircuitDocument edited,CircuitTrial.Proof proof) {
        String incoming=draft.document().id();
        if(draft.slot()==null || find(incoming).isPresent() || edited!=null && edited.id().equals(incoming)) {
            warning="请选择新的区间草稿文件导入。";
            return false;
        }
        var records=new ArrayList<>(data.maps());
        if(edited!=null) putDraft(records,edited,proof,null);
        putDraft(records,draft.document(),null,List.of(draft.slot()));
        return writeDrafts(records);
    }

    /** Apply the file's setup to one slot of the reviewed, already saved map. */
    public boolean applyImportedPracticeSlot(ImportedDraft draft,CircuitDocument target,int number,PracticeSlot expected) {
        if(!validPracticeSlot(number)) {warning="请选择有效的练习预设槽位。";return false;}
        var saved=find(target.id()).orElse(null);
        if(saved==null || !target.equals(saved.document())) {
            warning="目标地图已变化，请重新查看待导入区间。";
            return false;
        }
        if(draft.slot()==null || !target.sameDrivingLayout(draft.document())) {
            warning="目标地图与文件的路线或布景不一致。";
            return false;
        }
        if(!Objects.equals(expected,saved.practiceSlot(number))) return practiceSlotChanged();
        var source=draft.slot().preset();
        var preset=checkedPracticePreset(target,source.range(),source.start(),source.split());
        return preset!=null && replacePracticeSlot(saved,number,new PracticeSlot(number,draft.slot().name(),preset));
    }

    public void exportPracticeFile(CircuitDocument document,PracticeSlot slot,Path path,boolean replace) throws IOException {
        exportPracticeFile(document,slot,path,ExportDestination.forWrite(path,replace));
    }
    public void exportPracticeFile(CircuitDocument document,PracticeSlot slot,Path path,ExportDestination reviewed) throws IOException {
        checkExportTarget(path,reviewed);
        CircuitPracticeCodec.write(path,document,slot,reviewed);
    }
    public void exportFile(CircuitDocument document,Path path) throws IOException {
        exportFile(document,path,false);
    }
    public void exportFile(CircuitDocument document,Path path,boolean replace) throws IOException {
        exportFile(document,path,ExportDestination.forWrite(path,replace));
    }
    public void exportFile(CircuitDocument document,Path path,ExportDestination reviewed) throws IOException {
        checkExportTarget(path,reviewed);
        byte[] encoded=ArchiveWriter.encodeStrict(gson.toJson(document));
        var diagnostics=new SaveDiagnostics("circuit-map-export");
        diagnostics.begin();
        var filesystem=reviewed.replacing()?ArchiveWriter.FORCED_FILES:ArchiveWriter.NEW_FILES;
        ArchiveWriter.bytes(path.toAbsolutePath().normalize(),encoded,IMPORT_BYTES,"circuit-map-",diagnostics,filesystem,reviewed::verifyPublication);
    }
    private void checkExportTarget(Path path,ExportDestination reviewed) throws IOException {
        if(Files.isSymbolicLink(path) || samePath(path,file) || samePath(path,lock)) throw new IOException("请选择独立的导出文件");
        // A stale selection must not create missing folders while staging a new map.
        if(!reviewed.replacing()) reviewed.verifyPublication(path);
    }
    private static boolean samePath(Path first,Path second) throws IOException {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())
                || Files.exists(first) && Files.exists(second) && Files.isSameFile(first,second);
    }
}
