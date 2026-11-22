package com.f1car.persistence;

import com.f1car.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Provider records stay outside local measurements. Publishing occurs only after a successful write. */
public final class ReceivedEquipmentLapStore {
    public static final int CAPACITY=8,MAX_BYTES=24_000_000;
    private static final String STALE="接收的装备圈库已由另一窗口更新，请重新读取，待存记录保留。";
    private final Path file;
    private final GuardedArchive archive;
    private List<EquipmentLapShare> entries=List.of();
    private EquipmentLapShare pending;
    private boolean writable=true,stale;
    private String warning="";
    public ReceivedEquipmentLapStore(Path directory) {
        file=directory.resolve("received-equipment-laps.json");
        archive=new GuardedArchive(directory,"received-equipment-laps",MAX_BYTES);
        reload();
    }
    public Path file() {return file;}public List<EquipmentLapShare> entries() {return entries;}public EquipmentLapShare pending() {return pending;}public boolean writable() {return writable;}public String warning() {return warning;}
    public EquipmentLapShare find(String id) {return entries.stream().filter(e->e.id().equals(id)).findFirst().orElse(null);}
    public boolean receive(EquipmentLapShare value) {
        Objects.requireNonNull(value);if(pending!=null && !pending.equals(value)) {warning="先重试或取消原待存接收记录；原文保持。";return false;}
        var old=find(value.id());
        if(old!=null && pending==null) return matchSaved(value,old);
        pending=value;return retry();
    }
    public boolean retry() {
        if(pending==null) return true;
        if(!writable) {warning="接收库读取受阻，请修复后重读。";return false;}
        var known=find(pending.id());
        if(known!=null) {
            if(!matchSaved(pending,known)) return false;
            pending=null;
            return true;
        }
        try {
            if(entries.size()>=CAPACITY) {
                archive.verifyAccepted();
                warning="最多八份收到记录，待存保留，请先移除一份再重试。";
                return false;
            }
            var next=new ArrayList<>(entries);
            next.add(pending);
            write(next);
            pending=null;
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error,"接收记录尚未保存，同份原文可重试或取消。");
        }
    }
    private boolean matchSaved(EquipmentLapShare value,EquipmentLapShare old) {
        if(!old.equals(value)) {warning="同一圈身份包含不同内容或声明，原接收记录保持。";return false;}
        if(!writable) return false;
        try {
            archive.verifyAccepted();
            stale=false;
            warning="";
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error,"无法核对已收录的装备圈，请检查资料库后重试。");
        }
    }
    public void cancelPending() {pending=null;warning=writable?"":"接收库读取受阻；修复后明确重读。";}
    public boolean remove(EquipmentLapShare expected) {
        if(expected==null || expected!=find(expected.id())) {warning="接收记录已变化，请刷新后重选。";return false;}if(!writable) return false;
        try {
            write(entries.stream().filter(e->e!=expected).toList());
            return true;
        } catch(IOException | RuntimeException error) {
            return rejected(error,"移除未保存，原接收记录保持。");
        }
    }
    private void write(List<EquipmentLapShare> next) throws IOException {
        archive.commit(output->EquipmentLapJson.writeArchive(output,next,
                (writer,row)->EquipmentLapJson.JSON.toJson(EquipmentLapShareCodec.tree(row),writer)),false);
        entries=List.copyOf(next);
        stale=false;
        warning="";
    }
    public boolean reload() {
        if(pending!=null && writable && !stale) {warning="待存接收记录仍在内存中；先重试或取消，再明确重读。";return false;}
        try {
            var loaded=archive.readStream(input->EquipmentLapJson.readArchive(input,CAPACITY,EquipmentLapShareCodec::decode,EquipmentLapShare::id));
            entries=loaded==null?List.of():loaded;
            writable=true;
            stale=false;
            warning=pending==null?"":"已重读最新接收记录，待存原文可在核对后重试。";
            return true;
        }
        catch(IOException | RuntimeException error) {writable=false;warning="接收库无法读取；原文件和已读内容保持，修复后明确重读。";return false;}
    }
    private boolean rejected(Exception error,String failure) {
        if(error instanceof ArchiveWriter.StaleArchiveException) {
            stale=true;
            warning=STALE;
        } else warning=failure;
        return false;
    }
}
