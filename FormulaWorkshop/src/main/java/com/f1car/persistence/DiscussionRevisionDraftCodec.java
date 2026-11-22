package com.f1car.persistence;

import com.f1car.model.DiscussionRevisionDraft;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;

/** Strict original envelopes, even when author/notes are still incomplete and cannot be published. */
public final class DiscussionRevisionDraftCodec implements EditorDraftStore.Codec<DiscussionRevisionDraft> {
    public static final int MAX_BYTES=4_000_000,RECOVERY_BYTES=8_000_000,MAX_DEPTH=30;
    public static EditorDraftStore<DiscussionRevisionDraft> store(Path directory) {return new EditorDraftStore<>(directory,"discussion-revision-editing",new DiscussionRevisionDraftCodec(),MAX_BYTES,RECOVERY_BYTES,MAX_DEPTH);}
    @Override public JsonElement encode(DiscussionRevisionDraft value) {
        var root=new JsonObject();root.add("parent",PartsDiscussionCodec.tree(value.parent()));root.add("child",PartsDiscussionCodec.tree(value.child()));
        root.addProperty("localParent",value.localParent()?1:0);root.addProperty("localChild",value.localChild()?1:0);
        root.addProperty("parentMessage",value.parentMessage());root.addProperty("childMessage",value.childMessage());root.addProperty("author",value.author());root.addProperty("notes",value.notes());
        if(value.pending()==null) root.add("pending",JsonNull.INSTANCE);
        else {var pending=new JsonObject();pending.addProperty("id",value.pending().id());pending.addProperty("createdAt",value.pending().createdAt());root.add("pending",pending);}
        return root;
    }
    @Override public DiscussionRevisionDraft decode(JsonElement element) throws IOException {
        try {
            var root=new PartsJson.Node(element,"/draft").object("parent","child","localParent","localChild","parentMessage","childMessage","author","notes","pending");
            DiscussionRevisionDraft.Pending pending=null;
            if(!root.child("pending").value().isJsonNull()) {
                var p=root.child("pending").object("id","createdAt");ActivityCardCodec.validate(p.child("createdAt").value(),long.class);
                pending=new DiscussionRevisionDraft.Pending(p.child("id").text(48,true),p.child("createdAt").value().getAsLong());
            }
            return new DiscussionRevisionDraft(PartsDiscussionCodec.decode(root.child("parent")),PartsDiscussionCodec.decode(root.child("child")),
                root.child("localParent").integer(0,1)==1,root.child("localChild").integer(0,1)==1,
                root.child("parentMessage").text(48,true),root.child("childMessage").text(48,true),root.child("author").text(40,false),root.child("notes").text(320,false),pending);
        }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid revision working draft",error);}
    }
}
