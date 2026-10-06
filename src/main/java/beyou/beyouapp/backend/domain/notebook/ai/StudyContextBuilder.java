package beyou.beyouapp.backend.domain.notebook.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.ai.dto.CitationDTO;
import beyou.beyouapp.backend.domain.notebook.source.NotebookRetriever;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSource;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceService;
import beyou.beyouapp.backend.domain.notebook.source.SourceChunkStore;
import beyou.beyouapp.backend.domain.notebook.source.TextChunker;
import beyou.beyouapp.backend.domain.notebook.study.StudyScopes;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * The numbered passages an answer is allowed to stand on, and the citations that come back.
 *
 * <p>Two kinds of passage. The person's own notes come from the pages the page's study scope
 * covers ({@link StudyScopes}: the page and its ancestors by default, or everything under it, or
 * the whole topic), always fresh because they are read from the pages' current text, and the
 * sources come from
 * {@link NotebookRetriever} over the sources this page may read. Notes go first: they are what
 * the person wrote, and an answer that agrees with them is the one that sticks.
 *
 * <p>Numbers are assigned here and nowhere else, and {@link #citations} drops any number the model
 * wrote that is not in the list. A citation the reader cannot open is worse than none.
 */
@Component
@RequiredArgsConstructor
public class StudyContextBuilder {

    static final int SOURCE_PASSAGES = 6;
    static final int PAGE_PASSAGES = 3;
    static final int MAX_PASSAGE_CHARS = 1500;
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)\\]");

    private final NotebookSourceService sourceService;
    private final NotebookRetriever retriever;
    private final StudyScopes scopes;

    /**
     * @param goal what the person set out to get from studying the page (the study room's setup),
     *             or null. Not a passage: it steers the answer and is never cited.
     */
    public record Context(List<Passage> passages, String goal) {

        public Context(List<Passage> passages) {
            this(passages, null);
        }

        public boolean isEmpty() {
            return passages.isEmpty();
        }

        /** The passages as the model reads them. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            if (goal != null && !goal.isBlank()) {
                sb.append("The person's goal for studying this: ").append(goal.strip())
                        .append("\nAim the answer at that goal.\n\n");
            }
            sb.append("Passages:\n");
            for (Passage p : passages) {
                sb.append('[').append(p.number()).append("] ").append(p.label()).append('\n')
                        .append(p.text()).append("\n\n");
            }
            return sb.toString();
        }
    }

    /** Passages for a question asked on {@code page}. */
    public Context forQuestion(User user, NotebookPage page, String question) {
        return build(user, page, question, false);
    }

    /** Passages for a task about the page as a whole (a summary, a quiz): no question to rank by. */
    public Context forPage(User user, NotebookPage page) {
        String text = page.getContentText() == null ? "" : page.getContentText();
        String query = page.getTitle() + " " + text.substring(0, Math.min(500, text.length()));
        return build(user, page, query, true);
    }

    private Context build(User user, NotebookPage page, String query, boolean wholePage) {
        List<Passage> passages = new ArrayList<>();
        Set<String> tokens = tokens(query);

        // The person's notes, from the pages the study scope covers, best matches only.
        List<ScoredText> notes = new ArrayList<>();
        for (NotebookPage scoped : scopes.pagesFor(page, page.getStudyScope())) {
            String text = scoped.getContentText();
            if (text == null || text.isBlank()) continue;
            boolean self = scoped.getId().equals(page.getId());
            for (TextChunker.Chunk chunk : TextChunker.chunk(List.of(new TextChunker.PageText(null, text)))) {
                int score = overlap(tokens, chunk.content()) + (self ? 2 : 0);
                // A whole-page task reads the page in order; a question reads what matches.
                if (wholePage && self) score = 1000 - chunk.ordinal();
                notes.add(new ScoredText(scoped, chunk.content(), score));
            }
        }
        notes.sort(Comparator.comparingInt(ScoredText::score).reversed());
        int notesWanted = wholePage ? PAGE_PASSAGES + 1 : PAGE_PASSAGES;
        for (ScoredText note : notes.stream().filter(n -> n.score() > 0).limit(notesWanted).toList()) {
            passages.add(new Passage(passages.size() + 1, Passage.PAGE, null, null, note.page().getId(),
                    "Your page \"" + note.page().getTitle() + "\"", null, cap(note.text())));
        }

        List<NotebookSource> sources = sourceService.readable(user, page.getId());
        Map<UUID, NotebookSource> byId = sourceService.byId(sources);
        for (SourceChunkStore.StoredChunk chunk :
                retriever.retrieve(NotebookSourceService.ids(sources), query, SOURCE_PASSAGES)) {
            NotebookSource source = byId.get(chunk.sourceId());
            if (source == null) continue;
            String label = source.getTitle() + " (" + source.getKind().name().toLowerCase(Locale.ROOT)
                    + (chunk.pageNumber() != null ? ", page " + chunk.pageNumber() : "") + ")";
            passages.add(new Passage(passages.size() + 1, Passage.SOURCE, source.getId(), chunk.id(), null,
                    label, chunk.pageNumber(), cap(chunk.content())));
        }
        return new Context(passages, page.getStudyGoal());
    }

    /**
     * The citations an answer earned: every [n] in its text plus any the model listed, kept only
     * when n is a passage that was actually given, in the order they first appear.
     */
    public List<CitationDTO> citations(Context context, String markdown, List<Integer> listed) {
        Map<Integer, Passage> byNumber = new HashMap<>();
        context.passages().forEach(p -> byNumber.put(p.number(), p));
        Set<Integer> numbers = new LinkedHashSet<>();
        if (markdown != null) {
            Matcher m = MARKER.matcher(markdown);
            while (m.find()) {
                for (String n : m.group(1).split("\\s*,\\s*")) {
                    numbers.add(Integer.parseInt(n));
                }
            }
        }
        if (listed != null) numbers.addAll(listed);
        return numbers.stream()
                .filter(byNumber::containsKey)
                .map(byNumber::get)
                .map(p -> new CitationDTO(p.number(), p.kind(), p.sourceId(), p.chunkId(), p.pageId(),
                        p.label(), p.pageNumber(), excerpt(p.text())))
                .toList();
    }

    /** [n] markers that point at nothing are removed, so the reader never sees a dead one. */
    public String withoutDeadMarkers(Context context, String markdown) {
        if (markdown == null) return "";
        Set<Integer> valid = context.passages().stream().map(Passage::number).collect(Collectors.toSet());
        Matcher m = MARKER.matcher(markdown);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            List<String> kept = Arrays.stream(m.group(1).split("\\s*,\\s*"))
                    .filter(n -> valid.contains(Integer.parseInt(n)))
                    .toList();
            m.appendReplacement(out, kept.isEmpty() ? "" : Matcher.quoteReplacement("[" + String.join(", ", kept) + "]"));
        }
        m.appendTail(out);
        return out.toString().replaceAll(" +([.,;:])", "$1").strip();
    }

    static Set<String> tokens(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() >= 3)
                .collect(Collectors.toSet());
    }

    private static int overlap(Set<String> tokens, String text) {
        if (tokens.isEmpty()) return 0;
        Set<String> words = tokens(text);
        int score = 0;
        for (String token : tokens) {
            if (words.contains(token)) score++;
        }
        return score;
    }

    private static String cap(String text) {
        return text.length() <= MAX_PASSAGE_CHARS ? text : text.substring(0, MAX_PASSAGE_CHARS) + "…";
    }

    private static String excerpt(String text) {
        return text.length() <= 280 ? text : text.substring(0, 280).strip() + "…";
    }

    private record ScoredText(NotebookPage page, String text, int score) {
    }
}
