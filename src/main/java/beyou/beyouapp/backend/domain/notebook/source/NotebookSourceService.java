package beyou.beyouapp.backend.domain.notebook.source;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.source.dto.AddLinkRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.AddTextRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.PassageDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.SourceDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * The sources attached to pages, and which of them a page can read.
 *
 * <p>Scope: a page reads its own sources and every ancestor's, up to the topic. That is what makes
 * one textbook added to the topic answer questions on every node in it, while a paper added to
 * one node stays on that node. Siblings never see each other's sources.
 */
@Service
@RequiredArgsConstructor
public class NotebookSourceService {

    /** Sources attached to one page. The scope adds the ancestors' on top. */
    public static final int MAX_SOURCES_PER_PAGE = 20;
    public static final int MAX_PDF_BYTES = LinkFetcher.MAX_PDF_BYTES;

    private final NotebookSourceRepository sourceRepository;
    private final NotebookPageRepository pageRepository;
    private final NotebookOwnership ownership;
    private final SourceIngestionService ingestion;
    private final LinkFetcher linkFetcher;
    private final SourceChunkStore chunkStore;

    @Transactional(readOnly = true)
    public List<SourceDTO> list(User user, UUID pageId) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        Map<UUID, NotebookPage> scope = scope(page);
        return sourceRepository.findByPageIdInOrderByCreatedAtAsc(scope.keySet()).stream()
                .map(s -> toDto(s, scope.get(s.getPageId()), !s.getPageId().equals(pageId)))
                .toList();
    }

    /** The sources answers on this page may quote: in scope, switched on, and fully read. */
    @Transactional(readOnly = true)
    public List<NotebookSource> readable(User user, UUID pageId) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        return sourceRepository.findByPageIdInOrderByCreatedAtAsc(scope(page).keySet()).stream()
                .filter(s -> s.isEnabled() && s.getStatus() == NotebookSourceStatus.READY)
                .toList();
    }

    /** The page and its ancestors, by id, the page itself included. */
    @Transactional(readOnly = true)
    public Map<UUID, NotebookPage> scope(NotebookPage page) {
        Map<UUID, NotebookPage> scope = new HashMap<>();
        NotebookPage current = page;
        while (current != null && !scope.containsKey(current.getId())) {
            scope.put(current.getId(), current);
            current = current.getParentId() == null ? null : pageRepository.findById(current.getParentId()).orElse(null);
        }
        return scope;
    }

    @Transactional
    public SourceDTO addPdf(User user, UUID pageId, MultipartFile file) {
        NotebookPage page = attachable(user, pageId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "No file was sent");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_TOO_LARGE, "The PDF must be under 15 MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException unreadable) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "The upload could not be read");
        }
        // The magic number, not the client's content type: a browser will call anything a PDF.
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "The file is not a PDF");
        }
        NotebookSource source = newSource(page, NotebookSourceKind.PDF, titleFromFilename(file.getOriginalFilename()), null);
        ingestion.submit(source.getId(), user.getId(), new SourceIngestionService.PdfPayload(bytes));
        return toDto(source, page, false);
    }

    @Transactional
    public SourceDTO addLink(User user, UUID pageId, AddLinkRequestDTO request) {
        NotebookPage page = attachable(user, pageId);
        // Checked now, not only in the background job, so a refused link never becomes a row.
        var uri = linkFetcher.validate(request.url());
        String title = (uri.getHost() + (uri.getPath() == null ? "" : uri.getPath())).replaceAll("/+$", "");
        NotebookSource source = newSource(page, NotebookSourceKind.LINK, title, uri.toString());
        ingestion.submit(source.getId(), user.getId(), new SourceIngestionService.LinkPayload(uri.toString()));
        return toDto(source, page, false);
    }

    @Transactional
    public SourceDTO addText(User user, UUID pageId, AddTextRequestDTO request) {
        NotebookPage page = attachable(user, pageId);
        NotebookSource source = newSource(page, NotebookSourceKind.TEXT, request.title().strip(), null);
        ingestion.submit(source.getId(), user.getId(), new SourceIngestionService.TextPayload(request.text()));
        return toDto(source, page, false);
    }

    @Transactional
    public SourceDTO setEnabled(User user, UUID sourceId, boolean enabled) {
        NotebookSource source = owned(user, sourceId);
        source.setEnabled(enabled);
        source.setUpdatedAt(Instant.now());
        return toDto(source, pageRepository.findById(source.getPageId()).orElse(null), false);
    }

    @Transactional
    public void delete(User user, UUID sourceId) {
        sourceRepository.delete(owned(user, sourceId));
    }

    @Transactional(readOnly = true)
    public PassageDTO passage(User user, UUID sourceId, UUID chunkId) {
        NotebookSource source = owned(user, sourceId);
        SourceChunkStore.StoredChunk chunk = chunkStore.byIds(List.of(chunkId)).stream()
                .filter(c -> c.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorKey.NOTEBOOK_SOURCE_NOT_FOUND, "Passage not found"));
        Map<Integer, String> around = chunkStore.around(sourceId, chunk.ordinal()).stream()
                .collect(Collectors.toMap(SourceChunkStore.StoredChunk::ordinal, SourceChunkStore.StoredChunk::content));
        return new PassageDTO(source.getId(), source.getTitle(), source.getKind(), source.getUrl(),
                chunk.id(), chunk.pageNumber(), chunk.content(),
                around.get(chunk.ordinal() - 1), around.get(chunk.ordinal() + 1));
    }

    /** Titles of these sources, for labelling citations. */
    public Map<UUID, NotebookSource> byId(List<NotebookSource> sources) {
        Map<UUID, NotebookSource> map = new HashMap<>();
        sources.forEach(s -> map.put(s.getId(), s));
        return map;
    }

    private NotebookPage attachable(User user, UUID pageId) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        if (sourceRepository.countByPageId(pageId) >= MAX_SOURCES_PER_PAGE) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_LIMIT_REACHED,
                    "A page holds at most " + MAX_SOURCES_PER_PAGE + " sources");
        }
        return page;
    }

    private NotebookSource newSource(NotebookPage page, NotebookSourceKind kind, String title, String url) {
        Instant now = Instant.now();
        NotebookSource source = new NotebookSource();
        source.setUser(page.getUser());
        source.setPageId(page.getId());
        source.setKind(kind);
        source.setTitle(title.length() > 255 ? title.substring(0, 255) : title);
        source.setUrl(url);
        source.setStatus(NotebookSourceStatus.PENDING);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        return sourceRepository.save(source);
    }

    private NotebookSource owned(User user, UUID sourceId) {
        NotebookSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(ErrorKey.NOTEBOOK_SOURCE_NOT_FOUND, "Source not found"));
        ownership.page(user.getId(), source.getPageId());
        return source;
    }

    /** The file name the person picked, without any path a browser or a crafted request put in. */
    static String titleFromFilename(String original) {
        if (original == null || original.isBlank()) return "Untitled PDF";
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        return name.isEmpty() ? "Untitled PDF" : name;
    }

    static SourceDTO toDto(NotebookSource s, NotebookPage page, boolean inherited) {
        return new SourceDTO(s.getId(), s.getPageId(), page == null ? null : page.getTitle(), inherited,
                s.getKind(), s.getTitle(), s.getUrl(), s.getStatus(), s.getProgress(), s.getErrorKey(),
                s.isEnabled(), s.getPageCount(), s.getCharCount(), s.getCreatedAt());
    }

    /** The ids of these sources, for the retriever. */
    public static Set<UUID> ids(List<NotebookSource> sources) {
        return sources.stream().map(NotebookSource::getId).collect(Collectors.toSet());
    }
}
