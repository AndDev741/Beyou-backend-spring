package beyou.beyouapp.backend.domain.notebook.diagram;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one place a diagram is cleaned. Every diagram the server stores, sends or appends goes
 * through {@link #clean}: the generate endpoint, a study-chat answer, "save to page" and both
 * agent tools. Free-tier model output and hand-edited request bodies are equally untrusted.
 *
 * <p>It clips rather than rejects. The only refusal is a diagram left with fewer than
 * {@link #MIN_NODES} nodes, which has nothing to draw. The client keeps a copy of these rules
 * ({@code sanitizeDiagram} in {@code @beyou/state}) for blocks the server never saw, so the two
 * must stay in step rule for rule and in the same order: a diagram that passed one passes the
 * other unchanged. Cleaning a clean diagram changes nothing.
 *
 * <p>Order of work, which the id rule depends on: nodes with a blank label go first, then the
 * per-kind cap drops extras from the end, and only then are ids settled. A node keeps its own id
 * unless it is blank or an earlier node took it; the rest get {@code n<position>} (1-based in
 * the surviving list), counting up past any number already taken. Links are matched against
 * those final ids.
 */
public final class DiagramSanitizer {

    public static final int MIN_NODES = 2;
    public static final int MAX_NODES = 24;
    public static final int MAX_LINKS = 40;
    public static final int TITLE_MAX = 80;
    public static final int ID_MAX = 40;
    public static final int LABEL_MAX = 60;
    public static final int DETAIL_MAX = 140;
    public static final int LINK_LABEL_MAX = 40;

    private DiagramSanitizer() {}

    /** A clean copy of {@code raw}, or empty when fewer than two nodes survive. */
    public static Optional<DiagramSpec> clean(DiagramSpec raw) {
        if (raw == null) return Optional.empty();
        DiagramKind kind = raw.kind() == null ? DiagramKind.FLOW : raw.kind();
        String title = clip(raw.title(), TITLE_MAX);

        record Kept(String askedId, String label, String detail, DiagramTone tone) {
        }
        List<Kept> kept = new ArrayList<>();
        for (DiagramNode node : orEmpty(raw.nodes())) {
            if (node == null) continue;
            String label = clip(node.label(), LABEL_MAX);
            if (label.isEmpty()) continue;
            String detail = clip(node.detail(), DETAIL_MAX);
            kept.add(new Kept(clip(node.id(), ID_MAX), label, detail.isEmpty() ? null : detail,
                    node.tone() == null ? DiagramTone.NEUTRAL : node.tone()));
        }
        int cap = Math.min(MAX_NODES, kind.maxNodes());
        if (kept.size() > cap) {
            kept = kept.subList(0, cap);
        }
        if (kept.size() < MIN_NODES) return Optional.empty();

        // First pass: own ids, first come first served. Second pass: n<position> for the rest.
        Set<String> taken = new HashSet<>();
        String[] ids = new String[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            String asked = kept.get(i).askedId();
            if (!asked.isEmpty() && taken.add(asked)) ids[i] = asked;
        }
        List<DiagramNode> nodes = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            if (ids[i] == null) {
                int candidate = i + 1;
                while (taken.contains("n" + candidate)) candidate++;
                ids[i] = "n" + candidate;
                taken.add(ids[i]);
            }
            Kept k = kept.get(i);
            nodes.add(new DiagramNode(ids[i], k.label(), k.detail(), k.tone()));
        }

        Set<List<String>> seen = new LinkedHashSet<>();
        List<DiagramLink> links = new ArrayList<>();
        for (DiagramLink link : orEmpty(raw.links())) {
            if (links.size() >= MAX_LINKS) break;
            if (link == null) continue;
            String from = strip(link.from());
            String to = strip(link.to());
            if (!taken.contains(from) || !taken.contains(to)) continue;
            // A sequence diagram can send a message to itself; elsewhere a loop on one node draws nothing useful.
            if (from.equals(to) && kind != DiagramKind.SEQUENCE) continue;
            String label = clip(link.label(), LINK_LABEL_MAX);
            // In a sequence the same message twice is two rows; elsewhere it is one line drawn twice.
            if (kind != DiagramKind.SEQUENCE && !seen.add(List.of(from, to, label))) continue;
            links.add(new DiagramLink(from, to, label.isEmpty() ? null : label,
                    Boolean.TRUE.equals(link.dashed())));
        }
        return Optional.of(new DiagramSpec(DiagramSpec.VERSION, kind, title, List.copyOf(nodes), List.copyOf(links)));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String strip(String text) {
        return text == null ? "" : text.strip();
    }

    /** Stripped, then cut to {@code max} characters with the cut's trailing space removed. */
    static String clip(String text, int max) {
        String stripped = strip(text);
        return stripped.length() <= max ? stripped : stripped.substring(0, max).stripTrailing();
    }
}
