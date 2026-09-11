package com.pnas.server.architecture;

import com.pnas.server.PnasServerApplication;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 包边界约束(架构 v0.3 §4)。
 *
 * 注意:包模式必须用**项目内全限定前缀**(com.pnas.server.xxx..),
 * 否则像 `..web..` 会连 `org.springframework.web.*` 一起命中(Ruling R14)。
 */
@AnalyzeClasses(packagesOf = PnasServerApplication.class)
class PackageArchitectureTest {

    private static final String WEB = "com.pnas.server.web..";

    @ArchTest
    static final ArchRule controllers_only_in_allowed_packages = classes()
        .that().haveSimpleNameEndingWith("Controller")
        .should().resideInAnyPackage(
            "com.pnas.server.web..", "com.pnas.server.auth..", "com.pnas.server.iam..",
            "com.pnas.server.files..", "com.pnas.server.job..");

    @ArchTest
    static final ArchRule web_does_not_depend_on_blobstore = noClasses()
        .that().resideInAPackage(WEB)
        .should().dependOnClassesThat().resideInAPackage("com.pnas.server.blobstore..");

    @ArchTest
    static final ArchRule domain_packages_do_not_depend_on_web = noClasses()
        .that().resideInAnyPackage(
            "com.pnas.server.files..", "com.pnas.server.blobstore..", "com.pnas.server.job..",
            "com.pnas.server.iam..", "com.pnas.server.common..")
        .should().dependOnClassesThat().resideInAPackage(WEB);

    @ArchTest
    static final ArchRule storage_and_common_do_not_depend_on_controllers = noClasses()
        .that().resideInAnyPackage("com.pnas.server.blobstore..", "com.pnas.server.common..")
        .should().dependOnClassesThat().resideInAPackage("com.pnas.server..")
        .andShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller");
}
