package beyou.beyouapp.backend.domain.routine.schedule;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum WeekDay {
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    Sunday;

    /** Lowercased alias -> constant: the English names in any case plus the Portuguese
     *  day names (with and without the accent and the -feira suffix). Values normalize
     *  to the capitalized constants, which is what the schedule_days CHECK constraint
     *  and the frontends expect on the wire. */
    private static final Map<String, WeekDay> ALIASES = new HashMap<>();

    static {
        for (WeekDay day : values()) {
            ALIASES.put(day.name().toLowerCase(Locale.ROOT), day);
        }
        ALIASES.put("segunda", Monday);
        ALIASES.put("segunda-feira", Monday);
        ALIASES.put("terca", Tuesday);
        ALIASES.put("terça", Tuesday);
        ALIASES.put("terca-feira", Tuesday);
        ALIASES.put("terça-feira", Tuesday);
        ALIASES.put("quarta", Wednesday);
        ALIASES.put("quarta-feira", Wednesday);
        ALIASES.put("quinta", Thursday);
        ALIASES.put("quinta-feira", Thursday);
        ALIASES.put("sexta", Friday);
        ALIASES.put("sexta-feira", Friday);
        ALIASES.put("sabado", Saturday);
        ALIASES.put("sábado", Saturday);
        ALIASES.put("domingo", Sunday);
    }

    /**
     * Jackson entry point for every WeekDay arriving as JSON, agent tool arguments
     * and REST DTOs alike. Tool-calling models weigh the tool description over the
     * schema, and the free-tier ones send "MONDAY" or "segunda-feira"; before this,
     * each such miss burned an extra LLM round trip on a bind error (the onboarding
     * service normalizes day names by hand for the same reason). Unknown values
     * still fail, with the accepted names in the message so the model can correct
     * its next attempt.
     */
    @JsonCreator
    public static WeekDay fromJson(String value) {
        WeekDay day = value == null ? null : ALIASES.get(value.trim().toLowerCase(Locale.ROOT));
        if (day == null) {
            throw new IllegalArgumentException("Unknown week day \"" + value
                    + "\": accepted values are Monday..Sunday (any letter case) or Portuguese day names");
        }
        return day;
    }
}
