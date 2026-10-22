package io.github.lutzseverino.cardo.invite.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InvitationMessage(
    @NotBlank @Size(max = 200) String subject, @NotBlank @Size(max = 10000) String body) {

  public static final String ACCEPT_URL_PLACEHOLDER = "{{acceptUrl}}";
}
