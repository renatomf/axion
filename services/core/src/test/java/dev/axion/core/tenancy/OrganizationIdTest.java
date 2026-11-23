package dev.axion.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.shared.PublicId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationIdTest {

    @Test
    void showsItselfWithTheOrganizationPrefix() {
        var uuid = UUID.fromString("0193a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");

        var publicId = new OrganizationId(uuid).publicId();

        assertThat(publicId.toString()).startsWith("org_");
        assertThat(PublicId.parse("org", publicId.toString()).uuid()).isEqualTo(uuid);
    }
}
