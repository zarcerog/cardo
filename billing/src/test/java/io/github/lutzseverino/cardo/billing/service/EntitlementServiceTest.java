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
import io.github.lutzseverino.cardo.billing.model.TrialStart;
import io.github.lutzseverino.cardo.billing.model.TrialTerms;
import io.github.lutzseverino.cardo.billing.repository.EntitlementProjection;
import io.github.lutzseverino.cardo.billing.repository.EntitlementRepository;
import io.github.lutzseverino.cardo.common.api.ApiException;
import java.time.OffsetDateTime;
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
        .thenReturn(Optional.of(entitlement(subjectId, EntitlementStatus.PAST_DUE)));

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
  void returnsACurrentTrialOrActiveEntitlementUnchanged() {
    for (Entitlement current :
        List.of(
            trialEndingAt(OffsetDateTime.now().plusDays(3)), stored(EntitlementStatus.ACTIVE))) {
      UUID subjectId = current.getSubjectId();
      EntitlementService service = service();
      when(entitlements.findEntityBySubjectIdAndProduct(subjectId, "clinic"))
          .thenReturn(Optional.of(current));
      when(entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic"))
          .thenReturn(Optional.of(entitlement(subjectId, current.getStatus())));
      OffsetDateTime trialEndsAt = current.getTrialEndsAt();

      TrialStart trial = service.startTrial(subjectId, "clinic", new TrialTerms(30, 9, 99));

      assertThat(trial.started()).isFalse();
      assertThat(current.getStatus()).isIn(EntitlementStatus.TRIALING, EntitlementStatus.ACTIVE);
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
            stored(EntitlementStatus.CANCELED),
            stored(EntitlementStatus.PAST_DUE))) {
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

  private record TestEntitlementProjection(UUID subjectId, EntitlementStatus status)
      implements EntitlementProjection {

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
      return null;
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
