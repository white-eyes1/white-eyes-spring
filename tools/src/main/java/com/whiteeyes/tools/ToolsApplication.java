package com.whiteeyes.tools;

import java.util.Arrays;
import java.util.Map;
import java.util.function.IntConsumer;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.whiteeyes.tools.region.RegionSqlCommand;

/**
 * 운영 앱에 넣지 않는 보조 도구(decisions/0020). 첫 인자가 명령이다.
 * <ul>
 *   <li>generate-demo, delete-demo: 시연용 통계 생성·삭제(FNC-A-051·052). DB에 접속하고 local·dev 프로필에서만 실행된다</li>
 *   <li>region-sql: 행정구역 경계 파일을 Flyway SQL로 바꾼다(FNC-A-012). DB에 접속하지 않는다</li>
 * </ul>
 * DB를 쓰는 명령은 웹 서버·예약 작업 없이 스프링으로 뜨고, 명령이 끝나면 프로세스를 끝낸다. 실패하면 종료 코드 1이다.
 */
@SpringBootApplication
@EntityScan("com.whiteeyes.common")
@EnableJpaRepositories("com.whiteeyes.common")
public class ToolsApplication {

    public static final String COMMAND_PROPERTY = "tools.command";
    private static final int FAILED = 1;

    public static void main(String[] args) {
        System.exit(run(args, code -> {}));
    }

    static int run(String[] args, IntConsumer onExit) {
        if (args.length == 0 || args[0].startsWith("--")) {
            System.err.println("사용법: <명령> [옵션]  명령: generate-demo, delete-demo, region-sql");
            return FAILED;
        }
        String command = args[0];
        String[] options = Arrays.copyOfRange(args, 1, args.length);
        if (RegionSqlCommand.NAME.equals(command)) {
            return RegionSqlCommand.run(options);
        }
        SpringApplication application = new SpringApplication(ToolsApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of(COMMAND_PROPERTY, command));
        try (ConfigurableApplicationContext context = application.run(options)) {
            if (context.getBeansOfType(ApplicationRunner.class).isEmpty()) {
                System.err.println("실행할 수 없는 명령입니다: " + command + " (시연용 명령은 local·dev 프로필에서만 실행된다)");
                return FAILED;
            }
            return SpringApplication.exit(context);
        } catch (RuntimeException e) {
            // 실패 원인은 Spring이 이미 로그로 남겼다
            return FAILED;
        }
    }
}
