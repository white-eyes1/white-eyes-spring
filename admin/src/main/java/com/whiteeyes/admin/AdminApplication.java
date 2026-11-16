package com.whiteeyes.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 관리자 앱(admin 모듈)의 시작 클래스. 스프링 부트가 이 클래스를 기준으로 앱을 띄운다.
 *
 * <p>공통 모듈(common)의 엔티티·저장소·서비스를 함께 쓰므로, 컴포넌트 스캔·엔티티 스캔·JPA 저장소 스캔
 * 세 곳 모두에 common과 admin 패키지를 나란히 지정한다. 기본값(이 클래스의 패키지 하위만 스캔)을 쓰면
 * com.whiteeyes.common 아래의 빈과 엔티티를 못 찾는다.
 */
@SpringBootApplication(scanBasePackages = {"com.whiteeyes.common", "com.whiteeyes.admin"})
// JPA 엔티티(@Entity)를 찾을 패키지. common의 Camera 등과 admin의 Recording 등을 모두 포함한다.
@EntityScan({"com.whiteeyes.common", "com.whiteeyes.admin"})
// Spring Data JPA 저장소 인터페이스를 찾을 패키지. 엔티티 스캔과 같은 범위로 맞춘다.
@EnableJpaRepositories({"com.whiteeyes.common", "com.whiteeyes.admin"})
public class AdminApplication {

    public static void main(String[] args) {
        // 내장 웹 서버와 스프링 컨텍스트를 띄운다.
        SpringApplication.run(AdminApplication.class, args);
    }
}
