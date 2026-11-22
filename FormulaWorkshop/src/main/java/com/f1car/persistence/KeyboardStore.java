package com.f1car.persistence;

import com.f1car.input.KeyboardSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Objects;

public final class KeyboardStore {
    private static final String NEWER = "键位文件来自较新版本。当前窗口的改键仅在本次运行生效；移走 keyboard.json 后可重试保存。";
    private final GuardedArchive archive;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private KeyboardSettings settings=KeyboardSettings.defaults();
    private boolean damaged,dirty,newer;
    public String warning="";
    public KeyboardStore(Path directory) {
        archive = new GuardedArchive(directory, "keyboard", 16_384, new SaveDiagnostics("keyboard"),
                ArchiveWriter.FORCED_FILES, "keyboard-damaged-");
        try {
            var loaded = archive.readVersioned(gson, KeyboardSettings.class, settings.version());
            if (loaded != null) settings = loaded;
        } catch(GuardedArchive.NewerVersionException unsupported) {
            newer=true;warning=NEWER;
        } catch(IOException | RuntimeException error) {damaged=true;warning="键位文件无法读取，使用默认键位；原文件已保留。";}
    }
    public KeyboardSettings settings() { return settings; }
    public boolean dirty() { return dirty; }
    public boolean set(KeyboardSettings value) { settings=Objects.requireNonNull(value);dirty=true;return flush(); }
    public boolean flush() {
        if(!dirty) return true;
        try {
            if(newer && !archive.acceptMissingSource()) {warning=NEWER;return false;}
            if(damaged) archive.acceptMissingSource();
            archive.commit(gson.toJson(settings), damaged);
            damaged=false;newer=false;dirty=false;warning="";return true;
        } catch (ArchiveWriter.StaleArchiveException error) {
            warning = "磁盘键位已更新，本次绑定仍在当前窗口生效。";
            return false;
        } catch(IOException | RuntimeException error) {
            warning=damaged && archive.observedStamp()==null
                    ?"无法核对 keyboard.json。当前键位在本窗口生效。可将原文件移到别处，再重试保存。"
                    :"键位已在本次运行生效，文件尚未保存，可重试。";
            return false;
        }
    }
}
