package beyou.beyouapp.backend.unit.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import beyou.beyouapp.backend.domain.briefing.DailyBriefing;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingFactsBuilder;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingNarrator;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingRepository;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingService;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingWrites;
import beyou.beyouapp.backend.domain.briefing.NarrativeStatus;
import beyou.beyouapp.backend.domain.briefing.WeekSignals;
import beyou.beyouapp.backend.domain.briefing.dto.DailyBriefingResponseDTO;
import beyou.beyouapp.backend.domain.briefing.dto.TodayAhead;
import beyou.beyouapp.backend.domain.briefing.dto.YesterdayRecap;
import beyou.beyouapp.backend.security.aiconsent.AiConsentPolicy;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.enums.AiConsent;

/**
 * The daily briefing reaches the AI provider from the server, without the person opening an
 * AI feature. So without a yes the person gets the facts and never the model call, and prose
 * stored before a "no" is not handed out either.
 */
class DailyBriefingConsentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);

    private final DailyBriefingFactsBuilder factsBuilder = mock(DailyBriefingFactsBuilder.class);
    private final DailyBriefingNarrator narrator = mock(DailyBriefingNarrator.class);
    private final DailyBriefingWrites writes = mock(DailyBriefingWrites.class);
    private final DailyBriefingRepository repository = mock(DailyBriefingRepository.class);
    private final AiConsentPolicy policy = mock(AiConsentPolicy.class);

    private User user(AiConsent consent) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setAiConsent(consent);
        return user;
    }

    private DailyBriefingService service() {
        return new DailyBriefingService(factsBuilder, narrator, writes, repository, policy, true);
    }

    @ParameterizedTest
    @EnumSource(value = AiConsent.class, names = {"UNASKED", "DECLINED"})
    void withoutAYesTheFactsComeAndTheModelIsNeverCalled(AiConsent consent) {
        User user = user(consent);
        YesterdayRecap yesterday = new YesterdayRecap(TODAY.minusDays(1), true, false, 2, 0, 20,
                List.of(), 0, null, 0, 0);
        TodayAhead today = new TodayAhead(3, true, 1, 4, List.of(), null, List.of(), List.of());
        when(factsBuilder.build(any(), any())).thenReturn(
                new DailyBriefingFactsBuilder.Facts(yesterday, today, WeekSignals.empty()));
        DailyBriefing row = new DailyBriefing(user, TODAY);
        row.setId(UUID.randomUUID());
        when(writes.findOrCreate(any(), any())).thenReturn(row);

        DailyBriefingResponseDTO briefing = service().briefingFor(user, TODAY);

        assertThat(briefing.today().scheduledItemCount()).isEqualTo(3);
        // UNAVAILABLE, not PENDING: the client polls a PENDING narrative and this one never comes.
        assertThat(briefing.narrative().status()).isEqualTo(NarrativeStatus.UNAVAILABLE);
        verifyNoInteractions(narrator);
    }

    @ParameterizedTest
    @EnumSource(value = AiConsent.class, names = {"UNASKED", "DECLINED"})
    void proseStoredBeforeANoIsNotHandedOut(AiConsent consent) {
        User user = user(consent);
        DailyBriefing row = new DailyBriefing(user, TODAY);
        row.setId(UUID.randomUUID());
        row.setNarrativeStatus(NarrativeStatus.READY);
        row.setNarrativeJson("{\"status\":\"READY\",\"todayLines\":[\"Go\"],\"yesterdayLines\":[]}");
        when(repository.findByUserIdAndBriefingDate(any(), any())).thenReturn(Optional.of(row));

        assertThat(service().narrativeFor(user, TODAY).status()).isEqualTo(NarrativeStatus.UNAVAILABLE);
    }
}
