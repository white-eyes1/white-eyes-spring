package com.whiteeyes.admin.demo.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.whiteeyes.admin.demo.service.DemoDeleteService;

// 등록 조건만 본다. ApplicationContextRunner는 ApplicationRunner를 실행하지 않으므로 앱이 끝나지 않는다
/** 시연용 데이터 삭제 작업(FNC-A-052)은 local·dev 프로필에서 --job=delete-demo일 때만 등록된다(decisions/0004) */
class DemoDeleteJobRunnerProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DemoDeleteService.class, () -> mock(DemoDeleteService.class))
            .withUserConfiguration(DemoDeleteJobRunner.class);

    @ParameterizedTest(name = "프로필 {0}, job={1} → 등록 {2}")
    @CsvSource({
        "local, delete-demo, true",
        "dev, delete-demo, true",
        "prod, delete-demo, false",
        "local, load-regions, false"
    })
    @DisplayName("FNC-A-052 local·dev 프로필의 delete-demo 작업에서만 등록되고 prod에서는 등록되지 않는다")
    void registeredOnlyInLocalAndDev(String profile, String job, boolean expected) {
        contextRunner
                .withPropertyValues("spring.profiles.active=" + profile, "job=" + job)
                .run(context -> assertThat(context.getBeansOfType(DemoDeleteJobRunner.class)).hasSize(expected ? 1 : 0));
    }
}
