package io.github.lutzseverino.cardo.billing.repository;

import io.github.lutzseverino.cardo.billing.model.Entitlement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EntitlementRepository extends JpaRepository<Entitlement, UUID> {

  Optional<EntitlementProjection> findProjectedBySubjectIdAndProduct(
      UUID subjectId, String product);

  Optional<Entitlement> findEntityBySubjectIdAndProduct(UUID subjectId, String product);

  List<Entitlement> findEntitiesBySubjectId(UUID subjectId);

  /**
   * Inserts a trialing entitlement unless the subject already has one for the product, so
   * concurrent first requests start exactly one trial.
   *
   * @return 1 when this call inserted the trial, otherwise 0
   */
  @Modifying(flushAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
          insert into billing_entitlements
            (id, subject_id, product, status, tenant_limit, seat_limit, trial_ends_at)
          values
            (:id, :subjectId, :product, 'TRIALING', :tenantLimit, :seatLimit, :trialEndsAt)
          on conflict (subject_id, product) do nothing
          """)
  int insertTrialIfAbsent(
      @Param("id") UUID id,
      @Param("subjectId") UUID subjectId,
      @Param("product") String product,
      @Param("tenantLimit") int tenantLimit,
      @Param("seatLimit") int seatLimit,
      @Param("trialEndsAt") OffsetDateTime trialEndsAt);
}
