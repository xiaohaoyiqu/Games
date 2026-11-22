package com.f1car.model;

import java.util.*;

/** Three-way component merge. Route points and scenery have no stable item identities. */
public record CircuitMapMigration(CircuitDocument base,CircuitDocument local,CircuitDocument target) {
    public enum Section { ROAD("道路与路线点"),SCENE("全部布景"),NAME("地图名称"),CARD("作者与地图卡");public final String title;Section(String title) {this.title=title;} }
    public enum Choice { LOCAL("旧版本地内容"),TARGET("当前新版草稿");public final String title;Choice(String title) {this.title=title;} }
    private record Road(float width,List<CircuitDocument.Point> anchors) {}
    public CircuitMapMigration {Objects.requireNonNull(base);Objects.requireNonNull(local);Objects.requireNonNull(target);}
    public Choice automatic(Section section) {var original=value(base,section);var left=value(local,section);var right=value(target,section);if(Objects.equals(left,original) || Objects.equals(left,right)) return Choice.TARGET;if(Objects.equals(right,original)) return Choice.LOCAL;return null;}
    public boolean conflict(Section section) {return automatic(section)==null;}
    public CircuitDocument candidate(Map<Section,Choice> choices) {
        var picked=new EnumMap<Section,CircuitDocument>(Section.class);for(var section:Section.values()) {var choice=choices.get(section);if(choice==null) choice=automatic(section);if(choice==null) throw new IllegalStateException("Resolve every conflicting component");picked.put(section,choice==Choice.LOCAL?local:target);}
        var road=picked.get(Section.ROAD);return new CircuitDocument(target.format(),target.version(),target.id(),picked.get(Section.NAME).name(),road.width(),road.anchors(),picked.get(Section.SCENE).scene(),picked.get(Section.CARD).card());
    }
    private static Object value(CircuitDocument document,Section section) {return switch(section) {case ROAD->new Road(document.width(),document.anchors());case SCENE->document.scene();case NAME->document.name();case CARD->document.metadata();};}
}
