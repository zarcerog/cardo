package io.github.lutzseverino.cardo.billing.controller;

import io.github.lutzseverino.cardo.authorization.spring.AuthenticatedUserReader;
import io.github.lutzseverino.cardo.billing.api.EntitlementsApi;
import io.github.lutzseverino.cardo.billing.api.model.EntitlementResponse;
import io.github.lutzseverino.cardo.billing.api.model.StartTrialRequest;
import io.github.lutzseverino.cardo.billing.mapper.EntitlementTransportMapper;
import io.github.lutzseverino.cardo.billing.model.TrialStart;
import io.github.lutzseverino.cardo.billing.service.EntitlementService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${cardo.api.base-path}")
@RequiredArgsConstructor
public class EntitlementController implements EntitlementsApi {

  private final EntitlementTransportMapper mapper;
  private final EntitlementService entitlements;
  private final AuthenticatedUserReader users;

  @Override
  public ResponseEntity<EntitlementResponse> getCurrentEntitlement(String product) {
    return ResponseEntity.ok(
        mapper.toResponse(entitlements.getCurrent(users.currentUser().id(), product)));
  }

  @Override
  public ResponseEntity<EntitlementResponse> getSubjectEntitlement(
      @PathVariable("subjectId") UUID subjectId, @PathVariable("product") String product) {
    return ResponseEntity.ok(mapper.toResponse(entitlements.get(subjectId, product)));
  }

  @Override
  public ResponseEntity<EntitlementResponse> requireSubjectEntitlement(
      @PathVariable("subjectId") UUID subjectId, @PathVariable("product") String product) {
    return ResponseEntity.ok(mapper.toResponse(entitlements.require(subjectId, product)));
  }

  @Override
  public ResponseEntity<EntitlementResponse> startSubjectTrial(
      @PathVariable("subjectId") UUID subjectId,
      @PathVariable("product") String product,
      @Valid @RequestBody StartTrialRequest request) {
    TrialStart trial = entitlements.startTrial(subjectId, product, mapper.toTerms(request));
    return ResponseEntity.status(trial.started() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(mapper.toResponse(trial.entitlement()));
  }
}
