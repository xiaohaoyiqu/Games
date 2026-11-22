package com.f1car.persistence;

import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

/** The time-trial format records exactly three sector durations. */
final class GhostSectorJson extends TypeAdapter<List<Double>> {
    static final Type LIST_TYPE = new TypeToken<List<Double>>() {}.getType();

    @Override public List<Double> read(JsonReader reader) throws IOException {
        reader.beginArray();
        var sectors = List.of(reader.nextDouble(), reader.nextDouble(), reader.nextDouble());
        if (reader.hasNext()) throw new IOException("Too many ghost sectors");
        reader.endArray();
        return sectors;
    }

    @Override public void write(JsonWriter writer, List<Double> sectors) throws IOException {
        writer.beginArray();
        for (double duration : sectors) writer.value(duration);
        writer.endArray();
    }
}
