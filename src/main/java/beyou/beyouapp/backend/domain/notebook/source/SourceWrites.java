package beyou.beyouapp.backend.domain.notebook.source;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The status writes ingestion makes from its pool thread, each in a transaction of its own.
 *
 * <p>The request that added the source has long returned by the time these run, so there is no
 * transaction to join. A row that is gone (the person deleted the source while it was being
 * read) is not an error: there is nothing left to update, and the writes say so by returning.
 * Same shape as {@code DailyBriefingWrites}.
 */
@Component
@RequiredArgsConstructor
public class SourceWrites {

    private final NotebookSourceRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean progress(UUID sourceId, NotebookSourceStatus status, int progress) {
        return repository.findById(sourceId).map(source -> {
            source.setStatus(status);
            source.setProgress(Math.max(0, Math.min(100, progress)));
            source.setUpdatedAt(Instant.now());
            return true;
        }).orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retitle(UUID sourceId, String title) {
        repository.findById(sourceId).ifPresent(source -> {
            source.setTitle(title.length() > 255 ? title.substring(0, 255) : title);
            source.setUpdatedAt(Instant.now());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ready(UUID sourceId, Integer pageCount, int charCount) {
        repository.findById(sourceId).ifPresent(source -> {
            source.setStatus(NotebookSourceStatus.READY);
            source.setProgress(100);
            source.setPageCount(pageCount);
            source.setCharCount(charCount);
            source.setErrorKey(null);
            source.setUpdatedAt(Instant.now());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(UUID sourceId, String errorKey) {
        repository.findById(sourceId).ifPresent(source -> {
            source.setStatus(NotebookSourceStatus.FAILED);
            source.setErrorKey(errorKey);
            source.setUpdatedAt(Instant.now());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failInterrupted(String errorKey) {
        return repository.failInterrupted(errorKey, Instant.now());
    }
}
