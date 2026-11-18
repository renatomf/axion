package dev.finaxis.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.time.Clock;

/**
 * Coding rules of the engineering standards (overview design, section 19.2), checked against the
 * compiled production classes.
 *
 * <p>{@code @AnalyzeClasses} imports every class under {@code dev.finaxis.core} once, without tests,
 * and ArchUnit's JUnit engine runs each {@code @ArchTest} rule against them. No Spring context is
 * started. A failing rule lists every offending class and line.
 */
@AnalyzeClasses(packagesOf = Application.class, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Dependencies are passed to the constructor, never injected into fields with {@code @Autowired},
     * {@code @Inject}, {@code @Resource} or {@code @Value}. A class then cannot be created without
     * them, its fields can be final, and tests create it with {@code new}.
     */
    @ArchTest
    static final ArchRule constructorInjectionOnly = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    /**
     * Domain code is plain Java: it knows nothing about Spring, persistence or transactions, so the
     * business rules can be read and tested on their own.
     */
    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "jakarta.transaction..", "org.hibernate..");

    /**
     * The current time comes from an injected {@link Clock}, so tests can fix it. This forbids the
     * shortcuts that read the system clock directly: {@code Instant.now()}, {@code
     * LocalDate.now(zone)} and every other {@code java.time} {@code now} that does not take a
     * {@code Clock}, as well as {@code System.currentTimeMillis()}.
     */
    @ArchTest
    static final ArchRule timeComesFromAClock = noClasses().should().callMethodWhere(readsTheSystemClock());

    private static DescribedPredicate<JavaMethodCall> readsTheSystemClock() {
        return DescribedPredicate.describe("reads the system clock", call -> {
            var target = call.getTarget();
            var parameters = target.getRawParameterTypes();
            boolean takesAClock =
                    parameters.size() == 1 && parameters.getFirst().isEquivalentTo(Clock.class);
            boolean javaTimeNow = target.getOwner().getPackageName().equals("java.time")
                    && target.getName().equals("now")
                    && !takesAClock;
            boolean currentTimeMillis = target.getOwner().isEquivalentTo(System.class)
                    && target.getName().equals("currentTimeMillis");
            return javaTimeNow || currentTimeMillis;
        });
    }
}
