package com.f1car.persistence;

import com.f1car.model.EventSeries;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Explicit snapshots of completed local events. Active progress and awards live in their own stores. */
public final class EventHistoryStore {
    public static final int CAPACITY=12;
    private static final int MAX_BYTES=524_288;

    public record Entry(long savedAt,EventSeries.State event) {
        public Entry {
            if(savedAt<0 || event==null || event.phase()!=EventSeries.Phase.COMPLETE)
                throw new IllegalArgumentException("An archive entry requires a completed event");
        }
        public String id() {return event.eventId();}
    }

    private final Path file,lock;
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("completed-events");
    private final ArchiveWriter.Filesystem filesystem;
    private List<Entry> entries=List.of();
    private ArchiveWriter.Stamp expected=ArchiveWriter.ABSENT;
    private boolean writable=true;
    private String warning="";

    public EventHistoryStore(Path directory) {
        this(directory,ArchiveWriter.FORCED_FILES);
    }

    EventHistoryStore(Path directory,ArchiveWriter.Filesystem filesystem) {
        file=directory.resolve("event-history.json");
        lock=file.resolveSibling("event-history.lock");
        this.filesystem=Objects.requireNonNull(filesystem);
        reload();
    }

    public Path file() {return file;}
    public List<Entry> entries() {return entries;}
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public Entry find(String id) {
        return entries.stream().filter(entry->entry.id().equals(id)).findFirst().orElse(null);
    }

