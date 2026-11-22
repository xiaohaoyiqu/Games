package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Bounded, declaration-only companion content. A recommendation never installs or applies itself. */
public record CircuitPartsBundle(int version,List<Pack> packs,PartsPlan suggestion) {
    public CircuitPartsBundle {
        if(version!=1 || packs==null || packs.size()>PartsCatalog.MAX_PACKS || suggestion==null) throw new IllegalArgumentException("Invalid circuit parts content");packs=List.copyOf(packs);
        if(packs.stream().anyMatch(pack->pack.id().equals("builtin")) || packs.stream().map(Pack::id).distinct().count()!=packs.size()) throw new IllegalArgumentException("Reserved or duplicate circuit parts namespace");
    }
    public static CircuitPartsBundle capture(PartsCatalog catalog,PartsActivityRule rule,PartsBuild build,String name,String notes) {
        Objects.requireNonNull(rule);var checked=rule.review(build,catalog,rule.vehicle(),rule.physics(),0);if(!checked.valid()) throw new IllegalArgumentException("Recommendation does not satisfy the rule");
        var namespaces=new HashSet<String>();rule.allowed().forEach(part->namespaces.add(part.part().split(":",2)[0]));var packs=catalog.packs().stream().filter(pack->!pack.id().equals("builtin") && namespaces.contains(pack.id())).toList();
        var content=new CircuitPartsBundle(1,packs,PartsPlan.capture(name,notes,rule.normalize(build)));content.validate(rule);return content;
    }
    public void validate(PartsActivityRule rule) {
        if(rule==null || !rule.acceptsIdentity(suggestion.build())) throw new IllegalArgumentException("Recommendation differs from the package rule");
        var parts=new HashMap<String,Part>();packs.forEach(pack->pack.parts().forEach(part->parts.put(part.id(),part)));var used=new HashSet<String>();
        for(var allowed:rule.allowed()) if(!allowed.part().startsWith("builtin:")) {var part=parts.get(allowed.part());if(part==null || part.slot()!=allowed.slot() || !part.fingerprint().equals(allowed.fingerprint())) throw new IllegalArgumentException("Package does not contain its pinned custom part");used.add(allowed.part().split(":",2)[0]);}
        if(packs.stream().anyMatch(pack->!used.contains(pack.id()))) throw new IllegalArgumentException("Unrelated part package namespace");
    }
    public PartsCatalog catalog(Pack builtin) {return new PartsCatalog(builtin,packs);}
}
