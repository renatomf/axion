package dev.finaxis.core.rails.web;

import dev.finaxis.core.rails.application.CreditInbox;
import dev.finaxis.core.rails.domain.ReceivedCredit;
import dev.finaxis.core.rails.security.SignedMessage;
import dev.finaxis.core.rails.web.CreditNotification.MalformedNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where the Pix rails deliver each credit notification (billing design, section 7.1). Its contract
 * is the rails' {@code rails.yaml}, not {@code v1.yaml}. The signature was verified by the filter
 * chain; the endpoint reads the verified bytes, records the credit in the inbox and answers
 * {@code 202}, a duplicate included, before any charge is settled. A notification the core cannot
 * read is a {@code 400}, which the rails keep for an operator.
 */
@RestController
@EnableConfigurationProperties(RailsProperties.class)
class PixCreditsController {

    private static final Logger LOG = LoggerFactory.getLogger(PixCreditsController.class);

    private final CreditInbox inbox;
    private final RailsProperties rails;
    // The rails' contract asks receivers to ignore fields they do not know, so a message can grow.
    private final ObjectReader notifications;

    PixCreditsController(CreditInbox inbox, RailsProperties rails, JsonMapper json) {
        this.inbox = inbox;
        this.rails = rails;
        this.notifications =
                json.readerFor(CreditNotification.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostMapping(path = "/rails/pix/credits", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(@RequestAttribute(SignedMessage.ATTRIBUTE) SignedMessage message) {
        ReceivedCredit credit = read(message);
        inbox.record(credit, message.keyId(), message.digest());
        return ResponseEntity.accepted().build();
    }

    private ReceivedCredit read(SignedMessage message) {
        CreditNotification notification;
        try {
            notification = notifications.readValue(message.body());
        } catch (JacksonException e) {
            throw malformed(message, "body");
        }
        try {
            return notification.toCredit(rails.ispb());
        } catch (MalformedNotification e) {
            throw malformed(message, e.field());
        }
    }

    // The field's name only: the message is the rails', and its values could forge log lines.
    private static ErrorResponseException malformed(SignedMessage message, String field) {
        LOG.warn("Refused a credit notification signed by {}: {} is missing or malformed", message.keyId(), field);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The credit notification is malformed.");
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, problem, null);
    }
}
