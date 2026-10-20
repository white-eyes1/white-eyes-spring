package com.whiteeyes.admin.region.job;

import com.whiteeyes.admin.region.service.RegionLoadService;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** 실행 옵션 --job=load-regions --file={경계 파일}로 행정구역을 적재하고 앱을 끝낸다(FNC-A-012). */
@Component
@ConditionalOnProperty(name = "job", havingValue = RegionLoadJobRunner.JOB_NAME)
public class RegionLoadJobRunner implements ApplicationRunner {

    static final String JOB_NAME = "load-regions";
    private static final String FILE_OPTION = "file";

    private static final Logger log = LoggerFactory.getLogger(RegionLoadJobRunner.class);

    private final RegionLoadService regionLoadService;
    private final ConfigurableApplicationContext context;

    public RegionLoadJobRunner(RegionLoadService regionLoadService, ConfigurableApplicationContext context) {
        this.regionLoadService = regionLoadService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> files = args.getOptionValues(FILE_OPTION);
        if (files == null || files.size() != 1) {
            throw new IllegalArgumentException("--file={경계 파일} 옵션이 하나 필요합니다");
        }

        int loaded = regionLoadService.load(Path.of(files.get(0)));
        log.info("행정구역 적재 작업 종료 건수={}", loaded);

        // 1회 실행 작업이므로 웹 서버를 계속 띄워 두지 않는다. 실패하면 예외로 시작이 중단되어 종료 코드가 0이 아니다.
        System.exit(SpringApplication.exit(context));
    }
}
