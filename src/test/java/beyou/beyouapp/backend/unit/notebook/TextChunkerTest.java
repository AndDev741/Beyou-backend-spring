package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.source.TextChunker;
import beyou.beyouapp.backend.domain.notebook.source.TextChunker.Chunk;
import beyou.beyouapp.backend.domain.notebook.source.TextChunker.PageText;

class TextChunkerTest {

    @Test
    void shortParagraphsArePackedAndPagesAreKept() {
        List<Chunk> chunks = TextChunker.chunk(List.of(
                new PageText(1, "First paragraph.\n\nSecond paragraph."),
                new PageText(2, "On page two.")));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).pageNumber()).isEqualTo(1);
        assertThat(chunks.get(0).content()).isEqualTo("First paragraph.\nSecond paragraph.");
        assertThat(chunks.get(1).pageNumber()).isEqualTo(2);
        assertThat(chunks.get(1).ordinal()).isEqualTo(1);
    }

    @Test
    void aLongParagraphIsCutAtSentenceEndsAndNothingIsLost() {
        String sentence = "This sentence is exactly long enough to matter here. ";
        String paragraph = sentence.repeat(80).strip();

        List<Chunk> chunks = TextChunker.chunk(List.of(new PageText(null, paragraph)));

        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(1600));
        String joined = String.join(" ", chunks.stream().map(Chunk::content).toList()).replace('\n', ' ');
        assertThat(joined.replaceAll("\\s+", " ")).isEqualTo(paragraph);
    }

    @Test
    void linesInsideAParagraphAreJoined() {
        List<Chunk> chunks = TextChunker.chunk(List.of(new PageText(3, "A line broken\nby the PDF layout.")));

        assertThat(chunks.get(0).content()).isEqualTo("A line broken by the PDF layout.");
    }
}
