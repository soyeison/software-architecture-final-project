package com.retail.inventory.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hace cumplir las reglas de dependencia de la spec de Inventario (sección 3). Algunas reglas
 * describen paquetes que llegan en sub-tareas posteriores; solo las dos reglas sobre paquetes que
 * aún no existen (las reglas 3 y 4) permiten evaluación vacía.
 */
@AnalyzeClasses(packages = "com.retail.inventory", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule featurePackagesAreFreeOfCycles = slices()
            .matching("com.retail.inventory.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule onlyRabbitStockEventSenderUsesAmqp = noClasses()
            .that().doNotHaveSimpleName("RabbitStockEventSender")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.amqp..");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule stockAndSaleStayIndependentOfWebAndMessaging = noClasses()
            .that().resideInAnyPackage("com.retail.inventory.stock..", "com.retail.inventory.sale..")
            .and().haveSimpleNameNotEndingWith("Controller")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
            .orShould().dependOnClassesThat().haveSimpleName("RabbitStockEventSender")
            .allowEmptyShould(true);
}
