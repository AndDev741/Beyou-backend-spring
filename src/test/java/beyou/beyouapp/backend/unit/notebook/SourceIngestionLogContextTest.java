package beyou.beyouapp.backend.unit.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import beyou.beyouapp.backend.domain.notebook.source.EmbeddingClient;
import beyou.beyouapp.backend.domain.notebook.source.LinkFetcher;
import beyou.beyouapp.backend.domain.notebook.source.PdfTextExtractor;
import beyou.beyouapp.backend.domain.notebook.source.SourceChunkStore;
import beyou.beyouapp.backend.domain.notebook.source.SourceIngestionService;
import beyou.beyouapp.backend.domain.notebook.source.SourceWrites;
import beyou.beyouapp.backend.monitoring.UserContextLogFilter;

/**
 * A source is read on the ingest pool, after the request that added it has returned, so the
 * user id the request's log lines carried does not come along by itself. Those are the lines
 * that say a PDF was unreadable or a link timed out, which is exactly when "whose?" matters.
 *
 * <p>The pool has two platform threads that live for the whole run, so the id also has to be
 * gone after the job: otherwise the next user's job would log under this one's id.
 */
class SourceIngestionLogContextTest {

    @Test
    void aSourceIsReadWithItsOwnersIdInTheLogContext() throws Exception {
        SourceWrites writes = mock(SourceWrites.class);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<String> seen = new AtomicReference<>();
        when(writes.progress(any(), any(), anyInt())).thenAnswer(inv -> {
            seen.set(MDC.get(UserContextLogFilter.USER_ID_KEY));
            ran.countDown();
            return false; // stop the job here; the log context is all this test is about
        });
        SourceIngestionService service = new SourceIngestionService(writes, mock(SourceChunkStore.class),
                mock(PdfTextExtractor.class), mock(LinkFetcher.class), mock(EmbeddingClient.class), true);
        UUID userId = UUID.randomUUID();

        service.submit(UUID.randomUUID(), userId, new SourceIngestionService.TextPayload("notes"));

        assertTrue(ran.await(5, TimeUnit.SECONDS), "the job never ran");
        assertEquals(userId.toString(), seen.get());
    }

    @Test
    void theHelperSetsTheIdForTheWorkAndRemovesItAfter() {
        UUID userId = UUID.randomUUID();
        AtomicReference<String> during = new AtomicReference<>();

        UserContextLogFilter.withUserId(userId, () -> during.set(MDC.get(UserContextLogFilter.USER_ID_KEY)));

        assertEquals(userId.toString(), during.get());
        assertEquals(null, MDC.get(UserContextLogFilter.USER_ID_KEY));
    }
}