    public boolean reload() {
        try {
            if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) {
                entries=List.of();
                expected=ArchiveWriter.ABSENT;
            }else {
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid event archive");
                byte[] bytes;
                try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                    bytes=input.readNBytes(MAX_BYTES+1);
                }
                if(bytes.length>MAX_BYTES) throw new IOException("Event archive exceeds bound");
                var stamp=ArchiveWriter.stamp(bytes);
                if(!stamp.equals(expected)) {
                    entries=decode(bytes);
                    expected=stamp;
                }
            }
            writable=true;
            warning="";
            return true;
        }catch(IOException | ReflectiveOperationException | RuntimeException error) {
            writable=false;
            warning="赛事档案无法读取，已打开的档案和原文件保留。可在恢复文件后重新读取。";
            return false;
        }
    }

    public boolean add(EventSeries.State event,long savedAt) {
        if(!writable) return false;
        if(event==null || event.phase()!=EventSeries.Phase.COMPLETE || savedAt<0) {
            warning="可以收录已经保存的完整赛事。";
            return false;
        }
        var old=find(event.eventId());
        if(old!=null) {
            if(!old.event().equals(event)) {
                warning="此赛事编号已有不同内容，原档案保留。";
                return false;
            }
            try {
                ArchiveWriter.verifyStamp(file,expected,LinkOption.NOFOLLOW_LINKS);
                warning="";
                return true;
            }catch(IOException error) {
                return stale();
            }
        }
        if(entries.size()>=CAPACITY) {
            warning="赛事档案已满。可先移除一项不需要的档案。";
            return false;
        }
        var next=new ArrayList<Entry>(entries.size()+1);
        next.add(new Entry(savedAt,event));
        next.addAll(entries);
        return commit(List.copyOf(next));
    }

    public boolean remove(Entry reviewed) {
        if(!writable) return false;
        if(reviewed==null || !reviewed.equals(find(reviewed.id()))) {
            warning="此赛事档案已变化，请重新选择。";
            return false;
        }
        return commit(entries.stream().filter(entry->!entry.equals(reviewed)).toList());
    }

    private boolean stale() {
        writable=false;
        warning="磁盘赛事档案已变化，请重新读取后再操作。";
        return false;
    }

    private boolean commit(List<Entry> next) {
        diagnostics.begin();
        boolean published=false;
        try {
            byte[] encoded=encode(next);
            diagnostics.encoded(encoded.length);
            if(encoded.length>MAX_BYTES) throw new IOException("Event archive exceeds bound");
            var nextStamp=ArchiveWriter.stamp(encoded);
            var previous=expected;
            diagnostics.at(SaveDiagnostics.Stage.PREPARE);
            filesystem.directories(file.toAbsolutePath().getParent());
            try(var channel=ArchiveWriter.openLock(lock);var held=channel.tryLock()) {
                if(held==null) throw new IOException("Event archive is busy");
                ArchiveWriter.bytes(file,encoded,MAX_BYTES,"event-history-",diagnostics,filesystem,
                        target->ArchiveWriter.verifyStamp(target,previous,LinkOption.NOFOLLOW_LINKS));
                entries=next;
                expected=nextStamp;
                warning="";
                published=true;
            }
            return true;
        }catch(ArchiveWriter.StaleArchiveException error) {
            diagnostics.failed(error);
            return stale();
        }catch(IOException | RuntimeException error) {
            if(published) {
                diagnostics.cleanupFailed(error);
                return true;
            }
            diagnostics.failed(error);
            warning="赛事档案尚未保存，原档案和赛事进度保留。";
            return false;
        }
    }

    private static byte[] encode(List<Entry> entries) {
        var root=new JsonObject();
        root.addProperty("version",1);
        var array=new JsonArray();
        for(var entry:entries) {
            var item=new JsonObject();
            item.addProperty("savedAt",entry.savedAt());
            item.add("event",EventRecoveryCodec.stateTree(entry.event()));
            array.add(item);
        }
        root.add("entries",array);
        RecoveryJson.unicode(root);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<Entry> decode(byte[] bytes) throws IOException,ReflectiveOperationException {
        var source=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        try(var reader=new JsonReader(new StringReader(source))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            boolean versionRead=false;
            List<Entry> entries=null;
            while(reader.hasNext()) {
                switch(reader.nextName()) {
                    case "version" -> {
                        if(versionRead || readInteger(reader)!=1) throw new IOException("Invalid event archive version");
                        versionRead=true;
                    }
                    case "entries" -> {
                        if(entries!=null) throw new IOException("Duplicate event archive entries");
                        entries=readEntries(reader);
                    }
                    default -> throw new IOException("Unknown event archive field");
                }
            }
            reader.endObject();
            if(!versionRead || entries==null || reader.peek()!=JsonToken.END_DOCUMENT)
                throw new IOException("Invalid event archive envelope");
            return entries;
        }
    }

    private static List<Entry> readEntries(JsonReader reader) throws IOException,ReflectiveOperationException {
        var entries=new ArrayList<Entry>();
        var ids=new HashSet<String>();
        reader.beginArray();
        while(reader.hasNext()) {
            if(entries.size()>=CAPACITY) throw new IOException("Too many archived events");
            Long savedAt=null;
            EventSeries.State event=null;
            reader.beginObject();
            while(reader.hasNext()) {
                switch(reader.nextName()) {
                    case "savedAt" -> {
                        if(savedAt!=null) throw new IOException("Duplicate archive date");
                        savedAt=readInteger(reader);
                    }
                    case "event" -> {
                        if(event!=null) throw new IOException("Duplicate event state");
                        event=EventRecoveryCodec.readState(reader);
                    }
                    default -> throw new IOException("Unknown archived event field");
                }
            }
            reader.endObject();
            if(savedAt==null || event==null) throw new IOException("Incomplete archived event");
            var entry=new Entry(savedAt,event);
            if(!ids.add(entry.id())) throw new IOException("Duplicate archived event");
            entries.add(entry);
        }
        reader.endArray();
        return List.copyOf(entries);
    }

    private static long readInteger(JsonReader reader) throws IOException {
        if(reader.peek()!=JsonToken.NUMBER) throw new IOException("Expected event archive number");
        return RecoveryJson.number(reader.nextString()).getAsBigDecimal().longValueExact();
    }
}
