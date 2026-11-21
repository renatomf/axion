package dev.finaxis.simulator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.time.Clock;

/** The core's coding rules (overview design, section 19.2) that apply to the simulator, on its production classes. */
@AnalyzeClasses(packagesOf = Application.class, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Constructor injection only, so fields can be final and tests create a class with {@code new}. */
    // Allowed to find no fields, so the rule is in place before the first class that has one.
    @ArchTest
    static final ArchRule constructorInjectionOnly =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

    /** Time comes from an injected {@link Clock}: no {@code now()} without one, no {@code System.currentTimeMillis()}. */
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
