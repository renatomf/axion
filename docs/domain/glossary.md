# Glossary

Every domain term and its meaning. Code uses these names (ubiquitous language); a new term is added here in the pull request that introduces it.

| Term | Meaning | In code |
|---|---|---|
| **Minor unit** | The smallest unit of a currency: one centavo is 1/100 of a real. All amounts are stored and computed as whole minor units | `Money.minorUnits()` |
| **Money** | An amount in minor units together with its currency. It can be negative (a balance or a difference); a rule that needs a positive amount checks it. Amounts in different currencies never combine | [`Money`](../../services/core/src/main/java/dev/finaxis/core/shared/Money.java) |
| **Rate** | A multiplier applied to money, such as a fee percentage, held as an exact decimal (`BigDecimal`). Applying a rate is the only place money is rounded, with half-even (banker's) rounding | `Money.times(rate)` |
| **Allocation** | Splitting money into parts by ratios, such as a 70/27/3 split, so that the parts always add up to the original amount. Centavos that do not divide evenly go one each to the parts with the largest remainder | `Money.allocate(ratios)` |
| **Identifier** | A resource's primary key: a version 7 UUID, which starts with its creation time in milliseconds, so keys sort by creation and stay close together in database indexes | [`UuidV7Generator`](../../services/core/src/main/java/dev/finaxis/core/shared/UuidV7Generator.java) |
| **Public ID** | The identifier as the API shows it: a prefix naming the resource type and the UUID in 26 lowercase Crockford base32 characters ([TypeID](https://github.com/jetify-com/typeid) format), such as `ch_01h455vb4pex5vsknk084sn02q`. It is derived from the UUID, never stored separately, and parsed strictly, so each resource has one spelling | [`PublicId`](../../services/core/src/main/java/dev/finaxis/core/shared/PublicId.java) |
| **Business date** | The calendar date of an instant in `America/Sao_Paulo`, where the money settles. Instants are stored in UTC; a payment at 22:30 in São Paulo belongs to that day even though it is already the next day in UTC | [`BusinessCalendar.businessDateOf`](../../services/core/src/main/java/dev/finaxis/core/shared/BusinessCalendar.java) |
| **Bank holiday** | A national holiday on which banks do not settle, as in the ANBIMA calendar: fixed dates plus Carnival Monday and Tuesday, Good Friday and Corpus Christi, which move with Easter. State and municipal holidays are not included | [`BankHolidays`](../../services/core/src/main/java/dev/finaxis/core/shared/BankHolidays.java) |
| **Business day** | A weekday that is not a bank holiday. Pix runs every day; business days govern due dates and payout schedules, and a due date that falls on a non-business day moves to the next business day | `BusinessCalendar.isBusinessDay(date)` |
