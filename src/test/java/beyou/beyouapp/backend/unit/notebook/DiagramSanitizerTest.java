package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.diagram.DiagramKind;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramLink;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramNode;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSanitizer;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramTone;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every rule of the diagram spec's cleaning (contract section 1), one test each. The client's
 * sanitizeDiagram mirrors these rules, so a change here is a change there too.
 */
class DiagramSanitizerTest {

    private static DiagramNode node(String id, String label) {
        return new DiagramNode(id, label, null, null);
    }

    private static DiagramLink link(String from, String to) {
        return new DiagramLink(from, to, null, null);
    }

    private static DiagramSpec spec(DiagramKind kind, List<DiagramNode> nodes, List<DiagramLink> links) {
        return new DiagramSpec(null, kind, "A title", nodes, links);
    }

    private static DiagramSpec clean(DiagramSpec raw) {
        return DiagramSanitizer.clean(raw).orElseThrow();
    }

    private static List<DiagramNode> nodes(int count) {
        List<DiagramNode> nodes = new ArrayList<>();
        for (int i = 1; i <= count; i++) nodes.add(node("n" + i, "Node " + i));
        return nodes;
    }

    // ---------------------------------------------------------------- spec

    @Test
    void theVersionIsAlwaysOne() {
        DiagramSpec raw = new DiagramSpec(7, DiagramKind.FLOW, "t", nodes(2), List.of());
        assertThat(clean(raw).v()).isEqualTo(1);
    }

    @Test
    void aMissingKindIsFlow() {
        assertThat(clean(spec(null, nodes(2), null)).kind()).isEqualTo(DiagramKind.FLOW);
    }

    /** An unknown kind binds to null (DiagramKind.fromJson) and so cleans to FLOW, in any case. */
    @Test
    void anUnknownKindIsFlowAndAnyCaseIsAccepted() {
        JsonMapper mapper = JsonMapper.builder().build();
        DiagramSpec tree = mapper.readValue("""
                {"kind":"TREE","nodes":[{"id":"a","label":"A","tone":"glow"},{"id":"b","label":"B","tone":"success"}]}
                """, DiagramSpec.class);
        DiagramSpec map = mapper.readValue("""
                {"kind":"map","nodes":[{"label":"A"},{"label":"B"}]}
                """, DiagramSpec.class);

        assertThat(clean(tree).kind()).isEqualTo(DiagramKind.FLOW);
        assertThat(clean(tree).nodes()).extracting(DiagramNode::tone).containsExactly(DiagramTone.NEUTRAL, DiagramTone.SUCCESS);
        assertThat(clean(map).kind()).isEqualTo(DiagramKind.MAP);
    }

    @Test
    void theTitleIsStrippedClippedTo80AndMayBeEmpty() {
        DiagramSpec longTitle = new DiagramSpec(1, DiagramKind.FLOW, "  " + "t".repeat(100) + "  ", nodes(2), null);
        DiagramSpec noTitle = new DiagramSpec(1, DiagramKind.FLOW, null, nodes(2), null);

        assertThat(clean(longTitle).title()).hasSize(80);
        assertThat(clean(noTitle).title()).isEmpty();
    }

    @Test
    void theCutLeavesNoTrailingSpace() {
        String label = "a".repeat(59) + " b";
        assertThat(clean(spec(DiagramKind.FLOW, List.of(node("x", label), node("y", "Y")), null)).nodes().get(0).label())
                .isEqualTo("a".repeat(59));
    }

    // --------------------------------------------------------------- nodes

    @Test
    void fewerThanTwoNodesIsNotADiagram() {
        assertThat(DiagramSanitizer.clean(spec(DiagramKind.FLOW, nodes(1), null))).isEmpty();
        assertThat(DiagramSanitizer.clean(spec(DiagramKind.FLOW, null, null))).isEmpty();
        assertThat(DiagramSanitizer.clean(null)).isEmpty();
    }

