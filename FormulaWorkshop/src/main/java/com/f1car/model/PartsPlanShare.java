package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Portable author intent and recent declared sources; independent from installed/competitive identity. */
public record PartsPlanShare(PartsPlan plan,String author,Trail trail,List<Pack> packs) {
    public record Trail(List<PartsPlanSource> steps,int omitted) {
        public static final int LIMIT=8;
        public static final Trail EMPTY=new Trail(List.of(),0);
        public Trail {steps=List.copyOf(steps);if(steps.size()>LIMIT || omitted<0 || omitted>1000000 || steps.stream().distinct().count()!=steps.size() || steps.isEmpty() && omitted!=0) throw new IllegalArgumentException("Invalid declared plan trail");}
        public Trail prepend(PartsPlanSource source) {var values=new ArrayList<PartsPlanSource>();values.add(Objects.requireNonNull(source));values.addAll(steps);int count=Math.max(0,values.size()-LIMIT);return new Trail(values.subList(0,Math.min(values.size(),LIMIT)),Math.addExact(omitted,count));}
    }
    public PartsPlanShare {
        if(plan==null || !PartsCatalog.text(author,40) || author.isBlank() || trail==null || packs==null || packs.size()>PartsCatalog.MAX_PACKS) throw new IllegalArgumentException("Invalid shared parts plan");
        packs=packs.stream().sorted(Comparator.comparing(Pack::id)).toList();if(packs.stream().anyMatch(pack->pack.id().equals("builtin")) || packs.stream().map(Pack::id).distinct().count()!=packs.size()) throw new IllegalArgumentException("Reserved or duplicate shared namespace");
        var wanted=namespaces(plan.build(),packs);if(packs.stream().anyMatch(pack->!wanted.contains(pack.id()))) throw new IllegalArgumentException("Unrelated shared parts namespace");
        if(trail.steps().contains(origin(plan,author))) throw new IllegalArgumentException("A plan cannot declare its current snapshot as its own source");
    }
    public static PartsPlanShare capture(PartsPlan plan,String author,Trail trail,PartsCatalog catalog) {var wanted=namespaces(plan.build(),catalog.packs());return new PartsPlanShare(plan,author,trail,catalog.packs().stream().filter(pack->!pack.id().equals("builtin") && wanted.contains(pack.id())).toList());}
    private static Set<String> namespaces(PartsBuild build,List<Pack> packs) {var wanted=new HashSet<String>();build.slots().values().forEach(selection->wanted.add(selection.part().split(":",2)[0]));boolean changed;do {changed=false;for(var pack:packs) if(wanted.contains(pack.id())) for(var part:pack.parts()) for(String required:part.requires()) changed|=wanted.add(required.split(":",2)[0]);}while(changed);return wanted;}
    private static PartsPlanSource origin(PartsPlan plan,String author) {var source=PartsPlanSource.local(plan);return new PartsPlanSource(source.kind(),source.id(),source.name(),author,source.digest());}
    public Trail receivedTrail() {return trail.prepend(origin(plan,author));}
    public PartsPlan localCopy() {return PartsPlan.capture(plan.name(),plan.notes(),plan.build());}
}
