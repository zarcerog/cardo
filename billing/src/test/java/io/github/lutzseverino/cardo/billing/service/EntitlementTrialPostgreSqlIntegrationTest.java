package io.github.lutzseverino.cardo.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.lutzseverino.cardo.billing.BillingPermissions;
import io.github.lutzseverino.cardo.billing.mapper.EntitlementApplicationMapperImpl;
import io.github.lutzseverino.cardo.billing.model.EntitlementResult;
import io.github.lutzseverino.cardo.billing.model.EntitlementStatus;
import io.github.lutzseverino.cardo.billing.model.EntitlementSyncItem;
import io.github.lutzseverino.cardo.billing.model.TrialStart;
import io.github.lutzseverino.cardo.billing.model.TrialTerms;
import io.github.lutzseverino.cardo.billing.repository.EntitlementRepository;
import io.github.lutzseverino.cardo.common.api.ApiException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(
    properties = {
      "spring.flyway.locations=classpath:db/migration",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({
  EntitlementService.class,
  EntitlementApplicationMapperImpl.class,
  EntitlementTrialPostgreSqlIntegrationTest.MethodSecurity.class
})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EntitlementTrialPostgreSqlIntegrationTest {

  private static final TrialTerms TERMS = new TrialTerms(14, 1, 5);

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("billing")
          .withUsername("billing")
          .withPassword("billing");

  @Autowired private EntitlementService service;
  @Autowired private EntitlementRepository entitlements;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void configurePostgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeEach
  void callAsTheProductOutboundClient() {
    entitlements.deleteAll();
    authenticateWith(BillingPermissions.ENTITLEMENT_TRIAL_AUTHORITY);
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void startsATrialAndReturnsItUnchangedWhenTheSignUpIsRetried() {
    UUID subjectId = UUID.randomUUID();

    TrialStart first = service.startTrial(subjectId, "clinic", TERMS);
    TrialStart retry = service.startTrial(subjectId, "clinic", new TrialTerms(60, 3, 30));

    assertThat(first.started()).isTrue();
    EntitlementResult trial = first.entitlement();
    assertThat(trial.subjectId()).isEqualTo(subjectId);
    assertThat(trial.product()).isEqualTo("clinic");
    assertThat(trial.status()).isEqualTo(EntitlementStatus.TRIALING);
    assertThat(trial.tenantLimit()).isEqualTo(1);
    assertThat(trial.seatLimit()).isEqualTo(5);
    assertThat(trial.trialEndsAt())
        .isCloseTo(OffsetDateTime.now().plusDays(14), within(1, ChronoUnit.MINUTES));
    assertThat(trial.createdAt()).isNotNull();

    assertThat(retry.started()).isFalse();
    assertThat(retry.entitlement()).isEqualTo(trial);
    assertThat(entitlements.count()).isOne();
  }

  @Test
  void concurrentFirstRequestsStartExactlyOneTrial() throws Exception {
    UUID subjectId = UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      List<Future<TrialStart>> trials =
          List.of(
              executor.submit(() -> startAfter(start, subjectId)),
              executor.submit(() -> startAfter(start, subjectId)));
      start.countDown();

      assertThat(List.of(trials.get(0).get(), trials.get(1).get()))
          .extracting(TrialStart::started)
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(entitlements.count()).isOne();
  }

  @Test
  void refusesASecondTrialAfterTheTrialExpiredOrTheSubscriptionWasCancelled() {
    UUID expired = UUID.randomUUID();
    UUID cancelled = UUID.randomUUID();
    service.startTrial(expired, "clinic", TERMS);
    service.startTrial(cancelled, "clinic", TERMS);
    jdbc.update(
        "update billing_entitlements set trial_ends_at = now() - interval '1 minute'"
            + " where subject_id = ?",
        expired);
    jdbc.update(
        "update billing_entitlements set status = 'CANCELED' where subject_id = ?", cancelled);

    for (UUID subjectId : List.of(expired, cancelled)) {
      assertThatThrownBy(() -> service.startTrial(subjectId, "clinic", TERMS))
          .isInstanceOfSatisfying(
              ApiException.class,
              exception -> {
                assertThat(exception.status()).isEqualTo(409);
                assertThat(exception.code()).isEqualTo("trial_already_used");
              });
    }
  }

  @Test
  void refusesACallerWithoutTheTrialAuthority() {
    authenticateWith(BillingPermissions.ENTITLEMENT_READ_AUTHORITY);

    assertThatThrownBy(() -> service.startTrial(UUID.randomUUID(), "clinic", TERMS))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(entitlements.count()).isZero();
  }

  @Test
  void stripeSyncReplacesTheTrialWithThePaidEntitlement() {
    UUID subjectId = UUID.randomUUID();
    service.startTrial(subjectId, "clinic", TERMS);

    service.replaceActive(subjectId, List.of(new EntitlementSyncItem("clinic", 2, 10)));

    var paid = entitlements.findProjectedBySubjectIdAndProduct(subjectId, "clinic").orElseThrow();
    assertThat(paid.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);
    assertThat(paid.getTrialEndsAt()).isNull();
    assertThat(paid.getTenantLimit()).isEqualTo(2);
    assertThat(paid.getSeatLimit()).isEqualTo(10);
  }

  private TrialStart startAfter(CountDownLatch start, UUID subjectId) throws Exception {
    authenticateWith(BillingPermissions.ENTITLEMENT_TRIAL_AUTHORITY);
    try {
      start.await();
      return service.startTrial(subjectId, "clinic", TERMS);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static void authenticateWith(String authority) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("product-outbound", null, authority));
  }

  @TestConfiguration
  @EnableMethodSecurity
  static class MethodSecurity {}
}
