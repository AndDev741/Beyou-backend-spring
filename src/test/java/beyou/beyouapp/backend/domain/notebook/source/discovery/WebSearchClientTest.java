package beyou.beyouapp.backend.domain.notebook.source.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;

/**
 * The two search providers' answers, parsed from responses shaped like the real ones. Gemini's
 * sources are the grounding chunks Google returned, never URLs in the model's text, and each
 * chunk's excerpt is the first sentence of the answer it supports.
 */
class WebSearchClientTest {

    @Test
    void tavilyResultsBecomeHitsInOrderUpToTheMax() {
        String body = """
                {"query":"subjunctive","results":[
                  {"title":"Spanish subjunctive","url":"https://example.org/subjunctive","content":"When to use it.","score":0.9},
                  {"title":"No url","url":"","content":"Dropped."},
                  {"title":"Conditionals","url":"https://example.org/si","content":"Si clauses.","score":0.8},
                  {"title":"Third","url":"https://example.org/third","content":"Over the max."}
                ]}""";

        List<SearchHit> hits = WebSearchClient.parseTavily(body, 2);

        assertThat(hits).containsExactly(
                new SearchHit("Spanish subjunctive", "https://example.org/subjunctive", "When to use it."),
                new SearchHit("Conditionals", "https://example.org/si", "Si clauses."));
    }

    @Test
    void geminiSourcesAreItsGroundingChunksWithTheTextEachSupports() {
        String body = """
                {"candidates":[{"content":{"parts":[{"text":"SpanishDict explains the triggers. The RAE grammar is the reference."}]},
                  "groundingMetadata":{
                    "webSearchQueries":["spanish subjunctive c1"],
                    "groundingChunks":[
                      {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA","title":"spanishdict.com"}},
                      {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/BBB","title":"rae.es"}},
                      {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/CCC","title":"unused.org"}}
                    ],
                    "groundingSupports":[
                      {"segment":{"startIndex":0,"endIndex":36,"text":"SpanishDict explains the triggers."},"groundingChunkIndices":[0]},
                      {"segment":{"startIndex":37,"endIndex":72,"text":"The RAE grammar is the reference."},"groundingChunkIndices":[1,0]}
                    ]}}]}""";

        List<SearchHit> hits = WebSearchClient.parseGemini(body, 8);

        assertThat(hits).containsExactly(
                new SearchHit("spanishdict.com", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA",
                        "SpanishDict explains the triggers."),
                new SearchHit("rae.es", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/BBB",
                        "The RAE grammar is the reference."),
                new SearchHit("unused.org", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/CCC", ""));
    }

    /** No grounding (a refusal, or Search off) means nothing to offer, not URLs from the text. */
    @Test
    void aGeminiAnswerWithoutGroundingOffersNothing() {
        String body = """
                {"candidates":[{"content":{"parts":[{"text":"Try https://made-up.example/course"}]}}]}""";

        assertThat(WebSearchClient.parseGemini(body, 8)).isEmpty();
    }

    @Test
    void theKeysPickTheProviderAndTavilyWins() {
        assertThat(new DiscoveryProperties("tvly", null, "gem", null, null, null).provider())
                .isEqualTo(DiscoveryProperties.Provider.TAVILY);
        assertThat(new DiscoveryProperties("", null, "gem", null, null, null).provider())
                .isEqualTo(DiscoveryProperties.Provider.GEMINI);
        assertThat(new DiscoveryProperties(null, null, " ", null, null, null).provider()).isNull();
    }

    @Test
    void linksAreComparedWithoutFragmentTrailingSlashOrWww() {
        assertThat(SourceDiscoveryService.normalise("https://www.Example.org/notes/#part-2"))
                .isEqualTo(SourceDiscoveryService.normalise("http://example.org/notes"));
        assertThat(SourceDiscoveryService.normalise("https://example.org/notes?page=2"))
                .isNotEqualTo(SourceDiscoveryService.normalise("https://example.org/notes"));
    }

    @Test
    void theQueryNamesThePageWhenTheDescriptionDoesNot() {
        NotebookPage page = new NotebookPage();
        page.setTitle("Subjunctive");
        page.setTopicId(java.util.UUID.randomUUID());
        page.setKind(beyou.beyouapp.backend.domain.notebook.NotebookPageKind.PAGE);

        assertThat(SourceDiscoveryService.query("exercises with answers", page, "Spanish C1"))
                .isEqualTo("exercises with answers (Spanish C1: Subjunctive)");
        assertThat(SourceDiscoveryService.query("subjunctive exercises", page, "Spanish C1"))
                .isEqualTo("subjunctive exercises");
    }
}
