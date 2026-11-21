package dev.finaxis.simulator.spi;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Pix participants the simulator plays, by their fictitious ISPBs (rails simulator design, section
 * 3.2).
 *
 * @param receiver Finaxis, the receiving participant, credited by every payment
 * @param payers the payers' institutions, one of which debits each payment
 */
@ConfigurationProperties("simulator.participants")
record ParticipantsProperties(Ispb receiver, List<Ispb> payers) {

    ParticipantsProperties {
        if (payers.isEmpty()) {
            throw new IllegalArgumentException("simulator.participants.payers needs at least one institution");
        }
        payers = List.copyOf(payers);
    }
}
