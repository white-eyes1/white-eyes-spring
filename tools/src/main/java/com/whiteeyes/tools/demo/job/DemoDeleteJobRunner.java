package com.whiteeyes.tools.demo.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.whiteeyes.tools.ToolsApplication;
import com.whiteeyes.tools.demo.service.DemoDeleteResult;
import com.whiteeyes.tools.demo.service.DemoDeleteService;

/**
 * 명령 delete-demo로 시연용 통계를 지운다(FNC-A-052).
 * 시연용 데이터는 local·dev에만 있으므로(decisions/0004) 이 작업도 local·dev 프로필에서만 등록한다(package-structure.md demo/).
 */
@Slf4j
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(name = ToolsApplication.COMMAND_PROPERTY, havingValue = DemoDeleteJobRunner.COMMAND)
@RequiredArgsConstructor
public class DemoDeleteJobRunner implements ApplicationRunner {

    static final String COMMAND = "delete-demo";

    private final DemoDeleteService demoDeleteService;

    @Override
    public void run(ApplicationArguments args) {
        DemoDeleteResult result = demoDeleteService.deleteAll();
        log.info("시연용 데이터 삭제 작업 종료 시간별={} 일별={} 합계={}", result.hourly(), result.daily(), result.total());
    }
}
