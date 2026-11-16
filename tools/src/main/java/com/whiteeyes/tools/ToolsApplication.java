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

    // 명령 이름을 스프링 속성으로 넘길 때 쓰는 키. 각 JobRunner가 @ConditionalOnProperty로 "자기 명령일 때만" 등록되게 하는 데 쓴다.
    public static final String COMMAND_PROPERTY = "tools.command";
    // 실패 종료 코드. 셸·CI가 종료 코드로 성공/실패를 구분할 수 있다.
    private static final int FAILED = 1;

    /**
     * 명령줄 진입점. 실행 결과(종료 코드)를 그대로 프로세스 종료 코드로 쓴다.
     */
    public static void main(String[] args) {
        // 스프링 컨텍스트가 닫힌 뒤에도 남는 스레드가 있어도 프로세스가 끝나도록 System.exit로 명시적으로 끝낸다.
        System.exit(run(args, code -> {}));
    }

    /**
     * 명령을 실행하고 종료 코드를 돌려준다. main과 분리해 두어 테스트에서 System.exit 없이 부를 수 있다.
     *
     * @param args 첫 값이 명령(generate-demo, delete-demo, region-sql), 나머지는 그 명령의 옵션
     * @param onExit 종료 코드를 받는 콜백 자리. 현재 본문에서는 쓰이지 않는다(main은 아무것도 하지 않는 콜백을 넘긴다)
     * @return 0은 성공, 1은 실패
     */
    static int run(String[] args, IntConsumer onExit) {
        // 명령이 없거나 첫 인자가 옵션(--...)이면 어떤 일을 할지 알 수 없으므로 사용법을 알리고 실패로 끝낸다.
        if (args.length == 0 || args[0].startsWith("--")) {
            System.err.println("사용법: <명령> [옵션]  명령: generate-demo, delete-demo, region-sql");
            return FAILED;
        }
        // 첫 인자는 명령, 나머지는 명령에 넘길 옵션이다.
        String command = args[0];
        String[] options = Arrays.copyOfRange(args, 1, args.length);
        // region-sql은 DB에 접속하지 않으므로 스프링 컨텍스트를 띄우지 않고 바로 실행한다.
        if (RegionSqlCommand.NAME.equals(command)) {
            return RegionSqlCommand.run(options);
        }
        // DB를 쓰는 명령(generate-demo, delete-demo)은 스프링으로 실행한다.
        SpringApplication application = new SpringApplication(ToolsApplication.class);
        // 웹 서버가 필요 없는 일회성 작업이므로 웹 서버를 띄우지 않는다.
        application.setWebApplicationType(WebApplicationType.NONE);
        // 명령 이름을 속성으로 넘겨 해당 명령의 JobRunner 빈만 등록되게 한다.
        application.setDefaultProperties(Map.of(COMMAND_PROPERTY, command));
        // 컨텍스트를 실행하면 등록된 ApplicationRunner가 작업을 수행한다. try-with-resources라 끝나면 컨텍스트가 닫힌다.
        try (ConfigurableApplicationContext context = application.run(options)) {
            // 등록된 러너가 없다는 것은 모르는 명령이거나 local·dev 프로필이 아니라서 JobRunner가 만들어지지 않았다는 뜻이다.
            if (context.getBeansOfType(ApplicationRunner.class).isEmpty()) {
                System.err.println("실행할 수 없는 명령입니다: " + command + " (시연용 명령은 local·dev 프로필에서만 실행된다)");
                return FAILED;
            }
            // 러너가 예외 없이 끝났으면 정상 종료 코드(0)를 돌려준다.
            return SpringApplication.exit(context);
        } catch (RuntimeException e) {
            // 실패 원인은 Spring이 이미 로그로 남겼다
            return FAILED;
        }
    }
}
