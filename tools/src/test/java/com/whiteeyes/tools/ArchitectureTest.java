package com.whiteeyes.tools;

import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.whiteeyes.common.architecture.ArchitectureRules;

/** tools 모듈의 빈 필드 규칙(code-conventions.md 3-3) */
class ArchitectureTest {

    private static final JavaClasses CLASSES = ArchitectureRules.importMain("com.whiteeyes.tools");

    @Test
    @DisplayName("스프링 빈의 인스턴스 필드는 private final이다")
    void beanFieldsArePrivateFinal() {
        ArchitectureRules.beanFieldsArePrivateFinal().check(CLASSES);
    }
}
