package com.whiteeyes.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** 1회 실행 작업(--job=…)이면 웹 서버 없이 시작하고(spring #280), 실패하면 종료 코드 1로 끝난다(spring #358) */
class AdminApplicationJobArgsTest {

    @Test
    @DisplayName("--job=… 인자가 있을 때만 작업 모드로 본다")
    void detectsJobArgument() {
        assertThat(AdminApplication.isJob(new String[] {"--spring.profiles.active=local", "--job=generate-demo"}))
                .isTrue();
        assertThat(AdminApplication.isJob(new String[] {"--spring.profiles.active=local"})).isFalse();
        assertThat(AdminApplication.isJob(new String[] {})).isFalse();
    }

    @Test
    @DisplayName("spring #358 작업 모드에서 시작이 예외로 실패하면 종료 코드 1로 끝낸다")
    void jobFailure_exitsWithOne() {
        List<Integer> exitCodes = new ArrayList<>();

        AdminApplication.run(failingApplication(), new String[] {"--job=generate-demo", "--zone=99"}, exitCodes::add);

        assertThat(exitCodes).containsExactly(1);
    }

    @Test
    @DisplayName("spring #358 작업 모드에서 성공하면 여기서 끝내지 않는다(작업이 스스로 종료 코드 0으로 끝냄)")
    void jobSuccess_doesNotExitHere() {
        List<Integer> exitCodes = new ArrayList<>();

        AdminApplication.run(succeedingApplication(), new String[] {"--job=delete-demo"}, exitCodes::add);

        assertThat(exitCodes).isEmpty();
    }

    @Test
    @DisplayName("spring #358 웹 앱으로 뜰 때의 실패는 종료하지 않고 예외를 그대로 던진다")
    void webFailure_rethrows() {
        List<Integer> exitCodes = new ArrayList<>();

        assertThatThrownBy(() -> AdminApplication.run(
                        failingApplication(), new String[] {"--spring.profiles.active=local"}, exitCodes::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("시작 실패");
        assertThat(exitCodes).isEmpty();
    }

    // run이 시작 실패처럼 예외를 던지는 앱(실제 컨텍스트는 만들지 않는다)
    private static SpringApplication failingApplication() {
        return new SpringApplication(AdminApplication.class) {
            @Override
            public ConfigurableApplicationContext run(String... args) {
                throw new IllegalStateException("시작 실패");
            }
        };
    }

    private static SpringApplication succeedingApplication() {
        return new SpringApplication(AdminApplication.class) {
            @Override
            public ConfigurableApplicationContext run(String... args) {
                return null;
            }
        };
    }
}
