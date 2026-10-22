package io.github.lutzseverino.cardo.invite.integration;

import io.github.lutzseverino.cardo.invite.config.InvitationDeliveryProperties;
import io.github.lutzseverino.cardo.invite.model.InvitationMessage;
import io.github.lutzseverino.cardo.invite.provider.InvitationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmtpInvitationSender implements InvitationSender {

  private final InvitationDeliveryProperties properties;
  private final JavaMailSender mail;

  @Override
  public void send(String email, String acceptUrl, String subject, String body) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(properties.from());
    message.setTo(email);
    if (subject == null || body == null) {
      message.setSubject("You have been invited");
      message.setText("Open this invitation to continue: " + acceptUrl);
    } else {
      message.setSubject(subject);
      message.setText(body.replace(InvitationMessage.ACCEPT_URL_PLACEHOLDER, acceptUrl));
    }
    mail.send(message);
  }
}
