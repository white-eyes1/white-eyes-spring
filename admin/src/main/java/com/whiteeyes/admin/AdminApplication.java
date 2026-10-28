package com.whiteeyes.admin;

import java.util.Arrays;
import java.util.function.IntConsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.whiteeyes.common", "com.whiteeyes.admin"})
@EntityScan({"com.whiteeyes.common", "com.whiteeyes.admin"})
@EnableJpaRepositories({"com.whiteeyes.common", "com.whiteeyes.admin"})
public class AdminApplication {

    private static final String JOB_OPTION = "--job=";
    private static final int JOB_FAILED_EXIT_CODE = 1;

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AdminApplication.class);
        // 1회 실행 작업(--job=…)은 웹 서버(8081·8082)를 띄우지 않는다. 떠 있는 웹 앱과 포트가 겹치지 않게 한다(spring #280)
        if (isJob(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        run(application, args, System::exit);
    }

    /**
     * 앱을 시작한다. 작업 모드에서 시작(작업 실행 포함)이 예외로 실패하면 종료 코드 1로 프로세스를 끝낸다.
     * Spring은 실패를 로그로 남기고 컨텍스트를 닫지만, 남은 비데몬 스레드(spring-session 정리 등) 때문에
     * JVM이 끝나지 않아 `docker compose run` 컨테이너가 계속 떠 있었다(spring #358).
     * 웹 앱으로 뜰 때의 실패는 지금처럼 예외를 그대로 던진다.
     *
     * @param exit 종료 함수(시험에서는 바꿔 끼운다)
     */
    static void run(SpringApplication application, String[] args, IntConsumer exit) {
        try {
            application.run(args);
        } catch (RuntimeException ex) {
            if (!isJob(args)) {
                throw ex;
            }
            // 실패 원인은 Spring이 이미 로그로 남겼다
            exit.accept(JOB_FAILED_EXIT_CODE);
        }
    }

    static boolean isJob(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.startsWith(JOB_OPTION));
    }
}
