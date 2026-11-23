package dev.finaxis.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import dev.finaxis.core.access.NoPermissionRequired;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.RequiresStepUp;
import java.time.Clock;
import org.springframework.web.bind.annotation.RequestMapping;

/** The coding rules of the engineering standards (overview design, section 19.2), on the production classes. */
@AnalyzeClasses(packagesOf = Application.class, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Constructor injection only, so fields can be final and tests create a class with {@code new}. */
    @ArchTest
    static final ArchRule constructorInjectionOnly = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    /** Domain code knows nothing of Spring, persistence or transactions. */
    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "jakarta.transaction..", "org.hibernate..");

    /** Time comes from an injected {@link Clock}: no {@code now()} without one, no {@code System.currentTimeMillis()}. */
    @ArchTest
    static final ArchRule timeComesFromAClock = noClasses().should().callMethodWhere(readsTheSystemClock());

    /**
     * Every endpoint declares exactly one of {@link RequiresPermission} and {@link NoPermissionRequired}.
     * The rails module's are the exception: their caller is the Pix rails, authenticated by its
     * signature, which acts for no tenant and holds no permission.
     */
    @ArchTest
    static final ArchRule everyEndpointDeclaresItsPermission = methods()
            .that()
            .areMetaAnnotatedWith(RequestMapping.class)
            .and()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage("dev.finaxis.core.rails..")
            .should(declareExactlyOnePermission());

    /** Step-up is checked with the permission, so {@link RequiresStepUp} alone would be ignored. */
    @ArchTest
    static final ArchRule stepUpGoesWithAPermission =
            methods().that().areAnnotatedWith(RequiresStepUp.class).should().beAnnotatedWith(RequiresPermission.class);

    private static ArchCondition<JavaMethod> declareExactlyOnePermission() {
        return new ArchCondition<>("declare exactly one of @RequiresPermission and @NoPermissionRequired") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean requires = method.isAnnotatedWith(RequiresPermission.class);
                boolean exempt = method.isAnnotatedWith(NoPermissionRequired.class);
                if (requires == exempt) {
                    events.add(SimpleConditionEvent.violated(
                            method, method.getFullName() + " must declare exactly one permission annotation"));
                }
            }
        };
    }

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
