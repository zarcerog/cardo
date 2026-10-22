package io.github.lutzseverino.cardo.invite.provider;

public interface InvitationSender {

  void send(String email, String acceptUrl, String subject, String body);

  default void send(String email, String acceptUrl) {
    send(email, acceptUrl, null, null);
  }
}
