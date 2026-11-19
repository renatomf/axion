package dev.finaxis.core.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an operation that needs proof that the member authenticated recently, not just a valid
 * session (tenancy design, section 4.2): issuing, rolling and revoking API keys, changing webhook
 * endpoints and managing members. It goes beside {@link RequiresPermission}:
 *
 * <pre>{@code
 * @RequiresPermission(Permission.API_KEYS_MANAGE)
 * @RequiresStepUp
 * @PostMapping("/internal/tenants/{tenant_id}/api_keys")
 * }</pre>
 *
 * <p>After the permission check, a member whose last authentication is more than five minutes old
 * gets {@code 403 step_up_required} with an RFC 9470 challenge, which the console answers by
 * sending the member back to Keycloak to authenticate again.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresStepUp {}
