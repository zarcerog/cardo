package io.github.lutzseverino.cardo.billing.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * A paid subscription a product reports for a subject: its status ({@code active}, {@code past_due}
 * or {@code canceled}), its limits and when its current period ends.
 */
public record SubscriptionTerms(
    @NotNull EntitlementStatus status,
    @Min(1) @Max(TrialTerms.MAX_TENANT_LIMIT) int tenantLimit,
    @Min(1) @Max(TrialTerms.MAX_SEAT_LIMIT) int seatLimit,
    OffsetDateTime currentPeriodEndsAt) {}
