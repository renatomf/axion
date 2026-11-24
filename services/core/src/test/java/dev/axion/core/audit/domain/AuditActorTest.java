package dev.axion.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.shared.Actor;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.MemberId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditActorTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void recordsAMemberByItsId() {
        assertThat(AuditActor.of(new Actor.Member(new MemberId(id))))
                .isEqualTo(new AuditActor(AuditActor.Type.MEMBER, id.toString()));
    }

    @Test
    void recordsAnApiKeyByItsId() {
        assertThat(AuditActor.of(new Actor.ApiKey(new ApiKeyId(id))))
                .isEqualTo(new AuditActor(AuditActor.Type.API_KEY, id.toString()));
    }

    @Test
    void recordsAJobByItsName() {
        assertThat(AuditActor.of(new Actor.Job("webhook-delivery")))
                .isEqualTo(new AuditActor(AuditActor.Type.JOB, "webhook-delivery"));
    }
}
