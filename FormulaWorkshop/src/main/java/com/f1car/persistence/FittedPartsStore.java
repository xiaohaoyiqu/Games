package com.f1car.persistence;

import com.f1car.model.FittedParts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A staged fitting survives a profile-save interruption; the two files are not a transaction. */
public final class FittedPartsStore {
    public enum Completion { COMPLETE, PROFILE_FAILED, FITTING_FAILED, PROFILE_SAVED }
    public record State(int version,FittedParts installed,FittedParts pending) {
        public State {if(version!=1) throw new IllegalArgumentException("Unsupported fitted parts version");}
    }
    private final Path directory;
    private EditorDraftStore<State> disk;
    private State state=new State(1,null,null);
    private boolean failed;
    public FittedPartsStore(Path directory) {this.directory=directory;reload();}
    public State state() {return state;}
    public boolean pending() {return state.pending()!=null;}
    public boolean blocked() {return disk.needsBackup();}
    public String warning() {return blocked()?"装备记录无法读取或已在磁盘改变；原文件保留，请重新读取或查看恢复。":failed?"装备记录尚未保存；原装备与待安装记录保留，可重试。":"";}
    public Path file() {return disk.file();}
    public void reload() {
        var next=new EditorDraftStore<>(directory,"fitted-parts",State.class);disk=next;
        if(!next.needsBackup()) {state=next.current()==null?new State(1,null,null):next.current();failed=false;}
    }
    public boolean stage(FittedParts candidate) {
        Objects.requireNonNull(candidate);
        if(pending()) return false;
        return commit(new State(1,state.installed(),candidate));
    }
    public boolean complete(FittedParts expected) {
        return expected!=null && expected.equals(state.pending()) && commit(new State(1,expected,null));
    }
    public Completion completeAfterProfile(FittedParts expected,BooleanSupplier saveProfile) {
        Objects.requireNonNull(saveProfile);
        if(expected==null || !expected.equals(state.pending()) || blocked()) return Completion.FITTING_FAILED;
        var next=new State(1,expected,null);
        boolean[] attempted={false}, profileSaved={false};
        boolean complete=disk.commitAfter(next,()->{
            attempted[0]=true;
            profileSaved[0]=saveProfile.getAsBoolean();
            return profileSaved[0];
        });
        if(complete) {
            state=next;
            failed=false;
            return Completion.COMPLETE;
        }
        disk.capture(state);
        failed=!attempted[0] || profileSaved[0];
        if(profileSaved[0]) return Completion.PROFILE_SAVED;
        return attempted[0]?Completion.PROFILE_FAILED:Completion.FITTING_FAILED;
    }
    /** Cancel only the pending record. The caller must tell players that garage values are retained. */
    public boolean cancelPending() {return !pending() || commit(new State(1,state.installed(),null));}
    public boolean detach() {return !pending() && commit(new State(1,null,null));}
    public boolean checkpoint(State expected,FittedParts snapshot) {
        if(expected==null || !state.equals(expected) || pending() || state.installed()==null || !state.installed().sameEquipment(snapshot)) return false;
        return commit(new State(1,snapshot,null));
    }
    private boolean commit(State next) {
        if(blocked()) return false;
        disk.capture(next);if(!disk.retry()) {failed=true;return false;}
        state=next;failed=false;return true;
    }
    public EditorDraftStore.Stamp inspectDisk() throws IOException {return disk.inspectDisk();}
    public boolean backupAndSave(EditorDraftStore.Stamp reviewed,State expected) {
        if(!state.equals(expected)) return false;
        if(!disk.backupAndSave(reviewed,state)) {failed=true;return false;}
        failed=false;return true;
    }
}
