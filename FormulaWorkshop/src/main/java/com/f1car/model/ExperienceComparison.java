package com.f1car.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Gate-by-gate timing of two complete recordings with exactly the same driving conditions. */
public final class ExperienceComparison {
    public record Gate(int number, double referenceSplit, double recentSplit, double referenceElapsed, double recentElapsed) {
        public double splitDifference() {return recentSplit - referenceSplit;}
        public double elapsedDifference() {return recentElapsed - referenceElapsed;}
    }

    private final ExperienceRun reference, recent;
    private final List<Gate> gates;

    public ExperienceComparison(ExperienceRun reference, ExperienceRun recent) {
        this.reference = Objects.requireNonNull(reference);
        this.recent = Objects.requireNonNull(recent);
        if (reference.id().equals(recent.id()) || !reference.conditions().equals(recent.conditions()))
            throw new IllegalArgumentException("Experience comparison requires two independent recordings under the same conditions");
        var rows = new ArrayList<Gate>();
        double beforeReference = 0, beforeRecent = 0;
        for (int index = 0; index < reference.gateTimes().size(); index++) {
            double elapsedReference = reference.gateTimes().get(index);
            double elapsedRecent = recent.gateTimes().get(index);
            rows.add(new Gate(index + 1, elapsedReference - beforeReference, elapsedRecent - beforeRecent,
                    elapsedReference, elapsedRecent));
            beforeReference = elapsedReference;
            beforeRecent = elapsedRecent;
        }
        gates = List.copyOf(rows);
    }

    public ExperienceRun reference() {return reference;}
    public ExperienceRun recent() {return recent;}
    public List<Gate> gates() {return gates;}
    public double difference() {return recent.duration() - reference.duration();}
}
