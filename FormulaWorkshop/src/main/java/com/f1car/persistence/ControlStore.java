package com.f1car.persistence;

import com.f1car.input.ControlSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

/** Controller settings are independent of driving progress; failed saves remain retryable. */
public class ControlStore {
    private static final String NEWER = "手柄设置来自较新版本。当前窗口的设置仅在本次运行生效；移走 controls.json 后可重试保存。";
    public static final int LIMIT=32*1024;
    private final GuardedArchive archive;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private ControlSettings settings=ControlSettings.defaults();
    private boolean dirty,damaged,newer;
    public String warning="";
    public ControlStore(Path directory) {
        var filesystem = new ArchiveWriter.Filesystem() {
            @Override public void write(Path path, byte[] bytes) throws IOException {ArchiveWriter.writeAndForce(path, bytes);}
            @Override public void atomic(Path temporary, Path target) throws IOException {ControlStore.this.replace(temporary, target);}
        };
        archive = new GuardedArchive(directory, "controls", LIMIT, new SaveDiagnostics("controls"), filesystem, "controls-damaged-");
        try {
            var loaded = archive.readVersioned(gson, ControlSettings.class, settings.version());
            if (loaded != null) settings = loaded;
        } catch(GuardedArchive.NewerVersionException unsupported) {
            newer=true;warning=NEWER;
        } catch(IOException | RuntimeException error) {settings=ControlSettings.defaults();damaged=true;warning="手柄设置无法读取，已使用键盘；原文件保留。";}
    }
    public ControlSettings settings() { return settings; }
    public boolean dirty() { return dirty; }
    public boolean set(ControlSettings value) { settings=Objects.requireNonNull(value);dirty=true;return flush(); }
    public boolean flush() {
        if(!dirty) return true;
        try {
            if(newer && !archive.acceptMissingSource()) {warning=NEWER;return false;}
            if(damaged) archive.acceptMissingSource();
            archive.commit(gson.toJson(settings), damaged);
            damaged=false;newer=false;dirty=false;warning="";return true;
        } catch (ArchiveWriter.StaleArchiveException error) {
            warning = "磁盘手柄设置已更新，本次校准仍在当前窗口。";
            return false;
        } catch(IOException | RuntimeException error) {
            warning=damaged && archive.observedStamp()==null
                    ?"无法核对 controls.json。当前窗口已保留校准设置。可将原文件移到别处，再重试保存。"
                    :"手柄设置尚未保存，当前进程仍保留设置，可重试保存。";
            return false;
        }
    }
    protected void replace(Path temporary,Path target) throws IOException {
        try { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException error) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
    }
}
