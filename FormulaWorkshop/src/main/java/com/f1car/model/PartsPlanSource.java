package com.f1car.model;

import com.google.gson.Gson;

/** Initial source snapshot for a local named card; neither a signature nor a live dependency. */
public record PartsPlanSource(Kind kind,String id,String name,String author,String digest) {
    public enum Kind { CIRCUIT_PACKAGE,LOCAL_PLAN }
    public PartsPlanSource {
        if(kind==null || !ActivityCard.identity(id) || !PartsCatalog.text(name,40) || name.isBlank() || !PartsCatalog.text(author,40) || digest==null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid plan source");
    }
    public static PartsPlanSource circuit(CircuitEventPack pack) {return new PartsPlanSource(Kind.CIRCUIT_PACKAGE,pack.id(),pack.title(),pack.author(),pack.digest());}
    public static PartsPlanSource local(PartsPlan plan) {return new PartsPlanSource(Kind.LOCAL_PLAN,plan.id(),plan.name(),"",PartsCatalog.hash(new Gson().toJson(plan)));}
    public String title() {return kind==Kind.CIRCUIT_PACKAGE?"原始来源赛事包":"直接来源方案";}
}
