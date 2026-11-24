package dev.axion.core.webhooks.domain;

/**
 * One request of a delivery and what came of it (events and webhooks design, section 8.3): a
 * response, with its status code, or a failure that left none to judge.
 */
public sealed interface Attempt {

    /** Whether the receiver took the event: a {@code 2xx} within the timeout. */
    boolean succeeded();

    /** The receiver answered, with any status but a redirect. */
    record Answered(int statusCode) implements Attempt {

        public Answered {
            if (statusCode < 100 || statusCode > 599 || (statusCode >= 300 && statusCode < 400)) {
                throw new IllegalArgumentException("Not a final status other than a redirect: " + statusCode);
            }
        }

        @Override
        public boolean succeeded() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /** No answer to judge, for the reason it names. */
    record Failed(AttemptFailure failure) implements Attempt {

        @Override
        public boolean succeeded() {
            return false;
        }
    }
}
