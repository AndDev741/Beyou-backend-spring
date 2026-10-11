package beyou.beyouapp.backend.unit.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.notification.EmailService;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * What the calendar reminder mail says: the subject, the link back to the day, the way to turn
 * these mails off, in both languages, with the user's own words escaped.
 *
 * <p>The HTML is dug out of the multipart by hand. {@code getContent()} on the message returns
 * the multipart, not the text, and an assertion against its {@code toString()} passes for any
 * body at all.
 */
class CalendarReminderMailUnitTest {

    private JavaMailSender mailSender;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new MimeMessage(Session.getInstance(new Properties())));
        emailService = new EmailService(mailSender);
        ReflectionTestUtils.setField(emailService, "fromAddress", "noreply@beyou.test");
        // Trailing slash on purpose: the link must not come out with a double slash.
        ReflectionTestUtils.setField(emailService, "frontendUrl", "https://app.beyou.test/");
    }

    @Test
    @DisplayName("English: the title is the subject, and the body links to the day and to the off switch")
    void english() throws Exception {
        emailService.sendCalendarReminder("someone@test.com", "Sign the lease", "bring the ID",
                LocalDate.of(2026, 10, 14), LocalTime.of(10, 0), "en");

        MimeMessage sent = captureSent();
        String html = htmlOf(sent);
        assertThat(sent.getSubject()).isEqualTo("Reminder: Sign the lease");
        assertThat(html).contains("Set for 2026-10-14 at 10:00.");
        assertThat(html).contains("bring the ID");
        assertThat(html).contains("href=\"https://app.beyou.test/calendar?date=2026-10-14\"");
        assertThat(html).contains("href=\"https://app.beyou.test/configuration\"");
        assertThat(html).contains("Reminders");
    }

    @Test
    @DisplayName("Portuguese, untimed and without a note")
    void portuguese() throws Exception {
        emailService.sendCalendarReminder("someone@test.com", "Pagar a conta", null,
                LocalDate.of(2026, 10, 14), null, "pt");

        MimeMessage sent = captureSent();
        String html = htmlOf(sent);
        assertThat(sent.getSubject()).isEqualTo("Lembrete: Pagar a conta");
        assertThat(html).contains("Marcado para 2026-10-14.");
        assertThat(html).contains("Abrir o calendário");
        assertThat(html).contains("Lembretes");
        assertThat(html).doesNotContain("white-space:pre-line");
    }

    @Test
    @DisplayName("the user's words are escaped, and a line break cannot reach the subject header")
    void userTextIsEscaped() throws Exception {
        emailService.sendCalendarReminder("someone@test.com", "<b>Rent</b>\r\nBcc: x@y.z", "<script>x</script>",
                LocalDate.of(2026, 10, 14), LocalTime.of(10, 0), "en");

        MimeMessage sent = captureSent();
        String html = htmlOf(sent);
        // The raw header, not getSubject(): Jakarta Mail folds an embedded line break into a
        // continuation line and getSubject() unfolds it again, so the decoded value looks clean
        // either way. Flattened, the header stays on one line.
        assertThat(sent.getHeader("Subject", null)).doesNotContain("\r").doesNotContain("\n");
        assertThat(sent.getSubject()).isEqualTo("Reminder: <b>Rent</b>  Bcc: x@y.z");
        assertThat(html).doesNotContain("<b>Rent</b>").contains("&lt;b&gt;Rent&lt;/b&gt;");
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    private MimeMessage captureSent() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        // What a real sender does before the wire; without it the parts carry no Content-Type.
        sent.saveChanges();
        return sent;
    }

    private static String htmlOf(Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String html = htmlOf(child);
                if (html != null) {
                    return html;
                }
            }
        }
        return null;
    }
}
