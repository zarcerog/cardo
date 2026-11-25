package io.github.lutzseverino.cardo.billing.model;

/**
 * The outcome of a trial request: the subject's entitlement and whether this request started it.
 */
public record TrialStart(EntitlementResult entitlement, boolean started) {}
