package io.github.lutzseverino.cardo.billing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lutzseverino.cardo.billing.api.model.SetSubscriptionRequest;
import io.github.lutzseverino.cardo.billing.model.EntitlementStatus;
import org.junit.jupiter.api.Test;

class BillingTransportConversionsTest {

  private final BillingTransportConversions conversions = new BillingTransportConversions();

  @Test
  void mapsEveryReportedSubscriptionStatusToItsEntitlementStatus() {
    assertThat(conversions.toDomain(SetSubscriptionRequest.StatusEnum.fromValue("active")))
        .isEqualTo(EntitlementStatus.ACTIVE);
    assertThat(conversions.toDomain(SetSubscriptionRequest.StatusEnum.fromValue("past_due")))
        .isEqualTo(EntitlementStatus.PAST_DUE);
    assertThat(conversions.toDomain(SetSubscriptionRequest.StatusEnum.fromValue("canceled")))
        .isEqualTo(EntitlementStatus.CANCELED);
    assertThat(SetSubscriptionRequest.StatusEnum.values()).hasSize(3);
  }

  @Test
  void aPastDueSubscriptionStillGrantsAccessAndACancelledOneDoesNot() {
    assertThat(EntitlementStatus.PAST_DUE.grantsAccess(null, null)).isTrue();
    assertThat(EntitlementStatus.ACTIVE.grantsAccess(null, null)).isTrue();
    assertThat(EntitlementStatus.CANCELED.grantsAccess(null, null)).isFalse();
  }
}
