package dev.axion.core.tenancy;

/**
 * The right to perform one kind of operation (tenancy design, section 3.3). Code checks permissions,
 * never roles. Human-only permissions are never API key scopes, so a leaked key cannot mint keys,
 * add members or approve payouts.
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

    /** As the API and the database spell it, such as {@code charges:write}. */
    public String code() {
        return code;
    }

    public boolean isApiKeyScope() {
        return apiKeyScope;
    }

    /** The inverse of {@link #code}. */
    public static Permission fromCode(String code) {
        for (Permission permission : values()) {
            if (permission.code.equals(code)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("Unknown permission: " + code);
    }
}
