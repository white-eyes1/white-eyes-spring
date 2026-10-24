package com.whiteeyes.admin.demo.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.whiteeyes.admin.demo.service.DemoGenerateService;

// 등록 조건만 본다. ApplicationContextRunner는 ApplicationRunner를 실행하지 않으므로 앱이 끝나지 않는다
/** 시연용 통계 생성 작업(FNC-A-051)은 local·dev 프로필에서 --job=generate-demo일 때만 등록된다(decisions/0004) */
class DemoGenerateJobRunnerProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DemoGenerateService.class, () -> mock(DemoGenerateService.class))
            .withUserConfiguration(DemoGenerateJobRunner.class);

    @ParameterizedTest(name = "프로필 {0}, job={1} → 등록 {2}")
    @CsvSource({
        "local, generate-demo, true",
        "dev, generate-demo, true",
        "prod, generate-demo, false",
        "local, delete-demo, false"
    })
    @DisplayName("FNC-A-051 local·dev 프로필의 generate-demo 작업에서만 등록되고 prod에서는 등록되지 않는다")
    void registeredOnlyInLocalAndDev(String profile, String job, boolean expected) {
        contextRunner
                .withPropertyValues("spring.profiles.active=" + profile, "job=" + job)
                .run(context -> assertThat(context.getBeansOfType(DemoGenerateJobRunner.class)).hasSize(expected ? 1 : 0));
    }
}
