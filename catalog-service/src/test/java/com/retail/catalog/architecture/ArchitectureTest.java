package com.retail.catalog.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hace cumplir las reglas de dependencia de la spec del Catálogo (sección 3). Ninguna regla permite
 * evaluación vacía: si un paquete deja de tener clases que evaluar, la prueba falla.
 */
@AnalyzeClasses(packages = "com.retail.catalog", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule featurePackagesAreFreeOfCycles = slices()
            .matching("com.retail.catalog.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule onlyStockEventListenerUsesAmqp = noClasses()
            .that().doNotHaveSimpleName("StockEventListener")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.amqp..");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule productDoesNotDependOnStockEvent = noClasses()
            .that().resideInAPackage("com.retail.catalog.product..")
            .should().dependOnClassesThat().resideInAPackage("com.retail.catalog.stockevent..");
}
