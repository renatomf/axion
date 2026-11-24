package dev.axion.core.audit.domain;

import dev.axion.core.shared.Actor;

/**
 * Who performed an audited action, as a record stores it: the actor's type and its identifier, the
 * member's or the key's UUID, or the job's name.
 */
public record AuditActor(Type type, String id) {

    public enum Type {
        MEMBER,
        API_KEY,
        JOB
    }

    public AuditActor {
        if (id.isBlank()) {
            throw new IllegalArgumentException("An actor needs an identifier");
        }
    }

    public static AuditActor of(Actor actor) {
        return switch (actor) {
            case Actor.Member member ->
                new AuditActor(Type.MEMBER, member.member().value().toString());
            case Actor.ApiKey key ->
                new AuditActor(Type.API_KEY, key.key().value().toString());
            case Actor.Job job -> new AuditActor(Type.JOB, job.name());
        };
    }
}
