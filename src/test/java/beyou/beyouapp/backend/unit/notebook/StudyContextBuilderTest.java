package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.ai.Passage;
import beyou.beyouapp.backend.domain.notebook.ai.StudyContextBuilder;
import beyou.beyouapp.backend.domain.notebook.ai.dto.CitationDTO;

/** A citation the reader cannot open is worse than none: invented numbers are dropped. */
class StudyContextBuilderTest {

    private final StudyContextBuilder builder = new StudyContextBuilder(null, null, null);
    private final StudyContextBuilder.Context context = new StudyContextBuilder.Context(List.of(
            new Passage(1, Passage.PAGE, null, null, UUID.randomUUID(), "Your page \"Trees\"", null, "Notes"),
            new Passage(2, Passage.SOURCE, UUID.randomUUID(), UUID.randomUUID(), null, "CLRS (pdf, page 296)", 296, "Text")));

    @Test
    void onlyNumbersThatWereGivenBecomeCitations() {
        List<CitationDTO> citations = builder.citations(context, "A [2]. B [1, 7]. C [9].", List.of(5, 1));

        assertThat(citations).extracting(CitationDTO::n).containsExactly(2, 1);
        assertThat(citations.get(0).pageNumber()).isEqualTo(296);
        assertThat(citations.get(1).kind()).isEqualTo(Passage.PAGE);
    }

    @Test
    void deadMarkersAreRemovedFromTheText() {
        assertThat(builder.withoutDeadMarkers(context, "Kept [2]. Gone [9]. Mixed [1, 9]."))
                .isEqualTo("Kept [2]. Gone. Mixed [1].");
    }
}
