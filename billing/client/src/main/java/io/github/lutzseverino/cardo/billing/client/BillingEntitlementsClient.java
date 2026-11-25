package io.github.lutzseverino.cardo.billing.client;

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
}
