# C4 system context

The [C4 model](https://c4model.com) system context: Axion as one box, the people who use it and the systems it talks to. The containers inside the box are described in section 3 of the [platform overview](../design-docs/2026-11-17-platform-overview.md#3-architecture).

```mermaid
flowchart TB
    staff["<b>Tenant staff and developers</b><br/>[Person]<br/>Run billing and integrate with the API"]
    owners["<b>Tenant owners and sellers</b><br/>[Person]<br/>Charge at the counter, approve payouts"]
    operators["<b>Platform operators</b><br/>[Person]<br/>Handle risk cases and exceptions"]
    payer["<b>Payer</b><br/>[Person]<br/>Pays Pix charges from a bank app"]

    axion["<b>Axion</b><br/>[Software system]<br/>Charges, ledger, settlement, payouts and webhooks for tenants"]

    tenant["<b>Tenant's systems</b><br/>[External system]<br/>The tenant's own applications"]
    rails["<b>Payment rails</b><br/>[External system, simulated]<br/>SPI, DICT and banks"]

    staff -- "Uses the web console" --> axion
    owners -- "Uses the mobile app" --> axion
    operators -- "Uses the back office" --> axion
    tenant -- "Calls the /v1 API with an API key" --> axion
    axion -- "Sends signed webhooks" --> tenant
    axion -- "Sends payment orders" --> rails
    rails -- "Sends signed callbacks" --> axion
    payer -- "Pays through their bank" --> rails

    classDef person fill:#08427b,stroke:#052e56,color:#fff
    classDef system fill:#1168bd,stroke:#0b4884,color:#fff
    classDef external fill:#6b6b6b,stroke:#4d4d4d,color:#fff
    class staff,owners,operators,payer person
    class axion system
    class tenant,rails external
```

| Element | Role |
|---|---|
| Tenant staff and developers | Use the web console: dashboard, charges, ledger, API keys, webhook endpoints |
| Tenant owners and sellers | Use the mobile app: "Charge now" at the counter, biometric payout approval (phases 1b and 2) |
| Platform operators | Use the back office in the same web app: risk cases, dead letters, kill switches |
| Payer | Pays a Pix charge; never talks to Axion directly, only through the rails |
| Tenant's systems | Create and read charges through the public `/v1` API and receive webhooks |
| Payment rails | The Central Bank's Pix infrastructure (SPI, DICT) and other banks. Axion connects to none of them: `services/rails-simulator` plays their part over a real network boundary, so timeouts, duplicates and outages are real |

The identity provider (Keycloak) and the database are inside the Axion boundary: they are containers of the system, not systems it integrates with.

The diagram is Mermaid text, rendered by GitHub, so it changes in pull requests like code. It changes when a person or an external system is added or removed.
