package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantIdTest {

    @Test
    void showsItselfWithTheTenantPrefix() {
        var uuid = UUID.fromString("0193a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");

        var publicId = new TenantId(uuid).publicId();

        assertThat(publicId.toString()).startsWith("ten_");
        assertThat(PublicId.parse("ten", publicId.toString()).uuid()).isEqualTo(uuid);
    }
}
