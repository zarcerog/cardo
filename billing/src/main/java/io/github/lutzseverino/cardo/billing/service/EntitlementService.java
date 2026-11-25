package io.github.lutzseverino.cardo.billing.service;

import io.github.lutzseverino.cardo.billing.BillingPermissions;
import io.github.lutzseverino.cardo.billing.mapper.EntitlementApplicationMapper;
import io.github.lutzseverino.cardo.billing.model.Entitlement;
import io.github.lutzseverino.cardo.billing.model.EntitlementResult;
import io.github.lutzseverino.cardo.billing.model.EntitlementStatus;
import io.github.lutzseverino.cardo.billing.model.EntitlementSyncItem;
import io.github.lutzseverino.cardo.billing.model.TrialStart;
import io.github.lutzseverino.cardo.billing.model.TrialTerms;
import io.github.lutzseverino.cardo.billing.repository.EntitlementProjection;
import io.github.lutzseverino.cardo.billing.repository.EntitlementRepository;
import io.github.lutzseverino.cardo.common.api.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Validated
@Service
@RequiredArgsConstructor
public class EntitlementService {

  private final Clock clock = Clock.systemUTC();
  private final EntitlementRepository entitlements;
  private final EntitlementApplicationMapper mapper;

  public EntitlementResult getCurrent(UUID subjectId, @NotBlank String product) {
    return getResult(subjectId, product);
  }

  @PreAuthorize("hasAuthority('" + BillingPermissions.ENTITLEMENT_READ_AUTHORITY + "')")
  public EntitlementResult get(UUID subjectId, @NotBlank String product) {
    return getResult(subjectId, product);
  }

  @PreAuthorize("hasAuthority('" + BillingPermissions.ENTITLEMENT_READ_AUTHORITY + "')")
  public EntitlementResult require(UUID subjectId, @NotBlank String product) {
    EntitlementProjection entitlement = getProjection(subjectId, product);
    if (!entitlement.getStatus().usable()) {
      throw ApiException.forbidden("entitlement_inactive", "Entitlement is not active.");
    }
    return mapper.toResult(entitlement);
  }

  /**
   * Starts a trial of {@code product} for {@code subjectId}, once. A subject that already has a
   * current entitlement gets it back unchanged, so a retried sign-up never starts a second trial.
   */
  @Transactional
  @PreAuthorize("hasAuthority('" + BillingPermissions.ENTITLEMENT_TRIAL_AUTHORITY + "')")
  public TrialStart startTrial(
      @NotNull UUID subjectId, @NotBlank String product, @NotNull @Valid TrialTerms terms) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    Optional<Entitlement> existing =
        entitlements.findEntityBySubjectIdAndProduct(subjectId, product);
    if (existing.isEmpty()) {
      int inserted =
          entitlements.insertTrialIfAbsent(
              UUID.randomUUID(),
              subjectId,
              product,
              terms.tenantLimit(),
              terms.seatLimit(),
              now.plusDays(terms.trialDays()));
      if (inserted == 1) {
        return new TrialStart(getResult(subjectId, product), true);
      }
      // A concurrent request inserted it first; judge the row it wrote.
    }

    Entitlement entitlement = existing.orElseGet(() -> getEntity(subjectId, product));
    if (entitlement.isCurrent(now)) {
      return new TrialStart(getResult(subjectId, product), false);
    }
    if (!mayStartAnotherTrial(entitlement)) {
      throw ApiException.conflict(
          "trial_already_used", "The trial for this product has already been used.");
    }
    entitlement.startTrial(terms, now);
    entitlements.saveAndFlush(entitlement);
    return new TrialStart(getResult(subjectId, product), true);
  }

  /**
   * The repeat-trial rule: whether a subject whose entitlement for a product has ended (an expired
   * trial, a cancelled or a past-due subscription) may start a new trial of it. Every subject gets
   * one trial per product.
   */
  static boolean mayStartAnotherTrial(Entitlement ended) {
    return false;
  }

  @Transactional
  public void replaceActive(UUID subjectId, Collection<EntitlementSyncItem> activeItems) {
    Map<String, EntitlementSyncItem> activeByProduct = activeByProduct(activeItems);
    OffsetDateTime now = OffsetDateTime.now(clock);

    for (Entitlement entitlement : entitlements.findEntitiesBySubjectId(subjectId)) {
      EntitlementSyncItem active = activeByProduct.remove(entitlement.getProduct());
      if (active == null) {
        if (isUnexpiredTrial(entitlement, now)) {
          // Billing started this trial, not the provider; the provider's list never includes it.
          continue;
        }
        entitlement.setStatus(EntitlementStatus.CANCELED);
      } else {
        syncActive(entitlement, active);
      }
    }

    for (EntitlementSyncItem active : activeByProduct.values()) {
      Entitlement entitlement = Entitlement.create(subjectId, active.product());
      syncActive(entitlement, active);
      entitlements.save(entitlement);
    }
  }

  private Map<String, EntitlementSyncItem> activeByProduct(
      Collection<EntitlementSyncItem> activeItems) {
    Map<String, EntitlementSyncItem> activeByProduct = new LinkedHashMap<>();
    for (EntitlementSyncItem active : activeItems) {
      activeByProduct.put(active.product(), active);
    }
    return activeByProduct;
  }

  private void syncActive(Entitlement entitlement, EntitlementSyncItem active) {
    entitlement.setStatus(EntitlementStatus.ACTIVE);
    entitlement.setTenantLimit(active.tenantLimit());
    entitlement.setSeatLimit(active.seatLimit());
    entitlement.setTrialEndsAt(null);
    entitlement.setCurrentPeriodEndsAt(null);
  }

  private static boolean isUnexpiredTrial(Entitlement entitlement, OffsetDateTime now) {
    return entitlement.getStatus() == EntitlementStatus.TRIALING && entitlement.isCurrent(now);
  }

  private Entitlement getEntity(UUID subjectId, String product) {
    return entitlements
        .findEntityBySubjectIdAndProduct(subjectId, product)
        .orElseThrow(
            () -> ApiException.notFound("entitlement_not_found", "Entitlement not found."));
  }

  private EntitlementProjection getProjection(UUID subjectId, String product) {
    return entitlements
        .findProjectedBySubjectIdAndProduct(subjectId, product)
        .orElseThrow(
            () -> ApiException.notFound("entitlement_not_found", "Entitlement not found."));
  }

  private EntitlementResult getResult(UUID subjectId, String product) {
    return mapper.toResult(getProjection(subjectId, product));
  }
}
