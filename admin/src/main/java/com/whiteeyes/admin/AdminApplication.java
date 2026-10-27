package com.whiteeyes.admin;

import java.util.Arrays;

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

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AdminApplication.class);
        // 1회 실행 작업(--job=…)은 웹 서버(8081·8082)를 띄우지 않는다. 떠 있는 웹 앱과 포트가 겹치지 않게 한다(spring #280)
        if (isJob(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }

    static boolean isJob(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.startsWith(JOB_OPTION));
    }
}
