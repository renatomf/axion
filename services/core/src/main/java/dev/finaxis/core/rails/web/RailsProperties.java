package dev.finaxis.core.rails.web;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Finaxis on the rails.
 *
 * @param ispb Finaxis's ISPB, the receiving participant every credit must name (rails simulator
 *     design, section 3.2)
 */
@ConfigurationProperties("finaxis.rails")
record RailsProperties(String ispb) {

    private static final Pattern ISPB = Pattern.compile("[0-9]{8}");

    RailsProperties {
        Assert.isTrue(ISPB.matcher(ispb).matches(), "finaxis.rails.ispb must be eight digits");
    }
}
