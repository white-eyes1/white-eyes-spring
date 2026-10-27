package com.whiteeyes.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 1회 실행 작업(--job=…)이면 웹 서버 없이 시작한다(spring #280) */
class AdminApplicationJobArgsTest {

    @Test
    @DisplayName("--job=… 인자가 있을 때만 작업 모드로 본다")
    void detectsJobArgument() {
        assertThat(AdminApplication.isJob(new String[] {"--spring.profiles.active=local", "--job=generate-demo"}))
                .isTrue();
        assertThat(AdminApplication.isJob(new String[] {"--spring.profiles.active=local"})).isFalse();
        assertThat(AdminApplication.isJob(new String[] {})).isFalse();
    }
}
