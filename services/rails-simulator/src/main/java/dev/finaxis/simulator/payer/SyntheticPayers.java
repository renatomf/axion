package dev.finaxis.simulator.payer;

import dev.finaxis.simulator.spi.Debtor;
import java.util.List;
import java.util.random.RandomGenerator;

/** Synthetic people, each with a valid CPF, who pay when a request names no payer (rails simulator design, section 4.2). */
final class SyntheticPayers {

    private static final List<Debtor> PEOPLE = List.of(
            new Debtor("Ana Souza", "52601815906"),
            new Debtor("Bruno Lima", "11144477735"),
            new Debtor("Carla Mendes", "24681357928"),
            new Debtor("Diego Rocha", "13579246828"),
            new Debtor("Elisa Prado", "98765432100"));

    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();

    private SyntheticPayers() {}

    static Debtor any() {
        return PEOPLE.get(RANDOM.nextInt(PEOPLE.size()));
    }
}
