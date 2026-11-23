package dev.axion.simulator.delivery;

/** Why a delivery exists (rails simulator design, section 8.3). */
enum Kind {
    /** The notification of a payment. */
    ORIGINAL("original"),
    /** A copy a chaos {@code duplicate} rule asked for. */
    DUPLICATE("duplicate"),
    /** A delivery sent again because an operator asked for it. */
    REDELIVERY("redelivery");

    private final String tag;

    Kind(String tag) {
        this.tag = tag;
    }

    /** The name in the deliveries table and the operator API. */
    String tag() {
        return tag;
    }
}
