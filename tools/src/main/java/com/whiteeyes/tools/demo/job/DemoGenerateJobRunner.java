package com.whiteeyes.tools.demo.job;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.whiteeyes.tools.ToolsApplication;
import com.whiteeyes.tools.demo.service.DemoGenerateResult;
import com.whiteeyes.tools.demo.service.DemoGenerateService;

/**
 * 명령 generate-demo로 시연용 통계를 만든다(FNC-A-051).
 * 선택 옵션: --config={설정 파일}(없으면 기본 설정), --zone={구역 번호,…}(없으면 모든 구역).
 * 시연용 데이터는 local·dev에만 넣으므로(decisions/0004) 이 작업도 local·dev 프로필에서만 등록한다(package-structure.md demo/).
 */
@Slf4j
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(name = ToolsApplication.COMMAND_PROPERTY, havingValue = DemoGenerateJobRunner.COMMAND)
@RequiredArgsConstructor
public class DemoGenerateJobRunner implements ApplicationRunner {

    // ToolsApplication이 넘기는 명령 이름. 이 값일 때만 이 러너가 등록된다.
    static final String COMMAND = "generate-demo";
    // --config=파일, --zone=번호,번호 옵션의 이름(앞의 --는 제외).
    private static final String CONFIG_OPTION = "config";
    private static final String ZONE_OPTION = "zone";

    private final DemoGenerateService demoGenerateService;

    /**
     * 옵션을 읽어 시연용 통계를 만들고 결과를 로그로 남긴다.
     *
     * @param args 명령줄 옵션(--config, --zone)
     */
    @Override
    public void run(ApplicationArguments args) {
        // 옵션 해석은 이 클래스가, 실제 생성은 서비스가 맡는다.
        DemoGenerateResult result = demoGenerateService.generate(configFile(args), zoneIds(args));
        // 만든 구역 수·건수와, 새로 만들기 전에 지운 기존 시연용 건수를 남긴다.
        log.info(
                "시연용 통계 생성 작업 종료 구역={} 시간별={} 일별={} 먼저 지운 건수={}",
                result.zones(),
                result.hourly(),
                result.daily(),
                result.deleted().total());
    }

    /**
     * --config 옵션을 읽는다.
     *
     * @return 설정 파일 경로. 옵션이 없으면 null(기본 설정을 쓴다)
     * @throws IllegalArgumentException 옵션이 여러 번 주어졌거나 값이 비어 있을 때
     */
    // 없으면 null(기본 설정). 두 번 주면 어느 쪽인지 알 수 없어 멈춘다
    private static Path configFile(ApplicationArguments args) {
        // 옵션 이름 하나에 값이 여러 개 올 수 있어 목록으로 받는다. 옵션이 아예 없으면 null이다.
        List<String> configs = args.getOptionValues(CONFIG_OPTION);
        if (configs == null) {
            return null;
        }
        // 두 번 이상 주거나 빈 값이면 어느 파일을 쓸지 정할 수 없어 멈춘다.
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
        // --zone=1,2 와 --zone=1 --zone=2 두 가지 형태를 모두 받으므로 값 목록 각각을 쉼표로 다시 나눈다.
        List<String> values = args.getOptionValues(ZONE_OPTION);
        // 옵션이 없으면 null을 돌려준다. 서비스는 null을 "모든 구역"으로 해석한다.
        if (values == null) {
            return null;
        }
        List<Long> zoneIds = new ArrayList<>();
        for (String value : values) {
            // 쉼표로 이은 번호를 하나씩 숫자로 바꾼다. 공백은 trim으로 정리한다.
            for (String part : value.split(",")) {
                try {
                    zoneIds.add(Long.valueOf(part.trim()));
                } catch (NumberFormatException e) {
                    // 숫자가 아닌 값은 조용히 무시하지 않고 원인(원래 값)과 함께 멈춘다.
                    throw new IllegalArgumentException("--zone에는 구역 번호를 쉼표로 이어 씁니다: " + value, e);
                }
            }
        }
        return zoneIds;
    }
}
