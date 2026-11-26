package beyou.beyouapp.backend.unit.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

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
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.enums.AiConsent;

/**
 * The daily briefing reaches the AI provider from the server, without the person opening an
 * AI feature. So a person who said no must get the facts and never the model call.
 */
class DailyBriefingConsentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);

    private final DailyBriefingFactsBuilder factsBuilder = mock(DailyBriefingFactsBuilder.class);
    private final DailyBriefingNarrator narrator = mock(DailyBriefingNarrator.class);
    private final DailyBriefingWrites writes = mock(DailyBriefingWrites.class);
    private final DailyBriefingRepository repository = mock(DailyBriefingRepository.class);

    @Test
    void aDeclinedPersonGetsTheFactsAndNoModelCall() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setAiConsent(AiConsent.DECLINED);

        YesterdayRecap yesterday = new YesterdayRecap(TODAY.minusDays(1), true, false, 2, 0, 20,
                List.of(), 0, null, 0, 0);
        TodayAhead today = new TodayAhead(3, true, 1, 4, List.of(), null, List.of(), List.of());
        when(factsBuilder.build(any(), any())).thenReturn(
                new DailyBriefingFactsBuilder.Facts(yesterday, today, WeekSignals.empty()));
        DailyBriefing row = new DailyBriefing(user, TODAY);
        row.setId(UUID.randomUUID());
        when(writes.findOrCreate(any(), any())).thenReturn(row);

        DailyBriefingService service = new DailyBriefingService(factsBuilder, narrator, writes, repository, true);
        DailyBriefingResponseDTO briefing = service.briefingFor(user, TODAY);

        assertThat(briefing.today().scheduledItemCount()).isEqualTo(3);
        // UNAVAILABLE, not PENDING: the client polls a PENDING narrative and this one never comes.
        assertThat(briefing.narrative().status()).isEqualTo(NarrativeStatus.UNAVAILABLE);
        verifyNoInteractions(narrator);
    }
}
