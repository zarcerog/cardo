package io.github.lutzseverino.cardo.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lutzseverino.cardo.billing.BillingPermissions;
import io.github.lutzseverino.cardo.billing.mapper.EntitlementApplicationMapperImpl;
import io.github.lutzseverino.cardo.billing.model.EntitlementResult;
import io.github.lutzseverino.cardo.billing.model.EntitlementStatus;
import io.github.lutzseverino.cardo.billing.model.SubscriptionChange;
import io.github.lutzseverino.cardo.billing.model.SubscriptionTerms;
import io.github.lutzseverino.cardo.billing.model.TrialTerms;
import io.github.lutzseverino.cardo.billing.repository.EntitlementRepository;
import io.github.lutzseverino.cardo.common.api.ApiException;
import java.time.OffsetDateTime;
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
  EntitlementSubscriptionPostgreSqlIntegrationTest.MethodSecurity.class
})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EntitlementSubscriptionPostgreSqlIntegrationTest {

  private static final OffsetDateTime PERIOD_ENDS_AT = OffsetDateTime.parse("2026-12-26T09:00:00Z");
  private static final SubscriptionTerms PAID =
      new SubscriptionTerms(EntitlementStatus.ACTIVE, 2, 8, PERIOD_ENDS_AT);

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("billing")
          .withUsername("billing")
          .withPassword("billing");

  @Autowired private EntitlementService service;
  @Autowired private EntitlementRepository entitlements;

  @DynamicPropertySource
  static void configurePostgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeEach
  void callAsTheProductOutboundClient() {
    entitlements.deleteAll();
    authenticateWith(
        BillingPermissions.ENTITLEMENT_SUBSCRIPTION_AUTHORITY,
        BillingPermissions.ENTITLEMENT_TRIAL_AUTHORITY,
        BillingPermissions.ENTITLEMENT_READ_AUTHORITY);
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aSubscriptionReplacesTheTrial() {
    UUID subjectId = UUID.randomUUID();
    service.startTrial(subjectId, "clinic", new TrialTerms(30, 1, 3));

    SubscriptionChange change = service.setSubscription(subjectId, "clinic", PAID);

    assertThat(change.created()).isFalse();
    EntitlementResult paid = service.require(subjectId, "clinic");
    assertThat(paid.status()).isEqualTo(EntitlementStatus.ACTIVE);
    assertThat(paid.trialEndsAt()).isNull();
    assertThat(paid.tenantLimit()).isEqualTo(2);
    assertThat(paid.seatLimit()).isEqualTo(8);
    assertThat(paid.currentPeriodEndsAt().toInstant()).isEqualTo(PERIOD_ENDS_AT.toInstant());
    assertThat(entitlements.count()).isOne();
  }

  @Test
  void reportingTheSameSubscriptionTwiceLeavesOneIdenticalEntitlement() {
    UUID subjectId = UUID.randomUUID();

    SubscriptionChange first = service.setSubscription(subjectId, "clinic", PAID);
    SubscriptionChange again = service.setSubscription(subjectId, "clinic", PAID);

    assertThat(first.created()).isTrue();
    assertThat(again.created()).isFalse();
    assertThat(again.entitlement()).isEqualTo(first.entitlement());
    assertThat(entitlements.count()).isOne();
  }

  @Test
  void concurrentFirstReportsCreateOneEntitlement() throws Exception {
    UUID subjectId = UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      List<Future<SubscriptionChange>> changes =
          List.of(
              executor.submit(() -> setAfter(start, subjectId)),
              executor.submit(() -> setAfter(start, subjectId)));
      start.countDown();

      assertThat(List.of(changes.get(0).get(), changes.get(1).get()))
          .extracting(SubscriptionChange::created)
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(entitlements.count()).isOne();
  }

  @Test
  void aPastDueSubscriptionKeepsAccessUntilItIsCancelled() {
    UUID subjectId = UUID.randomUUID();
    service.setSubscription(subjectId, "clinic", PAID);

    service.setSubscription(
        subjectId,
        "clinic",
        new SubscriptionTerms(EntitlementStatus.PAST_DUE, 2, 8, PERIOD_ENDS_AT));
    assertThat(service.require(subjectId, "clinic").status()).isEqualTo(EntitlementStatus.PAST_DUE);

    service.setSubscription(
        subjectId,
        "clinic",
        new SubscriptionTerms(EntitlementStatus.CANCELED, 2, 8, PERIOD_ENDS_AT));
    assertThatThrownBy(() -> service.require(subjectId, "clinic"))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(403);
              assertThat(exception.code()).isEqualTo("entitlement_inactive");
            });
  }

  @Test
  void refusesACallerWithoutTheSubscriptionAuthority() {
    authenticateWith(
        BillingPermissions.ENTITLEMENT_TRIAL_AUTHORITY,
        BillingPermissions.ENTITLEMENT_READ_AUTHORITY);

    assertThatThrownBy(() -> service.setSubscription(UUID.randomUUID(), "clinic", PAID))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(entitlements.count()).isZero();
  }

  private SubscriptionChange setAfter(CountDownLatch start, UUID subjectId) throws Exception {
    authenticateWith(BillingPermissions.ENTITLEMENT_SUBSCRIPTION_AUTHORITY);
    try {
      start.await();
      return service.setSubscription(subjectId, "clinic", PAID);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static void authenticateWith(String... authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("product-outbound", null, authorities));
  }

  @TestConfiguration
  @EnableMethodSecurity
  static class MethodSecurity {}
}
