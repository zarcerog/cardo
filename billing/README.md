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
| `trialing` (not yet ended), `active` or `past_due` | `200` with it, unchanged |
| ended: an expired trial or `canceled` | `409 trial_already_used` |

A trial ends at its `trialEndsAt`: from then on `/access` (`require`) refuses it
with `403 entitlement_inactive` and it counts as used. `EntitlementStatus.grantsAccess`
holds that access rule. Each subject gets one trial per product; `EntitlementService.mayStartAnotherTrial`
holds that rule. The caller needs the `billing:entitlement:trial` authority:
the `entitlement:trial` role of the `billing` Keycloak client, granted only to
the product's outbound service client.

When Stripe later reports a paid entitlement for the product, the sync replaces
the trial with it (`active`, no `trialEndsAt`). A sync that does not list the
product leaves an unexpired trial alone, because Billing started it, not Stripe,
and cancels an expired one.

### Subscriptions a product bills itself

A product that takes payment through its own payment provider reports the
subscription with `PUT /billing/subjects/{subjectId}/entitlements/{product}/subscription`
(Java: `BillingEntitlementsClient.setSubscription`). The body carries `status`
(`active`, `past_due` or `canceled`), `tenantLimit` (1-1000), `seatLimit`
(1-10000) and an optional `currentPeriodEndsAt`. It replaces the subject's
entitlement for the product, a trial included: no `trialEndsAt`, the reported
status, limits and period end. It answers `201` when it created the entitlement
and `200` otherwise; reporting the same subscription again leaves it unchanged.

A `past_due` subscription keeps granting access while the provider retries the
payment; the product decides how long a grace it allows and reports `canceled`
when it ends. The caller needs the `billing:entitlement:subscription`
authority: the `entitlement:subscription` role of the `billing` Keycloak client,
granted only to the product's outbound service client.

Billing's Stripe sync does not know these subscriptions. A sync for a subject
that does not list the product cancels the entitlement, as it does any paid
entitlement Stripe no longer reports, so a product should not use both Cardo's
Stripe checkout and this operation for the same product.

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
