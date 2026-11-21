package dev.finaxis.simulator.chaos;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.finaxis.simulator.spi.EndToEndId;
import dev.finaxis.simulator.spi.Iso20022;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The chaos rules of {@code openapi/simulator.yaml}: add, list and remove fault rules. */
@RestController
class ChaosController {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosController.class);

    private static final Pattern DURATION = Pattern.compile("PT[0-9]{1,4}(\\.[0-9]{1,3})?S");
    private static final Pattern TRANSACTION_ID = Pattern.compile("[A-Za-z0-9]{1,25}");
    private static final String FAULTS =
            Arrays.stream(Fault.values()).map(Fault::tag).collect(Collectors.joining(", "));

    private final ChaosRules rules;

    ChaosController(ChaosRules rules) {
        this.rules = rules;
    }

    @GetMapping("/chaos/rules")
    RulesView list() {
        return new RulesView(rules.list().stream().map(RuleView::of).toList());
    }

    @PostMapping("/chaos/rules")
    @ResponseStatus(HttpStatus.CREATED)
    RuleView add(@RequestBody RuleRequest request) {
        String tag = request.fault();
        if (tag == null) {
            throw badRequest("fault is required.");
        }
        Fault fault = Fault.ofTag(tag).orElseThrow(() -> badRequest("fault must be one of " + FAULTS + "."));
        Integer times = request.times();
        if (times == null || times < 1 || times > FaultRule.MAX_TIMES) {
            throw badRequest("times must be an integer from 1 to " + FaultRule.MAX_TIMES + ".");
        }
        FaultRule rule = rules.add(fault, parameters(fault, request), match(request.match()), times);
        LOG.atInfo()
                .addKeyValue("rule", rule.id())
                .addKeyValue("fault", fault.tag())
                .addKeyValue("times", times)
                .log("Fault rule added");
        return RuleView.of(rule);
    }

    @DeleteMapping("/chaos/rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear() {
        rules.clear();
        LOG.info("Every fault rule removed");
    }

    @DeleteMapping("/chaos/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String id) {
        if (!parse(id).map(rules::remove).orElse(false)) {
            throw new ErrorResponseException(
                    HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No rule in force has this id."),
                    null);
        }
        LOG.atInfo().addKeyValue("rule", id).log("Fault rule removed");
    }

    private static FaultParameters parameters(Fault fault, RuleRequest request) {
        if (fault != Fault.LATENCY && (request.min() != null || request.max() != null)) {
            throw badRequest("min and max apply only to latency.");
        }
        if (fault != Fault.DELAY && request.duration() != null) {
            throw badRequest("duration applies only to delay.");
        }
        if (fault != Fault.DUPLICATE && request.copies() != null) {
            throw badRequest("copies applies only to duplicate.");
        }
        return switch (fault) {
            case LATENCY -> latency(request.min(), request.max());
            case DELAY -> FaultParameters.delay(delay(request.duration()));
            case DUPLICATE -> FaultParameters.duplicate(copies(request.copies()));
            case FAIL, LOST_RESPONSE -> FaultParameters.NONE;
        };
    }

    private static FaultParameters latency(@Nullable String minimum, @Nullable String maximum) {
        if (minimum == null || maximum == null) {
            throw badRequest("min and max are required for latency.");
        }
        Duration min = duration("min", minimum);
        Duration max = duration("max", maximum);
        if (max.compareTo(FaultRule.MAX_LATENCY) > 0) {
            throw badRequest("max must not exceed " + Durations.inSeconds(FaultRule.MAX_LATENCY) + ".");
        }
        if (min.compareTo(max) > 0) {
            throw badRequest("min must not exceed max.");
        }
        return FaultParameters.latency(min, max);
    }

    private static Duration delay(@Nullable String value) {
        if (value == null) {
            throw badRequest("duration is required for delay.");
        }
        Duration duration = duration("duration", value);
        if (!duration.isPositive() || duration.compareTo(FaultRule.MAX_DELAY) > 0) {
            throw badRequest(
                    "duration must be more than zero and at most " + Durations.inSeconds(FaultRule.MAX_DELAY) + ".");
        }
        return duration;
    }

    private static int copies(@Nullable Integer copies) {
        if (copies == null || copies < 1 || copies > FaultRule.MAX_COPIES) {
            throw badRequest("copies must be an integer from 1 to " + FaultRule.MAX_COPIES + ".");
        }
        return copies;
    }

    private static Duration duration(String field, String value) {
        if (!DURATION.matcher(value).matches()) {
            throw badRequest(field + " must be an ISO 8601 duration of seconds, such as PT1.5S.");
        }
        return Duration.parse(value);
    }

    private static Match match(@Nullable MatchRequest match) {
        if (match == null) {
            return Match.ANY;
        }
        String endToEndId = match.endToEndId();
        if (endToEndId != null && !isEndToEndId(endToEndId)) {
            throw badRequest("match.endToEndId must be an EndToEndId.");
        }
        String transactionId = match.transactionId();
        if (transactionId != null && !TRANSACTION_ID.matcher(transactionId).matches()) {
            throw badRequest("match.transactionId must have 1 to 25 letters or digits.");
        }
        return new Match(endToEndId, transactionId);
    }

    private static boolean isEndToEndId(String value) {
        try {
            new EndToEndId(value);
            return true;
        } catch (IllegalArgumentException notAnEndToEndId) {
            return false;
        }
    }

    // An id that cannot exist names no rule: a 404, like one that does not.
    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static ErrorResponseException badRequest(String detail) {
        return new ErrorResponseException(
                HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }

    /** Every field is nullable so that a body without one is a 400 with a detail. */
    record RuleRequest(
            @Nullable String fault,
            @Nullable String min,
            @Nullable String max,
            @Nullable String duration,
            @Nullable Integer copies,
            @Nullable MatchRequest match,
            @Nullable Integer times) {}

    record MatchRequest(
            @Nullable String endToEndId, @Nullable String transactionId) {}

    record RulesView(List<RuleView> rules) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RuleView(
            String id,
            String fault,
            @Nullable String min,
            @Nullable String max,
            @Nullable String duration,
            @Nullable Integer copies,
            MatchView match,
            int times,
            int remaining,
            String createdAt) {

        static RuleView of(FaultRule rule) {
            FaultParameters parameters = rule.parameters();
            return new RuleView(
                    rule.id().toString(),
                    rule.fault().tag(),
                    inSeconds(parameters.min()),
                    inSeconds(parameters.max()),
                    inSeconds(parameters.duration()),
                    parameters.copies(),
                    new MatchView(rule.match().endToEndId(), rule.match().transactionId()),
                    rule.times(),
                    rule.remaining(),
                    Iso20022.dateTime(rule.createdAt()));
        }

        private static @Nullable String inSeconds(@Nullable Duration duration) {
            return duration == null ? null : Durations.inSeconds(duration);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MatchView(@Nullable String endToEndId, @Nullable String transactionId) {}
}
