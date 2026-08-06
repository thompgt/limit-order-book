package io.github.thompgt.lob.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Invariant 1, enforced rather than trusted.
 *
 * <p>{@code engine-core} must stay free of any framework. This is not a taste
 * preference: if a benchmark number is to mean anything, nothing may sit in the
 * measured path that is not in the served path, and vice versa. A single
 * {@code @Component} added to the engine "just to wire it up" would put proxy
 * dispatch inside the match loop and quietly invalidate every figure in the
 * README.
 *
 * <p>This test lives in {@code engine-api} on purpose. It is the only module
 * that <em>has</em> Spring on its classpath, so it is the only place where the
 * rule can fail for a reason other than the dependency being absent. Running it
 * inside {@code engine-core} would pass trivially and prove nothing.
 */
class ArchitectureTest {

    private static final String CORE = "io.github.thompgt.lob.core";

    private final JavaClasses core = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(CORE);

    @Test
    void theEngineDoesNotDependOnSpring() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(CORE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "javax.servlet..")
                .because("a framework in the measured path makes every benchmark "
                        + "number a statement about the framework");
        rule.check(core);
    }

    @Test
    void theEngineDoesNotDependOnTheApiLayer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(CORE + "..")
                .should().dependOnClassesThat().resideInAPackage("io.github.thompgt.lob.api..")
                .because("the engine is a library the service uses, not the other way round");
        rule.check(core);
    }

    @Test
    void theEngineDoesNotUseFloatingPointForPricesOrQuantities() {
        // Invariant 3. A double price is exact until it is not, and the day it
        // is not, a book quietly stops crossing where it should.
        ArchRule rule = noClasses()
                .that().resideInAPackage(CORE + "..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.math.BigDecimal")
                .because("prices are long ticks; conversion happens at the API boundary");
        rule.check(core);
    }

    @Test
    void theEngineDoesNotLogOrPrint() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(CORE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.slf4j..", "org.apache.logging..", "java.util.logging..")
                .because("a log call on the hot path allocates and can block on IO");
        rule.check(core);
    }
}
