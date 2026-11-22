package com.f1car.persistence;

import com.f1car.model.GhostLap;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

final class GhostSampleJson extends TypeAdapter<List<GhostLap.Sample>> {
    static final Type LIST_TYPE = new TypeToken<List<GhostLap.Sample>>() {}.getType();

    @Override
    public List<GhostLap.Sample> read(JsonReader reader) throws IOException {
        var samples = new ArrayList<GhostLap.Sample>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (samples.size() >= GhostLap.MAX_SAMPLES) throw new IOException("Too many ghost samples");
            samples.add(readSample(reader));
        }
        reader.endArray();
        return samples;
    }

    private static GhostLap.Sample readSample(JsonReader reader) throws IOException {
        // The pose constructor rejects missing components while accepting recorded zeroes.
        double time = Double.NaN;
        float x = Float.NaN, z = Float.NaN, yaw = Float.NaN;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "time" -> time = readComponent(reader, time);
                case "x" -> x = (float) readComponent(reader, x);
                case "z" -> z = (float) readComponent(reader, z);
                case "yaw" -> yaw = (float) readComponent(reader, yaw);
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        return new GhostLap.Sample(time, x, z, yaw);
    }

    private static double readComponent(JsonReader reader, double previous) throws IOException {
        if (!Double.isNaN(previous)) throw new IOException("Duplicate ghost pose field");
        double value = reader.nextDouble();
        if (!Double.isFinite(value)) throw new IOException("Invalid ghost pose field");
        return value;
    }

    @Override
    public void write(JsonWriter writer, List<GhostLap.Sample> samples) throws IOException {
        writer.beginArray();
        for (var sample : samples) writeSample(writer, sample);
        writer.endArray();
    }

    private static void writeSample(JsonWriter writer, GhostLap.Sample sample) throws IOException {
        // Field order and float formatting are part of the exchange checksum.
        writer.beginObject();
        writer.name("time").value(sample.time());
        writer.name("x").value(sample.x());
        writer.name("z").value(sample.z());
        writer.name("yaw").value(sample.yaw());
        writer.endObject();
    }
}
