package beyou.beyouapp.backend.domain.notebook.source;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import jakarta.annotation.PreDestroy;
import beyou.beyouapp.backend.monitoring.UserContextLogFilter;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads a new source into chunks, and embeds them when an embedding model is configured.
 *
 * <p>Off the request thread: a 400-page PDF takes longer to read than anyone should wait on a
 * spinner, and the person can keep studying while the row moves from PENDING to READY. The job
 * starts AFTER the request's transaction commits, so the pool never looks for a row that is not
 * there yet.
 *
 * <p>Embedding failures do not fail the source. The chunks are already stored and searchable by
 * full text, which is what retrieval falls back to; a provider outage costs answer quality, not
 * the source.
 *
 * <p>{@code notebook.ingest.background=false} (the integration-test profile) stops the pool from
 * being used at all, and tests call {@link #ingest} themselves, on their own thread, so they can
 * assert on the result without waiting for a thread they do not control.
 */
@Service
@Slf4j
public class SourceIngestionService {

    private static final int THREADS = 2;

    private final SourceWrites writes;
    private final SourceChunkStore chunkStore;
    private final PdfTextExtractor pdfExtractor;
    private final LinkFetcher linkFetcher;
    private final EmbeddingClient embeddingClient;
    private final boolean background;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS, runnable -> {
        Thread thread = new Thread(runnable, "notebook-ingest");
        thread.setDaemon(true);
        return thread;
    });

    public SourceIngestionService(SourceWrites writes, SourceChunkStore chunkStore,
            PdfTextExtractor pdfExtractor, LinkFetcher linkFetcher, EmbeddingClient embeddingClient,
            @Value("${notebook.ingest.background:true}") boolean background) {
        this.writes = writes;
        this.chunkStore = chunkStore;
        this.pdfExtractor = pdfExtractor;
        this.linkFetcher = linkFetcher;
        this.embeddingClient = embeddingClient;
        this.background = background;
    }

    /** What is being read. The bytes of a PDF live only in this object until the job ends. */
    public sealed interface Payload permits PdfPayload, TextPayload, LinkPayload {
    }

    public record PdfPayload(byte[] bytes) implements Payload {
    }

    public record TextPayload(String text) implements Payload {
    }

    public record LinkPayload(String url) implements Payload {
    }

    /** Queues the job to run once the current transaction commits. */
    public void submit(UUID sourceId, UUID userId, Payload payload) {
        if (!background) return;
        // Off the request thread, so the user id every log line carries is set by hand.
        Runnable job = () -> pool.submit(() -> UserContextLogFilter.withUserId(userId,
                () -> ingest(sourceId, userId, payload)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    job.run();
                }
            });
        } else {
            job.run();
        }
    }

    /** The job itself. Public so the integration tests can run it on their own thread. */
    public void ingest(UUID sourceId, UUID userId, Payload payload) {
        try {
            if (!writes.progress(sourceId, NotebookSourceStatus.READING, 1)) return;
            List<TextChunker.PageText> pages;
            Integer pageCount = null;
            switch (payload) {
                case PdfPayload pdf -> {
                    PdfTextExtractor.Extracted extracted = readPdf(sourceId, pdf.bytes());
                    pages = extracted.pages();
                    pageCount = extracted.pageCount();
                }
                case TextPayload text -> pages = List.of(new TextChunker.PageText(null, text.text()));
                case LinkPayload link -> {
                    LinkFetcher.Fetched fetched = linkFetcher.fetch(link.url());
                    if (fetched.isPdf()) {
                        PdfTextExtractor.Extracted extracted = readPdf(sourceId, fetched.pdf());
                        pages = extracted.pages();
                        pageCount = extracted.pageCount();
                    } else {
                        if (fetched.title() != null) writes.retitle(sourceId, fetched.title());
                        pages = List.of(new TextChunker.PageText(null, fetched.text()));
                    }
                }
            }
            List<TextChunker.Chunk> chunks = TextChunker.chunk(pages);
            if (chunks.isEmpty()) {
                throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "Nothing readable in the source");
            }
            List<UUID> ids = chunkStore.insert(sourceId, userId, chunks);
            if (!writes.progress(sourceId, NotebookSourceStatus.READING, 55)) return;
            embed(sourceId, ids, chunks);
            int chars = chunks.stream().mapToInt(c -> c.content().length()).sum();
            writes.ready(sourceId, pageCount, chars);
        } catch (BusinessException expected) {
            log.info("Notebook source {} failed: {}", sourceId, expected.getErrorKey());
            writes.failed(sourceId, expected.getErrorKey().name());
        } catch (RuntimeException unexpected) {
            log.warn("Notebook source {} failed unexpectedly", sourceId, unexpected);
            writes.failed(sourceId, ErrorKey.NOTEBOOK_SOURCE_UNREADABLE.name());
        }
    }

    private PdfTextExtractor.Extracted readPdf(UUID sourceId, byte[] bytes) {
        int[] lastReported = {0};
        return pdfExtractor.extract(bytes, page -> {
            // Extraction is the first half of the bar. Reported every ten pages, not every page:
            // a write per page of a long book is a transaction per page for a progress bar.
            if (page - lastReported[0] >= 10) {
                lastReported[0] = page;
                writes.progress(sourceId, NotebookSourceStatus.READING, Math.min(50, 1 + page / 4));
            }
        });
    }

    private void embed(UUID sourceId, List<UUID> ids, List<TextChunker.Chunk> chunks) {
        if (!embeddingClient.enabled()) return;
        int batch = embeddingClient.batchSize();
        try {
            for (int from = 0; from < chunks.size(); from += batch) {
                int to = Math.min(chunks.size(), from + batch);
                List<String> inputs = chunks.subList(from, to).stream().map(TextChunker.Chunk::content).toList();
                List<float[]> vectors = embeddingClient.embed(inputs);
                chunkStore.setEmbeddings(ids.subList(from, to), vectors, embeddingClient.model());
                writes.progress(sourceId, NotebookSourceStatus.READING, 55 + (44 * to) / chunks.size());
            }
        } catch (RuntimeException providerDown) {
            // See the class comment: the source stays usable through full-text search.
            log.warn("Embedding source {} stopped part way, full-text search covers the rest: {}",
                    sourceId, providerDown.getMessage());
        }
    }

    /**
     * Sources a restart caught half-read. A PDF's bytes died with the process, so nothing can be
     * resumed; they are marked FAILED with a key that tells the person to add them again.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        int count = writes.failInterrupted(ErrorKey.NOTEBOOK_SOURCE_INTERRUPTED.name());
        if (count > 0) {
            log.info("Marked {} notebook source(s) interrupted by the restart", count);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
