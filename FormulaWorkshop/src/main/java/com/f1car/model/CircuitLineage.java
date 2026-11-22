package com.f1car.model;

import com.google.gson.Gson;
import java.util.*;

/** Explicit author declaration bound to exact immutable packages, never inferred from names. */
public record CircuitLineage(int version,String childId,String childDigest,Parent parent,List<Link> links) {
    public record MapRef(String id,String name,String digest) {
        public MapRef {if(id==null || !id.matches("user-[a-f0-9]{32}") || !PartsCatalog.text(name,60) || name.isBlank() || !hash(digest)) throw new IllegalArgumentException("Invalid map reference");}
        static MapRef of(CircuitDocument map) {return new MapRef(map.id(),map.name(),PartsCatalog.hash(new Gson().toJson(map)));}
    }
    public record Parent(String id,String digest,String title,String author,List<MapRef> maps) {
        public Parent {if(!ActivityCard.identity(id) || !hash(digest) || !PartsCatalog.text(title,40) || title.isBlank() || !PartsCatalog.text(author,40) || author.isBlank() || maps==null || maps.isEmpty() || maps.size()>8) throw new IllegalArgumentException("Invalid parent package");maps=List.copyOf(maps);if(maps.stream().map(MapRef::id).distinct().count()!=maps.size()) throw new IllegalArgumentException("Duplicate parent map");}
        public static Parent of(CircuitEventPack pack) {return new Parent(pack.id(),pack.digest(),pack.title(),pack.author(),pack.circuits().stream().map(MapRef::of).toList());}
        public boolean matches(CircuitEventPack pack) {return id.equals(pack.id()) && digest.equals(pack.digest()) && equals(of(pack));}
    }
    public record Link(String before,String after) {
        public Link {if(before==null || after==null || !before.matches("user-[a-f0-9]{32}") || !after.matches("user-[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid map correspondence");}
    }
    public CircuitLineage {
        if(version!=1 || !ActivityCard.identity(childId) || !hash(childDigest) || parent==null || parent.id().equals(childId) || links==null || links.size()>8) throw new IllegalArgumentException("Invalid circuit lineage");links=links.stream().sorted(Comparator.comparing(Link::after).thenComparing(Link::before)).toList();
        var before=new HashSet<String>();var after=new HashSet<String>();for(var link:links) if(!before.add(link.before()) || !after.add(link.after()) || parent.maps().stream().noneMatch(map->map.id().equals(link.before()))) throw new IllegalArgumentException("Duplicate or unknown map correspondence");
    }
    public static CircuitLineage declare(CircuitEventPack parent,CircuitEventPack child,List<Link> links) {var result=new CircuitLineage(1,child.id(),child.digest(),Parent.of(parent),links);result.validateChild(child);return result;}
    public void validateChild(CircuitEventPack child) {
        if(!child.id().equals(childId) || !child.digest().equals(childDigest)) throw new IllegalArgumentException("Lineage differs from child package");
        var maps=child.circuits();
        for(var link:links) if(maps.stream().noneMatch(map->map.id().equals(link.after())))
            throw new IllegalArgumentException("Lineage differs from child package");
    }
    public boolean matches(CircuitEventPack before,CircuitEventPack after) {
        if(!childId.equals(after.id()) || !childDigest.equals(after.digest()) || !parent.matches(before)) return false;
        try {validateChild(after);return true;}
        catch(IllegalArgumentException invalid) {return false;}
    }
    public CircuitLineage retarget(CircuitEventPack child) {var result=new CircuitLineage(version,child.id(),child.digest(),parent,links);result.validateChild(child);return result;}
    private static boolean hash(String value) {return value!=null && value.matches("[a-f0-9]{64}");}
}
