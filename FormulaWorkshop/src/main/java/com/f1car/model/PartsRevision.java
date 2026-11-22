package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Import preview computed once from the old and incoming definitions, including removed parts. */
public record PartsRevision(String pack,int fromRevision,int toRevision,List<Change> changes) {
    public record Change(String subject,String field,String before,String after,boolean functional) {
        public String description() {return subject+" · "+field+"："+before+" → "+after+(functional?"（功能）":"（文字／外观）");}
    }
    public PartsRevision {changes=List.copyOf(changes);}
    public long functionalChanges() {return changes.stream().filter(Change::functional).count();}
    public static PartsRevision compare(Pack before,Pack after) {
        Objects.requireNonNull(after);if(before!=null && !before.id().equals(after.id())) throw new IllegalArgumentException("Different pack namespaces");var changes=new ArrayList<Change>();
        if(before!=null) {add(changes,after.id(),"修订",before.revision(),after.revision(),false);add(changes,after.id(),"包名",before.name(),after.name(),false);add(changes,after.id(),"作者",before.author(),after.author(),false);}
        var old=new TreeMap<String,Part>();if(before!=null) before.parts().forEach(part->old.put(part.id(),part));var current=new TreeMap<String,Part>();after.parts().forEach(part->current.put(part.id(),part));var ids=new TreeSet<>(old.keySet());ids.addAll(current.keySet());
        for(String id:ids) {var a=old.get(id);var b=current.get(id);if(a==null || b==null) {changes.add(new Change(id,a==null?"新增部件":"移除部件",a==null?"无":a.name(),b==null?"无":b.name(),true));if(a==null) describe(changes,b);continue;}
            add(changes,id,"槽位",a.slot(),b.slot(),true);add(changes,id,"点数",a.points(),b.points(),true);add(changes,id,"名称",a.name(),b.name(),false);add(changes,id,"用途",a.purpose(),b.purpose(),false);add(changes,id,"代价说明",a.tradeoff(),b.tradeoff(),false);add(changes,id,"颜色",rgb(a.color()),rgb(b.color()),false);
            var fields=EnumSet.noneOf(Field.class);fields.addAll(a.settings().keySet());fields.addAll(b.settings().keySet());for(var field:fields) add(changes,id,field.title,range(a.settings().get(field)),range(b.settings().get(field)),true);
            add(changes,id,"需要",links(a.requires()),links(b.requires()),true);add(changes,id,"禁配",links(a.excludes()),links(b.excludes()),true);
        }
        return new PartsRevision(after.id(),before==null?0:before.revision(),after.revision(),changes);
    }
    private static void describe(List<Change> changes,Part part) {changes.add(new Change(part.id(),"槽位／点数","无",part.slot().title+"／"+part.points(),true));part.settings().forEach((field,value)->changes.add(new Change(part.id(),field.title,"无",range(value),true)));changes.add(new Change(part.id(),"需要／禁配","无",links(part.requires())+"／"+links(part.excludes()),true));}
    private static void add(List<Change> changes,String id,String field,Object before,Object after,boolean functional) {if(!Objects.equals(before,after)) changes.add(new Change(id,field,Objects.toString(before),Objects.toString(after),functional));}
    private static String range(Range value) {return value==null?"不适用":value.min()+"%–"+value.max()+"%，默认 "+value.initial()+"%";}
    private static String links(Set<String> values) {return values.isEmpty()?"无":String.join(", ",new TreeSet<>(values));}
    private static String rgb(int value) {return String.format(Locale.ROOT,"%06x",value);}
}
