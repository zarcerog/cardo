package io.github.lutzseverino.cardo.billing;

public final class BillingPermissions {

  public static final String ENTITLEMENT_READ_AUTHORITY = "billing:entitlement:read";

  /**
   * Starts a product trial for a subject. Granted to a product's outbound service client as the
   * {@code entitlement:trial} role of the {@code billing} Keycloak client.
   */
  public static final String ENTITLEMENT_TRIAL_AUTHORITY = "billing:entitlement:trial";

  /**
   * Sets a subject's entitlement to the subscription a product's own payment provider confirmed.
   * Granted to a product's outbound service client as the {@code entitlement:subscription} role of
   * the {@code billing} Keycloak client.
   */
  public static final String ENTITLEMENT_SUBSCRIPTION_AUTHORITY =
      "billing:entitlement:subscription";

  private BillingPermissions() {}
}
