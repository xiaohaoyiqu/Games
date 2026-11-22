package com.f1car.model;

import java.util.*;

/** A player's explicit revision claim, bound to two complete discussions and original messages. */
public record PartsDiscussionRevision(int version,String id,PartsDiscussion parent,PartsDiscussion child,
                                      String parentMessage,String childMessage,String author,String notes,long createdAt) {
    public enum Status {
        EXACT("本地原文一致"),EXTENDED("本地已追加留言，冻结原文仍保留"),MISSING("本地话题缺失"),CONFLICT("本地话题与冻结原文冲突");
        public final String title;Status(String title) {this.title=title;}
    }
    public PartsDiscussionRevision {
        if(version!=1 || id==null || !id.matches("revision-[a-f0-9]{32}") || parent==null || child==null
            || parent.id().equals(child.id()) || parent.plan().equals(child.plan())
            || parent.message(parentMessage)==null || child.message(childMessage)==null
            || !PartsCatalog.text(author,40) || author.isBlank() || !PartsCatalog.text(notes,320) || notes.isBlank() || createdAt<0)
            throw new IllegalArgumentException("Revision needs distinct topics/plans, original messages and an explicit explanation");
    }
    public static PartsDiscussionRevision declare(PartsDiscussion parent,PartsDiscussion child,String parentMessage,String childMessage,String author,String notes) {
        return new PartsDiscussionRevision(1,"revision-"+UUID.randomUUID().toString().replace("-",""),parent,child,parentMessage,childMessage,author,notes,System.currentTimeMillis());
    }
    public static Status status(PartsDiscussion frozen,PartsDiscussion local) {
        if(local==null) return Status.MISSING;
        if(frozen.equals(local)) return Status.EXACT;
        var merge=PartsDiscussion.merge(frozen,local);
        return merge.valid() && local.equals(merge.candidate())?Status.EXTENDED:Status.CONFLICT;
    }
    /** One accepted parent per exact full child plan. Card names, IDs and timestamps are not graph keys. */
    public static List<PartsDiscussionRevision> checked(List<PartsDiscussionRevision> values) {
        values=List.copyOf(values);var ids=new HashSet<String>();var parents=new HashMap<PartsPlanShare,PartsPlanShare>();
        for(var value:values) {
            if(!ids.add(value.id())) throw new IllegalArgumentException("修订声明身份重复。");
            if(parents.put(value.child().plan(),value.parent().plan())!=null) throw new IllegalArgumentException("该完整子方案已有修订声明；请先查看已有关系。");
        }
        for(var start:parents.keySet()) {
            var seen=new HashSet<PartsPlanShare>();
            for(var cursor=start;cursor!=null;cursor=parents.get(cursor))
                if(!seen.add(cursor)) throw new IllegalArgumentException("修订关系形成循环；请检查父子方向。");
        }
        return values;
    }
}
