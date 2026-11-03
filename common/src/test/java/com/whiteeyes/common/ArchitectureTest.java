package com.whiteeyes.common;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.whiteeyes.common.architecture.ArchitectureRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** common 모듈의 빈 필드·엔티티 Lombok 규칙(code-conventions.md 3-3·3-6) */
class ArchitectureTest {

    private static final JavaClasses CLASSES = ArchitectureRules.importMain("com.whiteeyes.common");

    @Test
    @DisplayName("스프링 빈의 인스턴스 필드는 private final이다")
    void beanFieldsArePrivateFinal() {
        ArchitectureRules.beanFieldsArePrivateFinal().check(CLASSES);
    }

    @Test
    @DisplayName("엔티티에 Lombok이 만든 toString·equals·hashCode가 없다")
    void entitiesHaveNoLombokEqualsOrToString() {
        ArchitectureRules.entitiesHaveNoLombokEqualsOrToString().allowEmptyShould(true).check(CLASSES);
    }
}
