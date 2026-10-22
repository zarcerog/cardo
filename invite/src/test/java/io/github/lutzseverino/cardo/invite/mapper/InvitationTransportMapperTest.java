package io.github.lutzseverino.cardo.invite.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lutzseverino.cardo.invite.api.model.CreateInvitationRequest;
import io.github.lutzseverino.cardo.invite.api.model.InvitationMessageRequest;
import io.github.lutzseverino.cardo.invite.model.CreateInvitationInput;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class InvitationTransportMapperTest {

  private final InvitationTransportMapper mapper =
      Mappers.getMapper(InvitationTransportMapper.class);

  private CreateInvitationRequest request(InvitationMessageRequest message) {
    return new CreateInvitationRequest()
        .requestId(UUID.randomUUID())
        .tenantId(UUID.randomUUID())
        .tenantResourceType("clinic:clinic")
        .email("employee@example.com")
        .invitedBy(UUID.randomUUID())
        .acceptUrlBase(URI.create("https://clinic.example.com/invitations"))
        .message(message);
  }

  @Test
  void mapsAnAbsentMessageToNull() {
    CreateInvitationInput input = mapper.toInput(request(null));
    assertThat(input.message()).isNull();
  }

  @Test
  void mapsASuppliedMessage() {
    CreateInvitationInput input =
        mapper.toInput(
            request(new InvitationMessageRequest().subject("Join").body("Go {{acceptUrl}}")));
    assertThat(input.message()).isNotNull();
    assertThat(input.message().subject()).isEqualTo("Join");
    assertThat(input.message().body()).isEqualTo("Go {{acceptUrl}}");
  }
}
