package io.github.lutzseverino.cardo.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.lutzseverino.cardo.billing.mapper.EntitlementApplicationMapperImpl;
import io.github.lutzseverino.cardo.billing.model.Entitlement;
import io.github.lutzseverino.cardo.billing.model.EntitlementStatus;
import io.github.lutzseverino.cardo.billing.model.EntitlementSyncItem;
import io.github.lutzseverino.cardo.billing.model.SubscriptionChange;
import io.github.lutzseverino.cardo.billing.model.SubscriptionTerms;
import io.github.lutzseverino.cardo.billing.model.TrialStart;
import io.github.lutzseverino.cardo.billing.model.TrialTerms;
import io.github.lutzseverino.cardo.billing.repository.EntitlementProjection;
import io.github.lutzseverino.cardo.billing.repository.EntitlementRepository;
import io.github.lutzseverino.cardo.common.api.ApiException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

  @Mock private EntitlementRepository entitlements;

  @Test
  void requiresUsableEntitlement() {
    UUID subjectId = UUID.randomUUID();
    EntitlementService service =
        new EntitlementService(entitlements, new EntitlementApplicationMapperImpl());
    when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.of(entitlement(subjectId, EntitlementStatus.TRIALING)));

    assertThat(service.require(subjectId, "clinic").status()).isEqualTo(EntitlementStatus.TRIALING);
  }

  @Test
  void rejectsInactiveEntitlement() {
    UUID subjectId = UUID.randomUUID();
    EntitlementService service =
        new EntitlementService(entitlements, new EntitlementApplicationMapperImpl());
    when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.of(entitlement(subjectId, EntitlementStatus.CANCELED)));

    assertThatThrownBy(() -> service.require(subjectId, "clinic"))
        .isInstanceOf(ApiException.class)
        .hasMessage("Entitlement is not active.");
  }

  @Test
  void replacesActiveEntitlements() {
    UUID subjectId = UUID.randomUUID();
    EntitlementService service =
        new EntitlementService(entitlements, new EntitlementApplicationMapperImpl());
    when(entitlements.findEntitiesBySubjectId(subjectId)).thenReturn(List.of());

    service.replaceActive(subjectId, List.of(new EntitlementSyncItem("clinic", 1, 5)));

    ArgumentCaptor<Entitlement> captor = ArgumentCaptor.forClass(Entitlement.class);
    verify(entitlements).save(captor.capture());
    Entitlement entitlement = captor.getValue();
    assertThat(entitlement.getSubjectId()).isEqualTo(subjectId);
    assertThat(entitlement.getProduct()).isEqualTo("clinic");
    assertThat(entitlement.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);
    assertThat(entitlement.getTenantLimit()).isEqualTo(1);
    assertThat(entitlement.getSeatLimit()).isEqualTo(5);
  }

  @Test
  void aTrialGrantsAccessUntilItEndsAndNotAfter() {
    OffsetDateTime trialEndsAt = OffsetDateTime.parse("2026-12-25T09:00:00Z");
    UUID subjectId = UUID.randomUUID();
    when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(
            Optional.of(
                new TestEntitlementProjection(subjectId, EntitlementStatus.TRIALING, trialEndsAt)));

    assertThat(serviceAt(trialEndsAt.minusSeconds(1)).require(subjectId, "clinic").status())
        .isEqualTo(EntitlementStatus.TRIALING);
    assertThatThrownBy(() -> serviceAt(trialEndsAt.plusSeconds(1)).require(subjectId, "clinic"))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(403);
              assertThat(exception.code()).isEqualTo("entitlement_inactive");
            });
  }

  @Test
  void aTrialThatHasJustEndedCountsAsUsed() {
    OffsetDateTime trialEndsAt = OffsetDateTime.parse("2026-12-25T09:00:00Z");
    Entitlement trial = trialEndingAt(trialEndsAt);
    when(entitlements.findEntityBySubjectIdAndProduct(trial.getSubjectId(), "clinic"))
        .thenReturn(Optional.of(trial));
    when(entitlements.findProjectedBySubjectIdAndProduct(trial.getSubjectId(), "clinic"))
        .thenReturn(Optional.of(entitlement(trial.getSubjectId(), EntitlementStatus.TRIALING)));

    assertThat(
            serviceAt(trialEndsAt.minusSeconds(1))
                .startTrial(trial.getSubjectId(), "clinic", new TrialTerms(14, 1, 5))
                .started())
        .isFalse();
    assertThatThrownBy(
            () ->
                serviceAt(trialEndsAt.plusSeconds(1))
                    .startTrial(trial.getSubjectId(), "clinic", new TrialTerms(14, 1, 5)))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> assertThat(exception.code()).isEqualTo("trial_already_used"));
  }

  @Test
  void startsATrialForASubjectWithoutAnEntitlement() {
    UUID subjectId = UUID.randomUUID();
    EntitlementService service = service();
    when(entitlements.findEntityBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.empty());
    when(entitlements.insertTrialIfAbsent(
            any(), eq(subjectId), eq("clinic"), eq(1), eq(5), any(OffsetDateTime.class)))
        .thenReturn(1);
    when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.of(entitlement(subjectId, EntitlementStatus.TRIALING)));

    TrialStart trial = service.startTrial(subjectId, "clinic", new TrialTerms(14, 1, 5));

    assertThat(trial.started()).isTrue();
    assertThat(trial.entitlement().status()).isEqualTo(EntitlementStatus.TRIALING);
    ArgumentCaptor<OffsetDateTime> trialEndsAt = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(entitlements)
        .insertTrialIfAbsent(
            any(), eq(subjectId), eq("clinic"), eq(1), eq(5), trialEndsAt.capture());
    assertThat(trialEndsAt.getValue())
        .isCloseTo(OffsetDateTime.now().plusDays(14), within(1, ChronoUnit.MINUTES));
  }

  @Test
  void returnsACurrentTrialOrActiveOrPastDueEntitlementUnchanged() {
    for (Entitlement current :
        List.of(
            trialEndingAt(OffsetDateTime.now().plusDays(3)),
            stored(EntitlementStatus.ACTIVE),
            stored(EntitlementStatus.PAST_DUE))) {
      UUID subjectId = current.getSubjectId();
      EntitlementService service = service();
      when(entitlements.findEntityBySubjectIdAndProduct(subjectId, "clinic"))
          .thenReturn(Optional.of(current));
      when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
          .thenReturn(Optional.of(entitlement(subjectId, current.getStatus())));
      OffsetDateTime trialEndsAt = current.getTrialEndsAt();

      TrialStart trial = service.startTrial(subjectId, "clinic", new TrialTerms(30, 9, 99));

      assertThat(trial.started()).isFalse();
      assertThat(current.getStatus())
          .isIn(EntitlementStatus.TRIALING, EntitlementStatus.ACTIVE, EntitlementStatus.PAST_DUE);
      assertThat(current.getTrialEndsAt()).isEqualTo(trialEndsAt);
      assertThat(current.getTenantLimit()).isEqualTo(1);
      verify(entitlements, never())
          .insertTrialIfAbsent(any(), any(), any(), anyInt(), anyInt(), any());
      verify(entitlements, never()).saveAndFlush(any());
    }
  }

  @Test
  void judgesTheRowAConcurrentRequestInsertedFirst() {
    Entitlement concurrent = trialEndingAt(OffsetDateTime.now().plusDays(14));
    UUID subjectId = concurrent.getSubjectId();
    EntitlementService service = service();
    when(entitlements.findEntityBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.empty(), Optional.of(concurrent));
    when(entitlements.insertTrialIfAbsent(any(), any(), any(), anyInt(), anyInt(), any()))
        .thenReturn(0);
    when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.of(entitlement(subjectId, EntitlementStatus.TRIALING)));

    assertThat(service.startTrial(subjectId, "clinic", new TrialTerms(14, 1, 5)).started())
        .isFalse();
  }

  @Test
  void refusesASecondTrialOnceTheEntitlementHasEnded() {
    for (Entitlement ended :
        List.of(
            trialEndingAt(OffsetDateTime.now().minusMinutes(1)),
            stored(EntitlementStatus.CANCELED))) {
      EntitlementService service = service();
      when(entitlements.findEntityBySubjectIdAndProduct(ended.getSubjectId(), "clinic"))
          .thenReturn(Optional.of(ended));

      assertThatThrownBy(
              () -> service.startTrial(ended.getSubjectId(), "clinic", new TrialTerms(14, 1, 5)))
          .isInstanceOfSatisfying(
              ApiException.class,
              exception -> {
                assertThat(exception.status()).isEqualTo(409);
                assertThat(exception.code()).isEqualTo("trial_already_used");
              });
      verify(entitlements, never()).saveAndFlush(any());
    }
  }

  @Test
  void aPastDueSubscriptionKeepsGrantingAccessAndACancelledOneDoesNot() {
    UUID pastDue = UUID.randomUUID();
    UUID cancelled = UUID.randomUUID();
    when(entitlements.findProjectedBySubjectIdAndProduct(pastDue, "clinic"))
        .thenReturn(Optional.of(entitlement(pastDue, EntitlementStatus.PAST_DUE)));
    when(entitlements.findProjectedBySubjectIdAndProduct(cancelled, "clinic"))
        .thenReturn(Optional.of(entitlement(cancelled, EntitlementStatus.CANCELED)));

    assertThat(service().require(pastDue, "clinic").status()).isEqualTo(EntitlementStatus.PAST_DUE);
    assertThatThrownBy(() -> service().require(cancelled, "clinic"))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> assertThat(exception.code()).isEqualTo("entitlement_inactive"));
  }

  @Test
  void aSubscriptionReplacesATrial() {
    Entitlement trial = trialEndingAt(OffsetDateTime.now().plusDays(10));
    UUID subjectId = trial.getSubjectId();
    OffsetDateTime periodEndsAt = OffsetDateTime.parse("2026-12-26T09:00:00Z");
    when(entitlements.findEntityBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.of(trial));
    when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.of(entitlement(subjectId, EntitlementStatus.ACTIVE)));

    SubscriptionChange change =
        service()
            .setSubscription(
                subjectId,
                "clinic",
                new SubscriptionTerms(EntitlementStatus.ACTIVE, 2, 8, periodEndsAt));

    assertThat(change.created()).isFalse();
    assertThat(trial.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);
    assertThat(trial.getTrialEndsAt()).isNull();
    assertThat(trial.getTenantLimit()).isEqualTo(2);
    assertThat(trial.getSeatLimit()).isEqualTo(8);
    assertThat(trial.getCurrentPeriodEndsAt()).isEqualTo(periodEndsAt);
    verify(entitlements).saveAndFlush(trial);
    verify(entitlements, never())
        .insertSubscriptionIfAbsent(any(), any(), any(), any(), anyInt(), anyInt(), any());
  }

  @Test
  void createsTheSubscriptionForASubjectWithoutAnEntitlement() {
    UUID subjectId = UUID.randomUUID();
    OffsetDateTime periodEndsAt = OffsetDateTime.parse("2026-12-26T09:00:00Z");
    when(entitlements.findEntityBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.empty());
    when(entitlements.insertSubscriptionIfAbsent(
            any(), eq(subjectId), eq("clinic"), eq("PAST_DUE"), eq(1), eq(3), eq(periodEndsAt)))
        .thenReturn(1);
    when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.of(entitlement(subjectId, EntitlementStatus.PAST_DUE)));

    SubscriptionChange change =
        service()
            .setSubscription(
                subjectId,
                "clinic",
                new SubscriptionTerms(EntitlementStatus.PAST_DUE, 1, 3, periodEndsAt));

    assertThat(change.created()).isTrue();
    assertThat(change.entitlement().status()).isEqualTo(EntitlementStatus.PAST_DUE);
    verify(entitlements, never()).saveAndFlush(any());
  }

  @Test
  void replacesTheRowAConcurrentSubscriptionReportInsertedFirst() {
    Entitlement concurrent = stored(EntitlementStatus.ACTIVE);
    UUID subjectId = concurrent.getSubjectId();
    when(entitlements.findEntityBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.empty(), Optional.of(concurrent));
    when(entitlements.insertSubscriptionIfAbsent(
            any(), any(), any(), any(), anyInt(), anyInt(), any()))
        .thenReturn(0);
    when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
        .thenReturn(Optional.of(entitlement(subjectId, EntitlementStatus.CANCELED)));

    SubscriptionChange change =
        service()
            .setSubscription(
                subjectId, "clinic", new SubscriptionTerms(EntitlementStatus.CANCELED, 1, 3, null));

    assertThat(change.created()).isFalse();
    assertThat(concurrent.getStatus()).isEqualTo(EntitlementStatus.CANCELED);
    verify(entitlements).saveAndFlush(concurrent);
  }

  @Test
  void refusesToReportATrialingSubscription() {
    UUID subjectId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service()
                    .setSubscription(
                        subjectId,
                        "clinic",
                        new SubscriptionTerms(EntitlementStatus.TRIALING, 1, 3, null)))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(400);
              assertThat(exception.code()).isEqualTo("entitlement_status_invalid");
            });
    verify(entitlements, never()).findEntityBySubjectIdAndProduct(any(), any());
  }

  @Test
  void stripeSyncReplacesATrialWithThePaidEntitlement() {
    Entitlement trial = trialEndingAt(OffsetDateTime.now().plusDays(10));
    when(entitlements.findEntitiesBySubjectId(trial.getSubjectId())).thenReturn(List.of(trial));

    service()
        .replaceActive(trial.getSubjectId(), List.of(new EntitlementSyncItem("clinic", 3, 20)));

    assertThat(trial.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);
    assertThat(trial.getTrialEndsAt()).isNull();
    assertThat(trial.getTenantLimit()).isEqualTo(3);
    assertThat(trial.getSeatLimit()).isEqualTo(20);
    verify(entitlements, never()).save(any());
  }

  @Test
  void stripeSyncKeepsAnUnexpiredTrialItDoesNotListAndCancelsAnExpiredOne() {
    Entitlement current = trialEndingAt(OffsetDateTime.now().plusDays(10));
    Entitlement expired = trialEndingAt(OffsetDateTime.now().minusDays(1));
    when(entitlements.findEntitiesBySubjectId(current.getSubjectId()))
        .thenReturn(List.of(current, expired));

    service().replaceActive(current.getSubjectId(), List.of());

    assertThat(current.getStatus()).isEqualTo(EntitlementStatus.TRIALING);
    assertThat(expired.getStatus()).isEqualTo(EntitlementStatus.CANCELED);
  }

  private EntitlementService service() {
    return new EntitlementService(entitlements, new EntitlementApplicationMapperImpl());
  }

  private EntitlementService serviceAt(OffsetDateTime now) {
    return new EntitlementService(
        entitlements,
        new EntitlementApplicationMapperImpl(),
        Clock.fixed(now.toInstant(), ZoneOffset.UTC));
  }

  private Entitlement trialEndingAt(OffsetDateTime trialEndsAt) {
    Entitlement entitlement = stored(EntitlementStatus.TRIALING);
    entitlement.setTrialEndsAt(trialEndsAt);
    return entitlement;
  }

  private Entitlement stored(EntitlementStatus status) {
    Entitlement entitlement = Entitlement.create(UUID.randomUUID(), "clinic");
    entitlement.setStatus(status);
    entitlement.setTenantLimit(1);
    entitlement.setSeatLimit(5);
    return entitlement;
  }

  private EntitlementProjection entitlement(UUID subjectId, EntitlementStatus status) {
    return new TestEntitlementProjection(subjectId, status);
  }

  private record TestEntitlementProjection(
      UUID subjectId, EntitlementStatus status, OffsetDateTime trialEndsAt)
      implements EntitlementProjection {

    TestEntitlementProjection(UUID subjectId, EntitlementStatus status) {
      this(subjectId, status, null);
    }

    @Override
    public UUID getId() {
      return UUID.randomUUID();
    }

    @Override
    public UUID getSubjectId() {
      return subjectId;
    }

    @Override
    public String getProduct() {
      return "clinic";
    }

    @Override
    public EntitlementStatus getStatus() {
      return status;
    }

    @Override
    public Integer getTenantLimit() {
      return 1;
    }

    @Override
    public Integer getSeatLimit() {
      return 5;
    }

    @Override
    public OffsetDateTime getTrialEndsAt() {
      return trialEndsAt;
    }

    @Override
    public OffsetDateTime getCurrentPeriodEndsAt() {
      return null;
    }

    @Override
    public OffsetDateTime getCreatedAt() {
      return OffsetDateTime.now();
    }

    @Override
    public OffsetDateTime getUpdatedAt() {
      return OffsetDateTime.now();
    }
  }
}
