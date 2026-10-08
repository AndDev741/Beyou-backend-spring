package beyou.beyouapp.backend.domain.briefing;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.briefing.dto.BriefingNarrative;
import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.briefing.dto.GoalPace;
import beyou.beyouapp.backend.domain.briefing.dto.RecoveryWindow;
import beyou.beyouapp.backend.domain.briefing.dto.TodayAhead;
import beyou.beyouapp.backend.domain.briefing.dto.YesterdayRecap;
import beyou.beyouapp.backend.user.User;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a briefing's facts into a few lines of prose, and nothing more.
 *
 * <p>Built like {@code OnboardingSuggestionService}: one stateless structured call per
 * request, no advisors, no tools, no chat memory. The difference is what happens when it
 * fails. The onboarding wizard has nothing to show without its suggestions, so it raises
 * {@code AI_UNAVAILABLE}; a briefing without prose is still a briefing, so failure here is
 * reported to the caller as a status and never as an exception the user sees.
 *
 * <p><b>The model is given finished numbers and asked to say what they mean.</b> It
 * receives counts, percentages and day gaps that {@link DailyBriefingFactsBuilder} already
 * computed, with an explicit instruction not to invent any others. Anything it does invent
 * stays in the prose, where it is at worst a clumsy sentence, and never reaches the figures
 * the dialog renders.
 *
 * <p>The first version handed it only what the panel already shows and asked it to phrase
 * that, and it did, every morning: "you have 25 items today and a 93-day streak" under the
 * line that said exactly that. So the facts block is now split in two. What is on screen is
 * labelled as such and the model is told not to repeat it; the {@link WeekSignals} beside it
 * are the things the screen cannot say, and those are what it is asked to talk about.
 */
@Component
@Slf4j
public class DailyBriefingNarrator {

    /** Lines per carousel page. Two or three read as a briefing; five read as an essay. */
    static final int MAX_LINES = 3;

    /** Per line. Roughly a sentence and a half, which is all a panel this size holds. */
    static final int MAX_LINE_LENGTH = 220;

    private final ChatClient chatClient;
    private final Resource systemTemplate;

