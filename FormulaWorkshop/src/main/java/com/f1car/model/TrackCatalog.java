package com.f1car.model;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Built-in tracks share one definition across offline racing, courses and time trials. */
public final class TrackCatalog {
    public static final String DEFAULT_ID="harbor-loop";
    public enum Theme {
        HARBOR("海湾"),FOREST("林地"),PARK("公园"),STREET("港区街道");
        public final String title;Theme(String title) { this.title=title; }
    }
    public record Section(String id,String name,int anchor,int side,List<Integer> boards) {
        public Section {
            if(id==null || !id.matches("[a-z][a-z0-9-]{0,39}") || name==null || name.isBlank() || name.length()>24 || name.chars().anyMatch(Character::isISOControl)
                || anchor<0 || anchor>127 || side!=1 && side!=-1 || boards==null || boards.isEmpty() || boards.size()>3) throw new IllegalArgumentException("Invalid circuit section");
            boards=List.copyOf(boards);
            if(boards.stream().anyMatch(value->value<20 || value>200) || boards.stream().distinct().count()!=boards.size()) throw new IllegalArgumentException("Invalid distance boards");
            for(int i=1;i<boards.size();i++) if(boards.get(i)>=boards.get(i-1)) throw new IllegalArgumentException("Boards must approach the corner");
        }
    }
    public record Entry(TrackLayout layout,String description,Theme theme,long scenerySeed,float flowStart,float flowEnd,List<Section> sections) {
        public Entry(TrackLayout layout,String description,Theme theme,long scenerySeed,float flowStart,float flowEnd) { this(layout,description,theme,scenerySeed,flowStart,flowEnd,List.of()); }
        public Entry {
            Objects.requireNonNull(layout);Objects.requireNonNull(theme);
            if(description==null || description.isBlank() || !Float.isFinite(flowStart) || !Float.isFinite(flowEnd)
                || flowStart<0 || flowEnd<=flowStart || flowEnd>.95f) throw new IllegalArgumentException("Invalid track metadata");
            sections=List.copyOf(Objects.requireNonNull(sections));
            if(sections.size()>12 || sections.stream().anyMatch(section->section.anchor()>=layout.controlPoints().size()) || sections.stream().map(Section::id).distinct().count()!=sections.size()) throw new IllegalArgumentException("Invalid track guide");
        }
        public String id() { return layout.id; }
    }
    private static final class Index { int version; List<Definition> tracks; }
    private static final class Definition { String id,resource,description,theme;long scenerySeed;float flowStart,flowEnd;List<Section> sections; }
    private static final List<Entry> ENTRIES=load();
    private TrackCatalog() {}
    public static List<Entry> all() { return ENTRIES; }
    public static Entry require(String id) {
        return ENTRIES.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown circuit: "+id));
    }
    public static Entry orDefault(String id) { return ENTRIES.stream().filter(e->e.id().equals(id)).findFirst().orElseGet(()->require(DEFAULT_ID)); }
    public static boolean isBuiltin(TrackLayout track) { return ENTRIES.stream().anyMatch(entry->entry.id().equals(track.id) && entry.layout().geometryKey().equals(track.geometryKey())); }
    public static Entry forLayout(TrackLayout track) {
        var builtin=ENTRIES.stream().filter(entry->entry.id().equals(track.id) && entry.layout().geometryKey().equals(track.geometryKey())).findFirst();
        if(builtin.isPresent()) return builtin.get();
        if(track.scene()!=null) return new Entry(track,"自制赛道 · 四车合法试跑后开放",track.scene().theme(),track.scene().seed(),.29f,.6f);
        return orDefault(track.id);
    }
    private static List<Entry> load() {
        try(var stream=Objects.requireNonNull(TrackCatalog.class.getResourceAsStream("/data/tracks/catalog.json"));
            var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
            Index index=new Gson().fromJson(reader,Index.class);
            if(index.version!=1 || index.tracks==null || index.tracks.isEmpty()) throw new IllegalArgumentException("Unsupported track catalog");
            List<Entry> result=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(var definition:index.tracks) {
                if(definition.resource==null || !definition.resource.matches("/data/[a-z0-9/-]+\\.json")) throw new IllegalArgumentException("Invalid track resource");
                TrackLayout track=TrackLayout.loadResource(definition.resource);
                if(!track.id.equals(definition.id) || !ids.add(track.id)) throw new IllegalArgumentException("Duplicate or mismatched track ID");
                result.add(new Entry(track,definition.description,Theme.valueOf(definition.theme),definition.scenerySeed,definition.flowStart,definition.flowEnd,definition.sections==null?List.of():definition.sections));
            }
            if(!ids.contains(DEFAULT_ID)) throw new IllegalArgumentException("Default track is missing");
            return List.copyOf(result);
        } catch(Exception error) { throw new IllegalStateException("Cannot load track catalog",error); }
    }
}
