package dev.finaxis.core.billing.domain;

/** A payment as {@link Charge#receive} recorded it, and the charge afterwards. */
public record Receipt(Charge charge, Payment payment) {}
