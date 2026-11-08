package com.whiteeyes.common.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * 세 모듈의 ArchitectureTest가 같이 쓰는 규칙. lombok.config로 막을 수 없는 것을 컴파일된 클래스로 확인한다
 * (code-conventions.md 3-2·3-3·3-6).
 */
public final class ArchitectureRules {

    private ArchitectureRules() {}

    /** 시험 클래스를 뺀 운영 코드만 읽는다. */
    public static JavaClasses importMain(String basePackage) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(basePackage);
    }

    /** 스프링 빈(@Component 계열)의 인스턴스 필드는 private final이다. final이 빠지면 @RequiredArgsConstructor가 주입하지 않는다. */
    public static ArchRule beanFieldsArePrivateFinal() {
        return fields().that()
                .areDeclaredInClassesThat()
                .areMetaAnnotatedWith(Component.class)
                .and()
                .areNotStatic()
                .should()
                .bePrivate()
                .andShould()
                .beFinal()
                .because("빈 필드는 생성자 주입 + private final이다(code-conventions.md 3-3)");
    }

    /** 엔티티에 Lombok이 만든 toString·equals·hashCode가 없다. lombok.config의 addLombokGeneratedAnnotation이 표시를 붙인다. */
    public static ArchRule entitiesHaveNoLombokEqualsOrToString() {
        return noMethods()
                .that()
                .areDeclaredInClassesThat()
                .areAnnotatedWith(Entity.class)
                .and()
                .haveNameMatching("toString|equals|hashCode")
                .should()
                .beAnnotatedWith("lombok.Generated")
                .because("엔티티의 @ToString·@EqualsAndHashCode는 지연 로딩·저장 전후 hashCode 변경 문제를 만든다(code-conventions.md 3-6)");
    }

    /** REST 컨트롤러는 맡은 경로에 따라 ApiController·InternalController(8082)·StreamController(SSE)로 끝난다. */
    public static ArchRule restControllersHaveRoleSuffix() {
        return classes()
                .that()
                .areAnnotatedWith(RestController.class)
                .should()
                .haveSimpleNameEndingWith("ApiController")
                .orShould()
                .haveSimpleNameEndingWith("InternalController")
                .orShould()
                .haveSimpleNameEndingWith("StreamController")
                .because("컨트롤러 이름으로 맡은 경로를 알 수 있게 한다(code-conventions.md 3-2)");
    }

    /** 화면(Thymeleaf) 컨트롤러는 PageController로 끝난다. @ControllerAdvice는 @Controller가 아니라 대상이 아니다. */
    public static ArchRule pageControllersHavePageSuffix() {
        return classes()
                .that()
                .areAnnotatedWith(Controller.class)
                .should()
                .haveSimpleNameEndingWith("PageController")
                .because("컨트롤러 이름으로 맡은 경로를 알 수 있게 한다(code-conventions.md 3-2)");
    }
}
