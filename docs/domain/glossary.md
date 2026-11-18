# Glossary

Every domain term and its meaning. Code uses these names (ubiquitous language); a new term is added here in the pull request that introduces it.

| Term | Meaning | In code |
|---|---|---|
| **Minor unit** | The smallest unit of a currency: one centavo is 1/100 of a real. All amounts are stored and computed as whole minor units | `Money.minorUnits()` |
| **Money** | An amount in minor units together with its currency. It can be negative (a balance or a difference); a rule that needs a positive amount checks it. Amounts in different currencies never combine | [`Money`](../../services/core/src/main/java/dev/finaxis/core/shared/Money.java) |
| **Rate** | A multiplier applied to money, such as a fee percentage, held as an exact decimal (`BigDecimal`). Applying a rate is the only place money is rounded, with half-even (banker's) rounding | `Money.times(rate)` |
