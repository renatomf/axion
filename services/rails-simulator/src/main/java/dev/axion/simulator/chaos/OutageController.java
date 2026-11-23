package dev.axion.simulator.chaos;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.axion.simulator.spi.Iso20022;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The outage switch of {@code openapi/simulator.yaml}: take the rails down and bring them back. */
@RestController
class OutageController {

    private static final Logger LOG = LoggerFactory.getLogger(OutageController.class);

    private static final Pattern DURATION = Pattern.compile("PT[0-9]{1,4}(\\.[0-9]{1,3})?S");

    private final Outage outage;

    OutageController(Outage outage) {
        this.outage = outage;
    }

    @PutMapping("/chaos/outage")
    OutageView start(@RequestBody OutageRequest request) {
        Outage.State started = outage.start(duration(request.duration()));
        LOG.atWarn().addKeyValue("ends_at", started.endsAt()).log("Rails outage started");
        return OutageView.of(started);
    }

    @DeleteMapping("/chaos/outage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void end() {
        outage.end();
        LOG.info("Rails outage ended");
    }

    private static @Nullable Duration duration(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String detail = "duration must be an ISO 8601 duration of seconds, more than zero and at most "
                + Durations.inSeconds(Outage.MAX_DURATION) + ".";
        if (!DURATION.matcher(value).matches()) {
            throw badRequest(detail);
        }
        Duration duration = Duration.parse(value);
        if (!duration.isPositive() || duration.compareTo(Outage.MAX_DURATION) > 0) {
            throw badRequest(detail);
        }
        return duration;
    }

    private static ErrorResponseException badRequest(String detail) {
        return new ErrorResponseException(
                HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }

    record OutageRequest(@Nullable String duration) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OutageView(String startedAt, @Nullable String endsAt) {

        static OutageView of(Outage.State state) {
            Instant endsAt = state.endsAt();
            return new OutageView(
                    Iso20022.dateTime(state.startedAt()), endsAt == null ? null : Iso20022.dateTime(endsAt));
        }
    }
}
