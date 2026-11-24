package dev.axion.core.webhooks.persistence;

import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.EndpointView;

/** An endpoint the delivery workers disabled, with the tenant and environment it belongs to. */
public record DisabledEndpoint(TenantId tenant, Environment environment, EndpointView endpoint) {}
