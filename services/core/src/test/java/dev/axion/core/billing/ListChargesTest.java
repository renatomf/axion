package dev.axion.core.billing;

import static dev.axion.core.billing.BillingFixtures.brl;
import static dev.axion.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link BillingApi#charges} against a real PostgreSQL. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ListChargesTest {

    private final BillingApi billing;
    private final TenancyApi tenancy;
    private final JdbcClient owner;

    private NewOrganization organization;

    @Autowired
    ListChargesTest(BillingApi billing, TenancyApi tenancy, DatabaseOwner owner) {
        this.billing = billing;
        this.tenancy = tenancy;
        this.owner = owner.jdbc();
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Padaria São João", "subject-" + UUID.randomUUID());
    }

    @Test
    void listsTheChargesNewestFirst() {
        List<ChargeView> created = create(3);

        ChargePage page = list(ChargeFilter.all(), ChargeCursor.first(), 10);

        assertThat(page.charges()).containsExactlyElementsOf(created.reversed());
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void pagesThroughEveryChargeOnceWithTheCursor() {
        List<ChargeView> created = create(5);

        List<List<ChargeView>> pages = new ArrayList<>();
        ChargePage page = list(ChargeFilter.all(), ChargeCursor.first(), 2);
        pages.add(page.charges());
        while (page.next().isPresent()) {
            page = list(ChargeFilter.all(), page.next().get(), 2);
            pages.add(page.charges());
        }

        List<ChargeView> newest = created.reversed();
        assertThat(pages).containsExactly(newest.subList(0, 2), newest.subList(2, 4), newest.subList(4, 5));
    }

    @Test
    void endsWithAFullPageWhenTheChargesFillIt() {
        create(4);

        ChargePage first = list(ChargeFilter.all(), ChargeCursor.first(), 2);
        ChargePage second = list(ChargeFilter.all(), first.next().orElseThrow(), 2);

        assertThat(second.charges()).hasSize(2);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void listsOnlyTheChargesOfAStatus() {
        List<ChargeView> created = create(3);
        expire(created.get(0));
        expire(created.get(2));

        ChargePage expired = list(
                new ChargeFilter(Optional.of(ChargeStatus.EXPIRED), Optional.empty(), Optional.empty()),
                ChargeCursor.first(),
                1);
        ChargePage rest = list(
                new ChargeFilter(Optional.of(ChargeStatus.EXPIRED), Optional.empty(), Optional.empty()),
                expired.next().orElseThrow(),
                1);

        assertThat(expired.charges())
                .extracting(ChargeView::id)
                .containsExactly(created.get(2).id());
        assertThat(rest.charges())
                .extracting(ChargeView::id)
                .containsExactly(created.get(0).id());
        assertThat(rest.hasMore()).isFalse();
    }

    @Test
    void listsTheChargesCreatedInTheRangeFromItsStartToBeforeItsEnd() {
        List<ChargeView> created = create(4);
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Instant end = Instant.parse("2026-11-02T00:00:00Z");
        createdAt(created.get(0), start.minusNanos(1_000));
        createdAt(created.get(1), start);
        createdAt(created.get(2), end.minusNanos(1_000));
        createdAt(created.get(3), end);

        ChargePage page = list(
                new ChargeFilter(Optional.empty(), Optional.of(start), Optional.of(end)), ChargeCursor.first(), 10);

        assertThat(page.charges())
                .extracting(ChargeView::id)
                .containsExactly(created.get(2).id(), created.get(1).id());
    }

    @Test
    void listsNoChargeOfAnotherTenant() {
        create(2);
        var other = tenancy.createOrganization("Other", "subject-" + UUID.randomUUID());

        assertThat(list(other.test(), Environment.TEST).charges()).isEmpty();
        // The organization's other environment is another tenant too.
        assertThat(list(organization.live(), Environment.LIVE).charges()).isEmpty();
    }

    @Test
    void refusesAPageSizeOutOfRange() {
        for (int limit : new int[] {0, BillingApi.MAX_PAGE_SIZE + 1}) {
            assertThatThrownBy(() -> list(ChargeFilter.all(), ChargeCursor.first(), limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesToListChargesWithNoTenantBound() {
        assertThatThrownBy(() -> billing.charges(ChargeFilter.all(), ChargeCursor.first(), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a tenant bound");
    }

    private List<ChargeView> create(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> TestCaller.call(
                        organization.test(),
                        Environment.TEST,
                        () -> created(billing.createCharge(ChargeRequest.of(brl(100 + i))))))
                .toList();
    }

    private ChargePage list(ChargeFilter filter, ChargeCursor cursor, int limit) {
        return TestCaller.call(organization.test(), Environment.TEST, () -> billing.charges(filter, cursor, limit));
    }

    private ChargePage list(TenantId tenant, Environment environment) {
        return TestCaller.call(
                tenant, environment, () -> billing.charges(ChargeFilter.all(), ChargeCursor.first(), 10));
    }

    // No transition expires a charge yet; the expiry job arrives with PR 1.40.
    private void expire(ChargeView charge) {
        owner.sql("UPDATE billing.charges SET status = 'EXPIRED' WHERE id = ?")
                .param(charge.id().value())
                .update();
    }

    private void createdAt(ChargeView charge, Instant createdAt) {
        owner.sql("UPDATE billing.charges SET created_at = ? WHERE id = ?")
                .param(createdAt.atOffset(ZoneOffset.UTC))
                .param(charge.id().value())
                .update();
    }
}
