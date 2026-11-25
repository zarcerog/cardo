package io.github.lutzseverino.cardo.billing.model;

import io.github.lutzseverino.cardo.common.api.ApiException;
import java.time.OffsetDateTime;

public enum EntitlementStatus {
  ACTIVE("active"),
  TRIALING("trialing"),
  PAST_DUE("past_due"),
  CANCELED("canceled");

  private final String wireValue;

  EntitlementStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  /**
   * The single access rule: an entitlement grants access when it is active, or trialing with a
   * trial that has not ended at {@code now}. A trial ends at {@code trialEndsAt}; from then on it
   * counts as ended, like a cancelled subscription.
   */
  public boolean grantsAccess(OffsetDateTime trialEndsAt, OffsetDateTime now) {
    return switch (this) {
      case ACTIVE -> true;
      case TRIALING -> trialEndsAt == null || trialEndsAt.isAfter(now);
      case PAST_DUE, CANCELED -> false;
    };
  }

  public static EntitlementStatus fromWireValue(String value) {
    for (EntitlementStatus status : values()) {
      if (status.wireValue.equals(value)) {
        return status;
      }
    }
    throw ApiException.badRequest("entitlement_status_invalid", "Entitlement status is invalid.");
  }
}
