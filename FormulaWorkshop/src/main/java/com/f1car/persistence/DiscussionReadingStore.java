package com.f1car.persistence;

import com.f1car.model.*;
import com.f1car.model.DiscussionReading.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/** Published state changes only after durable save. Fingerprints are prepared outside rendering. */
public final class DiscussionReadingStore {
    private final Path directory;
    private final Gson gson=new GsonBuilder().serializeNulls().create();
    private EditorDraftStore<State> disk;
    private State state=State.empty();
    private boolean known,failed;
    private String notice="";
    private List<PartsDiscussion> parts;
    private List<CircuitFeedbackDiscussion> feedback;
    private IdentityHashMap<Object,Topic> prepared=new IdentityHashMap<>();
    public DiscussionReadingStore(Path directory) {this.directory=directory;reload();}
    public State state() {return state;}
    public Path file() {return disk.file();}
    public boolean blocked() {return disk.needsBackup();}
    public String status() {return blocked()?"已读标记暂停 · 查看恢复":failed?"标记尚未保存 · 重试":!known?"已读状态未知":"本机已读标记 · 保存与恢复";}
    public String warning() {return blocked()?"标记文件无法读取或已在磁盘改变；本次仍显示上次保存的标记。":failed?"标记未保存；仍显示上次保存的状态，请重新选择标记。":notice;}
    public void reload() {
        var next=new EditorDraftStore<>(directory,"discussion-reading",State.class);disk=next;
        if(!next.needsBackup()) {state=next.current()==null?State.empty():next.current();known=true;failed=false;notice="";}
    }
    /** Called on authoring updates or after imports, never from HUD rendering. */
    public void observe(List<PartsDiscussion> parts,List<CircuitFeedbackDiscussion> feedback) {
        Objects.requireNonNull(parts);Objects.requireNonNull(feedback);if(this.parts==parts && this.feedback==feedback) return;
        var next=new IdentityHashMap<Object,Topic>();for(var value:parts) next.put(value,prepared.containsKey(value)?prepared.get(value):prepare(value,Kind.PARTS,value.id()));
        for(var value:feedback) next.put(value,prepared.containsKey(value)?prepared.get(value):prepare(value,Kind.FEEDBACK,value.id()));
        this.parts=parts;this.feedback=feedback;prepared=next;
    }
    /** Lookup-only: unknown snapshots are not interpreted as zero unread. */
    public int unread(Object expected) {var value=prepared.get(expected);return !known || value==null?-1:state.unread(value);}
    public String badge(Object expected) {int count=unread(expected);return count<0?"已读状态待确认":count+" 条未读";}
    public boolean read(Object expected,String message) {var value=prepared.get(expected);return known && value!=null && state.read(value,message);}
    public boolean mark(Object expected,String message,boolean read) {
        var visible=prepared.get(expected);if(visible==null || !known || blocked()) return false;
        var next=state.mark(visible,message,read);if(next.equals(state)) return true;
        boolean rotated=state.topics().size()==DiscussionReading.CAPACITY && state.topics().stream().noneMatch(value->value.sameBackground(visible));
        disk.capture(next);if(!disk.retry()) {failed=true;return false;}
        state=next;failed=false;notice=rotated?"已保存本机标记；仅保留最近六十四份话题背景的标记，较早标记已移除。":"";return true;
    }
    public EditorDraftStore.Stamp inspectDisk() throws IOException {return disk.inspectDisk();}
    /** Recovery preserves the reviewed disk bytes and only the published local choices. */
    public boolean backupAndSave(EditorDraftStore.Stamp reviewed,State expected) {
        if(!state.equals(expected)) return false;
        if(!disk.backupAndSave(reviewed,state)) {failed=true;return false;}
        known=true;failed=false;notice="";return true;
    }
    private Topic prepare(Object value,Kind kind,String id) {
        JsonObject tree;
        if(value instanceof CircuitFeedbackDiscussion discussion) {
            tree=CircuitFeedbackDiscussionCodec.contentTree(discussion);
            tree.addProperty("version",CircuitFeedbackDiscussion.LEGACY_VERSION);
        } else tree=gson.toJsonTree(value).getAsJsonObject();
        var messages=tree.remove("messages").getAsJsonArray();var stamps=new ArrayList<Message>();
        for(var message:messages) stamps.add(new Message(message.getAsJsonObject().get("id").getAsString(),digest(message)));
        return new Topic(kind,id,digest(tree),stamps);
    }
    private static String digest(JsonElement value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PartsTrialEvidenceCodec.canonical(TrialTraceCodec.numbers(value)).toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
}
