package io.github.lutzseverino.cardo.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.lutzseverino.cardo.authorization.spring.AuthenticatedUserReader;
import io.github.lutzseverino.cardo.billing.api.model.SetSubscriptionRequest;
import io.github.lutzseverino.cardo.billing.api.model.StartTrialRequest;
import io.github.lutzseverino.cardo.billing.mapper.EntitlementTransportMapper;
import io.github.lutzseverino.cardo.billing.model.EntitlementStatus;
import io.github.lutzseverino.cardo.billing.model.SubscriptionChange;
import io.github.lutzseverino.cardo.billing.model.SubscriptionTerms;
import io.github.lutzseverino.cardo.billing.model.TrialStart;
import io.github.lutzseverino.cardo.billing.model.TrialTerms;
import io.github.lutzseverino.cardo.billing.service.EntitlementService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EntitlementController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(
    properties = {
      "cardo.api.base-path=/api/v1",
      "spring.autoconfigure.exclude=org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration"
    })
class EntitlementControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private AuthenticatedUserReader users;
  @MockitoBean private EntitlementTransportMapper mapper;
  @MockitoBean private EntitlementService entitlements;

  @Test
  void getsSubjectEntitlementFromPathParameters() throws Exception {
    UUID subjectId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    mvc.perform(
            get("/api/v1/billing/subjects/{subjectId}/entitlements/{product}", subjectId, "polity"))
        .andExpect(status().isOk());

    verify(entitlements).get(subjectId, "polity");
  }

  @Test
  void requiresSubjectEntitlementFromPathParameters() throws Exception {
    UUID subjectId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    mvc.perform(
            post(
                "/api/v1/billing/subjects/{subjectId}/entitlements/{product}/access",
                subjectId,
                "assembly"))
        .andExpect(status().isOk());

    verify(entitlements).require(subjectId, "assembly");
  }

  @Test
  void answersCreatedWhenTheTrialStartsAndOkWhenItAlreadyExists() throws Exception {
    UUID subjectId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    TrialTerms terms = new TrialTerms(14, 1, 5);
    when(mapper.toTerms(any(StartTrialRequest.class))).thenReturn(terms);
    when(entitlements.startTrial(eq(subjectId), eq("clinic"), eq(terms)))
        .thenReturn(new TrialStart(null, true), new TrialStart(null, false));

    mvc.perform(trial(subjectId, "{\"trialDays\":14,\"tenantLimit\":1,\"seatLimit\":5}"))
        .andExpect(status().isCreated());
    mvc.perform(trial(subjectId, "{\"trialDays\":14,\"tenantLimit\":1,\"seatLimit\":5}"))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsTrialTermsOutsideTheirBounds() throws Exception {
    UUID subjectId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    for (String body :
        new String[] {
          "{\"trialDays\":0,\"tenantLimit\":1,\"seatLimit\":5}",
          "{\"trialDays\":366,\"tenantLimit\":1,\"seatLimit\":5}",
          "{\"trialDays\":14,\"tenantLimit\":0,\"seatLimit\":5}",
          "{\"trialDays\":14,\"tenantLimit\":1,\"seatLimit\":10001}",
          "{\"trialDays\":14,\"tenantLimit\":1}"
        }) {
      mvc.perform(trial(subjectId, body)).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(entitlements);
  }

  @Test
  void answersCreatedWhenTheSubscriptionIsNewAndOkWhenItReplacesTheEntitlement() throws Exception {
    UUID subjectId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    SubscriptionTerms terms = new SubscriptionTerms(EntitlementStatus.PAST_DUE, 1, 3, null);
    when(mapper.toTerms(any(SetSubscriptionRequest.class))).thenReturn(terms);
    when(entitlements.setSubscription(eq(subjectId), eq("clinic"), eq(terms)))
        .thenReturn(new SubscriptionChange(null, true), new SubscriptionChange(null, false));
    String body =
        "{\"status\":\"past_due\",\"tenantLimit\":1,\"seatLimit\":3,"
            + "\"currentPeriodEndsAt\":\"2026-12-26T09:00:00Z\"}";

    mvc.perform(subscription(subjectId, body)).andExpect(status().isCreated());
    mvc.perform(subscription(subjectId, body)).andExpect(status().isOk());

    ArgumentCaptor<SetSubscriptionRequest> request =
        ArgumentCaptor.forClass(SetSubscriptionRequest.class);
    verify(mapper, times(2)).toTerms(request.capture());
    assertThat(request.getValue().getStatus())
        .isEqualTo(SetSubscriptionRequest.StatusEnum.PAST_DUE);
    assertThat(request.getValue().getCurrentPeriodEndsAt())
        .isEqualTo(OffsetDateTime.parse("2026-12-26T09:00:00Z"));
  }

  @Test
  void rejectsASubscriptionWithAnUnknownStatusOrLimitsOutsideTheirBounds() throws Exception {
    UUID subjectId = UUID.fromString("66666666-6666-6666-6666-666666666666");

    for (String body :
        new String[] {
          "{\"status\":\"trialing\",\"tenantLimit\":1,\"seatLimit\":3}",
          "{\"status\":\"paused\",\"tenantLimit\":1,\"seatLimit\":3}",
          "{\"status\":\"active\",\"tenantLimit\":0,\"seatLimit\":3}",
          "{\"status\":\"active\",\"tenantLimit\":1,\"seatLimit\":10001}",
          "{\"tenantLimit\":1,\"seatLimit\":3}",
          "{\"status\":\"active\",\"tenantLimit\":1}"
        }) {
      mvc.perform(subscription(subjectId, body)).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(entitlements);
  }

  private org.springframework.test.web.servlet.RequestBuilder subscription(
      UUID subjectId, String body) {
    return put(
            "/api/v1/billing/subjects/{subjectId}/entitlements/{product}/subscription",
            subjectId,
            "clinic")
        .contentType("application/json")
        .content(body);
  }

  private org.springframework.test.web.servlet.RequestBuilder trial(UUID subjectId, String body) {
    return put(
            "/api/v1/billing/subjects/{subjectId}/entitlements/{product}/trial",
            subjectId,
            "clinic")
        .contentType("application/json")
        .content(body);
  }
}
