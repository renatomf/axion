# Webhooks

Axion tells your system what happened to its charges by sending an HTTPS request to a URL you registered: a webhook. Each request is signed, so your server can tell Axion's requests from forged ones, and carries the resource as it was at the event's time, so you can act without calling the API.

This guide is for developers of systems that receive Axion's events. The contract, [`v1.yaml`](../../services/core/openapi/v1.yaml), is the reference for the endpoints API and for each event's schema; the design behind it is in the [events and webhooks design](../design-docs/2026-11-23-events-and-webhooks.md).

## Registering an endpoint

```http
POST /v1/webhook_endpoints HTTP/1.1
Authorization: Bearer sk_test_…
Idempotency-Key: <a new UUID for this operation>
Content-Type: application/json

{"url": "https://example.com/axion/webhooks", "enabled_events": ["charge.paid", "charge.refunded"]}
```

The response carries the endpoint's `secret`, `whsec_` followed by 32 bytes in base64. It is shown **once**: store it where your server reads its other secrets. A repeat of the request with the same `Idempotency-Key` returns the endpoint with `secret` set to `null`; if you lost the secret, roll it (see [Rolling the secret](#rolling-the-secret)).

- The URL is `https`, with a domain name that resolves only to public addresses, on port 443 or between 1024 and 65535. Axion checks the addresses again every time it connects, and never follows a redirect.
- `enabled_events` lists the types you want, or `["*"]` for all of them. A test API key manages test endpoints, which receive test events only.
- An endpoint receives the events that happen after it is created; a disabled one receives none.

| Type | When |
|---|---|
| `charge.created` | A charge was created |
| `charge.paid` | The payment that settles a charge arrived |
| `charge.expired` | A charge reached its expiry unpaid |
| `payment.flagged` | A payment arrived that does not settle its charge, with the reason |
| `charge.refunded` | A refund of a charge was sent |

## What a request looks like

```http
POST /axion/webhooks HTTP/1.1
Host: example.com
Content-Type: application/cloudevents+json; charset=utf-8
User-Agent: Axion-Webhooks/1.0
webhook-id: evt_01jdr3b6f7g8h9j0k1m2n3p4q5
webhook-timestamp: 1795442591
webhook-signature: v1,K5oZfzN95Z9UVu1EsfQmfVNQhnkZ2pj9o9NDN/H/pI4=

{"specversion":"1.0","id":"evt_01jdr3b6f7g8h9j0k1m2n3p4q5","source":"urn:axion:test","type":"charge.paid",…}
```

