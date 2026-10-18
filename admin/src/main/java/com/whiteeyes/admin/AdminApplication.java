package com.whiteeyes.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.whiteeyes.common", "com.whiteeyes.admin"})
@EntityScan({"com.whiteeyes.common", "com.whiteeyes.admin"})
@EnableJpaRepositories({"com.whiteeyes.common", "com.whiteeyes.admin"})
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
