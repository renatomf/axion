package dev.axion.core.shared;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.regex.Pattern;

/**
 * Who made a change (events and webhooks design, section 4.1): a member, an API key, or a job of the
 * platform. The audit trail records it; public events leave it out.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Actor.Member.class, name = "member"),
    @JsonSubTypes.Type(value = Actor.ApiKey.class, name = "api_key"),
    @JsonSubTypes.Type(value = Actor.Job.class, name = "job")
})
public sealed interface Actor {

    /** A member, through the console. */
    record Member(MemberId member) implements Actor {}

    /** A tenant's system, through an API key. */
    record ApiKey(ApiKeyId key) implements Actor {}

    /**
     * A job of the platform, such as {@code charge-expiry}.
     *
     * @param name lowercase words joined by hyphens
     */
    record Job(String name) implements Actor {

        private static final Pattern NAME = Pattern.compile("[a-z]+(-[a-z]+)*");

        public Job {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("A job's name is lowercase words joined by hyphens: " + name);
            }
        }
    }
}
