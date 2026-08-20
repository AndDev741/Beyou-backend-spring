package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.enums.UserRole;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U5 — the admin triage surface: list, filter, count, re-status, reply.
 *
 * Covers R11 (every submission carries a triage status, visible only to the
 * admin), R12 (the admin reads, filters and re-statuses submissions in a
 * protected console that also shows aggregate counts) and R14 (the admin
 * writes a reply). The silence of a status change (R15/KD4) is proven in
 * {@link FeedbackAdminNotificationIntegrationTest}, which can watch the mail
 * transport.
 *
 * Runs through the real security filter chain: every route here is under
 * {@code /feedback/admin/**}, which SecurityConfig gates to ROLE_ADMIN.
 */
@AutoConfigureMockMvc
class FeedbackAdminApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin-triage-admin@beyou.test";
    private static final String AUTHOR_EMAIL = "admin-triage-author@beyou.test";
    private static final String PASSWORD = "TestPassword1!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserService userService;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    FeedbackRepository feedbackRepository;

    @Autowired
    FeedbackReplyRepository feedbackReplyRepository;

    @Autowired
    FeedbackAttachmentRepository attachmentRepository;

    private User author;
    private String adminToken;
    private String authorToken;

    @BeforeEach
    void setUp() throws Exception {
        // The listing and count assertions here are exact, and this class is the
        // one that reads the WHOLE table rather than one user's rows. Sibling
        // classes are expected to leave it clean; start from certainty anyway.
        purgeAllFeedback();

        recreateUser(ADMIN_EMAIL, "triage admin", UserRole.ADMIN);
        author = recreateUser(AUTHOR_EMAIL, "triage author", UserRole.USER);

        adminToken = login(ADMIN_EMAIL);
        authorToken = login(AUTHOR_EMAIL);
    }

    @AfterEach
    void tearDown() {
        // Sibling classes assert the feedback table is empty — leave nothing behind.
        purgeAllFeedback();
        deleteUser(ADMIN_EMAIL);
        deleteUser(AUTHOR_EMAIL);
    }

    private void purgeAllFeedback() {
        feedbackReplyRepository.deleteAllInBatch();
        attachmentRepository.deleteAllInBatch();
        feedbackRepository.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- listing

    @Test
    @DisplayName("the listing returns only the submissions the filters ask for")
    void listingReturnsOnlyWhatTheFiltersAskFor() throws Exception {
        UUID openBug = submit("BUG", "Check-in button does nothing.");
        UUID takingCareBug = submit("BUG", "Routine order resets itself.");
        UUID openFeature = submit("FEATURE_REQUEST", "Let me drag routine sections.");
        UUID closedOther = submit("OTHER", "Just saying hello.");

        setStatus(takingCareBug, FeedbackStatus.TAKING_CARE);
        setStatus(closedOther, FeedbackStatus.CLOSED);

        assertThat(idsOf(listing("?status=OPEN")))
                .containsExactlyInAnyOrder(openBug, openFeature);

        assertThat(idsOf(listing("?category=BUG")))
                .containsExactlyInAnyOrder(openBug, takingCareBug);

        assertThat(idsOf(listing("?status=OPEN&category=BUG")))
                .containsExactly(openBug);

        assertThat(idsOf(listing("?status=CLOSED")))
                .containsExactly(closedOther);

        // Unfiltered: everything, newest first.
        assertThat(idsOf(listing("")))
                .containsExactly(closedOther, openFeature, takingCareBug, openBug);
    }

    @Test
    @DisplayName("the listing is paginated and reports the totals of the filtered set")
    void listingIsPaginatedAndReportsTotals() throws Exception {
        submit("BUG", "First report.");
        submit("BUG", "Second report.");
        submit("BUG", "Third report.");
        submit("OTHER", "Not a bug.");

        MvcResult firstPage = listing("?category=BUG&page=0&size=2");
        assertThat(idsOf(firstPage)).hasSize(2);
        assertThat(intAt(firstPage, "$.page")).isZero();
        assertThat(intAt(firstPage, "$.size")).isEqualTo(2);
        assertThat(longAt(firstPage, "$.totalItems")).isEqualTo(3);
        assertThat(intAt(firstPage, "$.totalPages")).isEqualTo(2);

        MvcResult secondPage = listing("?category=BUG&page=1&size=2");
        assertThat(idsOf(secondPage)).hasSize(1);
        assertThat(intAt(secondPage, "$.page")).isEqualTo(1);
        assertThat(longAt(secondPage, "$.totalItems")).isEqualTo(3);

        // Pages must not overlap.
        assertThat(idsOf(firstPage)).doesNotContainAnyElementsOf(idsOf(secondPage));
    }

    @Test
    @DisplayName("a listing row carries the triage status and the submitter, which the user-facing DTO never does")
    void listingRowCarriesStatusAndSubmitter() throws Exception {
        UUID id = submitWithContext("BUG", "Something broke on the routines screen.");
        setStatus(id, FeedbackStatus.TAKING_CARE);

        mockMvc.perform(adminGet("/feedback/admin/items?status=TAKING_CARE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].status").value("TAKING_CARE"))
                .andExpect(jsonPath("$.items[0].category").value("BUG"))
                .andExpect(jsonPath("$.items[0].submitter.id").value(author.getId().toString()))
                .andExpect(jsonPath("$.items[0].submitter.email").value(AUTHOR_EMAIL))
                .andExpect(jsonPath("$.items[0].context.screen").value("/routines"))
                .andExpect(jsonPath("$.items[0].context.platform").value("web"));
    }

    /**
     * The pagination bounds are declared as {@code @Min}/{@code @Max} on the
     * handler's own parameters, which is a different validation path from a
     * {@code @Valid} request body — nothing in the body path handles it. A
     * hand-edited query string must still come back as a 400 inside the
     * standard {@code ApiErrorResponse} envelope, not as an unhandled 500 that
     * clients cannot read.
     */
    @Test
    @DisplayName("an out-of-range page is a 400 in the standard error envelope")
    void anOutOfRangePageIsRejectedInTheStandardEnvelope() throws Exception {
        mockMvc.perform(adminGet("/feedback/admin/items?page=-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("a page size past the cap is a 400 in the standard error envelope")
    void aPageSizePastTheCapIsRejectedInTheStandardEnvelope() throws Exception {
        mockMvc.perform(adminGet("/feedback/admin/items?size=" + (FeedbackService.MAX_PAGE_SIZE + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("a zero page size is a 400 in the standard error envelope")
    void aZeroPageSizeIsRejectedInTheStandardEnvelope() throws Exception {
        mockMvc.perform(adminGet("/feedback/admin/items?size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("the bounds themselves still let a legal page through")
    void theBoundsStillLetALegalPageThrough() throws Exception {
        submit("BUG", "A submission to page over.");

        mockMvc.perform(adminGet("/feedback/admin/items?page=0&size=" + FeedbackService.MAX_PAGE_SIZE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(FeedbackService.MAX_PAGE_SIZE));
    }

    // ----------------------------------------------------------------- counts

    @Test
    @DisplayName("the counts match the underlying rows per status")
    void countsMatchTheStoredRowsPerStatus() throws Exception {
        submit("BUG", "Open one.");
        submit("BUG", "Open two.");
        UUID takingCare = submit("FEATURE_REQUEST", "Being handled.");
        UUID closedA = submit("OTHER", "Done with this one.");
        UUID closedB = submit("OTHER", "And this one.");

        setStatus(takingCare, FeedbackStatus.TAKING_CARE);
        setStatus(closedA, FeedbackStatus.CLOSED);
        setStatus(closedB, FeedbackStatus.CLOSED);

        MvcResult counts = mockMvc.perform(adminGet("/feedback/admin/counts"))
                .andExpect(status().isOk())
                .andReturn();

        Map<FeedbackStatus, Long> stored = feedbackRepository.findAll().stream()
                .collect(Collectors.groupingBy(Feedback::getStatus, Collectors.counting()));

        assertThat(longAt(counts, "$.open")).isEqualTo(stored.getOrDefault(FeedbackStatus.OPEN, 0L)).isEqualTo(2);
        assertThat(longAt(counts, "$.takingCare"))
                .isEqualTo(stored.getOrDefault(FeedbackStatus.TAKING_CARE, 0L)).isEqualTo(1);
        assertThat(longAt(counts, "$.closed"))
                .isEqualTo(stored.getOrDefault(FeedbackStatus.CLOSED, 0L)).isEqualTo(2);
        assertThat(longAt(counts, "$.total")).isEqualTo(feedbackRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("a status with no rows counts zero rather than being absent")
    void anEmptyStatusCountsZero() throws Exception {
        submit("BUG", "The only submission there is.");

        mockMvc.perform(adminGet("/feedback/admin/counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.takingCare").value(0))
                .andExpect(jsonPath("$.closed").value(0))
                .andExpect(jsonPath("$.total").value(1));
    }

    // ----------------------------------------------------------------- detail

    @Test
    @DisplayName("the detail view carries the submission, its context, its attachments and its reply thread")
    void detailCarriesContextAttachmentsAndReplies() throws Exception {
        UUID id = submitWithContext("BUG", "The check-in button does nothing, screenshot attached.");

        mockMvc.perform(multipart("/feedback/" + id + "/attachments")
                        .file(screenshot(40, 30))
                        .header("authorization", "Bearer " + authorToken))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/feedback/admin/items/" + id + "/replies")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Fixed in 1.4.3, thanks for the screenshot.\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(adminGet("/feedback/admin/items/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.body").value("The check-in button does nothing, screenshot attached."))
                .andExpect(jsonPath("$.context.screen").value("/routines"))
                .andExpect(jsonPath("$.submitter.email").value(AUTHOR_EMAIL))
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].url").value("/feedback/" + id + "/attachments/"
                        + attachmentRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id).getFirst().getId()))
                .andExpect(jsonPath("$.replies.length()").value(1))
                .andExpect(jsonPath("$.replies[0].body").value("Fixed in 1.4.3, thanks for the screenshot."))
                .andExpect(jsonPath("$.replies[0].authorName").value("triage admin"));
    }

    @Test
    @DisplayName("an unknown submission is reported as not found rather than as an empty detail")
    void unknownSubmissionIsReportedAsNotFound() throws Exception {
        mockMvc.perform(adminGet("/feedback/admin/items/" + UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("FEEDBACK_NOT_FOUND"));
    }

    // ----------------------------------------------------------- status change

    @Test
    @DisplayName("a status transition persists")
    void statusTransitionPersists() throws Exception {
        UUID id = submit("BUG", "Needs triage.");
        assertThat(feedbackRepository.findById(id).orElseThrow().getStatus()).isEqualTo(FeedbackStatus.OPEN);

        mockMvc.perform(put("/feedback/admin/items/" + id + "/status")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"TAKING_CARE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("TAKING_CARE"));

        assertThat(feedbackRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FeedbackStatus.TAKING_CARE);

        mockMvc.perform(put("/feedback/admin/items/" + id + "/status")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(feedbackRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FeedbackStatus.CLOSED);

        // R15/KD4 — a transition writes no reply, so there is nothing to notify about.
        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id)).isEmpty();
    }

    @Test
    @DisplayName("a status transition to an unknown state is rejected")
    void unknownStatusIsRejected() throws Exception {
        UUID id = submit("BUG", "Needs triage.");

        mockMvc.perform(put("/feedback/admin/items/" + id + "/status")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ON_FIRE\"}"))
                .andExpect(status().isBadRequest());

        assertThat(feedbackRepository.findById(id).orElseThrow().getStatus()).isEqualTo(FeedbackStatus.OPEN);
    }

    // ------------------------------------------------------------------ reply

    @Test
    @DisplayName("a reply is stored against the submission with the acting admin as its author")
    void replyIsStoredWithTheActingAdminAsAuthor() throws Exception {
        UUID id = submit("FEATURE_REQUEST", "Let me drag routine sections around.");

        mockMvc.perform(post("/feedback/admin/items/" + id + "/replies")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Drag-and-drop ships next release.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feedbackId").value(id.toString()))
                .andExpect(jsonPath("$.body").value("Drag-and-drop ships next release."))
                .andExpect(jsonPath("$.authorName").value("triage admin"));

        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id))
                .singleElement()
                .satisfies(reply -> {
                    assertThat(reply.getBody()).isEqualTo("Drag-and-drop ships next release.");
                    assertThat(reply.getAuthor().getEmail()).isEqualTo(ADMIN_EMAIL);
                });
    }

    @Test
    @DisplayName("an empty reply is rejected — a reply is always a written message")
    void emptyReplyIsRejected() throws Exception {
        UUID id = submit("OTHER", "Anything.");

        mockMvc.perform(post("/feedback/admin/items/" + id + "/replies")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));

        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id)).isEmpty();
    }

    @Test
    @DisplayName("a reply to an unknown submission is reported as not found")
    void replyToUnknownSubmissionIsReportedAsNotFound() throws Exception {
        mockMvc.perform(post("/feedback/admin/items/" + UUID.randomUUID() + "/replies")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Hello?\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("FEEDBACK_NOT_FOUND"));
    }

    // -------------------------------------------------------------- authority

    @Test
    @DisplayName("every admin route refuses a signed-in non-admin caller")
    void everyAdminRouteRefusesANonAdmin() throws Exception {
        UUID id = submit("BUG", "A report the submitter must not be able to triage.");

        for (RequestBuilder request : adminRoutesAs(authorToken, id)) {
            MvcResult result = mockMvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("a non-admin must be forbidden from every admin route")
                    .isEqualTo(403);
        }

        // Nothing may have changed as a side effect of the refused calls.
        assertThat(feedbackRepository.findById(id).orElseThrow().getStatus()).isEqualTo(FeedbackStatus.OPEN);
        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id)).isEmpty();
    }

    @Test
    @DisplayName("every admin route refuses an unauthenticated caller")
    void everyAdminRouteRefusesAnUnauthenticatedCaller() throws Exception {
        UUID id = submit("BUG", "A report nobody anonymous may touch.");

        for (RequestBuilder request : adminRoutesAs(null, id)) {
            MvcResult result = mockMvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("an unauthenticated caller must be refused on every admin route")
                    .isEqualTo(401);
        }

        assertThat(feedbackRepository.findById(id).orElseThrow().getStatus()).isEqualTo(FeedbackStatus.OPEN);
        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id)).isEmpty();
    }

    /** Every route this unit adds, so a new one cannot be added without an authority test. */
    private List<RequestBuilder> adminRoutesAs(String token, UUID feedbackId) {
        List<RequestBuilder> requests = List.of(
                withToken(get("/feedback/admin/items"), token),
                withToken(get("/feedback/admin/items?status=OPEN&category=BUG"), token),
                withToken(get("/feedback/admin/counts"), token),
                withToken(get("/feedback/admin/items/" + feedbackId), token),
                withToken(put("/feedback/admin/items/" + feedbackId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\"}"), token),
                withToken(post("/feedback/admin/items/" + feedbackId + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"You should never read this.\"}"), token));
        return requests;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withToken(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String token) {
        return token == null ? builder : builder.header("authorization", "Bearer " + token);
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminGet(String path) {
        return get(path).header("authorization", "Bearer " + adminToken);
    }

    private MvcResult listing(String query) throws Exception {
        return mockMvc.perform(adminGet("/feedback/admin/items" + query))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static List<UUID> idsOf(MvcResult result) throws Exception {
        List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.items[*].id");
        return ids.stream().map(UUID::fromString).toList();
    }

    private static int intAt(MvcResult result, String path) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), path)).intValue();
    }

    private static long longAt(MvcResult result, String path) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), path)).longValue();
    }

    private UUID submit(String category, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"" + category + "\", \"body\": \"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID submitWithContext(String category, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "%s",
                                  "body": "%s",
                                  "context": {
                                    "screen": "/routines",
                                    "appVersion": "1.4.2",
                                    "platform": "web",
                                    "language": "en",
                                    "theme": "beYouDark"
                                  }
                                }
                                """.formatted(category, body)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    /** Sets the triage state straight on the row — the endpoint under test is asserted separately. */
    private void setStatus(UUID feedbackId, FeedbackStatus status) {
        Feedback feedback = feedbackRepository.findById(feedbackId).orElseThrow();
        feedback.setStatus(status);
        feedbackRepository.saveAndFlush(feedback);
    }

    private static MockMultipartFile screenshot(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "screenshot.png", "image/png", out.toByteArray());
    }

    private User recreateUser(String email, String name, UserRole role) {
        deleteUser(email);
        userService.registerUser(new UserRegisterDTO(name, email, PASSWORD, null));

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user.setUserRole(role);
        return userRepository.saveAndFlush(user);
    }

    private void deleteUser(String email) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            // Replies this user wrote outlive their author elsewhere, but inside a
            // test they must go, or the next class inherits them.
            feedbackReplyRepository.deleteAll(feedbackReplyRepository.findAll().stream()
                    .filter(reply -> reply.getAuthor() != null
                            && reply.getAuthor().getId().equals(existing.getId()))
                    .toList());

            List<Feedback> owned = feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId());
            owned.forEach(feedback -> {
                feedbackReplyRepository.deleteAll(
                        feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedback.getId()));
                attachmentRepository.deleteAll(
                        attachmentRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedback.getId()));
            });
            feedbackRepository.deleteAll(owned);

            refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(existing.getId()));
            userRepository.delete(existing);
        });
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Access-Token"))
                .andReturn();

        return result.getResponse().getHeader("X-Access-Token");
    }
}
