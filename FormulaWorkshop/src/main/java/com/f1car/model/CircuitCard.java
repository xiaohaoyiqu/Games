package com.f1car.model;

import java.util.Objects;

/** Optional author-supplied labels. Difficulty is a description, never a driving qualification. */
public record CircuitCard(String author,String description,Difficulty difficulty,String revision) {
    public enum Difficulty {
        UNRATED("未标注"),BEGINNER("入门"),INTERMEDIATE("进阶"),ADVANCED("挑战");
        public final String title;Difficulty(String title) { this.title=title; }
    }
    public enum Status {
        DRAFT("草稿 · 几何待调整"),UNVERIFIED("待本机四车验证"),QUALIFIED("已通过本机四车验证");
        public final String title;Status(String title) { this.title=title; }
    }
    public static final CircuitCard EMPTY=new CircuitCard("","",Difficulty.UNRATED,"");
    public CircuitCard {
        author=text(author,40);description=text(description,240);revision=text(revision,120);Objects.requireNonNull(difficulty);
    }
    public boolean empty() { return equals(EMPTY); }
    public CircuitCard author(String value) { return new CircuitCard(value,description,difficulty,revision); }
    public CircuitCard description(String value) { return new CircuitCard(author,value,difficulty,revision); }
    public CircuitCard difficulty(Difficulty value) { return new CircuitCard(author,description,value,revision); }
    public CircuitCard revision(String value) { return new CircuitCard(author,description,difficulty,value); }
    public static Status status(CircuitDocument document,CircuitTrial.Proof proof,String physics,CircuitInspector.Report report) {
        Objects.requireNonNull(document);Objects.requireNonNull(physics);Objects.requireNonNull(report);
        if(!report.ready()) return Status.DRAFT;
        return proof!=null && proof.matches(document,physics)?Status.QUALIFIED:Status.UNVERIFIED;
    }
    private static String text(String value,int maximum) {
        if(value==null || value.length()>maximum || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid circuit card text");
        return value.strip();
    }
}
