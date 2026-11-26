package io.github.lutzseverino.cardo.billing.mapper;

import io.github.lutzseverino.cardo.billing.model.EntitlementStatus;
import org.springframework.stereotype.Component;

@Component
public class BillingTransportConversions {

  public io.github.lutzseverino.cardo.billing.api.model.EntitlementResponse.StatusEnum toTransport(
      EntitlementStatus status) {
    return status == null
        ? null
        : io.github.lutzseverino.cardo.billing.api.model.EntitlementResponse.StatusEnum.fromValue(
            status.wireValue());
  }

  /** A reported subscription status, by its wire value: {@code past_due} is {@code PAST_DUE}. */
  public EntitlementStatus toDomain(
      io.github.lutzseverino.cardo.billing.api.model.SetSubscriptionRequest.StatusEnum status) {
    return status == null ? null : EntitlementStatus.fromWireValue(status.getValue());
  }
}