    public DailyBriefingNarrator(ChatModel chatModel,
            @Value("classpath:/prompts/dailyBriefing.st") Resource systemTemplate) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.systemTemplate = systemTemplate;
    }

    /** What the model is asked to return. Prose only — every number is already decided. */
    public record NarrativePayload(List<String> todayLines, List<String> yesterdayLines) {}

    /**
     * One call, no retry.
     *
     * <p>Deliberately unlike the onboarding service, which retries once with a sterner
     * instruction. There, a parse failure loses the whole feature and a second attempt is
     * worth the wait. Here the caller is already holding a request open against a deadline
     * and the fallback is decent copy the user will not notice missing, so a retry buys a
     * slower dialog to avoid a cosmetic loss.
     *
     * @throws RuntimeException whatever the chain raises. The caller turns it into
     *                          {@link NarrativeStatus#UNAVAILABLE}
     */
    public BriefingNarrative narrate(DailyBriefingFactsBuilder.Facts facts, User user) {
        NarrativePayload payload = chatClient.prompt()
                .system(s -> s.text(systemTemplate)
                        .param("language", language(user))
                        // The account's day, not the server's. A user in UTC-3 opening the app
                        // at 22:00 is still on the day the facts describe, and a server clock
                        // past midnight would tell the model it is tomorrow.
                        .param("today", dayLabel(facts.date())))
                .user(factsMessage(facts))
                .call()
                .entity(NarrativePayload.class);

        return new BriefingNarrative(
                NarrativeStatus.READY,
                sanitize(payload == null ? null : payload.todayLines()),
                sanitize(payload == null ? null : payload.yesterdayLines()));
    }

    /** "Wednesday 2026-10-07": the weekday is what lets the model say "on Sunday". */
    public static String dayLabel(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + date;
    }

    private static String language(User user) {
        return user.getLanguageInUse() != null && !user.getLanguageInUse().isBlank()
                ? user.getLanguageInUse()
                : "en";
    }

    /**
     * The facts, rendered as the plainest text that still says what happened.
     *
     * <p>Written by hand rather than serialized as JSON. The free tiers phrase a labelled
     * sentence far better than they phrase a nested object, and a hand-written block is also
     * the place to state what the numbers MEAN — that a skip is a deliberate choice and not
     * a failure, that an unscheduled day cannot break a streak. Those are the two things a
     * model gets wrong about this product when left to guess.
     *
     * <p>Two sections, and the split is the instruction. ALREADY ON SCREEN is context the
     * model needs for tone (was yesterday finished, is anything on today) and must not
     * repeat. SIGNALS is what it is there to talk about. Yesterday's open items are left out
     * by name and by count on purpose: the user is checking them off beside the prose while
     * reading it, so any sentence about them is wrong a minute after it lands.
     *
     * <p>No journal text appears here, ever. {@link WeekSignals} carries mood levels only.
     *
     * <p>Static and public so the prompt can be asserted without standing up a model. What
     * goes into it is the whole defence: everything here has already been computed, so a
     * fact left out does not fail, it silently produces vaguer prose.
     */
    public static String factsMessage(DailyBriefingFactsBuilder.Facts facts) {
        StringBuilder sb = new StringBuilder();
        YesterdayRecap yesterday = facts.yesterday();
        TodayAhead today = facts.today();
        WeekSignals signals = facts.signals() == null ? WeekSignals.empty() : facts.signals();

        sb.append("ALREADY ON SCREEN (context only, do not restate)\n");
        if (!yesterday.hadRoutine()) {
            sb.append("- Yesterday no routine covered the day. Nothing was asked of the user, so "
                    + "there is nothing to praise and nothing to forgive.\n");
        } else {
            sb.append("- Yesterday finished: ").append(yesterday.complete() ? "yes" : "no")
              .append(". A skip is a choice the user made, never a failure.\n");
        }
        if (!today.scheduledToday()) {
            sb.append("- No routine covers today. The streak counts scheduled days only, so "
                    + "nothing is at risk.\n");
        } else {
            sb.append("- A routine covers today.\n");
        }
        sb.append("- Streak: ").append(today.currentStreak())
          .append(" days, personal best ").append(today.bestStreak()).append(".\n");
        RecoveryWindow recovery = today.recovery();
        if (recovery != null) {
            sb.append("- Older days still have open items; the oldest stops being checkable in ")
              .append(recovery.daysUntilExpiry()).append(" days.\n");
        }

        sb.append("\nSIGNALS (what to talk about)\n");
        boolean any = false;

        if (signals.thisWeekPercent() != null) {
            any = true;
            sb.append("- Over the seven days ending yesterday, ").append(signals.thisWeekPercent())
              .append("% of the items asked were checked");
            if (signals.previousWeekPercent() != null) {
                sb.append(" (the seven days before: ").append(signals.previousWeekPercent())
                  .append("%)");
            }
            sb.append(". Skips are left out of both.\n");
        }
        for (WeekSignals.ItemPattern item : signals.slipping()) {
            any = true;
            sb.append("- Slipping: \"").append(item.name()).append("\" was left open on ")
              .append(item.missed()).append(" of the ").append(item.asked())
              .append(" days it came up this week.\n");
        }
        for (WeekSignals.ItemPattern item : signals.steady()) {
            any = true;
            sb.append("- Steady: \"").append(item.name()).append("\" was checked on all ")
              .append(item.asked()).append(" days it came up this week.\n");
        }
        if (yesterday.focusCycles() > 0) {
            any = true;
            sb.append("- Focus sessions run yesterday: ").append(yesterday.focusCycles()).append(".\n");
        }
        if (yesterday.moodLevel() != null || signals.moodAverage() != null) {
            any = true;
            sb.append("- Mood, levels only (1 awful, 5 great):");
            if (yesterday.moodLevel() != null) {
                sb.append(" yesterday ").append(yesterday.moodLevel()).append(';');
            }
            if (signals.moodAverage() != null) {
                sb.append(" this week averaged ").append(trim(signals.moodAverage()))
                  .append(" over ").append(signals.moodDaysLogged()).append(" days logged");
                if (signals.previousMoodAverage() != null) {
                    sb.append(", the week before ").append(trim(signals.previousMoodAverage()));
                }
                sb.append('.');
            }
            sb.append('\n');
        }

        List<GoalAhead> goals = today.goalsAhead() == null ? List.of() : today.goalsAhead();
        for (GoalAhead goal : goals) {
            any = true;
            sb.append("- Goal \"").append(goal.name()).append("\": ")
              .append(goal.percentComplete()).append("% done (")
              .append(trim(goal.currentValue())).append(" of ").append(trim(goal.targetValue()))
              .append(' ').append(goal.unit()).append("), ");
            switch (goal.pace()) {
                case REACHED -> sb.append("target reached but not marked as done yet. Marking it "
                        + "done is what pays its XP.");
                case OVERDUE -> sb.append("ended ").append(Math.abs(goal.daysRemaining()))
                        .append(" days ago without reaching the target.");
                case BEHIND, ON_TRACK -> {
                    sb.append(goal.daysRemaining() == 0
                            ? "ends today"
                            : "ends in " + goal.daysRemaining() + " days");
                    sb.append(goal.pace() == GoalPace.BEHIND ? ", behind pace" : ", on pace")
                      .append(" (a straight line from its start says ")
                      .append(goal.expectedPercent()).append("% by today)");
                    if (goal.requiredPerDay() != null) {
                        sb.append(". Reaching it takes ").append(trim(goal.requiredPerDay()))
                          .append(' ').append(goal.unit()).append(" a day from today on");
                    }
                    sb.append('.');
                }
            }
            sb.append('\n');
        }

        if (!any) {
            sb.append("- Nothing stands out. Say something plain, or return empty lists.\n");
        }
        return sb.toString();
    }

    /** Whole numbers without a trailing .0, which is how a person writes a count. */
    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    /**
     * Never trust free-tier output: cap the count, cap the length, drop the blanks.
     *
     * <p>The length cap is a truncation and not a rejection on purpose. A line one character
     * over is still a usable line, and refusing the whole response over it would throw away
     * a good answer for a formatting miss.
     */
    public static List<String> sanitize(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH))
                .limit(MAX_LINES)
                .toList();
    }
}
