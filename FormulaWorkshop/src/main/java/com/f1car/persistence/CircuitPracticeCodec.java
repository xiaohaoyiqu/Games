package com.f1car.persistence;

import com.f1car.model.CircuitDocument;
import com.f1car.persistence.CircuitStore.PracticeSlot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** One editable map and its selected practice setup, independent from the local archive. */
final class CircuitPracticeCodec {
    static final String FORMAT="formula-circuit-practice";
    static final int MAX_BYTES=256_000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();

    record FileDraft(String format,int version,CircuitDocument document,PracticeSlot slot) {
        FileDraft {
            if(!FORMAT.equals(format) || document==null || slot==null || !slot.preset().matches(document)
                    || version!=versionFor(slot))
                throw new IllegalArgumentException("Invalid section draft or route binding");
            slot.preset().preview(document.track());
        }
    }

    private CircuitPracticeCodec() {}

    static FileDraft decode(byte[] bytes) throws IOException {
        return decode(readTree(bytes));
    }

    static JsonObject readTree(byte[] bytes) throws IOException {
        try {
            var root=RecoveryJson.read(bytes,MAX_BYTES,2048);
            RecoveryJson.unicode(root);
            return root.getAsJsonObject();
        } catch(RuntimeException error) {
            throw new IOException("Invalid circuit JSON",error);
        }
    }

    static FileDraft decode(JsonObject root) throws IOException {
        try {
            var preset=presetObject(root);
            boolean hasSplit=preset.has("split");
            if(!hasSplit) preset.add("split",JsonNull.INSTANCE);
            ActivityCardCodec.validate(root,FileDraft.class);
            var draft=JSON.fromJson(root,FileDraft.class);
            if(draft==null || hasSplit!=(draft.version()==2) || !RecoveryJson.sameFacts(root,JSON.toJsonTree(draft),FileDraft.class))
                throw new IOException("Section draft fields cannot be restored exactly");
            return draft;
        } catch(RuntimeException | ReflectiveOperationException error) {
            throw new IOException("Invalid section draft file",error);
        }
    }

    static void write(Path target,CircuitDocument document,PracticeSlot slot,boolean replace) throws IOException {
        write(target,document,slot,ExportDestination.forWrite(target,replace));
    }
    static void write(Path target,CircuitDocument document,PracticeSlot slot,ExportDestination reviewed) throws IOException {
        var file=new FileDraft(FORMAT,versionFor(slot),document,slot);
        var tree=JSON.toJsonTree(file);
        if(file.version()==1) presetObject(tree).remove("split");
        RecoveryJson.unicode(tree);
        byte[] bytes=JSON.toJson(tree).getBytes(StandardCharsets.UTF_8);
        decode(bytes);
        var diagnostics=new SaveDiagnostics("circuit-practice-export");
        diagnostics.begin();
        ArchiveWriter.bytes(target.toAbsolutePath().normalize(),bytes,MAX_BYTES,"circuit-practice-",diagnostics,
                reviewed.replacing()?ArchiveWriter.FORCED_FILES:ArchiveWriter.NEW_FILES,reviewed::verifyPublication);
    }

    private static int versionFor(PracticeSlot slot) {return slot.preset().split()==null?1:2;}

    private static JsonObject presetObject(JsonElement root) {
        return root.getAsJsonObject().getAsJsonObject("slot").getAsJsonObject("preset");
    }
}
