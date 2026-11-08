package com.whiteeyes.user;

import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.whiteeyes.common.architecture.ArchitectureRules;

/** user 모듈의 빈 필드·엔티티 Lombok 규칙(code-conventions.md 3-3·3-6), 컨트롤러 이름 접미사(3-2) */
class ArchitectureTest {

    private static final JavaClasses CLASSES = ArchitectureRules.importMain("com.whiteeyes.user");

    @Test
    @DisplayName("스프링 빈의 인스턴스 필드는 private final이다")
    void beanFieldsArePrivateFinal() {
        ArchitectureRules.beanFieldsArePrivateFinal().check(CLASSES);
    }

    @Test
    @DisplayName("엔티티에 Lombok이 만든 toString·equals·hashCode가 없다")
    void entitiesHaveNoLombokEqualsOrToString() {
        ArchitectureRules.entitiesHaveNoLombokEqualsOrToString()
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("REST 컨트롤러 이름은 ApiController·InternalController·StreamController로 끝난다")
    void restControllersHaveRoleSuffix() {
        ArchitectureRules.restControllersHaveRoleSuffix().allowEmptyShould(true).check(CLASSES);
    }

    @Test
    @DisplayName("화면 컨트롤러 이름은 PageController로 끝난다")
    void pageControllersHavePageSuffix() {
        ArchitectureRules.pageControllersHavePageSuffix().allowEmptyShould(true).check(CLASSES);
    }
}
