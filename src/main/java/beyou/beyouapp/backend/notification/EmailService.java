package beyou.beyouapp.backend.notification;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${mail.from}")
    private String fromAddress;

    public void sendPasswordResetEmail(String to, String resetLink, Duration ttl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setFrom(fromAddress);
        message.setSubject("Reset your BeYou password");
        message.setText(buildBody(resetLink, ttl));
        mailSender.send(message);
    }

    private String buildBody(String resetLink, Duration ttl) {
        long minutes = ttl.toMinutes();
        return "We received a request to reset your BeYou password.\n\n" +
               "Use the link below to set a new password.\n" +
               resetLink + "\n\n" +
               "This link expires in " + minutes + " minutes. If you didn't request this, you can ignore this email.";
    }
}
