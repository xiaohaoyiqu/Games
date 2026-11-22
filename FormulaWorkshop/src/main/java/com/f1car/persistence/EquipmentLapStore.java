package com.f1car.persistence;

import com.f1car.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Explicit local collection, independent of telemetry books. Failed captures retain the exact candidate. */
public final class EquipmentLapStore {
    public static final int CAPACITY=8,ENTRY_BYTES=4_000_000,MAX_BYTES=24_000_000;
    private static final String STALE="装备圈资料库已由另一窗口更新，请重新读取，待存记录保留。";
    private final Path file;
    private final GuardedArchive archive;
    private List<EquipmentLap> entries=List.of();
    private EquipmentLap pending;
    private boolean writable=true, stale;
    private String warning="";
    public EquipmentLapStore(Path directory) {
        file=directory.resolve("equipment-laps.json");
        archive=new GuardedArchive(directory,"equipment-laps",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}public List<EquipmentLap> entries() {return entries;}public EquipmentLap pending() {return pending;}public boolean writable() {return writable;}public String warning() {return warning;}
    public EquipmentLap find(String id) {return entries.stream().filter(e->e.id().equals(id)).findFirst().orElse(null);}
    public boolean capture(EquipmentLap value) {
        Objects.requireNonNull(value);if(pending!=null && !pending.equals(value)) {warning="先重试或取消上一份待存装备圈；原候选保持。";return false;}
        var old=find(value.id());
        if(old!=null && pending==null) return matchSaved(value,old);
        pending=value;return retry();
    }
    public boolean retry() {
        if(pending==null) return true;
        if(!writable) {warning="记录库读取受阻，请修复后重读。";return false;}
        var known=find(pending.id());
        if(known!=null) {
            if(!matchSaved(pending,known)) return false;
            pending=null;
            return true;
        }
        try {
            if(entries.size()>=CAPACITY) {
                archive.verifyAccepted();
                warning="最多八份装备圈，待存原文保留，请先移除一份再重试。";
                return false;
            }
            if(ArchiveWriter.utf8Size(writer -> EquipmentLapJson.JSON.toJson(pending, writer)) > ENTRY_BYTES)
                throw new IOException("Equipment lap exceeds bound");
            var next=new ArrayList<>(entries);
            next.add(pending);
            write(next);
            pending=null;
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error,"装备圈尚未收录，原候选保留，可明确重试或取消。");
        }
    }
    private boolean matchSaved(EquipmentLap value,EquipmentLap old) {
        if(!old.equals(value)) {warning="同身份装备圈内容冲突，原记录保持。";return false;}
        if(!writable) return false;
        try {
            archive.verifyAccepted();
            stale=false;
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error,"无法核对已存装备圈，请检查资料库后重试。");
        }
    }
    public void cancelPending() {pending=null;warning=writable?"":"记录库读取受阻；修复后明确重读。";}
    public boolean remove(EquipmentLap expected) {
        if(expected==null || expected!=find(expected.id())) {warning="记录已变化，请刷新后重新查看。";return false;}
        if(!writable) return false;
        try {
            write(entries.stream().filter(e->e!=expected).toList());
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error,"未能移除装备圈，原记录保持。");
        }
    }
    private void write(List<EquipmentLap> next) throws IOException {
        archive.commit(output->EquipmentLapJson.writeArchive(output,next,
                (writer,row)->EquipmentLapJson.JSON.toJson(row,EquipmentLap.class,writer)),false);
        entries=List.copyOf(next);
        stale=false;
        warning="";
    }
    public boolean reload() {
        if(pending!=null && writable && !stale) {warning="待存候选仍在内存中；先重试或明确取消，再重新读取。";return false;}
        try {
            var loaded=archive.readStream(input->EquipmentLapJson.readArchive(input,CAPACITY,EquipmentLapJson::decode,EquipmentLap::id));
            entries=loaded==null?List.of():loaded;
            writable=true;
            stale=false;
            warning=pending==null?"":"已重读最新装备圈，待存记录可在核对后重试。";
            return true;
        }
        catch(IOException | RuntimeException error) {writable=false;warning="装备圈记录库无法读取；原文件和已读内容保留，修复后明确重读。";return false;}
    }
    private boolean rejected(Exception error,String failure) {
        if(error instanceof ArchiveWriter.StaleArchiveException) {
            stale=true;
            warning=STALE;
        } else warning=failure;
        return false;
    }
}
