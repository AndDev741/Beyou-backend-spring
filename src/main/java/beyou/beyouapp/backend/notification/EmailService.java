package beyou.beyouapp.backend.notification;

import java.time.Duration;
import java.time.Year;

import beyou.beyouapp.backend.user.event.UserRegisteredEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

@Service
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

    public void sendVerificationEmail(String to, String token, String language) {
        try {
            String base = frontendUrl == null ? "" : frontendUrl.trim();
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            String verifyLink = base + "/auth/verify?token=" + token;

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

}
