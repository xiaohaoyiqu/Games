package com.f1car.network;

import com.f1car.model.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Transfer descriptor; custom maps remain ephemeral on guests and carry no local approval. */
public record RoomTrack(String id,CircuitDocument document,String geometry,String physicsVersion,String key) {
    public RoomTrack {
        if(id==null || !id.matches("[a-z0-9-]{1,64}") || geometry==null || !geometry.matches("[a-f0-9]{64}") || key==null || !key.matches("[a-f0-9]{64}")
            || physicsVersion==null || physicsVersion.isBlank() || physicsVersion.length()>120 || document!=null && !document.id().equals(id))
            throw new IllegalArgumentException("Invalid room track descriptor");
    }
    public static RoomTrack builtin(String id) {
        var track=TrackCatalog.require(id).layout();String physics=VehicleSpec.load().physicsVersion;
        return new RoomTrack(id,null,track.geometryKey(),physics,signature(id,track.geometryKey(),physics,"builtin"));
    }
    public static RoomTrack custom(CircuitDocument document) {
        Objects.requireNonNull(document);var report=CircuitInspector.inspect(document);
        if(!report.ready()) throw new IllegalArgumentException("Custom circuit geometry is not ready");
        String physics=VehicleSpec.load().physicsVersion,geometry=report.track().geometryKey();
        return new RoomTrack(document.id(),document,geometry,physics,signature(document.id(),geometry,physics,document.driveKey()));
    }
    public String name() { return document!=null?document.name():TrackCatalog.require(id).layout().name; }
    /** Called by the game thread before acknowledging a rules revision, never by socket workers. */
    public TrackLayout resolve() {
        if(!VehicleSpec.load().physicsVersion.equals(physicsVersion)) throw new IllegalArgumentException("Physics version mismatch");
        TrackLayout track;
        if(document==null) track=TrackCatalog.require(id).layout();
        else { var report=CircuitInspector.inspect(document);if(!report.ready()) throw new IllegalArgumentException("Unsafe custom circuit geometry");track=report.track(); }
        if(!geometry.equals(track.geometryKey()) || !key.equals(signature(id,geometry,physicsVersion,document==null?"builtin":document.driveKey())))
            throw new IllegalArgumentException("Track content mismatch");
        return track;
    }
    private static String signature(String id,String geometry,String physics,String document) { return digest(id+":"+geometry+":"+physics+":"+document); }
    static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
