package com.whiteeyes.admin.demo.job;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.demo.service.DemoGenerateResult;
import com.whiteeyes.admin.demo.service.DemoGenerateService;

/**
 * 실행 옵션 --job=generate-demo로 시연용 통계를 만들고 앱을 끝낸다(FNC-A-051).
 * 선택 옵션: --config={설정 파일}(없으면 기본 설정), --zone={구역 번호,…}(없으면 모든 구역).
 * 시연용 데이터는 local·dev에만 넣으므로(decisions/0004) 이 작업도 local·dev 프로필에서만 등록한다(package-structure.md demo/).
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(name = "job", havingValue = DemoGenerateJobRunner.JOB_NAME)
public class DemoGenerateJobRunner implements ApplicationRunner {

    static final String JOB_NAME = "generate-demo";
    private static final String CONFIG_OPTION = "config";
    private static final String ZONE_OPTION = "zone";

    private static final Logger log = LoggerFactory.getLogger(DemoGenerateJobRunner.class);

    private final DemoGenerateService demoGenerateService;
    private final ConfigurableApplicationContext context;

    public DemoGenerateJobRunner(DemoGenerateService demoGenerateService, ConfigurableApplicationContext context) {
        this.demoGenerateService = demoGenerateService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        DemoGenerateResult result = demoGenerateService.generate(configFile(args), zoneIds(args));
        log.info(
                "시연용 통계 생성 작업 종료 구역={} 시간별={} 일별={} 먼저 지운 건수={}",
                result.zones(),
                result.hourly(),
                result.daily(),
                result.deleted().total());

        // 1회 실행 작업이므로 웹 서버를 계속 띄워 두지 않는다. 실패하면 예외로 시작이 중단되어 종료 코드가 0이 아니다.
        System.exit(SpringApplication.exit(context));
    }

    // 없으면 null(기본 설정). 두 번 주면 어느 쪽인지 알 수 없어 멈춘다
    private static Path configFile(ApplicationArguments args) {
        List<String> configs = args.getOptionValues(CONFIG_OPTION);
        if (configs == null) {
            return null;
        }
        if (configs.size() != 1 || configs.get(0).isBlank()) {
            throw new IllegalArgumentException("--config={설정 파일} 옵션은 하나만 줄 수 있습니다");
        }
        return Path.of(configs.get(0));
    }

    /**
     * --zone=1,2 또는 --zone=1 --zone=2. 없으면 null(모든 구역).
     *
     * @throws IllegalArgumentException 숫자가 아닌 값이 있거나 비어 있을 때
     */
    static List<Long> zoneIds(ApplicationArguments args) {
        List<String> values = args.getOptionValues(ZONE_OPTION);
        if (values == null) {
            return null;
        }
        List<Long> zoneIds = new ArrayList<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                try {
                    zoneIds.add(Long.valueOf(part.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("--zone에는 구역 번호를 쉼표로 이어 씁니다: " + value, e);
                }
            }
        }
        return zoneIds;
    }
}
