# Security policy

Axion is a demonstration project: all money, payment rails and data are simulated, and no real customer data exists. Security is still treated as it would be in a production financial system, so reports are welcome.

## Supported versions

Only the latest commit on `main` receives security fixes. There are no released versions yet.

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a vulnerability.**

Report it privately through GitHub: **Security** tab → **Report a vulnerability** ([private vulnerability reporting](https://github.com/renatomf/axion/security/advisories/new)).

Please include:

- The affected component, file or endpoint, and the commit you tested.
- Steps to reproduce, or a proof of concept.
- The impact you expect (for example: cross-tenant access, ledger imbalance, secret exposure).

## What to expect

This is a personal project maintained on a best-effort basis:

| Step | Target |
|---|---|
| Acknowledgement | Within 7 days |
| Initial assessment | Within 14 days |
| Fix or mitigation | Depends on severity; critical and high first |

Once a fix is merged, the advisory is published with credit to the reporter, unless they prefer to stay anonymous.

## Scope

In scope: the code and configuration in this repository.

Out of scope:

- Third-party services and dependencies themselves (report those upstream; a vulnerable *use* of a dependency here is in scope).
- Findings that require a compromised developer machine.
- Denial of service through volume, and automated scanner output without a demonstrated impact.

## Testing guidelines

Test only against your own local environment (Docker Compose). Use synthetic data only, and never test against infrastructure you do not own.
