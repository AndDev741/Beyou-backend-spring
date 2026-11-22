package beyou.beyouapp.backend.domain.secondbrain;

import java.util.List;

/** Everything one answer taught: profile lines and notes on things. Either may be empty. */
public record Learned(List<LearnedFact> facts, List<LearnedNote> notes) {

    public static final Learned NOTHING = new Learned(List.of(), List.of());

    public static Learned facts(List<LearnedFact> facts) {
        return new Learned(facts, List.of());
    }
}
