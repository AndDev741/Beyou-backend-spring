package beyou.beyouapp.backend.domain.briefing;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.briefing.dto.BriefingNarrative;
import beyou.beyouapp.backend.domain.briefing.dto.DailyBriefingResponseDTO;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.user.User;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles one morning's briefing: facts every time, prose once a day.
 *
 * <p>The shape of this class is one decision. The facts are three or four indexed queries
 * and the user can change them from inside the dialog, so they are recomputed on every call
 * and never cached. The prose costs an LLM call against a free-tier chain, and nothing the
 * user does during the day makes yesterday's recap wrong enough to pay for again, so it is
 * generated once and read from the row afterwards.
 *
 * <p><b>Generated on demand and never in the nightly pass.</b> {@code DayCloseService} walks
 * every account that exists; a model call in that loop would spend the quota of people who
 * open the app on people who do not, and it would write the "today" half at 02:00, before
 * anything about today had happened.
 *
 * <p>The call is bounded rather than awaited. A free tier can take half a minute, and the
 * dialog opens from data the dashboard already holds, so only the prose area is kept
 * waiting — for {@link #NARRATIVE_DEADLINE}, after which the request returns
 * {@link NarrativeStatus#PENDING} and the call is left running. It writes the row when it
 * lands, so the next open of the same day usually finds it there.
 */
@Service
@Slf4j
public class DailyBriefingService {

    /**
     * How long a request will hold for the model before answering without it.
     *
     * <p>Eight seconds is picked against what the user is doing, not against what the
     * providers manage. The dashboard is already on screen and the dialog is already open;
     * this is the shimmer on two lines of text inside it. Past about eight seconds a person
     * has stopped waiting and started reading the rest of the panel, so the wait has bought
     * nothing and the request may as well return.
     *
     * <p>Nothing is cancelled when it expires. The point of the deadline is to free the
     * caller, not to abandon work already paid for.
     */
    static final Duration NARRATIVE_DEADLINE = Duration.ofSeconds(8);

    /**
     * How many narrations may be in flight at once across the whole instance.
     *
     * <p>Small on purpose. Every one of these is an outbound call that a free tier counts,
     * generation happens at most once per user per day, and requests arrive spread across
     * timezones and waking hours. A pool that cannot keep up rejects, and a rejected
     * narration is a panel with translated copy in it rather than an outage.
     */
    private static final int NARRATION_THREADS = 4;

    /**
     * Own instance, not an injected bean.
     *
     * <p>Boot 4 auto-configures Jackson 3 ({@code tools.jackson.databind}), so there is no
     * {@code com.fasterxml.jackson.databind.ObjectMapper} bean to inject — asking for one
     * fails the whole context at startup. Every other Jackson 2 user in this codebase does
     * the same thing ({@code SecurityFilter}, {@code StringListConverter}), and a mapper
     * with no configuration on it is safe to share: {@code ObjectMapper} is thread-safe once
     * configured, and this one never is.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DailyBriefingFactsBuilder factsBuilder;
    private final DailyBriefingNarrator narrator;
    private final DailyBriefingWrites writes;

    /**
     * Narrations currently running, keyed by briefing row.
     *
     * <p>Not an optimisation. Without it, a user with the app open on two devices fires two
     * model calls for the same row within a second of each other, and the second one is
     * money spent to overwrite the first with the same thing. Concurrent callers join the
     * one future here, and each waits out its own deadline against it.
     */
    private final Map<UUID, CompletableFuture<BriefingNarrative>> inFlight = new ConcurrentHashMap<>();

    private final ExecutorService narrationPool = Executors.newFixedThreadPool(
            NARRATION_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "briefing-narrator");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Whether the prose is generated at all.
     *
     * <p>Off leaves a fully working dialog with translated copy where the generated lines
     * would be — which is what makes it a usable switch during an incident, and what makes
     * the e2e suite able to run the feature without a model behind it.
     */
    private final boolean narrationEnabled;

    public DailyBriefingService(DailyBriefingFactsBuilder factsBuilder,
                                DailyBriefingNarrator narrator,
                                DailyBriefingWrites writes,
                                @Value("${briefing.narration-enabled:true}") boolean narrationEnabled) {
        this.factsBuilder = factsBuilder;
        this.narrator = narrator;
        this.writes = writes;
        this.narrationEnabled = narrationEnabled;
    }

    /** The briefing for the caller's own today, resolved in their timezone (R15). */
    public DailyBriefingResponseDTO briefingFor(User user) {
        return briefingFor(user, UserDateResolver.today(user));
    }

    /**
     * The same, against a day the caller names.
     *
     * <p>The date is a parameter rather than a clock read so that "what does a Wednesday
     * morning look like" is a question a test can ask without waiting for one. Same shape as
     * {@code UserStreakService.streakOf(user, referenceDay)}. Production callers use the
     * overload above; nothing outside this class should be deciding which day a user is in.
     */
    public DailyBriefingResponseDTO briefingFor(User user, LocalDate date) {
        DailyBriefingFactsBuilder.Facts facts = factsBuilder.build(user, date);

        DailyBriefingResponseDTO withoutNarrative = new DailyBriefingResponseDTO(
                date, facts.yesterday(), facts.today(),
                BriefingNarrative.absent(NarrativeStatus.UNAVAILABLE), null);

        // Nothing open, nothing scheduled, no goal moving and no deadline. There is no row
        // to create and certainly no model call to pay for: a dialog that greets somebody
        // every morning with "nothing happened" is how you teach them to close it unread.
        if (!withoutNarrative.worthShowing()) {
            return withoutNarrative;
        }

        DailyBriefing row = writes.findOrCreate(user, date);
        BriefingNarrative narrative = resolveNarrative(user, row, facts);

        return new DailyBriefingResponseDTO(
                date, facts.yesterday(), facts.today(), narrative, row.getSeenAt());
    }

    /** Acknowledges this day's dialog for the caller. Cross-device by design. */
    public void markSeen(User user) {
        markSeen(user, UserDateResolver.today(user));
    }

    /** The same, against a named day. See {@link #briefingFor(User, LocalDate)}. */
    public void markSeen(User user, LocalDate date) {
        writes.markSeen(user, date);
    }

    // ---- narrative ----

    private BriefingNarrative resolveNarrative(User user, DailyBriefing row,
                                               DailyBriefingFactsBuilder.Facts facts) {
        if (row.getNarrativeStatus() == NarrativeStatus.READY && row.getNarrativeJson() != null) {
            return readStored(row);
        }
        // UNAVAILABLE is not retried for the rest of the day. A chain in cooldown refuses
        // the retry too, and the panel reads identically either way — so a retry spends a
        // request to change nothing the user can see.
        if (row.getNarrativeStatus() == NarrativeStatus.UNAVAILABLE || !narrationEnabled) {
            return BriefingNarrative.absent(row.getNarrativeStatus());
        }
        return generate(user, row, facts);
    }

    private BriefingNarrative readStored(DailyBriefing row) {
        try {
            BriefingNarrative stored = OBJECT_MAPPER.readValue(
                    row.getNarrativeJson(), BriefingNarrative.class);
            // The status is authoritative on the row, not inside the blob: a column is what
            // the CHECK constraint guards and what a query can filter on.
            return new BriefingNarrative(NarrativeStatus.READY,
                    DailyBriefingNarrator.sanitize(stored.todayLines()),
                    DailyBriefingNarrator.sanitize(stored.yesterdayLines()));
        } catch (Exception e) {
            // Unreadable prose is a cosmetic loss, so it degrades rather than throwing. The
            // WARN is the alert: it means something wrote a shape this cannot read back.
            log.warn("Unreadable narrative on briefing {} — serving without it", row.getId(), e);
            return BriefingNarrative.absent(NarrativeStatus.UNAVAILABLE);
        }
    }

    private BriefingNarrative generate(User user, DailyBriefing row,
                                       DailyBriefingFactsBuilder.Facts facts) {
        UUID rowId = row.getId();
        CompletableFuture<BriefingNarrative> future;
        try {
            future = inFlight.computeIfAbsent(rowId, id -> submit(user, id, facts));
        } catch (RejectedExecutionException saturated) {
            log.warn("Narration pool saturated — briefing {} served without prose", rowId);
            return BriefingNarrative.absent(NarrativeStatus.PENDING);
        }

        try {
            return future.get(NARRATIVE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            // The ordinary outcome of a slow free tier, and not a failure. The call keeps
            // running and stores itself; this request simply stops waiting for it.
            return BriefingNarrative.absent(NarrativeStatus.PENDING);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return BriefingNarrative.absent(NarrativeStatus.PENDING);
        } catch (Exception failed) {
            // The narration itself already stored UNAVAILABLE before completing, so there is
            // nothing to write here.
            return BriefingNarrative.absent(NarrativeStatus.UNAVAILABLE);
        }
    }

    private CompletableFuture<BriefingNarrative> submit(User user, UUID rowId,
                                                       DailyBriefingFactsBuilder.Facts facts) {
        CompletableFuture<BriefingNarrative> future = CompletableFuture.supplyAsync(() -> {
            BriefingNarrative narrative = narrator.narrate(facts, user);
            writes.storeNarrative(rowId, NarrativeStatus.READY, writeJson(narrative));
            return narrative;
        }, narrationPool);

        // Registered on the future rather than in a finally inside the supplier, so the
        // entry outlives the work by exactly as long as it takes a waiting caller to be
        // handed the result.
        return future.whenComplete((narrative, error) -> {
            inFlight.remove(rowId);
            if (error != null) {
                log.warn("Briefing narration failed for {} — serving facts only", rowId, error);
                writes.storeNarrative(rowId, NarrativeStatus.UNAVAILABLE, null);
            }
        });
    }

    private String writeJson(BriefingNarrative narrative) {
        try {
            return OBJECT_MAPPER.writeValueAsString(narrative);
        } catch (Exception e) {
            // Serialising two lists of strings should not fail. If it does, the row keeps a
            // null narrative and the panel falls back, which is the same outcome as a
            // provider being down.
            log.warn("Could not serialise a briefing narrative", e);
            return null;
        }
    }

    /**
     * Lets in-flight narrations finish rather than killing them on shutdown.
     *
     * <p>A call already made has already been billed; letting it land means the row is
     * written and the user's next open finds prose instead of paying for it again. The wait
     * is bounded so a hung provider cannot hold a deploy open.
     */
    @PreDestroy
    void shutdown() {
        narrationPool.shutdown();
        try {
            if (!narrationPool.awaitTermination(NARRATIVE_DEADLINE.toSeconds(), TimeUnit.SECONDS)) {
                narrationPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            narrationPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
