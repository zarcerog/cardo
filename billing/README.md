# Billing

Billing owns commercial access: customers, entitlements, checkout sessions,
portal sessions, and provider webhook ingestion.

## Product Integration

Products use `billing-client` as the stable Java contract for entitlement
checks. It returns billing-owned entitlement state; products decide how product
limits apply to their domain workflows. `billing-client-http` provides the HTTP
implementation and Spring Boot auto-configuration for service-to-service calls
to Billing.

There is no product integration module yet. Add one only if real products repeat
the same entitlement guard, checkout, or portal wiring beyond the existing
client modules.

### Trials

A product starts a subject's free trial, without a card, with
`PUT /billing/subjects/{subjectId}/entitlements/{product}/trial` (Java:
`BillingEntitlementsClient.startTrial`). The body carries `trialDays` (1-365),
`tenantLimit` (1-1000), and `seatLimit` (1-10000). The call is idempotent per
subject and product:

| Subject's entitlement for the product | Result |
| --- | --- |
| none | `201` with a new `trialing` entitlement ending `trialDays` from now |
| `trialing` (not yet ended) or `active` | `200` with it, unchanged |
| ended: an expired trial, `canceled`, or `past_due` | `409 trial_already_used` |

Each subject gets one trial per product; `EntitlementService.mayStartAnotherTrial`
holds that rule. The caller needs the `billing:entitlement:trial` authority:
the `entitlement:trial` role of the `billing` Keycloak client, granted only to
the product's outbound service client.

When Stripe later reports a paid entitlement for the product, the sync replaces
the trial with it (`active`, no `trialEndsAt`). A sync that does not list the
product leaves an unexpired trial alone, because Billing started it, not Stripe,
and cancels an expired one.

Products still own product catalog meaning, tenant limits in domain workflows,
and the decision about which product operation requires an entitlement. Billing
must not silently grant product-domain access.

## Documentation

Set `BILLING_RUNTIME_MODE=production` with a remote HTTPS Keycloak issuer, Stripe credentials and
catalogue, and a Billing-owned PostgreSQL database with distinct no-login owner and login
application roles. Startup verifies the effective database and roles after Flyway completes. Stripe and issuer/JWK calls have
positive finite connection and response bounds; Stripe automatic network retries are disabled.
See the indexed [runtime property reference](../docs/reference/runtime-properties.md).

Start with the [Billing documentation index](docs/README.md). Cross-project
architecture and conventions remain in the [Cardo documentation](../docs/README.md).
