package io.github.lutzseverino.cardo.billing.client;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface BillingEntitlementsClient {

  BillingEntitlement require(UUID subjectId, String product);

  /**
   * Starts the subject's trial of the product, once. Retrying returns the subject's trialing or
   * active entitlement unchanged; a subject whose entitlement has ended is refused with {@code
   * trial_already_used}. Requires the {@code billing:entitlement:trial} authority.
   */
  BillingEntitlement startTrial(
      UUID subjectId, String product, int trialDays, int tenantLimit, int seatLimit);

  /**
   * Sets the subject's entitlement for the product to the paid subscription the product's own
   * payment provider confirmed, replacing whatever it was, a trial included. Safe to retry: the
   * same subscription again leaves the entitlement unchanged. {@code status} is {@code ACTIVE},
   * {@code PAST_DUE} (still grants access) or {@code CANCELED}. Requires the {@code
   * billing:entitlement:subscription} authority.
   *
   * @param currentPeriodEndsAt when the next payment is taken or a cancelled subscription stops, or
   *     {@code null}
   * @throws IllegalArgumentException when {@code status} is {@code TRIALING}
   */
  BillingEntitlement setSubscription(
      UUID subjectId,
      String product,
      BillingEntitlementStatus status,
      int tenantLimit,
      int seatLimit,
      OffsetDateTime currentPeriodEndsAt);
}
