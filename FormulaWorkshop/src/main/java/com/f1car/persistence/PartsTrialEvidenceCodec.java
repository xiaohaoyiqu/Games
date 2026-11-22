package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.util.HexFormat;
import java.util.TreeSet;

/** Strict exchange with the unchanged named-plan envelope embedded verbatim in its JSON form. */
public final class PartsTrialEvidenceCodec {
    public static final int MAX_BYTES=900000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private PartsTrialEvidenceCodec() {}
    private static JsonObject content(PartsTrialEvidence value) {String plan=PartsPlanShareCodec.encode(value.plan());if(plan.getBytes(StandardCharsets.UTF_8).length>PartsPlanShareCodec.MAX_BYTES) throw new IllegalArgumentException("Embedded plan exceeds size bound");var result=new JsonObject();result.addProperty("id",value.id());result.add("plan",JsonParser.parseString(plan));result.add("report",JSON.toJsonTree(value.report()));result.add("purpose",TuningPurposeJson.tree(value.purpose()));result.add("slot",JSON.toJsonTree(value.slot()));result.addProperty("notes",value.notes());return result;}
    public static String digest(PartsTrialEvidence value) {return digest(content(value));}
    private static String digest(JsonElement value) {return PartsCatalog.hash(JSON.toJson(canonical(value)));}
    static JsonObject tree(PartsTrialEvidence value) {var root=new JsonObject();root.addProperty("kind","formula-workshop-trial-evidence");root.addProperty("version",value.purpose()!=null && value.purpose().version()==2?2:1);var content=content(value);root.add("content",content);root.addProperty("digest",digest(content));if(JSON.toJson(root).getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new IllegalArgumentException("Evidence exceeds size bound");return root;}
    public static String encode(PartsTrialEvidence value) {return JSON.toJson(tree(value));}
    static PartsTrialEvidence decode(PartsJson.Node root) throws IOException {
        try {root.object("kind","version","content","digest");if(!root.child("kind").text(64,true).equals("formula-workshop-trial-evidence")) throw new IOException("Use a parts trial evidence file");int envelopeVersion=root.child("version").integer(1,2);var body=root.child("content").object("id","plan","report","purpose","slot","notes");
            if(JSON.toJson(root.value()).getBytes(StandardCharsets.UTF_8).length>MAX_BYTES || JSON.toJson(body.child("plan").value()).getBytes(StandardCharsets.UTF_8).length>PartsPlanShareCodec.MAX_BYTES) throw new IOException("Embedded evidence exceeds size bound");var plan=PartsPlanShareCodec.decode(body.child("plan"));ActivityCardCodec.validate(body.child("report").value(),TuningExperiment.Snapshot.class);ActivityCardCodec.validate(body.child("purpose").value(),TuningPurpose.class);ActivityCardCodec.validate(body.child("notes").value(),String.class);
            var value=new PartsTrialEvidence(body.child("id").text(48,true),plan,JSON.fromJson(body.child("report").value(),TuningExperiment.Snapshot.class),JSON.fromJson(body.child("purpose").value(),TuningPurpose.class),body.child("slot").enumeration(TuningExperiment.Slot.class),body.child("notes").value().getAsString());if(envelopeVersion!=(value.purpose()!=null && value.purpose().version()==2?2:1)) throw new IOException("Evidence source version differs");if(!digest(value).equals(root.child("digest").text(64,true))) throw new IOException("Evidence checksum differs");return value;
        }catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid parts trial evidence",error);}
    }
    public static PartsTrialEvidence read(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {return decode(PartsJson.read(stream,MAX_BYTES,14));}}
    public static void write(Path path,PartsTrialEvidence value) throws IOException {write(path,value,true);}
    public static void write(Path path,PartsTrialEvidence value,boolean replace) throws IOException {
        PartsStore.writeExport(path,encode(value),MAX_BYTES,replace);
    }
    static JsonElement canonical(JsonElement value) {
        return canonical(value, false);
    }

    /** Portable lap and trace envelopes share the same sorted keys and decimal spelling. */
    static String normalizedDigest(JsonElement value) {
        var digest = ArchiveWriter.digest();
        var output = new DigestOutputStream(OutputStream.nullOutputStream(), digest);
        try (var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            JSON.toJson(canonical(value, true), writer);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static JsonElement canonical(JsonElement value, boolean normalizeNumbers) {
        if (value.isJsonObject()) {
            var source = value.getAsJsonObject();
            var result = new JsonObject();
            for (String key : new TreeSet<>(source.keySet())) {
                var child = source.get(key);
                if ((key.equals("requires") || key.equals("excludes")) && child.isJsonArray()) {
                    var sorted = new JsonArray();
                    child.getAsJsonArray().asList().stream().map(JsonElement::getAsString).sorted().forEach(sorted::add);
                    result.add(key, sorted);
                } else {
                    result.add(key, canonical(child, normalizeNumbers));
                }
            }
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            for (var child : value.getAsJsonArray()) result.add(canonical(child, normalizeNumbers));
            return result;
        }
        if (normalizeNumbers && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
            return new JsonPrimitive(value.getAsBigDecimal().stripTrailingZeros());
        return value;
    }
}
