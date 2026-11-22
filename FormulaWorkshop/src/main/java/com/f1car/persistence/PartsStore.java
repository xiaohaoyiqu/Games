package com.f1car.persistence;

import com.f1car.model.*;
import com.f1car.model.PartsCatalog.*;
import com.f1car.persistence.PartsPackCodec.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit pack import and plan save; catalogue indexes are immutable and never rebuilt during rendering. */
public final class PartsStore {
    private final Path file;
    private final GuardedArchive archive;
    private List<Pack> packs=List.of();private Saved last;
    private PartsCatalog catalog=new PartsCatalog(PartsPackCodec.builtin(),List.of());
    private boolean writable=true;private String warning="";
    public PartsStore(Path directory) {
        file = directory.resolve("parts-workbench.json");
        archive = new GuardedArchive(directory, "parts-workbench", PartsPackCodec.STORE_BYTES);
        reload();
    }
    public PartsCatalog catalog() {return catalog;}
    public List<Pack> packs() {return packs;}
    public Saved last() {return last;}
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public Path file() {return file;}
    public boolean refreshIfChanged() {
        if(writable) {
            try {archive.verifyAccepted();return true;}
            catch(IOException | RuntimeException changed) { /* Rebuild only after reading a valid library. */ }
        }
        reload();
        return writable;
    }
    public void reload() {
        try {
            byte[] bytes = archive.readSource();
            var loaded = bytes == null ? new Library(List.of(), null)
                    : PartsPackCodec.readLibrary(new ByteArrayInputStream(bytes));
            var next = loaded.packs().equals(packs)?catalog:new PartsCatalog(PartsPackCodec.builtin(), loaded.packs());
            packs = loaded.packs();last = loaded.last();catalog = next;writable = true;warning = "";
        }
        catch(IOException | RuntimeException error) {writable=false;warning="部件资料无法读取；保留原文件与上次目录，恢复后重新读取。";}
    }
    public boolean install(Pack pack,boolean replace) {
        try {return commit(merge(List.of(pack),replace),last);}catch(IllegalArgumentException error) {warning=error.getMessage();return false;}
    }
    public PartsCatalog previewPacks(List<Pack> incoming) {return new PartsCatalog(PartsPackCodec.builtin(),merge(incoming,true));}
    public boolean installPacks(List<Pack> incoming,boolean replace) {try {return commit(merge(incoming,replace),last);}catch(IllegalArgumentException error) {warning=error.getMessage();return false;}}
    public boolean remove(String id) {return commit(packs.stream().filter(pack->!pack.id().equals(id)).toList(),last);}
    public boolean save(String name,PartsBuild build) {return commit(packs,new Saved(name.strip(),build));}
    public PartsCatalog preview(Bundle bundle) {return new PartsCatalog(PartsPackCodec.builtin(),merge(bundle.packs(),true));}
    public boolean importBundle(Bundle bundle) {try {return commit(merge(bundle.packs(),true),bundle.plan());}catch(IllegalArgumentException error) {warning=error.getMessage();return false;}}
    public Bundle bundle(String name,PartsBuild build) {return new Bundle(new Saved(name.strip(),build),packs);}
    private List<Pack> merge(List<Pack> incoming,boolean replace) {
        var next=new LinkedHashMap<String,Pack>();packs.forEach(pack->next.put(pack.id(),pack));
        for(var pack:incoming) {
            if(pack.id().equals("builtin")) throw new IllegalArgumentException("原厂部件不能由玩家包替换。");var old=next.get(pack.id());
            if(old!=null && !old.equals(pack) && !replace) throw new IllegalArgumentException("已有同名部件包；请检查修订并确认替换。");next.put(pack.id(),pack);
        }
        if(next.size()>PartsCatalog.MAX_PACKS) throw new IllegalArgumentException("最多保存 8 个玩家部件包；请先导出并移除不需要的包。");return List.copyOf(next.values());
    }
    private boolean commit(List<Pack> next,Saved saved) {
        if(!writable) return false;
        try {
            var compiled = next.equals(packs) ? catalog : new PartsCatalog(PartsPackCodec.builtin(), next);
            archive.commit(PartsPackCodec.library(next, saved), false);
            packs = List.copyOf(next);last = saved;catalog = compiled;warning = "";return true;
        } catch (ArchiveWriter.StaleArchiveException error) {
            warning = "磁盘上的部件库已更新。请重新读取目录，再核对并重试改动。";
            return false;
        }
        catch(IOException | RuntimeException error) {warning="部件资料尚未保存；原目录与文件保留，检查用户目录后可重试。";return false;}
    }
    public void exportBundle(Bundle bundle,Path target) throws IOException {
        exportBundle(bundle,target,true);
    }
    public void exportBundle(Bundle bundle,Path target,boolean replace) throws IOException {
        if(target.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize()) || Files.exists(target) && Files.exists(file) && Files.isSameFile(target,file)) throw new IOException("Cannot replace parts library with an exchange file");
        writeExport(target,PartsPackCodec.bundle(bundle),PartsPackCodec.BUNDLE_BYTES,replace);
    }
    public void exportPack(Pack pack,Path target) throws IOException {
        exportPack(pack,target,true);
    }
    public void exportPack(Pack pack,Path target,boolean replace) throws IOException {
        if(target.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize()) || Files.exists(target) && Files.exists(file) && Files.isSameFile(target,file)) throw new IOException("Cannot replace parts library");
        writeExport(target,PartsPackCodec.packJson(pack),PartsPackCodec.PACK_BYTES,replace);
    }
    static void writeExport(Path path,String value,int max,boolean replace) throws IOException {
        writeExport(path,value,max,replace,ArchiveWriter.NO_CHECK);
    }
    static void writeExport(Path path,String value,int max,boolean replace,ArchiveWriter.ReplacementCheck check) throws IOException {
        var diagnostics=new SaveDiagnostics("content-export");
        diagnostics.begin();
        ArchiveWriter.bytes(path,value.getBytes(java.nio.charset.StandardCharsets.UTF_8),max,"content-export-",
                diagnostics,replace?ArchiveWriter.FORCED_FILES:ArchiveWriter.NEW_FILES,check);
    }
    static void write(Path path,String value,int max) throws IOException {
        var diagnostics=new SaveDiagnostics("parts-workbench");diagnostics.begin();write(path,value,max,diagnostics);
    }
    static void write(Path path,String value,int max,SaveDiagnostics diagnostics) throws IOException {ArchiveWriter.text(path,value,max,"parts-workbench-",diagnostics);}
}
