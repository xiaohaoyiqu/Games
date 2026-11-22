package com.f1car.persistence;

import com.f1car.mini4wd.MiniSetup;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Keeps one complete pending assembly and the setup it was based on. */
public final class MiniAssemblyRecovery {
    public record Snapshot(MiniSetup before,MiniSetup after) {
        public Snapshot {
            if(before==null || after==null || before.equals(after))
                throw new IllegalArgumentException("A changed assembly and its original setup are required");
        }
    }
    public enum Result { PREVIOUS_RETAINED,APPLIED,APPLIED_NEEDS_CLEANUP }
    private final EditorDraftStore<Snapshot> draft;

    public MiniAssemblyRecovery(java.nio.file.Path directory) {
        draft=new EditorDraftStore<>(directory,"mini-assembly-pending",Snapshot.class);
    }

    public Snapshot current() {return draft.current();}
    public boolean saved(Snapshot value) {
        return value!=null && value.equals(draft.current()) && !draft.pending() && !draft.needsBackup();
    }

    public boolean retain(Snapshot value) {
        Objects.requireNonNull(value);
        var previous=draft.current();
        if(draft.needsBackup() || previous!=null && !value.equals(previous)) return false;
        draft.capture(value);
        if(draft.retry()) return true;
        draft.capture(previous);
        return false;
    }

    public Result apply(Snapshot expected,BooleanSupplier saveOrVerifyProfile) {
        if(!saved(expected)) return Result.PREVIOUS_RETAINED;
        boolean[] applied={false};
        boolean cleared=draft.commitAfter(null,()->{
            applied[0]=saveOrVerifyProfile.getAsBoolean();
            return applied[0];
        });
        if(!cleared) draft.capture(expected);
        return !applied[0]?Result.PREVIOUS_RETAINED:cleared?Result.APPLIED:Result.APPLIED_NEEDS_CLEANUP;
    }

    public boolean clear(Snapshot expected) {
        if(expected==null || !expected.equals(draft.current()) || draft.needsBackup()) return false;
        draft.capture(null);
        if(draft.retry()) return true;
        draft.capture(expected);
        return false;
    }
}
