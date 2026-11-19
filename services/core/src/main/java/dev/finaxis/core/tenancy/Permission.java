package dev.finaxis.core.tenancy;

/**
 * The right to perform one kind of operation (tenancy design, section 3.3). Code checks permissions,
 * never roles: an operation declares the permission it needs, and a caller has it through a member's
 * roles or an API key's scopes.
 *
 * <p>Some permissions are for people only and are never granted to an API key, so a leaked key
 * cannot mint other keys, add members or approve payouts.
 */
public enum Permission {
    CHARGES_READ("charges:read", true),
    CHARGES_WRITE("charges:write", true),
    REFUNDS_WRITE("refunds:write", true),
    BALANCES_READ("balances:read", true),
    WEBHOOKS_MANAGE("webhooks:manage", true),
    API_KEYS_MANAGE("api_keys:manage", false),
    MEMBERS_MANAGE("members:manage", false),
    /** Approving payouts, the second step of maker-checker; used from phase 2. */
    PAYOUTS_APPROVE("payouts:approve", false);

    private final String code;
    private final boolean apiKeyScope;

    Permission(String code, boolean apiKeyScope) {
        this.code = code;
        this.apiKeyScope = apiKeyScope;
    }

    /** The permission as the API and the database spell it, such as {@code charges:write}. */
    public String code() {
        return code;
    }

    /** Whether an API key may carry this permission as a scope; false for human-only permissions. */
    public boolean isApiKeyScope() {
        return apiKeyScope;
    }
}
