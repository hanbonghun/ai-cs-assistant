package com.aicsassistant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@AnalyzeClasses(packages = "com.aicsassistant", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final Pattern DOMAIN = Pattern.compile("com\\.aicsassistant\\.([a-z]+)(\\..*)?");
    private static final Pattern INFRA = Pattern.compile("com\\.aicsassistant\\.([a-z]+)\\.infra(\\..*)?");

    @ArchTest
    static final ArchRule controllers_do_not_touch_infra = noClasses()
            .that().resideInAnyPackage("..api..", "..ui.controller..")
            .should().dependOnClassesThat().resideInAPackage("..infra..");

    @ArchTest
    static final ArchRule infra_is_private_to_its_domain = classes()
            .should(notDependOnAnotherDomainsInfra());

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..api..", "..infra..", "..ui..");

    @ArchTest
    static final ArchRule no_cycles_between_domains = slices()
            .matching("com.aicsassistant.(*)..")
            .should().beFreeOfCycles();

    private static ArchCondition<JavaClass> notDependOnAnotherDomainsInfra() {
        return new ArchCondition<>("not depend on another domain's infra package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originDomain = domainOf(origin.getPackageName());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    Matcher infra = INFRA.matcher(dependency.getTargetClass().getPackageName());
                    if (infra.matches() && !infra.group(1).equals(originDomain)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static String domainOf(String packageName) {
        Matcher matcher = DOMAIN.matcher(packageName);
        return matcher.matches() ? matcher.group(1) : "";
    }
}
