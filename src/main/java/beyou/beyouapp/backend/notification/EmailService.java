package beyou.beyouapp.backend.notification;

import java.time.Duration;
import java.time.Year;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.event.FeedbackRepliedEvent;
import beyou.beyouapp.backend.domain.feedback.event.FeedbackSubmittedEvent;
import beyou.beyouapp.backend.user.event.UserRegisteredEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${mail.from}")
    private String fromAddress;

    @Value("${frontend.url}")
    private String frontendUrl;

    public void sendPasswordResetEmail(String to, String resetLink, Duration ttl, String language) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setTo(to);
            helper.setFrom(fromAddress);
            helper.setSubject(resolveSubject(language));
            helper.setText(buildHtmlBody(resetLink, ttl, language), true);

            mailSender.send(mimeMessage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send email", e);
        }
    }

    @Async
    @EventListener
    public void handleUserRegistered(UserRegisteredEvent event) {
        sendVerificationEmail(event.getEmail(), event.getVerificationToken(), event.getLanguage());
    }

    /**
     * R13/KD8 — acknowledge a submission that is already stored.
     *
     * {@code AFTER_COMMIT} is the whole point: a rolled-back submission never
     * produces mail, and this runs after the write is durable, on another
     * thread, so nothing here can cost the user their submission. The catch is
     * belt-and-braces on top of that — a dead transport must stay a log line.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleFeedbackSubmitted(FeedbackSubmittedEvent event) {
        try {
            sendFeedbackAcknowledgementEmail(
                    event.getRecipientEmail(),
                    event.getCategory(),
                    event.getBody(),
                    event.getRecipientLanguage());
        } catch (Exception e) {
            log.error("Failed to send the acknowledgement for feedback {}", event.getFeedbackId(), e);
        }

        // Its own try, deliberately: the receipt and the alert answer to
        // different people, and a dead mailbox on one side must not silence
        // the other.
        try {
            sendFeedbackInboxAlertEmails(event.getAdminRecipients(), event.getFeedbackId());
        } catch (Exception e) {
            log.error("Failed to alert the inbox about feedback {}", event.getFeedbackId(), e);
        }
    }

    /**
     * R14/R15/KD4 — deliver a written reply. Only a reply reaches the user:
     * there is no listener for a status transition, by design.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleFeedbackReplied(FeedbackRepliedEvent event) {
        try {
            sendFeedbackReplyEmail(
                    event.getRecipientEmail(),
                    event.getOriginalBody(),
                    event.getReplyBody(),
                    event.getRecipientLanguage());
        } catch (Exception e) {
            log.error("Failed to send the reply mail for feedback {}", event.getFeedbackId(), e);
        }
    }

    public void sendFeedbackAcknowledgementEmail(String to, FeedbackCategory category, String body, String language) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setTo(to);
            helper.setFrom(fromAddress);
            helper.setSubject(resolveFeedbackAcknowledgementSubject(language));
            helper.setText(buildFeedbackAcknowledgementHtmlBody(category, body, language), true);

            mailSender.send(mimeMessage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send feedback acknowledgement email", e);
        }
    }

    public void sendFeedbackReplyEmail(String to, String originalBody, String replyBody, String language) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setTo(to);
            helper.setFrom(fromAddress);
            helper.setSubject(resolveFeedbackReplySubject(language));
            helper.setText(buildFeedbackReplyHtmlBody(originalBody, replyBody, language), true);

            mailSender.send(mimeMessage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send feedback reply email", e);
        }
    }

    /**
     * Tell whoever runs the console that something arrived.
     *
     * One message per admin rather than one message addressed to all of them:
     * a shared To: line shows each admin the others' addresses, and a single
     * bad mailbox would take the whole send down with it. Here a failure costs
     * one recipient and is logged with the address that failed.
     *
     * The mail carries a link and nothing else — no category, no submitter, and
     * above all none of what the user wrote. Feedback text can be personal, and
     * routing it into a mail provider to save one click is a bad trade. The
     * console is one tap away and already holds the whole thread.
     */
    public void sendFeedbackInboxAlertEmails(List<String> recipients, UUID feedbackId) {
        if (recipients == null || recipients.isEmpty()) {
            log.debug("No admin recipients to alert about feedback {}", feedbackId);
            return;
        }

        String consoleLink = frontendLink("/admin/feedback");

        for (String recipient : recipients) {
            try {
                MimeMessage mimeMessage = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

                helper.setTo(recipient);
                helper.setFrom(fromAddress);
                helper.setSubject("New feedback in the Beyou inbox");
                helper.setText(buildFeedbackInboxAlertHtmlBody(consoleLink), true);

                mailSender.send(mimeMessage);
            } catch (Exception e) {
                log.error("Failed to alert {} about feedback {}", recipient, feedbackId, e);
            }
        }
    }

    /**
     * English only, and not by oversight. Every other template here branches on
     * the reader's language because it is addressed to a user; this one is
     * addressed to whoever operates the product, is two sentences long, and its
     * payload is a URL. A second translation would be upkeep with no reader.
     */
    private String buildFeedbackInboxAlertHtmlBody(String consoleLink) {
        String template = """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">Beyou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Level up your life.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">Someone just sent feedback</h2>
                                            <p style="color:#374151;line-height:1.6;">
                                                It is waiting in the console. Open the inbox to read it and reply.
                                            </p>

                                            <table cellpadding="0" cellspacing="0" style="margin:30px 0;">
                                                <tr>
                                                    <td align="center" bgcolor="#0082E1" style="border-radius:8px;">
                                                        <a href="%s"
                                                           style="display:inline-block;padding:14px 28px;color:#ffffff;
                                                                  font-size:16px;text-decoration:none;font-weight:bold;">
                                                            Open the feedback inbox
                                                        </a>
                                                    </td>
                                                </tr>
                                            </table>

                                            <p style="color:#6b7280;font-size:14px;line-height:1.5;">
                                                If the button does not work, paste this into your browser:<br/>
                                                <a href="%s" style="color:#0082E1;word-break:break-all;">%s</a>
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                You get this because your account holds the admin role.
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    &copy; %d Beyou. Keep evolving.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """;

        return template.formatted(consoleLink, consoleLink, consoleLink, Year.now().getValue());
    }

    private String buildFeedbackAcknowledgementHtmlBody(FeedbackCategory category, String body, String language) {
        String normalizedLanguage = normalizeLanguage(language);
        String template = normalizedLanguage.equals("pt")
            ? """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">BeYou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Evolua sua vida.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">Recebemos seu feedback</h2>
                                            <p style="color:#374151;line-height:1.6;">
                                                Recebemos o que voce nos enviou e uma pessoa de verdade vai ler.
                                                Se houver algo a responder, a resposta chega neste mesmo email.
                                            </p>

                                            <p style="color:#6b7280;font-size:14px;margin-bottom:6px;">
                                                Categoria: <strong>%s</strong>
                                            </p>

                                            <div style="background:#f5f7fa;border-left:4px solid #0082E1;
                                                        border-radius:8px;padding:16px;margin:10px 0 30px 0;">
                                                <p style="color:#374151;line-height:1.6;margin:0;white-space:pre-wrap;">%s</p>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Nao e preciso responder a este email. Ele so confirma que sua mensagem chegou.
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                Obrigado por ajudar a melhorar o BeYou.
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    &copy; %d BeYou. Continue evoluindo.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """
            : """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">BeYou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Level up your life.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">We got your feedback</h2>
                                            <p style="color:#374151;line-height:1.6;">
                                                Your message reached us and a real person will read it.
                                                If there is something to answer, the answer arrives at this same address.
                                            </p>

                                            <p style="color:#6b7280;font-size:14px;margin-bottom:6px;">
                                                Category: <strong>%s</strong>
                                            </p>

                                            <div style="background:#f5f7fa;border-left:4px solid #0082E1;
                                                        border-radius:8px;padding:16px;margin:10px 0 30px 0;">
                                                <p style="color:#374151;line-height:1.6;margin:0;white-space:pre-wrap;">%s</p>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                No need to reply to this email — it only confirms your message arrived.
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                Thank you for helping make BeYou better.
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    &copy; %d BeYou. Keep evolving.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """;

        return template.formatted(
                resolveCategoryLabel(category, normalizedLanguage),
                escape(body),
                Year.now().getValue());
    }

    private String buildFeedbackReplyHtmlBody(String originalBody, String replyBody, String language) {
        String normalizedLanguage = normalizeLanguage(language);
        String template = normalizedLanguage.equals("pt")
            ? """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">BeYou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Evolua sua vida.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">Respondemos seu feedback</h2>

                                            <div style="background:#f5f7fa;border-left:4px solid #0082E1;
                                                        border-radius:8px;padding:16px;margin:10px 0 30px 0;">
                                                <p style="color:#374151;line-height:1.6;margin:0;white-space:pre-wrap;">%s</p>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;margin-bottom:6px;">
                                                Sobre o que voce nos enviou:
                                            </p>

                                            <div style="border-left:4px solid #e5e7eb;padding:0 16px;margin:0 0 30px 0;">
                                                <p style="color:#9ca3af;line-height:1.6;margin:0;white-space:pre-wrap;">%s</p>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Quer continuar a conversa? Basta responder a este email.
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                Obrigado por ajudar a melhorar o BeYou.
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    &copy; %d BeYou. Continue evoluindo.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """
            : """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">BeYou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Level up your life.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">We replied to your feedback</h2>

                                            <div style="background:#f5f7fa;border-left:4px solid #0082E1;
                                                        border-radius:8px;padding:16px;margin:10px 0 30px 0;">
                                                <p style="color:#374151;line-height:1.6;margin:0;white-space:pre-wrap;">%s</p>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;margin-bottom:6px;">
                                                About what you sent us:
                                            </p>

                                            <div style="border-left:4px solid #e5e7eb;padding:0 16px;margin:0 0 30px 0;">
                                                <p style="color:#9ca3af;line-height:1.6;margin:0;white-space:pre-wrap;">%s</p>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Want to keep the conversation going? Just reply to this email.
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                Thank you for helping make BeYou better.
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    &copy; %d BeYou. Keep evolving.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """;

        return template.formatted(escape(replyBody), escape(originalBody), Year.now().getValue());
    }

    private String resolveFeedbackAcknowledgementSubject(String language) {
        return normalizeLanguage(language).equals("pt")
            ? "Recebemos seu feedback - BeYou"
            : "We got your feedback - BeYou";
    }

    private String resolveFeedbackReplySubject(String language) {
        return normalizeLanguage(language).equals("pt")
            ? "Respondemos seu feedback - BeYou"
            : "We replied to your feedback - BeYou";
    }

    private String resolveCategoryLabel(FeedbackCategory category, String normalizedLanguage) {
        boolean pt = normalizedLanguage.equals("pt");
        if (category == null) {
            return pt ? "Outro" : "Other";
        }
        return switch (category) {
            case BUG -> pt ? "Problema" : "Bug";
            case FEATURE_REQUEST -> pt ? "Sugestao" : "Feature request";
            case OTHER -> pt ? "Outro" : "Other";
        };
    }

    /**
     * User- and admin-written text goes into an HTML document, so it is escaped
     * rather than trusted. A missing body renders as empty, never as "null".
     */
    private String escape(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }

    /**
     * Absolute link into the web app. One place, because {@code FRONTEND_URL}
     * is configured with a trailing slash locally and without one in some
     * deployments, and every caller that re-derives that ends up shipping a
     * double slash the day the other form is used.
     *
     * @param path a leading-slash path, e.g. {@code /admin/feedback}
     */
    private String frontendLink(String path) {
        String base = frontendUrl == null ? "" : frontendUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    public void sendVerificationEmail(String to, String token, String language) {
        try {
            String verifyLink = frontendLink("/auth/verify?token=" + token);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setTo(to);
            helper.setFrom(fromAddress);
            helper.setSubject(resolveVerificationSubject(language));
            helper.setText(buildVerificationHtmlBody(verifyLink, language), true);

            mailSender.send(mimeMessage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send verification email", e);
        }
    }

    private String buildVerificationHtmlBody(String verifyLink, String language) {
        String normalizedLanguage = normalizeLanguage(language);
        String template = normalizedLanguage.equals("pt")
            ? """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">BeYou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Evolua sua vida.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">Verifique seu email</h2>
                                            <p style="color:#374151;line-height:1.6;">
                                                Bem-vindo ao BeYou! Para completar seu cadastro, por favor verifique seu email
                                                clicando no botao abaixo.
                                            </p>

                                            <div style="text-align:center;margin:30px 0;">
                                                <a href="%s"
                                                   style="background-color:#0082E1;
                                                          color:#ffffff;
                                                          padding:14px 28px;
                                                          text-decoration:none;
                                                          border-radius:10px;
                                                          font-weight:bold;
                                                          display:inline-block;">
                                                    Verificar meu email
                                                </a>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Este link expira em <strong>24 horas</strong>.
                                            </p>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Se voce nao criou uma conta no BeYou, pode ignorar este email.
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                Se o botao nao funcionar, copie e cole este link no seu navegador:
                                                <br/>
                                                <a href="%s" style="color:#0082E1;word-break:break-all;">%s</a>
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    &copy; %d BeYou. Continue evoluindo.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """
            : """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">BeYou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Level up your life.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">Verify your email</h2>
                                            <p style="color:#374151;line-height:1.6;">
                                                Welcome to BeYou! To complete your registration, please verify your email
                                                by clicking the button below.
                                            </p>

                                            <div style="text-align:center;margin:30px 0;">
                                                <a href="%s"
                                                   style="background-color:#0082E1;
                                                          color:#ffffff;
                                                          padding:14px 28px;
                                                          text-decoration:none;
                                                          border-radius:10px;
                                                          font-weight:bold;
                                                          display:inline-block;">
                                                    Verify my email
                                                </a>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                This link expires in <strong>24 hours</strong>.
                                            </p>

                                            <p style="color:#6b7280;font-size:14px;">
                                                If you did not create a BeYou account, you can safely ignore this email.
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                If the button doesn't work, copy and paste this link into your browser:
                                                <br/>
                                                <a href="%s" style="color:#0082E1;word-break:break-all;">%s</a>
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    &copy; %d BeYou. Keep evolving.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """;

        return template.formatted(verifyLink, verifyLink, verifyLink, Year.now().getValue());
    }

    private String resolveVerificationSubject(String language) {
        return normalizeLanguage(language).equals("pt")
            ? "Verifique seu email - BeYou"
            : "Verify your email - BeYou";
    }

    private String buildHtmlBody(String resetLink, Duration ttl, String language) {
        long minutes = ttl.toMinutes();

        String normalizedLanguage = normalizeLanguage(language);
        String template = normalizedLanguage.equals("pt")
            ? """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">✨ BeYou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Evolua sua vida.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">Esqueceu sua senha?</h2>
                                            <p style="color:#374151;line-height:1.6;">
                                                Sem problemas até os melhores heróis esquecem coisas às vezes.
                                                Vamos te colocar de volta no caminho para você não perder sua sequência de XP 😉
                                            </p>

                                            <p style="color:#374151;line-height:1.6;">
                                                Clique no botão abaixo para redefinir sua senha:
                                            </p>

                                            <div style="text-align:center;margin:30px 0;">
                                                <a href="%s"
                                                   style="background-color:#0082E1;
                                                          color:#ffffff;
                                                          padding:14px 28px;
                                                          text-decoration:none;
                                                          border-radius:10px;
                                                          font-weight:bold;
                                                          display:inline-block;">
                                                    Redefinir minha senha 🚀
                                                </a>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                ⏳ Este link expira em <strong>%d minutos</strong>.
                                            </p>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Não foi você? Sem problemas.
                                                Você pode ignorar este email sua conta continua protegida.
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                Se o botão não funcionar, copie e cole este link no seu navegador:
                                                <br/>
                                                <a href="%s" style="color:#0082E1;word-break:break-all;">%s</a>
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    © %d BeYou. Continue evoluindo.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """
            : """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">

                                    <tr>
                                        <td align="center" style="padding-bottom:20px;">
                                            <h1 style="margin:0;color:#0082E1;">✨ BeYou</h1>
                                            <p style="margin:5px 0 0 0;color:#6b7280;">Level up your life.</p>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding-top:20px;">
                                            <h2 style="color:#111827;">Forgot your password?</h2>
                                            <p style="color:#374151;line-height:1.6;">
                                                No worries even the best heroes forget things sometimes.
                                                Let’s get you back on track so you don’t lose your XP streak 😉
                                            </p>

                                            <p style="color:#374151;line-height:1.6;">
                                                Click the button below to reset your password:
                                            </p>

                                            <div style="text-align:center;margin:30px 0;">
                                                <a href="%s"
                                                   style="background-color:#0082E1;
                                                          color:#ffffff;
                                                          padding:14px 28px;
                                                          text-decoration:none;
                                                          border-radius:10px;
                                                          font-weight:bold;
                                                          display:inline-block;">
                                                    Reset My Password 🚀
                                                </a>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                ⏳ This link expires in <strong>%d minutes</strong>.
                                            </p>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Didn’t request this? No problem.
                                                You can safely ignore this email your account is still protected.
                                            </p>

                                            <hr style="margin:30px 0;border:none;border-top:1px solid #e5e7eb;"/>

                                            <p style="color:#9ca3af;font-size:12px;line-height:1.5;">
                                                If the button doesn’t work, copy and paste this link into your browser:
                                                <br/>
                                                <a href="%s" style="color:#0082E1;word-break:break-all;">%s</a>
                                            </p>
                                        </td>
                                    </tr>

                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    © %d BeYou. Keep evolving.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """;

        return template.formatted(resetLink, minutes, resetLink, resetLink, Year.now().getValue());
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "en";
        }
        String normalized = language.trim().toLowerCase();
        if (normalized.startsWith("pt")) {
            return "pt";
        }
        return "en";
    }

    private String resolveSubject(String language) {
        return normalizeLanguage(language).equals("pt")
            ? "Redefina sua senha BeYou 🔐✨"
            : "Reset your BeYou password 🔐✨";
    }

    /**
     * The code that unlocks account deletion.
     *
     * No link and no button, unlike every other mail here: a deletion must not be
     * one careless click away from an inbox. The code has to be carried back to the
     * app by hand, which is what makes it a second, deliberate step.
     */
    public void sendAccountDeletionCodeEmail(String to, String code, Duration ttl, String language) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setTo(to);
            helper.setFrom(fromAddress);
            helper.setSubject(resolveAccountDeletionSubject(language));
            helper.setText(buildAccountDeletionHtmlBody(code, ttl, language), true);

            mailSender.send(mimeMessage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send email", e);
        }
    }

    private String resolveAccountDeletionSubject(String language) {
        return normalizeLanguage(language).equals("pt")
            ? "Seu código para apagar a conta BeYou"
            : "Your code to delete your BeYou account";
    }

    private String buildAccountDeletionHtmlBody(String code, Duration ttl, String language) {
        long minutes = ttl.toMinutes();
        String template = normalizeLanguage(language).equals("pt")
            ? """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">
                                    <tr>
                                        <td>
                                            <h1 style="color:#111827;font-size:22px;margin:0 0 16px;">
                                                Você pediu para apagar sua conta
                                            </h1>

                                            <p style="color:#374151;line-height:1.6;">
                                                Digite este código no app para continuar:
                                            </p>

                                            <div style="text-align:center;margin:30px 0;">
                                                <span style="display:inline-block;
                                                             background:#f3f4f6;
                                                             color:#111827;
                                                             font-size:32px;
                                                             letter-spacing:10px;
                                                             font-weight:bold;
                                                             padding:16px 28px;
                                                             border-radius:12px;">%s</span>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                ⏳ O código expira em <strong>%d minutos</strong>.
                                            </p>

                                            <p style="color:#374151;line-height:1.6;">
                                                Apagar a conta remove tudo: hábitos, rotinas, metas, histórico e XP.
                                                Não dá para desfazer.
                                            </p>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Não foi você? Então não faça nada. Sem este código ninguém apaga sua
                                                conta, e vale trocar sua senha por segurança.
                                            </p>
                                        </td>
                                    </tr>
                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    © %d BeYou. Continue evoluindo.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """
            : """
                <html>
                <body style="margin:0;padding:0;background-color:#f5f7fa;font-family:Arial,Helvetica,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                        <tr>
                            <td align="center">
                                <table width="600" cellpadding="0" cellspacing="0"
                                       style="background:#ffffff;border-radius:16px;padding:40px;box-shadow:0 10px 30px rgba(0,0,0,0.08);">
                                    <tr>
                                        <td>
                                            <h1 style="color:#111827;font-size:22px;margin:0 0 16px;">
                                                You asked to delete your account
                                            </h1>

                                            <p style="color:#374151;line-height:1.6;">
                                                Type this code into the app to continue:
                                            </p>

                                            <div style="text-align:center;margin:30px 0;">
                                                <span style="display:inline-block;
                                                             background:#f3f4f6;
                                                             color:#111827;
                                                             font-size:32px;
                                                             letter-spacing:10px;
                                                             font-weight:bold;
                                                             padding:16px 28px;
                                                             border-radius:12px;">%s</span>
                                            </div>

                                            <p style="color:#6b7280;font-size:14px;">
                                                ⏳ The code expires in <strong>%d minutes</strong>.
                                            </p>

                                            <p style="color:#374151;line-height:1.6;">
                                                Deleting your account removes everything: habits, routines, goals,
                                                history and XP. It cannot be undone.
                                            </p>

                                            <p style="color:#6b7280;font-size:14px;">
                                                Wasn't you? Then do nothing. Nobody can delete your account without
                                                this code, and it is worth changing your password.
                                            </p>
                                        </td>
                                    </tr>
                                </table>

                                <p style="margin-top:20px;color:#9ca3af;font-size:12px;">
                                    © %d BeYou. Keep growing.
                                </p>

                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """;

        return template.formatted(escape(code), minutes, Year.now().getValue());
    }

}
