package io.github.lutzseverino.cardo.invite.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;

public record CreateInvitationInput(
    @NotNull UUID requestId,
    @NotNull UUID tenantId,
    @NotBlank @Pattern(regexp = "^[^:]+:[^:]+$") String tenantResourceType,
    @NotBlank @Email String email,
    @NotNull UUID invitedBy,
    @NotNull URI acceptUrlBase,
    @Valid InvitationMessage message) {

  @AssertTrue(message = "acceptUrlBase must be an absolute HTTP(S) URL without query or fragment")
  public boolean isAcceptUrlBaseValid() {
    return acceptUrlBase == null
        || (("http".equalsIgnoreCase(acceptUrlBase.getScheme())
                || "https".equalsIgnoreCase(acceptUrlBase.getScheme()))
            && acceptUrlBase.getHost() != null
            && !acceptUrlBase.getHost().isBlank()
            && acceptUrlBase.getQuery() == null
            && acceptUrlBase.getFragment() == null);
  }

  @AssertTrue(message = "message body must contain the {{acceptUrl}} placeholder exactly once") public boolean isMessageBodyPlaceholderValid() {
    if (message == null || message.body() == null) {
      return true;
    }
    int first = message.body().indexOf(InvitationMessage.ACCEPT_URL_PLACEHOLDER);
    if (first < 0) {
      return false;
    }
    int second =
        message
            .body()
            .indexOf(
                InvitationMessage.ACCEPT_URL_PLACEHOLDER,
                first + InvitationMessage.ACCEPT_URL_PLACEHOLDER.length());
    return second < 0;
  }

  @AssertTrue(message = "message subject must not contain a line break") public boolean isMessageSubjectSingleLine() {
    return message == null
        || message.subject() == null
        || (message.subject().indexOf('\n') < 0 && message.subject().indexOf('\r') < 0);
  }
}
