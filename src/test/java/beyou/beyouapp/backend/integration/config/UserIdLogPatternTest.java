package beyou.beyouapp.backend.integration.config;

import java.util.UUID;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.monitoring.UserContextLogFilter;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders a log line through the console appender Spring Boot actually configured and
 * asserts the user id is in it.
 *
 * <p>Two things have to line up for that to happen and neither is in this codebase's
 * control: {@code logging.pattern.correlation} from {@code application.yaml} has to be
 * bound to Logback's {@code LOG_CORRELATION_PATTERN}, and Boot's default console pattern
 * has to still reference that variable. A Boot upgrade could drop either — silently, since
 * nothing fails, the id just stops appearing and nobody notices until an incident. So the
 * assertion is made on the rendered output rather than on the property value.
 */
@AutoConfigureMockMvc
class UserIdLogPatternTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private static final LoggerContext LOGGER_CONTEXT = (LoggerContext) LoggerFactory.getILoggerFactory();

    @AfterEach
    void clearMdc() {
        MDC.remove(UserContextLogFilter.USER_ID_KEY);
    }

    /** The layout of the CONSOLE appender Boot installed on the root logger. */
    private static PatternLayoutEncoder consoleEncoder() {
        Logger root = LOGGER_CONTEXT.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> console = root.getAppender("CONSOLE");

        assertNotNull(console, "Boot's default logback config must still install a CONSOLE appender");
        return assertInstanceOf(PatternLayoutEncoder.class,
                ((OutputStreamAppender<ILoggingEvent>) console).getEncoder());
    }

    private String renderALine() {
        ILoggingEvent event = new LoggingEvent(
                getClass().getName(),
                LOGGER_CONTEXT.getLogger(getClass()),
                Level.INFO,
                "[PERFORMANCE] Method history exectued in 12 ms",
                null,
                new Object[0]);

        return consoleEncoder().getLayout().doLayout(event);
    }

    @Test
    void aLineLoggedDuringARequestCarriesTheUsersId() {
        String userId = UUID.randomUUID().toString();
        MDC.put(UserContextLogFilter.USER_ID_KEY, userId);

        assertTrue(renderALine().contains("userId=" + userId),
                "the console pattern must render the MDC user id — the log shipper parses this field");
    }

    @Test
    void aLineWithNoUserInScopeStillCarriesTheField() {
        assertTrue(renderALine().contains("userId=anonymous"),
                "startup and scheduler lines must keep the field so there is only one line shape to parse");
    }

    @Test
    void theFilterThatFillsTheFieldIsRegistered() {
        assertEquals(1, applicationContext.getBeanNamesForType(UserContextLogFilter.class).length,
                "without the filter bean every line would render `anonymous`");
    }

    /**
     * Reads the MDC at append time, on the thread that logged. The map on a stored
     * {@code ILoggingEvent} can be resolved lazily, so inspecting events after the request
     * has cleared its context is not a reliable read.
     *
     * <p>Scoped to this app's loggers on the request's own thread: a background job — the
     * snapshot scheduler is the one that runs on a timer — logging into the same window
     * would otherwise fail the run for a reason that has nothing to do with requests.
     */
    private static final class UserIdCapturingAppender extends AppenderBase<ILoggingEvent> {

        record Line(String message, String userId) {
        }

        private final List<Line> lines = new CopyOnWriteArrayList<>();
        private final String requestThread = Thread.currentThread().getName();

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLoggerName().startsWith("beyou.beyouapp.backend")
                    && requestThread.equals(event.getThreadName())) {
                lines.add(new Line(event.getFormattedMessage(),
                        event.getMDCPropertyMap().get(UserContextLogFilter.USER_ID_KEY)));
            }
        }
    }

    /**
     * The link the two tests above cannot reach: that the filter runs where the principal is
     * already resolved. It is a plain servlet filter, so its position comes from bean order
     * against Spring Security's own registration — an ordering nothing in the code states
     * twice. So drive a real authenticated request through the whole chain and read what the
     * application's own log lines carried.
     *
     * <p>{@code TokenService.validateToken} is excluded, and that is the honest boundary of
     * this feature rather than a convenience: the aspects log it from inside
     * {@code SecurityFilter}, before the token has been turned into a user, so at that
     * moment there is no id to attach. Every line after it has one.
     */
    @Test
    void everyLineAnAuthenticatedRequestLogsAfterAuthCarriesThatUsersId() throws Exception {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        userService.registerUser(new UserRegisterDTO("log ctx", "logcontext@beyou.test", "TestPassword1!", null));
        User user = userRepository.findByEmail("logcontext@beyou.test").orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);

        String accessToken = mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"logcontext@beyou.test\", \"password\": \"TestPassword1!\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Access-Token"))
                .andReturn()
                .getResponse()
                .getHeader("X-Access-Token");

        UserIdCapturingAppender captured = new UserIdCapturingAppender();
        captured.setContext(LOGGER_CONTEXT);
        captured.start();
        Logger root = LOGGER_CONTEXT.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(captured);

        try {
            mockMvc.perform(get("/category").header("authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk());
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }

        List<UserIdCapturingAppender.Line> afterAuth = captured.lines.stream()
                .filter(line -> !line.message().contains("validateToken"))
                .toList();

        assertFalse(afterAuth.isEmpty(),
                "the request logged nothing after authenticating, so this proves nothing — "
                        + "the AOP aspects log the service call and the controller");
        assertEquals(List.of(user.getId().toString()),
                afterAuth.stream().map(UserIdCapturingAppender.Line::userId).distinct().toList(),
                () -> "every line after authentication must carry this request's user id: " + afterAuth);
    }
}
