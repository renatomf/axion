package dev.axion.core.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    private static final Instant ENDED = Instant.parse("2026-11-24T10:00:00Z");

    private static final Attempt FAILED = new Attempt.Answered(503);

    @ParameterizedTest
    @CsvSource({"1, PT1M", "2, PT5M", "3, PT30M", "4, PT2H", "5, PT6H", "6, PT12H", "7, PT24H", "8, PT24H"})
    void retriesAFailedAttemptAfterTheDelayOfItsNumber(int number, Duration delay) {
        assertThat(policy(0.5).after(number, FAILED, ENDED)).isEqualTo(new RetryPolicy.Next.RetryAt(ENDED.plus(delay)));
    }

    @Test
    void spansAboutThreeDaysOverItsNineAttempts() {
        RetryPolicy policy = policy(0.5);
        Instant at = ENDED;
        for (int number = 1; number < RetryPolicy.ATTEMPTS; number++) {
            at = ((RetryPolicy.Next.RetryAt) policy.after(number, FAILED, at)).at();
        }

        assertThat(Duration.between(ENDED, at)).isEqualTo(Duration.ofMinutes(4116));
        assertThat(policy.after(RetryPolicy.ATTEMPTS, FAILED, at)).isEqualTo(new RetryPolicy.Next.Dead());
    }

    @Test
    void variesEachDelayByUpToATenth() {
        assertThat(policy(0.0).after(4, FAILED, ENDED))
                .isEqualTo(new RetryPolicy.Next.RetryAt(ENDED.plus(Duration.ofMinutes(108))));
        assertThat(policy(1.0).after(4, FAILED, ENDED))
                .isEqualTo(new RetryPolicy.Next.RetryAt(ENDED.plus(Duration.ofMinutes(132))));
    }

    @Test
    void keepsEveryJitteredDelayWithinItsTenth() {
        var policy = new RetryPolicy(new SplittableRandom(42));
        for (int i = 0; i < 1_000; i++) {
            Instant at = ((RetryPolicy.Next.RetryAt) policy.after(1, FAILED, ENDED)).at();
            assertThat(at).isBetween(ENDED.plusSeconds(54), ENDED.plusSeconds(66));
        }
    }

    @Test
    void endsWithTheSuccessOfAnyAttempt() {
        assertThat(policy(0.5).after(1, new Attempt.Answered(204), ENDED)).isEqualTo(new RetryPolicy.Next.Succeeded());
        assertThat(policy(0.5).after(RetryPolicy.ATTEMPTS, new Attempt.Answered(200), ENDED))
                .isEqualTo(new RetryPolicy.Next.Succeeded());
    }

    @Test
    void endsAtOnceWhenTheReceiverAnswersGone() {
        assertThat(policy(0.5).after(1, new Attempt.Answered(410), ENDED)).isEqualTo(new RetryPolicy.Next.Dead());
    }

    @Test
    void retriesAnAttemptThatGotNoAnswer() {
        assertThat(policy(0.5).after(2, new Attempt.Failed(AttemptFailure.TIMEOUT), ENDED))
                .isEqualTo(new RetryPolicy.Next.RetryAt(ENDED.plus(Duration.ofMinutes(5))));
    }

    @Test
    void refusesANumberOutsideTheAttempts() {
        assertThatThrownBy(() -> policy(0.5).after(0, FAILED, ENDED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(0.5).after(RetryPolicy.ATTEMPTS + 1, FAILED, ENDED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A policy whose jitter draws the value given: 0.5 adds none, 0 and 1 the most either way. */
    static RetryPolicy policy(double draw) {
        return new RetryPolicy(new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException("The policy draws doubles only");
            }

            @Override
            public double nextDouble() {
                return draw;
            }
        });
    }
}
