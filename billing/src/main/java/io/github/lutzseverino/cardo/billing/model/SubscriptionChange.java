package io.github.lutzseverino.cardo.billing.model;

/**
 * The outcome of reporting a subscription: the subject's entitlement and whether this request
 * created it.
 */
public record SubscriptionChange(EntitlementResult entitlement, boolean created) {}
