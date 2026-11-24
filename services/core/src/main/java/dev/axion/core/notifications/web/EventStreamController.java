package dev.axion.core.notifications.web;

import dev.axion.core.access.MemberCaller;
import dev.axion.core.access.RequiresPermission;
import dev.axion.core.notifications.application.LiveStreams;
import dev.axion.core.notifications.application.StreamMember;
import dev.axion.core.tenancy.Permission;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live stream of a tenant's events, for the console's BFF (events and webhooks design, section
 * 10). The member's tenant is the one in the path, already checked; every role reads charges, so the
 * permission checks the membership and filters nothing out by itself.
 */
@RestController
class EventStreamController {

    private final LiveStreams streams;

    EventStreamController(LiveStreams streams) {
        this.streams = streams;
    }

    @RequiresPermission(Permission.CHARGES_READ)
    @GetMapping(path = "/internal/tenants/{tenant_id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            MemberCaller caller,
            @RequestHeader(name = "Last-Event-ID", required = false) @Nullable String lastEventId) {
        StreamMember member = new StreamMember(caller.tenant(), caller.member(), caller.permissions());
        SseEmitter emitter = new SseEmitter();
        SseStream stream = new SseStream(emitter, ended -> streams.close(member, ended));
        return switch (streams.open(member, stream, Optional.ofNullable(lastEventId))) {
            case OPENED -> {
                stream.start();
                yield emitter;
            }
            case MEMBER_LIMIT_REACHED -> throw StreamProblems.memberLimitReached();
            case TENANT_LIMIT_REACHED -> throw StreamProblems.tenantLimitReached();
        };
    }
}
