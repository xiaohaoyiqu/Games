package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Player-authored declarations for the existing model. Invalid range relationships remain editable drafts. */
public final class PartsAuthoring {
    public static final int HISTORY=24;
    public record Setting(int min,int max,int initial) {
        public Setting {if(min<0 || max<0 || initial<0 || min>1000 || max>1000 || initial>1000) throw new IllegalArgumentException("Invalid draft setting");}
        public Setting cell(int column,int value) {return switch(column) {case 0->new Setting(value,max,initial);case 1->new Setting(min,max,value);case 2->new Setting(min,value,initial);default->throw new IllegalArgumentException("Unknown setting column");};}
    }
    public record Definition(String id,Slot slot,String name,String purpose,String tradeoff,int points,int color,
                             Map<Field,Setting> settings,Set<String> requires,Set<String> excludes) {
        public Definition {
            if(!PartsCatalog.validId(id) || slot==null || !PartsCatalog.text(name,40) || !PartsCatalog.text(purpose,120) || !PartsCatalog.text(tradeoff,160)
                || points<0 || points>100 || color<0 || color>0xffffff || settings==null || requires==null || excludes==null
                || requires.size()>PartsCatalog.MAX_LINKS || excludes.size()>PartsCatalog.MAX_LINKS
                || requires.stream().anyMatch(value->!PartsCatalog.validId(value)) || excludes.stream().anyMatch(value->!PartsCatalog.validId(value))) throw new IllegalArgumentException("Invalid authoring definition");
            var copy=new EnumMap<Field,Setting>(Field.class);copy.putAll(settings);
            if(!copy.keySet().equals(Set.copyOf(slot.fields()))) throw new IllegalArgumentException("Draft must contain exactly its slot's model settings");
            copy.forEach((field,value)->{if(value==null || value.min()<field.min || value.min()>field.max || value.max()<field.min || value.max()>field.max || value.initial()<field.min || value.initial()>field.max) throw new IllegalArgumentException("Draft setting exceeds supported model range");});
            settings=Collections.unmodifiableMap(copy);requires=Set.copyOf(requires);excludes=Set.copyOf(excludes);
        }
        public static Definition from(Part part,String id) {var ranges=new EnumMap<Field,Setting>(Field.class);part.settings().forEach((field,value)->ranges.put(field,new Setting(value.min(),value.max(),value.initial())));return new Definition(id,part.slot(),part.name(),part.purpose(),part.tradeoff(),part.points(),part.color(),ranges,part.requires(),part.excludes());}
        public Definition text(String name,String purpose,String tradeoff) {return new Definition(id,slot,name,purpose,tradeoff,points,color,settings,requires,excludes);}
        public Definition appearance(int points,int color) {return new Definition(id,slot,name,purpose,tradeoff,points,color,settings,requires,excludes);}
        public Definition rules(Set<String> requires,Set<String> excludes) {return new Definition(id,slot,name,purpose,tradeoff,points,color,settings,requires,excludes);}
        public Definition setting(Field field,Setting value) {if(!slot.fields().contains(field)) throw new IllegalArgumentException("Wrong slot field");var next=new EnumMap<Field,Setting>(settings);next.put(field,value);return new Definition(id,slot,name,purpose,tradeoff,points,color,next,requires,excludes);}
        Definition remap(String id,UnaryOperator<String> mapping) {return new Definition(id,slot,name,purpose,tradeoff,points,color,settings,map(requires,mapping),map(excludes,mapping));}
        public List<String> issues() {
            var issues=new ArrayList<String>();if(name.isBlank()) issues.add(id+"：部件名称不能为空。");
            for(var field:slot.fields()) {var value=settings.get(field);if(value.min()>value.max()) issues.add(id+" · "+field.title+"：下限大于上限。");if(value.initial()<value.min() || value.initial()>value.max()) issues.add(id+" · "+field.title+"：默认值不在当前范围内。");}
            if(requires.contains(id) || excludes.contains(id)) issues.add(id+"：不能依赖或禁配自身。");
            if(!Collections.disjoint(requires,excludes)) issues.add(id+"：同一部件不能同时需要和禁配。");return List.copyOf(issues);
        }
        public Part part() {
            var issues=issues();if(!issues.isEmpty()) throw new IllegalArgumentException(issues.get(0));
            var ranges=new EnumMap<Field,Range>(Field.class);settings.forEach((field,value)->ranges.put(field,new Range(value.min(),value.max(),value.initial())));
            return new Part(id,slot,name,purpose,tradeoff,points,color,ranges,requires,excludes);
        }
    }
    public record Draft(int version,String id,int revision,String name,String author,List<Definition> parts) {
        public Draft {
            if(version!=1 || !namespace(id) || revision<1 || revision>1_000_000 || !PartsCatalog.text(name,60) || !PartsCatalog.text(author,60) || parts==null || parts.isEmpty() || parts.size()>PartsCatalog.MAX_PARTS_PER_PACK) throw new IllegalArgumentException("Invalid pack draft");
            parts=List.copyOf(parts);var ids=new HashSet<String>();for(var part:parts) if(!part.id().startsWith(id+":") || !ids.add(part.id())) throw new IllegalArgumentException("Duplicate or foreign draft identity");
        }
        public static Draft from(Pack pack) {if(!namespace(pack.id())) throw new IllegalArgumentException("Copy built-in parts to a new player namespace");return new Draft(1,pack.id(),pack.revision(),pack.name(),pack.author(),pack.parts().stream().map(part->Definition.from(part,part.id())).toList());}
        public static Draft create(String id,String author,Part template) {return new Draft(1,id,1,"我的部件包",author,List.of(Definition.from(template,id+":part-1")));}
        public Draft metadata(String name,String author,int revision) {return new Draft(version,id,revision,name,author,parts);}
        public Draft replace(int index,Definition value) {var next=new ArrayList<>(parts);next.set(index,value);return new Draft(version,id,revision,name,author,next);}
        public Draft add(Part template) {if(parts.size()>=PartsCatalog.MAX_PARTS_PER_PACK) throw new IllegalArgumentException("一个包最多十六个部件。");int suffix=1;while(has(id+":part-"+suffix)) suffix++;var next=new ArrayList<>(parts);next.add(Definition.from(template,id+":part-"+suffix));return new Draft(version,id,revision,name,author,next);}
        public Draft remove(int index) {if(parts.size()==1) throw new IllegalArgumentException("部件包至少保留一个部件。");var next=new ArrayList<>(parts);next.remove(index);return new Draft(version,id,revision,name,author,next);}
        public Draft rename(int index,String localId) {
            String before=parts.get(index).id(),after=id+":"+localId;
            if(!PartsCatalog.validId(after) || !before.equals(after) && has(after)) throw new IllegalArgumentException("部件身份无效或已存在。");
            return new Draft(version,id,revision,name,author,parts.stream().map(part->part.remap(part.id().equals(before)?after:part.id(),value->value.equals(before)?after:value)).toList());
        }
        /** Re-key all local references, including missing local targets, so a fork cannot bind to the old package. */
        public Draft fork(String namespace) {
            if(!namespace(namespace) || namespace.equals(id)) throw new IllegalArgumentException("请使用另一个有效玩家命名空间。");
            UnaryOperator<String> mapping=value->value.startsWith(id+":")?namespace+value.substring(id.length()):value;
            return new Draft(version,namespace,1,name,author,parts.stream().map(part->part.remap(mapping.apply(part.id()),mapping)).toList());
        }
        private boolean has(String identity) {return parts.stream().anyMatch(part->part.id().equals(identity));}
        public List<String> issues() {var issues=new ArrayList<String>();if(name.isBlank()) issues.add("部件包名称不能为空。");for(var part:parts) issues.addAll(part.issues());return List.copyOf(issues);}
        public Pack pack() {var issues=issues();if(!issues.isEmpty()) throw new IllegalArgumentException(issues.get(0));return new Pack(id,revision,name,author,parts.stream().map(Definition::part).toList());}
    }
    public record State(int version,Draft draft,List<Draft> undo,List<Draft> redo) {
        public State {if(version!=1 || draft==null || undo==null || redo==null || undo.size()+redo.size()>HISTORY) throw new IllegalArgumentException("Invalid authoring history");undo=List.copyOf(undo);redo=List.copyOf(redo);}
    }
    private State state;
    public PartsAuthoring(Draft draft) {state=new State(1,draft,List.of(),List.of());}
    public PartsAuthoring(State state) {this.state=Objects.requireNonNull(state);}
    public State state() {return state;}
    public Draft draft() {return state.draft();}
    public void adopt(Draft value) {Objects.requireNonNull(value);if(value.equals(draft())) return;var undo=new ArrayList<>(state.undo());undo.add(draft());if(undo.size()>HISTORY) undo.remove(0);state=new State(1,value,undo,List.of());}
    public boolean undo() {if(state.undo().isEmpty()) return false;var undo=new ArrayList<>(state.undo());var redo=new ArrayList<>(state.redo());redo.add(draft());state=new State(1,undo.remove(undo.size()-1),undo,redo);return true;}
    public boolean redo() {if(state.redo().isEmpty()) return false;var undo=new ArrayList<>(state.undo());var redo=new ArrayList<>(state.redo());undo.add(draft());state=new State(1,redo.remove(redo.size()-1),undo,redo);return true;}
    public static boolean namespace(String value) {return value!=null && value.matches("[a-z][a-z0-9-]{0,31}") && !value.equals("builtin");}
    private static Set<String> map(Set<String> values,UnaryOperator<String> mapping) {var result=new HashSet<String>();for(var value:values) result.add(mapping.apply(value));return Set.copyOf(result);}
}