    @Test
    void aNodeWithABlankLabelIsDroppedAndSoAreItsLinks() {
        DiagramSpec raw = spec(DiagramKind.FLOW,
                Arrays.asList(node("a", "A"), node("b", "   "), null, node("c", "C")),
                List.of(link("a", "b"), link("b", "c"), link("a", "c")));

        DiagramSpec clean = clean(raw);

        assertThat(clean.nodes()).extracting(DiagramNode::id).containsExactly("a", "c");
        assertThat(clean.links()).extracting(l -> l.from() + ">" + l.to()).containsExactly("a>c");
    }

    /** Two nodes left after dropping the blanks is still a diagram; one is not. */
    @Test
    void theNodeCountIsTakenAfterTheBlanksGo() {
        DiagramSpec raw = spec(DiagramKind.FLOW, List.of(node("a", "A"), node("b", ""), node("c", " ")), null);
        assertThat(DiagramSanitizer.clean(raw)).isEmpty();
    }

    @Test
    void atMost24NodesExtrasDroppedFromTheEnd() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW, nodes(30), null));
        assertThat(clean.nodes()).hasSize(24);
        assertThat(clean.nodes().get(23).id()).isEqualTo("n24");
    }

    @Test
    void eachKindHasItsOwnCap() {
        assertThat(clean(spec(DiagramKind.LAYERS, nodes(20), null)).nodes()).hasSize(10);
        assertThat(clean(spec(DiagramKind.CYCLE, nodes(20), null)).nodes()).hasSize(10);
        assertThat(clean(spec(DiagramKind.TIMELINE, nodes(20), null)).nodes()).hasSize(12);
        assertThat(clean(spec(DiagramKind.SEQUENCE, nodes(20), null)).nodes()).hasSize(6);
        assertThat(clean(spec(DiagramKind.MAP, nodes(30), null)).nodes()).hasSize(24);
    }

    @Test
    void labelsAndIdsAreStrippedAndClipped() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW,
                List.of(node("  " + "i".repeat(50), "  " + "L".repeat(70)), node("b", "B")), null));

        assertThat(clean.nodes().get(0).id()).isEqualTo("i".repeat(40));
        assertThat(clean.nodes().get(0).label()).isEqualTo("L".repeat(60));
    }

    @Test
    void aDetailIsClippedTo140AndBlankBecomesNull() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW,
                List.of(new DiagramNode("a", "A", " " + "d".repeat(200), null), new DiagramNode("b", "B", "   ", null)), null));

        assertThat(clean.nodes().get(0).detail()).hasSize(140);
        assertThat(clean.nodes().get(1).detail()).isNull();
    }

    @Test
    void aMissingToneIsNeutral() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW,
                List.of(new DiagramNode("a", "A", null, DiagramTone.DANGER), node("b", "B")), null));
        assertThat(clean.nodes()).extracting(DiagramNode::tone).containsExactly(DiagramTone.DANGER, DiagramTone.NEUTRAL);
    }

    /**
     * Ids are settled after the blanks and the cap: a node keeps its id unless it is blank or an
     * earlier node has it, and the rest get n + their position. The second node has none and
     * takes n2; the fourth repeats "a" and takes n4; the fifth is blank and takes n5.
     */
    @Test
    void missingOrRepeatedIdsBecomeNAndTheirPositionSkippingTakenOnes() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW, List.of(
                node("a", "A"), node(null, "B"), node("n3", "C"), node("a", "D"), node(" ", "E")), null));

        assertThat(clean.nodes()).extracting(DiagramNode::id).containsExactly("a", "n2", "n3", "n4", "n5");
    }

    @Test
    void aGeneratedIdMovesPastANumberALaterNodeAlreadyHolds() {
        // The first node has no id, position 1, but a later node is called n1: it becomes n2.
        DiagramSpec clean = clean(spec(DiagramKind.FLOW, List.of(node(null, "A"), node("n1", "B"), node("n2", "C")), null));

        assertThat(clean.nodes()).extracting(DiagramNode::id).containsExactly("n3", "n1", "n2");
    }

    @Test
    void positionsCountTheSurvivingNodesNotTheOriginalOnes() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW, List.of(node("x", " "), node(null, "A"), node(null, "B")), null));

        assertThat(clean.nodes()).extracting(DiagramNode::id).containsExactly("n1", "n2");
    }

    // --------------------------------------------------------------- links

    @Test
    void aLinkToAnUnknownIdIsDropped() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW, nodes(2), List.of(link("n1", "n9"), link(" n1 ", "n2"))));
        assertThat(clean.links()).extracting(l -> l.from() + ">" + l.to()).containsExactly("n1>n2");
    }

    @Test
    void aSelfLinkIsDroppedExceptInASequence() {
        List<DiagramLink> links = List.of(link("n1", "n1"), link("n1", "n2"));

        assertThat(clean(spec(DiagramKind.FLOW, nodes(2), links)).links()).hasSize(1);
        assertThat(clean(spec(DiagramKind.SEQUENCE, nodes(2), links)).links()).hasSize(2);
    }

    @Test
    void anExactDuplicateIsDroppedExceptInASequence() {
        List<DiagramLink> links = List.of(new DiagramLink("n1", "n2", "ok", false), new DiagramLink("n1", "n2", " ok ", true),
                new DiagramLink("n1", "n2", "other", false));

        assertThat(clean(spec(DiagramKind.FLOW, nodes(2), links)).links()).extracting(DiagramLink::label)
                .containsExactly("ok", "other");
        assertThat(clean(spec(DiagramKind.SEQUENCE, nodes(2), links)).links()).hasSize(3);
    }

    @Test
    void atMost40LinksAreKept() {
        List<DiagramLink> links = new ArrayList<>();
        for (int i = 0; i < 60; i++) links.add(new DiagramLink("n1", "n2", "m" + i, false));

        assertThat(clean(spec(DiagramKind.FLOW, nodes(2), links)).links()).hasSize(40);
    }

    /** Dropped links do not count toward the 40. */
    @Test
    void theLinkCapCountsKeptLinksOnly() {
        List<DiagramLink> links = new ArrayList<>();
        for (int i = 0; i < 50; i++) links.add(link("n1", "gone"));
        links.add(link("n1", "n2"));

        assertThat(clean(spec(DiagramKind.FLOW, nodes(2), links)).links()).hasSize(1);
    }

    @Test
    void aLinkLabelIsClippedTo40AndBlankBecomesNull() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW, nodes(3),
                List.of(new DiagramLink("n1", "n2", "l".repeat(50), null), new DiagramLink("n2", "n3", "  ", null))));

        assertThat(clean.links().get(0).label()).hasSize(40);
        assertThat(clean.links().get(1).label()).isNull();
    }

    @Test
    void dashedDefaultsToFalse() {
        DiagramSpec clean = clean(spec(DiagramKind.FLOW, nodes(3),
                List.of(new DiagramLink("n1", "n2", null, null), new DiagramLink("n2", "n3", null, true))));
        assertThat(clean.links()).extracting(DiagramLink::dashed).containsExactly(false, true);
    }

    @Test
    void linksAreMatchedAgainstTheFinalIds() {
        // The repeated "a" became n2, so a link to "n2" reaches it and a link to "a" reaches the first.
        DiagramSpec clean = clean(spec(DiagramKind.FLOW, List.of(node("a", "A"), node("a", "B")),
                List.of(link("a", "n2"))));

        assertThat(clean.links()).extracting(l -> l.from() + ">" + l.to()).containsExactly("a>n2");
    }

    // ----------------------------------------------------------- stability

    @Test
    void cleaningACleanDiagramChangesNothing() {
        DiagramSpec once = clean(spec(DiagramKind.SEQUENCE,
                List.of(node(null, " Client "), node("api", "API"), node("api", "DB"), node("x", "")),
                List.of(new DiagramLink("n1", "api", "GET", null), new DiagramLink("api", "n1", "200", true),
                        new DiagramLink("api", "api", "cache", null))));

        assertThat(clean(once)).isEqualTo(once);
    }
}
