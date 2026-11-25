package io.github.lutzseverino.cardo.billing.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** The length and limits of a trial a product starts for a subject. */
public record TrialTerms(
    @Min(1) @Max(MAX_TRIAL_DAYS) int trialDays,
    @Min(1) @Max(MAX_TENANT_LIMIT) int tenantLimit,
    @Min(1) @Max(MAX_SEAT_LIMIT) int seatLimit) {

  public static final int MAX_TRIAL_DAYS = 365;
  public static final int MAX_TENANT_LIMIT = 1000;
  public static final int MAX_SEAT_LIMIT = 10000;
}
