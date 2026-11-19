package dev.finaxis.core.access.web;

import java.util.List;

/**
 * An {@code ApiKeyList} of {@code openapi/internal.yaml}. The keys sit under {@code data} rather
 * than being the whole body, so the list can later gain properties, such as a pagination cursor,
 * without breaking clients.
 *
 * @param data the tenant's keys, newest first
 */
record ApiKeyListResponse(List<ApiKeyResponse> data) {}
