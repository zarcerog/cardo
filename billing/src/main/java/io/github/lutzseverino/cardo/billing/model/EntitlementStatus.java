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
   * The single access rule: an entitlement grants access when it is active, past due, or trialing
   * with a trial that has not ended at {@code now}. A trial ends at {@code trialEndsAt}; from then
   * on it counts as ended, like a cancelled subscription. A past-due subscription keeps granting
   * access while the payment provider retries; the product decides how long a grace it allows, and
   * reports {@code canceled} when it ends.
   */
  public boolean grantsAccess(OffsetDateTime trialEndsAt, OffsetDateTime now) {
    return switch (this) {
      case ACTIVE, PAST_DUE -> true;
      case TRIALING -> trialEndsAt == null || trialEndsAt.isAfter(now);
      case CANCELED -> false;
    };
  }

  /** Whether a product may report this status for a paid subscription. */
  public boolean describesASubscription() {
    return this != TRIALING;
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
