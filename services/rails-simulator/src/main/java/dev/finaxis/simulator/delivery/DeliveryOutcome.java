package dev.finaxis.simulator.delivery;

/** What one attempt came to, and the state it leaves the delivery in (rails simulator design, section 5.3). */
enum DeliveryOutcome {
    DELIVERED("delivered", DeliveryStatus.DELIVERED),
    RETRIED("retried", DeliveryStatus.PENDING),
    REFUSED("refused", DeliveryStatus.REFUSED),
    FAILED("failed", DeliveryStatus.FAILED);

    private final String tag;
    private final DeliveryStatus status;

    DeliveryOutcome(String tag, DeliveryStatus status) {
        this.tag = tag;
        this.status = status;
    }

    /** Accepted on {@code 2xx}; tried again on no answer, {@code 408}, {@code 429} or {@code 5xx}; refused otherwise. */
    static DeliveryOutcome of(CoreClient.Response response) {
        Integer status = response.status();
        if (status == null || status == 408 || status == 429 || status >= 500) {
            return RETRIED;
        }
        return status >= 200 && status < 300 ? DELIVERED : REFUSED;
    }

    String tag() {
        return tag;
    }

    DeliveryStatus status() {
        return status;
    }
}
