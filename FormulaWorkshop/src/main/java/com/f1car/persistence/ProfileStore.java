package com.f1car.persistence;

import com.f1car.model.Tuning;
import com.f1car.model.TrackCatalog;
import com.f1car.model.PracticeSession;
import com.f1car.model.GraphicsQuality;
import com.f1car.model.DriverName;
import com.f1car.mini4wd.MiniSetup;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;

public final class ProfileStore {
    private static final int MAX_BYTES=2_000_000;
    private static final int MAX_COURSE_REPORTS=200,MAX_COMPLETION_RECEIPTS=256;
    public static final class CourseReport {
        public long recordedAt;
        public double elapsed,spread;
        public double reaction=-1,acceleration=-1;
        public float entrySpeed=-1,exitSpeed;
        public List<Double> lapTimes=List.of();
        public boolean valid() {
            return recordedAt>=0 && Double.isFinite(elapsed) && elapsed>0 && Double.isFinite(spread) && spread>=0
                && Double.isFinite(reaction) && reaction>=-1 && Double.isFinite(acceleration) && acceleration>=-1
                && Float.isFinite(entrySpeed) && entrySpeed>=-1 && Float.isFinite(exitSpeed) && exitSpeed>=0
                && lapTimes!=null && lapTimes.size()<=3 && lapTimes.stream().allMatch(t->t!=null && Double.isFinite(t) && t>0);
        }
    }
    public static final class Profile {
        public int schemaVersion = 1;
        public Tuning tuning = Tuning.STOCK;
        public MiniSetup miniSetup = MiniSetup.empty();
        public String playerName = DriverName.DEFAULT;
        public String trackId=TrackCatalog.DEFAULT_ID;
        public Map<String, Tuning> presets = new LinkedHashMap<>();
        public Map<String, Double> bestTimes = new LinkedHashMap<>();
        public Map<String, Integer> bestScores = new LinkedHashMap<>();
        public Map<String, Double> brakingDistances = new LinkedHashMap<>();
        public Map<String, Double> brakingEntrySpeeds = new LinkedHashMap<>();
        public Map<String, Long> brakingRecordedAt = new LinkedHashMap<>();
        public Map<String,CourseReport> courseReports=new LinkedHashMap<>();
        public List<String> completionReceipts=List.of();
        public int completedAttempts;
        public boolean humor = true, sound = true;
        public boolean ghostEnabled = true;
        public float ghostOpacity = .4f;
        public float volume = .4f;
        public boolean music = true;
        public float musicVolume = .35f;
        public int camera;
        public GraphicsQuality graphicsQuality=GraphicsQuality.BALANCED;
        public int raceLaps = 3, difficulty = 1;
    }
    private final Path directory;
    interface Filesystem extends ArchiveWriter.Filesystem {
        default void backup(Path source,Path target) throws IOException {Files.copy(source,target,LinkOption.NOFOLLOW_LINKS);}
        @Override default void write(Path path,byte[] bytes) throws IOException {
            ArchiveWriter.writeAndForce(path,bytes);
        }
    }
    private final Filesystem filesystem;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("profile");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public String warning = "";
    public Profile profile;
    private ArchiveWriter.Stamp acceptedStamp;
    private boolean damagedAtLoad;

