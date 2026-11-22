package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Disk-first commits keep the visible library and original file intact on failure. */
public final class SetupLibraryStore {
    public static final int MAX_ENTRIES=32,MAX_LIBRARY_BYTES=262144,MAX_SHARE_BYTES=16384;
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final String RELOAD_WARNING="磁盘上的配置库已改变，请重新读取配置库后再操作。";
    private record Library(int version,List<SetupCard> entries) { }
    private record Share(String kind,int version,SetupCard setup) { }
    private final Path file,lock;
    private ArchiveWriter.Stamp accepted;
    private List<SetupCard> entries=List.of();
    private boolean writable=true;
    private String warning="";
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("setup-library");
    public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}
    public SetupLibraryStore(Path directory) {
        file=directory.resolve("setup-library.json");
        lock=file.resolveSibling("setup-library.lock");
        reload();
    }
    public List<SetupCard> entries() { return entries; }
    public boolean writable() { return writable; }
    public String warning() { return warning; }
    public SetupCard find(String id) {
        if(id!=null) for(var card:entries) if(card.id().equals(id)) return card;
        return null;
    }
    public List<SetupCard> forTrack(TrackLayout track) { return entries.stream().filter(e->e.sameTrack(track)).toList(); }
    public synchronized void reload() {
        try {
            byte[] bytes=readBytes(file,MAX_LIBRARY_BYTES,LinkOption.NOFOLLOW_LINKS);
            var stamp=ArchiveWriter.stamp(bytes);
            if(!stamp.equals(accepted)) {
                var root=object(decode(bytes),"version","entries");
                int version=integer(root,"version");if(version<1 || version>3 || !root.get("entries").isJsonArray()) throw new IOException("Invalid library format");
                var values=new ArrayList<SetupCard>();var ids=new HashSet<String>();
                for(var element:root.getAsJsonArray("entries")) {
                    if(values.size()>=MAX_ENTRIES) throw new IOException("Setup library exceeds 32 entries");
                    var card=card(element,version);if(!ids.add(card.id())) throw new IOException("Duplicate setup identity");values.add(card);
                }
                if(!entries.equals(values)) entries=List.copyOf(values);
            }
            accepted=stamp;
            writable=true;
            warning="";
        } catch(NoSuchFileException missing) {
            entries=List.of();
            accepted=ArchiveWriter.ABSENT;
            writable=true;
            warning="";
        } catch(IOException | RuntimeException error) { writable=false;warning="配置库无法读取，上次读取的列表保留；恢复文件后点击重新读取。"; }
    }
    public synchronized boolean verifySelection(SetupCard selected,SetupCard... reviewed) {
        if(!writable) return false;
        if(selected==null || !selected.equals(find(selected.id()))) {
            warning="所选配置已变化，请重新核对。";
            return false;
        }
        for(var card:reviewed) {
            if(card!=null && !card.equals(find(card.id()))) {
                warning="所选配置已变化，请重新核对。";
                return false;
            }
        }
        try {
            ArchiveWriter.verifyStamp(file,accepted,LinkOption.NOFOLLOW_LINKS);
            warning="";
            return true;
        } catch(ArchiveWriter.StaleArchiveException changed) {
            warning=RELOAD_WARNING;
        } catch(IOException | RuntimeException error) {
            warning="暂时无法核对磁盘配置库，请重新读取。";
        }
        return false;
    }
    public synchronized boolean save(SetupCard card) {
        Objects.requireNonNull(card);var next=new ArrayList<>(entries);int index=-1;
        for(int i=0;i<next.size();i++) if(next.get(i).id().equals(card.id())) index=i;
        if(index<0) { if(next.size()>=MAX_ENTRIES) { warning="配置库已满（32 份），请先删除不需要的配置。";return false; }next.add(0,card); }
        else next.set(index,card);
        return commit(next);
    }
    public synchronized boolean editText(SetupCard reviewed,SetupCard candidate) {
        if(!reviewed.id().equals(candidate.id()) || !reviewed.trackName().equals(candidate.trackName())
                || !reviewed.sameConfiguration(candidate)) throw new IllegalArgumentException("Only setup text may be edited");
        if(!reviewed.equals(find(reviewed.id()))) {
            warning="这份配置已变化，请重新核对名称与备注。";
            return false;
        }
        return save(candidate);
    }
    public synchronized boolean remove(String id) { return commit(entries.stream().filter(e->!e.id().equals(id)).toList()); }
    private boolean commit(List<SetupCard> next) {
        if(!writable) return false;
        diagnostics.begin();
        boolean published=false;
        var base=accepted;
        try {
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            Files.createDirectories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Setup library is busy");
                if(entries.equals(next)) ArchiveWriter.verifyStamp(file,base,LinkOption.NOFOLLOW_LINKS);
                else {
                    accepted=ArchiveWriter.utf8(file,MAX_LIBRARY_BYTES,"setup-library-",diagnostics,ArchiveWriter.FORCED_FILES,
                            target->ArchiveWriter.verifyStamp(target,base,LinkOption.NOFOLLOW_LINKS),
                            writer->JSON.toJson(new Library(3,next),writer));
                    entries=List.copyOf(next);
                }
                warning="";
                published=true;
            }
            return true;
        } catch(IOException | RuntimeException error) {
            if(published) {diagnostics.cleanupFailed(error);return true;}
            diagnostics.failed(error);
            warning=error instanceof ArchiveWriter.StaleArchiveException
                    ?RELOAD_WARNING
                    :"配置库尚未保存，原列表与文件保留；检查目录后可重试。";
            return false;
        }
    }
    public static SetupCard readShare(Path path) throws IOException {
        try {
            var root=object(read(path,MAX_SHARE_BYTES),"kind","version","setup");
            int version=integer(root,"version");if(!"formula-workshop-setup".equals(string(root,"kind")) || version<1 || version>3) throw new IOException("Unsupported setup format");
            return card(root.get("setup"),version);
        } catch(IllegalArgumentException | IllegalStateException error) { throw new IOException("Invalid setup file",error); }
    }
    public synchronized SetupCard importCard(SetupCard incoming) {
        // Repeated import is idempotent even after a local rename or note edit.
        var existing=find(incoming.id());
        if(existing!=null) {
            if(!verifySelection(existing)) return null;
            if(!existing.sameConfiguration(incoming)) { warning="已有同身份的不同配置，请先检查文件来源；本地配置未覆盖。";return null; }
            warning="";return existing;
        }
        return save(incoming)?incoming:null;
    }
    public void exportCard(SetupCard card,Path path) throws IOException {
        exportCard(card,path,true);
    }
    public void exportCard(SetupCard card,Path path,boolean replace) throws IOException {
        if(Files.isSymbolicLink(path)) throw new IOException("Cannot replace a symbolic link with a setup exchange file");
        var absolute=path.toAbsolutePath().normalize();
        boolean exists=Files.exists(path);
        for(var owned:List.of(file,lock)) {
            if(absolute.equals(owned.toAbsolutePath().normalize())
                    || exists && Files.exists(owned) && Files.isSameFile(path,owned))
                throw new IOException("Cannot replace the setup library or lock with an exchange file");
        }
        var diagnostics=new SaveDiagnostics("named-setup-export");
        diagnostics.begin();
        ArchiveWriter.strictText(path,JSON.toJson(new Share("formula-workshop-setup",3,card)),MAX_SHARE_BYTES,
                "setup-export-",diagnostics,replace?ArchiveWriter.FORCED_FILES:ArchiveWriter.NEW_FILES);
    }
    private static SetupCard card(JsonElement value,int version) throws IOException {
        var o=object(value,"id","name","notes","vehicle","physics","trackId","trackName","geometry","controls","tuning");
        var t=switch(version) {
            case 1 -> object(o.get("tuning"),"power","brakes","suspension","wing","livery");
            case 2 -> object(o.get("tuning"),"power","brakes","suspension","wing","livery","brakeBias");
            default -> object(o.get("tuning"),"power","brakes","suspension","wing","livery","brakeBias","frontSpring","rearSpring","frontDamping","rearDamping");
        };
        // Validate raw values before Tuning's normal UI clamping can normalize invalid imports.
        float power=number(t,"power",.82,1.12),brakes=number(t,"brakes",.75,1.25),suspension=number(t,"suspension",.75,1.3),wing=number(t,"wing",.65,1.4);
        int livery=integer(t,"livery");if(livery<0 || livery>4) throw new IOException("Invalid livery");
        float bias=version==1?.5f:number(t,"brakeBias",.35,.65);
        float fs=version<3?1:axisNumber(t,"frontSpring"),rs=version<3?1:axisNumber(t,"rearSpring"),fd=version<3?1:axisNumber(t,"frontDamping"),rd=version<3?1:axisNumber(t,"rearDamping");
        return new SetupCard(string(o,"id"),string(o,"name"),string(o,"notes"),string(o,"vehicle"),string(o,"physics"),string(o,"trackId"),string(o,"trackName"),string(o,"geometry"),string(o,"controls"),new Tuning(power,brakes,suspension,wing,livery,bias,fs,rs,fd,rd));
    }
    private static float axisNumber(JsonObject o,String key) throws IOException {
        float value=number(o,key,.8,1.2);double raw=o.get(key).getAsDouble()*100;
        if(Math.abs(raw-Math.rint(raw))>.000001) throw new IOException("Axis factor requires one-percent resolution: "+key);
        return value;
    }
    private static JsonObject object(JsonElement value,String... fields) throws IOException {
        if(value==null || !value.isJsonObject()) throw new IOException("Expected JSON object");var o=value.getAsJsonObject();
        if(!o.keySet().equals(Set.of(fields))) throw new IOException("Unexpected or missing fields");return o;
    }
    private static String string(JsonObject o,String key) throws IOException {
        var v=o.get(key);if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new IOException("Expected text: "+key);return v.getAsString();
    }
    private static int integer(JsonObject o,String key) throws IOException {
        var v=o.get(key);if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IOException("Expected integer: "+key);
        try { return v.getAsBigDecimal().intValueExact(); }catch(ArithmeticException error) { throw new IOException("Invalid integer: "+key,error); }
    }
    private static float number(JsonObject o,String key,double min,double max) throws IOException {
        var v=o.get(key);if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IOException("Expected number: "+key);
        double raw=v.getAsDouble();if(!Double.isFinite(raw) || raw<min || raw>max) throw new IOException("Out of range: "+key);return (float)raw;
    }
    private static JsonElement read(Path path,int limit) throws IOException {
        return decode(readBytes(path,limit));
    }
    private static byte[] readBytes(Path path,int limit,OpenOption... options) throws IOException {
        byte[] bytes;
        try(var stream=Files.newInputStream(path,options)) {bytes=stream.readNBytes(limit+1);}
        if(bytes.length>limit) throw new IOException("Setup file too large");
        return bytes;
    }
    private static JsonElement decode(byte[] bytes) throws IOException {
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        String text=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        try(var reader=new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);var result=readValue(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON data");return result;
        }
    }
    private static JsonElement readValue(JsonReader reader,int depth) throws IOException {
        if(depth>8) throw new IOException("Setup nesting limit");
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();var o=new JsonObject();while(reader.hasNext()) { String key=reader.nextName();if(o.has(key)) throw new IOException("Duplicate field: "+key);o.add(key,readValue(reader,depth+1)); }reader.endObject();yield o;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();var a=new JsonArray();while(reader.hasNext()) { if(a.size()>=MAX_ENTRIES) throw new IOException("Too many entries");a.add(readValue(reader,depth+1)); }reader.endArray();yield a;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> { String number=reader.nextString();if(number.length()>32) throw new IOException("Invalid number length");yield new JsonPrimitive(new java.math.BigDecimal(number)); }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull();yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid JSON value");
        };
    }
    private static void write(Path path,String text,int limit) throws IOException {
        var diagnostics=new SaveDiagnostics("setup-export");diagnostics.begin();ArchiveWriter.strictText(path,text,limit,"setup-library-",diagnostics);
    }
}