The body is a [CloudEvent 1.0](https://github.com/cloudevents/spec) in JSON:

| Attribute | Value |
|---|---|
| `id` | The event's ID, the same as `webhook-id`, on every delivery of the event |
| `source` | `urn:axion:test` or `urn:axion:live` |
| `type` | One of the types above |
| `subject` | The ID of the resource the event is about |
| `time` | When it happened, in UTC |
| `dataschema` | The schema of `data` and its version, in `v1.yaml` |
| `data.object` | The resource as `GET` returns it at the event's time |

Ignore fields you do not know: new optional fields are added without a new version.

## Verifying a request

The headers follow [Standard Webhooks](https://www.standardwebhooks.com). `webhook-signature` is HMAC-SHA256, keyed with the 32 bytes of your secret (the base64 after `whsec_`), over the text `{webhook-id}.{webhook-timestamp}.{body}`, in base64 after the version `v1,`. Verify every request before you trust anything in it:

1. **Read the raw body.** Verify the bytes you received, before parsing them: JSON parsed and serialized again is not the same bytes, and its signature will not match.
2. **Check the timestamp.** Refuse a `webhook-timestamp` more than five minutes from your clock. That bounds how long a captured request can be replayed.
3. **Check the signature.** `webhook-signature` holds one or more signatures separated by spaces. Compute yours, and accept the request if any `v1` entry equals it, comparing in constant time.
4. **Deduplicate by `webhook-id`.** A request repeated inside the five minutes carries the same ID; so does a delivery sent again after its response was lost. Keep the IDs you processed for longer than the timestamp tolerance, and skip a repeat while still answering `2xx`.

Answer `400` or `401` to a request that fails, and do not act on it.

The [Standard Webhooks libraries](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries) do steps 2 and 3 for many languages. Without one, the check is short.

**Node.js**, with Express reading the body as raw bytes:

```js
import { createHmac, timingSafeEqual } from "node:crypto";

const TOLERANCE_SECONDS = 5 * 60;

export function verifyWebhook(secret, headers, rawBody, nowSeconds = Date.now() / 1000) {
  const id = headers["webhook-id"];
  const timestamp = headers["webhook-timestamp"];
  const signatures = headers["webhook-signature"];
  if (!id || !timestamp || !signatures) return false;

  const seconds = Number(timestamp);
  if (!Number.isInteger(seconds) || Math.abs(nowSeconds - seconds) > TOLERANCE_SECONDS) return false;

  const key = Buffer.from(secret.slice("whsec_".length), "base64");
  const expected = createHmac("sha256", key).update(`${id}.${timestamp}.`).update(rawBody).digest();

  return signatures.split(" ").some((entry) => {
    if (!entry.startsWith("v1,")) return false;
    const received = Buffer.from(entry.slice("v1,".length), "base64");
    return received.length === expected.length && timingSafeEqual(received, expected);
  });
}

app.post("/axion/webhooks", express.raw({ type: "application/cloudevents+json" }), (req, res) => {
  if (!verifyWebhook(process.env.AXION_WEBHOOK_SECRET, req.headers, req.body)) {
    return res.sendStatus(401);
  }
  // Record the event and answer at once; process it afterwards.
  res.sendStatus(204);
});
```

**Java**, with the JDK alone:

```java
public final class WebhookVerifier {

    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final byte[] key;

    public WebhookVerifier(String secret) {
        this.key = Base64.getDecoder().decode(secret.substring("whsec_".length()));
    }

    public boolean verify(String id, String timestamp, String signatures, byte[] rawBody, Instant now)
            throws GeneralSecurityException {
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - seconds) > TOLERANCE.toSeconds()) {
            return false;
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
        byte[] expected = mac.doFinal(rawBody);
        for (String entry : signatures.split(" ")) {
            if (!entry.startsWith("v1,")) {
                continue;
            }
            try {
                byte[] received = Base64.getDecoder().decode(entry.substring("v1,".length()));
                if (MessageDigest.isEqual(expected, received)) {
                    return true;
                }
            } catch (IllegalArgumentException notBase64) {
                // Another entry may still verify.
            }
        }
        return false;
    }
}
```

To test your check, the specification's vector must verify: secret `whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw`, `webhook-id` `msg_p5jXN8AQM9LWM0D4loKWxJek`, `webhook-timestamp` `1614265330`, body `{"test": 2432232314}`, signature `v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=` (with your clock at that timestamp).

## Answering

- **A `2xx` within 10 seconds** is a success; anything else is a failure: another status, a timeout, a redirect, a refused connection or a TLS error. The body of your response decides nothing, but its first 1 KiB is kept in the [delivery log](#the-delivery-log) for 30 days: an error message helps you there, a secret or personal data does not belong in it.
- **Answer first, process later.** Record the event and answer; do the work in the background, so a slow job does not turn into a timeout.
- **`410 Gone` stops the delivery.** Answer `410` when the URL is not coming back: Axion stops attempting that delivery at once. To stop receiving altogether, disable or delete the endpoint.

## Retries

A delivery that fails is attempted again, later each time:

| Attempt | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|
| After the previous | immediately | 1 min | 5 min | 30 min | 2 h | 6 h | 12 h | 24 h | 24 h |

Each delay varies by up to 10%, so the deliveries held back by an outage of your server do not all return in the same second. The nine attempts span about 68 hours; after the ninth failure the delivery is dead and is not attempted again.

Every attempt carries the same `webhook-id` and body, and a fresh `webhook-timestamp` and signature. A request your server took but answered too late is sent again: deduplicate by `webhook-id` (see [Duplicates and order](#duplicates-and-order)).

## When an endpoint keeps failing

An endpoint whose attempts have been failing for **three days**, with its last **20 attempts** all failed, is disabled: its `status` becomes `disabled`, its `disabled_reason` `failing`, its pending deliveries are canceled and it receives no new events. Both conditions must hold, so an endpoint that receives one event a week is not disabled by a single bad night, nor one that receives thousands by a few bad minutes. One success clears the count.

Once your server is fixed, enable the endpoint again:

```http
PATCH /v1/webhook_endpoints/{id} HTTP/1.1
Authorization: Bearer sk_test_…
Content-Type: application/merge-patch+json

{"status": "enabled"}
```

It starts afresh: it receives the events that happen from then on, and the deliveries canceled while it was disabled are not sent. The [delivery log](#the-delivery-log) shows which ones were canceled: [replay](#replaying-a-delivery) those you still need, or read the current state of what changed meanwhile from the API.

## The delivery log

Every event sent to an endpoint is a **delivery**, and every request sent for it an **attempt**. The log lists an endpoint's deliveries, newest first, and filters them by `status` (`pending`, `succeeded`, `dead` or `canceled`) and event `type`:

```http
GET /v1/webhook_endpoints/{id}/deliveries?status=dead&type=charge.paid HTTP/1.1
Authorization: Bearer sk_test_…
```

Retrieving a delivery returns it with every attempt, oldest first:

```json
{
  "id": "whd_01jdr3b7a8b9c0d1e2f3g4h5j6",
  "object": "webhook_delivery",
  "endpoint": "we_01jdr2m4n5p6q7r8s9t0v1w2x3",
  "event": "evt_01jdr3b6f7g8h9j0k1m2n3p4q5",
  "event_type": "charge.paid",
  "status": "succeeded",
  "attempt_count": 2,
  "next_attempt_at": null,
  "created_at": "2026-11-23T14:03:11.482Z",
  "attempts": [
    {
      "id": "wha_01jdr3b7c8d9e0f1g2h3j4k5m6",
      "object": "webhook_attempt",
      "replay": false,
      "started_at": "2026-11-23T14:03:11Z",
      "duration_ms": 182,
      "status_code": 503,
      "failure": null,
      "response_excerpt": "{\"error\":\"down for maintenance\"}"
    },
    {
      "id": "wha_01jdr3c2d3e4f5g6h7j8k9m0n1",
      "object": "webhook_attempt",
      "replay": false,
      "started_at": "2026-11-23T14:04:11Z",
      "duration_ms": 87,
      "status_code": 200,
      "failure": null,
      "response_excerpt": "ok"
    }
  ]
}
```

- `event` is the `webhook-id` of every request of the delivery: match it with what your server logged.
- An attempt has either the `status_code` your server answered or the `failure` that left none: `timeout`, `connection_refused`, `connection_failed`, `unresolved`, `address_refused`, `tls_error` or `redirect`.
- `response_excerpt` is at most the first 1,024 bytes of your answer, as text. It is shown as your server sent it, never interpreted.
- `replay` is `true` for a request you asked for with a [replay](#replaying-a-delivery); `attempt_count` counts only the others, the delivery's scheduled attempts.
- A delivery is kept for 30 days after it was made, last attempted and last replayed, with its attempts; the log of a deleted endpoint stays readable for as long.

## Replaying a delivery

A replay sends one delivery again, now:

```http
POST /v1/webhook_endpoints/{id}/deliveries/{delivery_id}/replay HTTP/1.1
Authorization: Bearer sk_test_…
Idempotency-Key: 6f1c2a9e-replay-whd_01jdr3b7a8b9c0d1e2f3g4h5j6
```

The answer is `202 Accepted` with the delivery as it is, and the request follows within seconds, in the delivery's `attempts` with `replay` set to `true`.

- **The same event.** The request carries the delivery's `webhook-id` and body, byte for byte, with a fresh `webhook-timestamp` and signature: verify it as any other, and deduplicate it by `webhook-id` if you processed the event already.
- **Any delivery.** A delivery can be replayed whatever its `status`: a `dead` one after you fixed your server, a `canceled` one your endpoint missed while disabled, a `succeeded` one whose processing failed on your side, or a `pending` one you do not want to wait for. A replay changes neither the delivery's `status` nor its schedule, and does not count toward [disabling](#when-an-endpoint-keeps-failing) the endpoint.
- **An enabled endpoint.** A disabled endpoint's deliveries answer `409 endpoint_disabled`: enable it first. Disabling or deleting an endpoint cancels the replays not yet sent.
- **At most 10 times in 24 hours** for each delivery. Past that, the answer is `409 replay_limit_reached`, whose `detail` says from when the delivery can be replayed again; send that retry with a new `Idempotency-Key`, since a repeat of a key gets its first answer.

## Duplicates and order

Events are delivered **at least once** and in **no guaranteed order**. A delivery may arrive twice, and a `charge.paid` may arrive before the `charge.created` of the same charge.

- Deduplicate by `webhook-id`, as in step 4 above.
- Compare `time`, or the resource's status, before overwriting newer data with an older event.
- Each event carries the resource as it was then. When you need its current state, read it by `subject`.

## Rolling the secret

`POST /v1/webhook_endpoints/{id}/secret/roll` issues a new secret and keeps the old one signing for a grace period, 24 hours unless you ask for another, up to 7 days. During it, every request carries two signatures, the new secret's first:

```
webhook-signature: v1,<signed with the new secret> v1,<signed with the old secret>
```

A receiver that accepts any one signature keeps working throughout: deploy the new secret within the grace period, and the old one stops signing when it ends. A secret can be rolled again once the grace period of the previous roll has ended.

## Receiving on your machine

A deployed Axion never sends a webhook to a private or loopback address. When you run the core on your machine (the `local` profile, see [`CONTRIBUTING.md`](../../CONTRIBUTING.md)), you can let it reach a server of yours on `localhost`:

1. Choose the port your server listens on, 1024 or above, and list it in `infra/compose/.env`:

   ```
   AXION_WEBHOOK_LOCAL_PORTS=4000
   ```

   Several ports are separated by commas. The core reads the setting at start, in the `local` profile only.

2. Register `http://localhost:4000/axion/webhooks`, or `https://` if your server has a certificate the core's JVM trusts. Only `localhost` is accepted, not `127.0.0.1`, and only on the ports listed.

3. Start your server and create a test charge: the `charge.created` event reaches it within a second or two, signed as in any environment.

Requests to a local port are verified the same way, so the code you test on your machine is the code you deploy.
