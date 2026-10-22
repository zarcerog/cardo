package io.github.lutzseverino.cardo.invite.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.lutzseverino.cardo.invite.config.InvitationDeliveryProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpInvitationSenderTest {

  private SimpleMailMessage capture(JavaMailSender mail) {
    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(message.capture());
    return message.getValue();
  }

  @Test
  void sendsTheDefaultInvitationWhenNoMessageIsSupplied() {
    JavaMailSender mail = mock(JavaMailSender.class);
    SmtpInvitationSender sender =
        new SmtpInvitationSender(new InvitationDeliveryProperties("invites@example.com"), mail);

    sender.send("member@example.com", "https://app.example.com/invitations/token-1", null, null);

    SimpleMailMessage message = capture(mail);
    assertThat(message.getFrom()).isEqualTo("invites@example.com");
    assertThat(message.getTo()).containsExactly("member@example.com");
    assertThat(message.getSubject()).isEqualTo("You have been invited");
    assertThat(message.getText()).contains("https://app.example.com/invitations/token-1");
  }

  @Test
  void sendsTheProductMessageAndReplacesThePlaceholder() {
    JavaMailSender mail = mock(JavaMailSender.class);
    SmtpInvitationSender sender =
        new SmtpInvitationSender(new InvitationDeliveryProperties("invites@example.com"), mail);

    sender.send(
        "member@example.com",
        "https://app.example.com/invitations/token-1",
        "Unase a la clinica",
        "Pulse {{acceptUrl}} para continuar");

    SimpleMailMessage message = capture(mail);
    assertThat(message.getSubject()).isEqualTo("Unase a la clinica");
    assertThat(message.getText())
        .isEqualTo("Pulse https://app.example.com/invitations/token-1 para continuar");
  }

  @Test
  void theLegacyOverloadStillSendsTheDefault() {
    JavaMailSender mail = mock(JavaMailSender.class);
    SmtpInvitationSender sender =
        new SmtpInvitationSender(new InvitationDeliveryProperties("invites@example.com"), mail);

    sender.send("member@example.com", "https://app.example.com/invitations/token-1");

    SimpleMailMessage message = capture(mail);
    assertThat(message.getSubject()).isEqualTo("You have been invited");
  }
}
