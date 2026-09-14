package beyou.beyouapp.backend.domain.briefing;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.briefing.dto.BriefingNarrative;
import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.briefing.dto.OpenItem;
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
 * <p><b>The model is given finished numbers and asked only to phrase them.</b> It receives
 * counts, percentages and day gaps that {@link DailyBriefingFactsBuilder} already computed,
 * with an explicit instruction not to invent any others. Anything it does invent stays in
 * the prose, where it is at worst a clumsy sentence, and never reaches the figures the
 * dialog renders.
 */
@Component
@Slf4j
public class DailyBriefingNarrator {

    /** Lines per carousel page. Two or three read as a briefing; five read as an essay. */
    static final int MAX_LINES = 3;

    /** Per line. Roughly a sentence and a half, which is all a panel this size holds. */
    static final int MAX_LINE_LENGTH = 220;

    /** How many open item names are worth naming in the prompt before it is just a list. */
    private static final int MAX_NAMED_ITEMS = 6;

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
                        .param("today", LocalDate.now().toString()))
                .user(factsMessage(facts))
                .call()
                .entity(NarrativePayload.class);

        return new BriefingNarrative(
                NarrativeStatus.READY,
                sanitize(payload == null ? null : payload.todayLines()),
                sanitize(payload == null ? null : payload.yesterdayLines()));
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
     * <p>Static and public so the prompt can be asserted without standing up a model. What
     * goes into it is the whole defence: everything here has already been computed, so a
     * fact left out does not fail, it silently produces vaguer prose.
     */
    public static String factsMessage(DailyBriefingFactsBuilder.Facts facts) {
        StringBuilder sb = new StringBuilder();
        YesterdayRecap yesterday = facts.yesterday();
        TodayAhead today = facts.today();

        sb.append("YESTERDAY (").append(yesterday.date()).append(")\n");
        if (!yesterday.hadRoutine()) {
            sb.append("- No routine covered the day. Nothing was asked of the user, so there is "
                    + "nothing to praise and nothing to forgive.\n");
        } else {
            sb.append("- Completed: ").append(yesterday.complete() ? "yes" : "no").append('\n');
            sb.append("- Checked: ").append(yesterday.doneCount())
              .append(", deliberately skipped: ").append(yesterday.skippedCount())
              .append(" (a skip is a choice the user made, never a failure)")
              .append(", still open: ").append(yesterday.openItems().size()).append('\n');
            sb.append("- XP earned: ").append(Math.round(yesterday.xpEarned())).append('\n');
            if (yesterday.focusCycles() > 0) {
                sb.append("- Focus sessions run: ").append(yesterday.focusCycles()).append('\n');
            }
            if (yesterday.moodLevel() != null) {
                sb.append("- Mood logged: ").append(yesterday.moodLevel()).append(" out of 5\n");
            }
            if (!yesterday.openItems().isEmpty()) {
                sb.append("- Still open: ").append(names(yesterday.openItems())).append('\n');
            }
        }

        sb.append("\nTODAY\n");
        if (!today.scheduledToday()) {
            sb.append("- No routine covers today. The streak counts scheduled days only, so "
                    + "nothing is at risk.\n");
        } else {
            sb.append("- Items scheduled: ").append(today.scheduledItemCount()).append('\n');
        }
        sb.append("- Current streak: ").append(today.currentStreak())
          .append(" days, personal best: ").append(today.bestStreak()).append('\n');

        for (GoalAhead goal : today.goalsApproaching()) {
            sb.append("- Goal \"").append(goal.name()).append("\": ")
              .append(goal.percentComplete()).append("% done (")
              .append(trim(goal.currentValue())).append(" of ").append(trim(goal.targetValue()))
              .append(' ').append(goal.unit()).append("), ")
              .append(goal.daysRemaining() < 0
                      ? "overdue by " + Math.abs(goal.daysRemaining()) + " days"
                      : goal.daysRemaining() + " days left")
              .append('\n');
        }

        RecoveryWindow recovery = today.recovery();
        if (recovery != null) {
            sb.append("- ").append(recovery.openItems().size())
              .append(" items are still open on older days. The oldest (")
              .append(recovery.oldestOpenDay()).append(") stops being checkable in ")
              .append(recovery.daysUntilExpiry()).append(" days and is now worth ")
              .append(recovery.remainingXpPercent()).append("% of its XP.\n");
        }

        return sb.toString();
    }

    private static String names(List<OpenItem> items) {
        List<String> named = items.stream()
                .map(OpenItem::itemName)
                .filter(Objects::nonNull)
                .limit(MAX_NAMED_ITEMS)
                .toList();
        String joined = String.join(", ", named);
        return items.size() > named.size()
                ? joined + " and " + (items.size() - named.size()) + " more"
                : joined;
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
