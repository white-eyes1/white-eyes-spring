package com.whiteeyes.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

// job 값은 어떤 작업과도 맞지 않게 둔다. 맞는 작업이 있으면 그 작업이 실행되고 앱이 끝난다
/** 1회 실행 작업(--job=…)으로 뜨면 예약 작업과 웹 서버를 켜지 않는다(spring #280) */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "job=job-mode-test")
class JobModeTest {

    private final ApplicationContextRunner schedulingRunner =
            new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("작업 모드에서도 관리자 앱이 웹 서버 없이 뜨고 예약 작업은 등록되지 않는다")
    void bootsWithoutWebServerAndScheduling() {
        assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    @Test
    @DisplayName("job 옵션이 없으면 예약 작업을 켜고, 있으면 켜지 않는다")
    void schedulingOnlyWithoutJob() {
        schedulingRunner.run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
        schedulingRunner
                .withPropertyValues("job=generate-demo")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
