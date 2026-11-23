package dev.axion.core.rails.web;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Axion on the rails.
 *
 * @param ispb Axion's ISPB, the receiving participant every credit must name (rails simulator
 *     design, section 3.2)
 */
@ConfigurationProperties("axion.rails")
record RailsProperties(String ispb) {

    private static final Pattern ISPB = Pattern.compile("[0-9]{8}");

    RailsProperties {
        Assert.isTrue(ISPB.matcher(ispb).matches(), "axion.rails.ispb must be eight digits");
    }
}