    public ProfileStore(Path directory) {this(directory,new Filesystem() {});}
    ProfileStore(Path directory,Filesystem filesystem) {this.directory=java.util.Objects.requireNonNull(directory);this.filesystem=java.util.Objects.requireNonNull(filesystem);profile=load();}
    public Path directory() { return directory; }
    public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}

    private static byte[] readProfileBytes(Path file) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid profile file");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            var bytes=input.readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES) throw new IOException("Profile too large");
            return bytes;
        }
    }

    private static JsonObject decodeProfile(byte[] bytes) throws IOException {
        var json=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private byte[] encodeProfile(Object value) throws IOException {
        var encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(gson.toJson(value)));
        var content=new byte[encoded.remaining()];
        encoded.get(content);
        return content;
    }

    private record ProfileDocument(ArchiveWriter.Stamp stamp,JsonObject json,Profile disk) {}

    private ProfileDocument latestProfile(Path target) throws IOException {
        ArchiveWriter.Stamp previous;
        JsonObject document;
        if(Files.notExists(target,LinkOption.NOFOLLOW_LINKS) && ArchiveWriter.ABSENT.equals(acceptedStamp)) {
            previous=ArchiveWriter.ABSENT;
            document=gson.toJsonTree(new Profile()).getAsJsonObject();
        } else {
            var bytes=readProfileBytes(target);
            previous=ArchiveWriter.stamp(bytes);
            document=decodeProfile(bytes);
        }
        var disk=gson.fromJson(document,Profile.class);
        if(disk==null || disk.schemaVersion!=1) throw new IOException("Unsupported profile schema");
        return new ProfileDocument(previous,document,disk);
    }

    private void publishProfileField(Path target,ProfileDocument current) throws IOException {
        diagnostics.at(SaveDiagnostics.Stage.ENCODE);
        var content=encodeProfile(current.json());
        ArchiveWriter.bytes(target,content,MAX_BYTES,"profile-save-",diagnostics,filesystem,
                destination->ArchiveWriter.verifyStamp(destination,current.stamp(),LinkOption.NOFOLLOW_LINKS));
        // A stale window still has its prior settings and must re-read them before writing the full profile.
        if(!damagedAtLoad && Objects.equals(acceptedStamp,current.stamp())) acceptedStamp=ArchiveWriter.stamp(content);
    }

    private static JsonObject profileMap(JsonObject document,String field) {
        var value=document.get(field);
        return value==null || value.isJsonNull()?new JsonObject():value.getAsJsonObject();
    }

    private Profile load() {
        Path file = directory.resolve("profile.json");
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) {
            acceptedStamp=ArchiveWriter.ABSENT;
            return new Profile();
        }
        try {
            var bytes=readProfileBytes(file);
            acceptedStamp=ArchiveWriter.stamp(bytes);
            var root=decodeProfile(bytes);
            Profile p=gson.fromJson(root, Profile.class);
            if (p == null || p.schemaVersion != 1) throw new IOException("Unsupported profile schema");
            if (p.tuning == null) p.tuning = Tuning.STOCK;
            if(p.trackId==null || !p.trackId.matches("user-[a-f0-9]{32}")) p.trackId=TrackCatalog.orDefault(p.trackId).id();
            if (p.miniSetup == null || !root.has("miniSetup")) p.miniSetup = MiniSetup.stock();
            p.playerName = DriverName.clean(p.playerName);
            if (p.presets == null) p.presets = new LinkedHashMap<>();
            p.presets.values().removeIf(v -> v == null);
            if (p.bestTimes == null) p.bestTimes = new LinkedHashMap<>();
            p.bestTimes.values().removeIf(v -> v == null || !Double.isFinite(v) || v <= 0);
            if (p.bestScores == null) p.bestScores = new LinkedHashMap<>();
            p.bestScores.values().removeIf(v -> v == null || v < 0);
            if (p.brakingDistances == null) p.brakingDistances = new LinkedHashMap<>();
            if (p.brakingEntrySpeeds == null) p.brakingEntrySpeeds = new LinkedHashMap<>();
            if (p.brakingRecordedAt == null) p.brakingRecordedAt = new LinkedHashMap<>();
            if(p.courseReports==null) p.courseReports=new LinkedHashMap<>();
            p.courseReports.entrySet().removeIf(e->e.getKey()==null || e.getValue()==null || !e.getValue().valid());
            while(p.courseReports.size()>MAX_COURSE_REPORTS) p.courseReports.remove(p.courseReports.keySet().iterator().next());
            p.completionReceipts=List.copyOf(completionReceipts(p.completionReceipts));
            p.volume = Float.isFinite(p.volume) ? Math.max(0, Math.min(1, p.volume)) : .4f;
            p.musicVolume = Float.isFinite(p.musicVolume) ? Math.max(0, Math.min(1, p.musicVolume)) : .35f;
            // An older player who muted all existing audio should keep a quiet upgrade.
            if(!root.has("music")) p.music=p.sound && p.volume>0;
            p.ghostOpacity = Float.isFinite(p.ghostOpacity) ? Math.max(.2f,Math.min(.7f,p.ghostOpacity)) : .4f;
            p.camera = Math.floorMod(p.camera, 3);
            if(p.graphicsQuality==null) p.graphicsQuality=GraphicsQuality.BALANCED;
            p.raceLaps = p.raceLaps == 1 || p.raceLaps == 5 ? p.raceLaps : 3;
            p.difficulty = Math.max(0, Math.min(2, p.difficulty));
            p.completedAttempts = Math.max(0, p.completedAttempts);
            return p;
        } catch (Exception e) {
            damagedAtLoad=true;
            warning = "存档读取失败，已使用默认配置；原文件保留。";
            return new Profile();
        }
    }

    public synchronized boolean save() {
        return save(false);
    }

    /** Routine navigation can keep an already published byte-identical configuration. */
    public synchronized boolean saveIfChanged() {
        return save(true);
    }

    /** Checks the published profile before a related recovery copy is acknowledged. */
    public synchronized boolean verifyAcceptedProfile() {
        if(acceptedStamp==null || damagedAtLoad || ArchiveWriter.ABSENT.equals(acceptedStamp)) return false;
        try {
            ArchiveWriter.verifyStamp(directory.resolve("profile.json"),acceptedStamp,LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch(IOException | RuntimeException error) {
            return false;
        }
    }

    private boolean save(boolean skipUnchanged) {
        diagnostics.begin();
        boolean published=false;
        try {
            diagnostics.at(SaveDiagnostics.Stage.ENCODE);
            var content=encodeProfile(profile);
            diagnostics.encoded(content.length);
            if(content.length>MAX_BYTES) throw new IOException("Profile too large");
            var newStamp=ArchiveWriter.stamp(content);
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);filesystem.directories(directory);
            Path target=directory.resolve("profile.json");
            if(acceptedStamp==null) throw new IOException("Cannot verify the original profile");
            var previous=acceptedStamp;
            try(var channel=ArchiveWriter.openLock(directory.resolve("profile.lock"));var held=channel.tryLock()) {
                if(held==null) throw new IOException("Profile is busy");
                if (skipUnchanged && !damagedAtLoad && newStamp.equals(previous)) {
                    ArchiveWriter.verifyStamp(target, previous, LinkOption.NOFOLLOW_LINKS);
                    warning = "";
                    return true;
                }
                if(damagedAtLoad) {
                    diagnostics.at(SaveDiagnostics.Stage.BACKUP);
                    ArchiveWriter.verifyStamp(target,previous,LinkOption.NOFOLLOW_LINKS);
                    var backup=directory.resolve("profile.backup-"+previous.sha256()+".json");
                    if(Files.notExists(backup,LinkOption.NOFOLLOW_LINKS)) filesystem.backup(target,backup);
                    ArchiveWriter.verifyStamp(backup,previous,LinkOption.NOFOLLOW_LINKS);
                }
                ArchiveWriter.bytes(target,content,MAX_BYTES,"profile-save-",diagnostics,filesystem,
                        destination->ArchiveWriter.verifyStamp(destination,previous,LinkOption.NOFOLLOW_LINKS));
                acceptedStamp=newStamp;
                damagedAtLoad=false;
                warning="";
                published=true;
            }
            return true;
        }catch(ArchiveWriter.StaleArchiveException changed) {
            diagnostics.failed(changed);
            warning="磁盘配置已变化，本次配置未写入。可重新打开游戏读取已存配置。";
            return false;
        } catch (IOException | RuntimeException e) {
            if(published) {
                diagnostics.cleanupFailed(e);
                return true;
            }
            diagnostics.failed(e);
            warning=acceptedStamp==null?"原配置无法核对，文件保留。请检查原文件后重新打开游戏。"
                    :"无法保存配置，请检查用户数据目录是否可写。";
            return false;
        }
    }

    public synchronized boolean recordTime(String key, double time) {
        if (!Double.isFinite(time) || time <= 0) return false;
        Double old = profile.bestTimes.get(key);
        if (old != null && time >= old) return false;
        profile.bestTimes.put(key, time);
        if (save()) return true;
        if (old == null) profile.bestTimes.remove(key);
        else profile.bestTimes.put(key, old);
        return false;
    }

    /** Commit one slot against its previously visible value, preserving other changes on disk. */
    public synchronized boolean savePreset(String slot,Tuning candidate) {
        if(candidate==null || !"A".equals(slot) && !"B".equals(slot)) {
            warning="请选择 A 或 B 快捷槽。";
            return false;
        }
        var expected=profile.presets.get(slot);
        var target=directory.resolve("profile.json");
        boolean accepted=false;
        diagnostics.begin();
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(directory);
            try(var channel=ArchiveWriter.openLock(directory.resolve("profile.lock"));var held=channel.tryLock()) {
                if(held==null) throw new IOException("Profile is busy");
                var current=latestProfile(target);
                var disk=current.disk().presets==null?null:current.disk().presets.get(slot);
                if(candidate.equals(disk)) {
                    ArchiveWriter.verifyStamp(target,current.stamp(),LinkOption.NOFOLLOW_LINKS);
                } else {
                    if(!Objects.equals(expected,disk)) {
                        warning="快捷槽 "+slot+" 已在另一窗口修改。请重新打开游戏读取，或保存到另一快捷槽。";
                        return false;
                    }
                    var presets=profileMap(current.json(),"presets");
                    presets.add(slot,gson.toJsonTree(candidate));
                    current.json().add("presets",presets);
                    publishProfileField(target,current);
                }
                profile.presets.put(slot,candidate);
                warning="";
                accepted=true;
            }
        } catch(IOException | RuntimeException error) {
            if(accepted) diagnostics.cleanupFailed(error);
            else {
                diagnostics.failed(error);
                warning=error instanceof ArchiveWriter.StaleArchiveException
                        ?"保存时配置又有更新，请重试。"
                        :"快捷槽未保存，原方案保留。";
            }
        }
        return accepted;
    }

    public enum MiniTimeSave { SAVED,COVERED,FAILED }
    public enum BestTimeSave { SAVED,COVERED,FAILED }

    /** Merge a Formula lap into the latest profile without publishing unrelated local settings. */
    public synchronized BestTimeSave recordLapTime(String key,double time) {
        if(key==null || !key.startsWith("lap:") || !Double.isFinite(time) || time<=0) {
            warning="圈速或计时条件无效。";
            return BestTimeSave.FAILED;
        }
        var best=profile.bestTimes.get(key);
        if(best!=null && best>0 && best<=time) return BestTimeSave.COVERED;
        return publishBestTime(key,time);
    }

    /** Retry a frozen Mini 4WD result without publishing this window's other profile fields. */
    public synchronized MiniTimeSave retryMiniTime(String key,double time) {
        if(key==null || !key.startsWith("mini4wd:") || !Double.isFinite(time) || time<=0) return MiniTimeSave.FAILED;
        return switch(publishBestTime(key,time)) {
            case SAVED -> MiniTimeSave.SAVED;
            case COVERED -> MiniTimeSave.COVERED;
            case FAILED -> MiniTimeSave.FAILED;
        };
    }

    private BestTimeSave publishBestTime(String key,double time) {
        var target=directory.resolve("profile.json");
        var outcome=BestTimeSave.FAILED;
        diagnostics.begin();
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(directory);
            try(var channel=ArchiveWriter.openLock(directory.resolve("profile.lock"));var held=channel.tryLock()) {
                if(held==null) throw new IOException("Profile is busy");
                var current=latestProfile(target);
                var disk=current.disk();
                var best=disk.bestTimes==null?null:disk.bestTimes.get(key);
                if(best!=null && Double.isFinite(best) && best>0 && best<=time) {
                    ArchiveWriter.verifyStamp(target,current.stamp(),LinkOption.NOFOLLOW_LINKS);
                    profile.bestTimes.put(key,best);
                    outcome=BestTimeSave.COVERED;
                } else {
                    var records=profileMap(current.json(),"bestTimes");
                    records.addProperty(key,time);
                    current.json().add("bestTimes",records);
                    publishProfileField(target,current);
                    profile.bestTimes.put(key,time);
                    outcome=BestTimeSave.SAVED;
                }
                warning="";
            }
        } catch(IOException | RuntimeException error) {
            if(outcome!=BestTimeSave.FAILED) diagnostics.cleanupFailed(error);
            else {
                diagnostics.failed(error);
                warning=error instanceof ArchiveWriter.StaleArchiveException?"保存时配置又有更新，可重新核对并重试。"
                        :"无法核对或保存当前配置，原记录保留。";
            }
        }
        return outcome;
    }
    private static CourseReport readCourseResult(PracticeSession session,long recordedAt) {
        if(session.state!=PracticeSession.State.SUCCESS) return null;
        var report=new CourseReport();report.elapsed=session.elapsed;report.entrySpeed=session.entrySpeed;report.exitSpeed=session.exitSpeed;
        report.recordedAt=recordedAt;report.lapTimes=session.consistency().laps();report.spread=session.consistency().spread();
        if(session.startPractice()!=null) { report.reaction=session.startPractice().reaction();report.acceleration=session.startPractice().accelerationTime(); }
        return report.valid()?report:null;
    }

    public synchronized boolean recordCourse(String key,PracticeSession session) {
        var report=readCourseResult(session,System.currentTimeMillis());
        if(report==null) return false;
        var previous=new LinkedHashMap<>(profile.courseReports);
        rememberCourse(key,report,true);
        if(save()) return true;
        profile.courseReports=previous;return false;
    }

    private record BrakingReading(String key,double distance,double entrySpeed,long recordedAt) {}

    public static final class SessionCompletion {
        private final String id,bestKey,courseKey;
        private final double elapsed;
        private final CourseReport course;
        private final BrakingReading braking;

        private SessionCompletion(String id,String bestKey,double elapsed,String courseKey,CourseReport course,BrakingReading braking) {
            if(id==null || !id.matches("[a-f0-9-]{36}") || bestKey==null || bestKey.isBlank()
                    || !Double.isFinite(elapsed) || elapsed<=0) throw new IllegalArgumentException("Invalid session completion");
            this.id=id;
            this.bestKey=bestKey;
            this.elapsed=elapsed;
            this.courseKey=courseKey;
            this.course=course;
            this.braking=braking;
        }
        public String id() {return id;}
        public double elapsed() {return elapsed;}
        public static SessionCompletion course(String id,String bestKey,String courseKey,String brakingKey,
                                               PracticeSession session,long recordedAt) {
            var report=readCourseResult(session,recordedAt);
            if(report==null || courseKey==null || courseKey.isBlank()) throw new IllegalArgumentException("Invalid course completion");
            var braking=brakingKey!=null && session.lesson.braking() && Float.isFinite(session.stopDistance)
                    && session.stopDistance>=0 && session.entrySpeed>0
                    ?new BrakingReading(brakingKey,session.stopDistance,session.entrySpeed,recordedAt):null;
            return new SessionCompletion(id,bestKey,report.elapsed,courseKey,report,braking);
        }
        public static SessionCompletion challenge(String id,String bestKey,double elapsed) {
            return new SessionCompletion(id,bestKey,elapsed,null,null,null);
        }
    }

    public enum CompletionSave { BEST,SAVED,FAILED }

    public synchronized CompletionSave saveCompletion(SessionCompletion result) {
        Objects.requireNonNull(result);
        var target=directory.resolve("profile.json");
        var outcome=CompletionSave.FAILED;
        diagnostics.begin();
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(directory);
            try(var channel=ArchiveWriter.openLock(directory.resolve("profile.lock"));var held=channel.tryLock()) {
                if(held==null) throw new IOException("Profile is busy");
                var current=latestProfile(target);
                var disk=current.disk();
                var receipts=completionReceipts(disk.completionReceipts);
                if(receipts.contains(result.id)) {
                    ArchiveWriter.verifyStamp(target,current.stamp(),LinkOption.NOFOLLOW_LINKS);
                    outcome=CompletionSave.SAVED;
                } else {
                    Double previous=disk.bestTimes==null?null:disk.bestTimes.get(result.bestKey);
                    boolean faster=previous==null || !Double.isFinite(previous) || previous<=0 || result.elapsed<previous;
                    double best=faster?result.elapsed:previous;
                    if(faster) putNumber(current.json(),"bestTimes",result.bestKey,best);
                    var report=addCourseReading(current,result);
                    var braking=addBrakingReading(current,result.braking);
                    int count=(int)Math.min((long)Math.max(0,disk.completedAttempts)+1,Integer.MAX_VALUE);
                    current.json().addProperty("completedAttempts",count);
                    receipts.add(result.id);
                    if(receipts.size()>MAX_COMPLETION_RECEIPTS) receipts.remove(receipts.iterator().next());
                    current.json().add("completionReceipts",gson.toJsonTree(receipts));

                    publishProfileField(target,current);
                    outcome=faster?CompletionSave.BEST:CompletionSave.SAVED;
                    profile.completedAttempts=count;
                    profile.completionReceipts=List.copyOf(receipts);
                    profile.bestTimes.put(result.bestKey,best);
                    if(report!=null) rememberCourse(result.courseKey,report,report==result.course);
                    if(braking!=null) {
                        profile.brakingDistances.put(braking.key(),braking.distance());
                        profile.brakingEntrySpeeds.put(braking.key(),braking.entrySpeed());
                        profile.brakingRecordedAt.put(braking.key(),braking.recordedAt());
                    }
                }
                warning="";
            }
        } catch(IOException | RuntimeException error) {
            if(outcome!=CompletionSave.FAILED) diagnostics.cleanupFailed(error);
            else {
                diagnostics.failed(error);
                warning=error instanceof ArchiveWriter.StaleArchiveException?"保存时配置又有更新，可重试原成绩。"
                        :"课程或挑战成绩尚未保存，可重试原成绩。";
            }
        }
        return outcome;
    }

    private static LinkedHashSet<String> completionReceipts(List<String> previous) {
        var receipts=new LinkedHashSet<String>();
        if(previous!=null) {
            for(int i=Math.max(0,previous.size()-MAX_COMPLETION_RECEIPTS);i<previous.size();i++) {
                var id=previous.get(i);
                if(id!=null && id.matches("[a-f0-9-]{36}")) receipts.add(id);
            }
        }
        return receipts;
    }

    private CourseReport addCourseReading(ProfileDocument current,SessionCompletion result) {
        if(result.course==null) return null;
        var disk=current.disk().courseReports;
        var old=disk==null?null:disk.get(result.courseKey);
        if(old!=null && old.valid() && old.recordedAt>result.course.recordedAt) return old;
        var reports=profileMap(current.json(),"courseReports");
        reports.remove(result.courseKey);
        reports.add(result.courseKey,gson.toJsonTree(result.course));
        while(reports.size()>MAX_COURSE_REPORTS) reports.remove(reports.keySet().iterator().next());
        current.json().add("courseReports",reports);
        return result.course;
    }

    private static BrakingReading addBrakingReading(ProfileDocument current,BrakingReading reading) {
        if(reading==null) return null;
        String key=reading.key();
        var disk=current.disk();
        var recorded=disk.brakingRecordedAt;
        var previous=recorded==null?null:recorded.get(key);
        if(previous!=null && previous>reading.recordedAt()) {
            var distance=disk.brakingDistances==null?null:disk.brakingDistances.get(key);
            var speed=disk.brakingEntrySpeeds==null?null:disk.brakingEntrySpeeds.get(key);
            return distance==null || speed==null?null:new BrakingReading(key,distance,speed,previous);
        }
        putNumber(current.json(),"brakingDistances",key,reading.distance());
        putNumber(current.json(),"brakingEntrySpeeds",key,reading.entrySpeed());
        putNumber(current.json(),"brakingRecordedAt",key,reading.recordedAt());
        return reading;
    }

    private static void putNumber(JsonObject document,String field,String key,Number number) {
        var map=profileMap(document,field);
        map.addProperty(key,number);
        document.add(field,map);
    }

    private void rememberCourse(String key,CourseReport report,boolean latest) {
        if(latest) profile.courseReports.remove(key);
        profile.courseReports.put(key,report);
        while(profile.courseReports.size()>MAX_COURSE_REPORTS) profile.courseReports.remove(profile.courseReports.keySet().iterator().next());
    }

    public synchronized void recordScore(String key, int score) {
        Integer old = profile.bestScores.get(key);
        profile.bestScores.merge(key, Math.max(0, score), Math::max);
        if (save()) return;
        if (old == null) profile.bestScores.remove(key);
        else profile.bestScores.put(key, old);
    }
}
