# Idempotency

A request over the network can fail without saying whether it worked: the connection may drop after Axion created a charge and before the response reached you. The safe reaction is to send the request again, and the `Idempotency-Key` header makes that safe: every retry of one operation has the effect of a single request.

This guide is for developers of systems that call the public `/v1` API. The contract, [`v1.yaml`](../../services/core/openapi/v1.yaml), is the reference; the design behind it is in the [idempotency design](../design-docs/2026-11-21-idempotency.md).

## Sending a key

Every `POST` under `/v1` requires the header. `GET` requests ignore it.

```http
POST /v1/charges HTTP/1.1
Authorization: Bearer sk_test_…
Idempotency-Key: <a new UUID for this operation>
Content-Type: application/json

{"amount": 15000, …}
```

- **One key per operation.** Generate a new key, such as a random UUID, when your system decides to make the request, and store it with the operation before sending it, so a retry after a crash on your side uses the same key.
- **The same key on every retry.** A retry sends the same key, the same path and the same body.
- **The format.** 1 to 255 visible ASCII characters (`!` to `~`). Keys are compared exactly, as opaque text.
- **The scope.** A key belongs to your tenant: any of your API keys with the operation's scope can retry with it, so rolling your API key during retries is safe. Another tenant's keys never meet yours, and test and live are separate tenants.

## What a retry gets

| Situation | Response |
|---|---|
| First request with the key | The operation's response |
| A retry after the first request completed | The first response again, byte for byte, with `Idempotent-Replayed: true`; the operation does not run again |
| A retry while the first request is still running | `409` with code `request_in_progress` and `Retry-After: 1`; retry after it |
| The key with another path or body | `422` with code `idempotency_key_reused` |
| No `Idempotency-Key` | `400` with code `idempotency_key_required` |
| A key that is empty, over 255 characters or outside visible ASCII, or two `Idempotency-Key` headers | `400` with code `invalid_idempotency_key` |
| A body over 64 KiB | `413` with code `content_too_large` |

A replay repeats the first response's status, `Content-Type`, `Location` and body. The headers about the request you just sent are its own: `X-Correlation-Id`, `RateLimit-Policy` and `RateLimit`.

A replay is answered only to a caller who could make the request now: it is authenticated, rate-limited and checked for the scope first. A revoked API key gets `401`, and a key without the scope `403`, not the stored response.

## Which responses are kept

- **Kept:** every response of a request that ran, success or refusal. A charge refused with `422` gets the same `422` on every retry, even if it would now pass: send a new request, with a new key, to try again.
- **Not kept:** a response of a request that left no effect. That is a rejection before the operation ran (`400`, `401`, `403`, `413`, `429`, an invalid body with `422 validation_failed`) and any `5xx`, which rolled back everything the request did. Retrying such a request with the same key runs it again, which is safe because nothing happened.

## The body is compared byte for byte

A key is bound to the request's path and the exact bytes of its body. A retry that serializes the same data differently, with other whitespace or another key order, is another request and gets `422 idempotency_key_reused`. Keep the bytes you sent the first time, or serialize deterministically, and send them again.

## How long a key lasts

A key is honored for at least 24 hours after its request ran. After that it may be forgotten, and a request with it runs as new. Do not retry an operation for longer than that; a later attempt is a new operation with a new key.

## Retrying well

- Retry on network errors, timeouts, `409 request_in_progress`, `429 rate_limited` and `5xx`, with the same key.
- Wait as `Retry-After` says when it is present, and back off exponentially, with jitter, otherwise.
- Do not retry `400`, `401`, `403`, `413` or `422` unchanged: they will be answered the same way. Fix the request and send it with a new key.
