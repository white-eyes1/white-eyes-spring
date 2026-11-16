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

    // ToolsApplication이 넘기는 명령 이름. 이 값일 때만 이 러너가 등록된다.
    static final String COMMAND = "delete-demo";

    private final DemoDeleteService demoDeleteService;

    /**
     * 시연용 통계를 모두 지우고 건수를 로그로 남긴다.
     *
     * @param args 사용하지 않는다(delete-demo는 옵션이 없다)
     */
    @Override
    public void run(ApplicationArguments args) {
        // 시간별·일별 표에서 is_demo=1 행만 지운다. 실제 데이터는 건드리지 않는다.
        DemoDeleteResult result = demoDeleteService.deleteAll();
        // 지운 건수를 남겨 어떤 일이 일어났는지 나중에 확인할 수 있게 한다. 예외가 나면 러너 밖(ToolsApplication.run)에서 실패 코드로 바뀐다.
        log.info("시연용 데이터 삭제 작업 종료 시간별={} 일별={} 합계={}", result.hourly(), result.daily(), result.total());
    }
}
