package com.f1car.model;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Three equal-arc sectors and one full circuit; author targets remain pending driving calibration. */
public final class CircuitExperience {
    public static final float ROLLING_SPEED=10;
    public static final String PROTOCOL="rolling10-ordered18-v1";
    public enum Medal {
        COMPLETE("完成"),BRONZE("铜"),SILVER("银"),GOLD("金");
        public final String title;Medal(String title) { this.title=title; }
    }
    public record Targets(double gold,double silver,double bronze) {
        public Targets { if(!Double.isFinite(gold) || !Double.isFinite(silver) || !Double.isFinite(bronze) || gold<=0 || silver<=gold || bronze<=silver || bronze>180) throw new IllegalArgumentException("Invalid experience targets"); }
        public Medal medal(double duration) { if(!Double.isFinite(duration) || duration<=0) throw new IllegalArgumentException("Invalid experience duration");return duration<=gold?Medal.GOLD:duration<=silver?Medal.SILVER:duration<=bronze?Medal.BRONZE:Medal.COMPLETE; }
    }
    public record Course(TrackLayout track,int part,String focus,Targets targets) {
        public Course {
            if(track==null || !TrackCatalog.isBuiltin(track) || part<0 || part>3 || focus==null || focus.isBlank() || focus.length()>100 || focus.chars().anyMatch(Character::isISOControl) || targets==null) throw new IllegalArgumentException("Invalid experience course");
        }
        public String title() { return part==3?"整圈攻关":"S"+(part+1)+" 分段攻关"; }
        public float start() { return part==3?0:track.length*part/3; }
        public float length() { return part==3?track.length:track.length/3; }
        public int gates() { return part==3?18:6; }
        public TrackLayout.Sample gate(int index) { if(index<0 || index>gates()) throw new IllegalArgumentException("Invalid experience gate");return track.sample(start()+length()*index/gates()); }
        public String key(String physics,String controls) { return ExperienceRun.Conditions.of(this,physics,controls).key(); }
    }
    private static final class Index { int version;List<Definition> tracks; }
    private static final class Definition { String id;List<String> focus;double[][] targets; }
    private static final List<Course> COURSES=load();
    private CircuitExperience() { }
    public static List<Course> all() { return COURSES; }
    public static List<Course> forTrack(String id) { TrackCatalog.require(id);return COURSES.stream().filter(course->course.track().id.equals(id)).toList(); }
    public static Course course(String trackId,int part) { return COURSES.stream().filter(course->course.track().id.equals(trackId) && course.part()==part).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown experience course")); }
    private static List<Course> load() {
        try(var stream=Objects.requireNonNull(CircuitExperience.class.getResourceAsStream("/data/experience.json"));var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
            var index=new Gson().fromJson(reader,Index.class);if(index==null || index.version!=1 || index.tracks==null || index.tracks.size()!=TrackCatalog.all().size()) throw new IllegalArgumentException("Invalid experience catalog");
            var result=new ArrayList<Course>();var ids=new HashSet<String>();
            for(var definition:index.tracks) {
                if(definition==null || !ids.add(definition.id) || definition.focus==null || definition.focus.size()!=4 || definition.targets==null || definition.targets.length!=4) throw new IllegalArgumentException("Invalid experience track");
                var track=TrackCatalog.require(definition.id).layout();
                for(int part=0;part<4;part++) { var values=definition.targets[part];if(values==null || values.length!=3) throw new IllegalArgumentException("Invalid target triple");result.add(new Course(track,part,definition.focus.get(part),new Targets(values[0],values[1],values[2]))); }
            }
            return List.copyOf(result);
        } catch(Exception error) { throw new IllegalStateException("Cannot load circuit experience catalog",error); }
    }
}
