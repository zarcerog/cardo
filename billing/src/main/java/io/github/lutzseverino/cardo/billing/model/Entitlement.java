package io.github.lutzseverino.cardo.billing.model;

import io.github.lutzseverino.cardo.common.data.AuditedEntity;
import io.github.lutzseverino.cardo.common.data.PersonalDataEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "billing_entitlements",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_billing_entitlements_subject_product",
            columnNames = {"subject_id", "product"}))
public class Entitlement extends AuditedEntity implements PersonalDataEntity {

  @Id @GeneratedValue private UUID id;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(nullable = false)
  private String product;

  @Setter
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EntitlementStatus status = EntitlementStatus.ACTIVE;

  @Setter
  @Column(name = "tenant_limit")
  private Integer tenantLimit;

  @Setter
  @Column(name = "seat_limit")
  private Integer seatLimit;

  @Setter
  @Column(name = "trial_ends_at")
  private OffsetDateTime trialEndsAt;

  @Setter
  @Column(name = "current_period_ends_at")
  private OffsetDateTime currentPeriodEndsAt;

  public static Entitlement create(UUID subjectId, String product) {
    Entitlement entitlement = new Entitlement();
    entitlement.subjectId = subjectId;
    entitlement.product = product;
    return entitlement;
  }

  /**
   * Whether the entitlement still grants access: active, or trialing with a trial that has not
   * ended at {@code now}.
   */
  public boolean isCurrent(OffsetDateTime now) {
    return switch (status) {
      case ACTIVE -> true;
      case TRIALING -> trialEndsAt == null || trialEndsAt.isAfter(now);
      case PAST_DUE, CANCELED -> false;
    };
  }

  public void startTrial(TrialTerms terms, OffsetDateTime now) {
    status = EntitlementStatus.TRIALING;
    tenantLimit = terms.tenantLimit();
    seatLimit = terms.seatLimit();
    trialEndsAt = now.plusDays(terms.trialDays());
    currentPeriodEndsAt = null;
  }
}
