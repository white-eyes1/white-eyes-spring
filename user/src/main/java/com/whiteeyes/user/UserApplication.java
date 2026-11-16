package com.whiteeyes.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 사용자 앱(user)의 시작점. 회원가입·승인 대기·통계 조회 등 일반 사용자용 화면과 API를 담는다.
 * 공통 모듈(common)의 엔티티·보안·서비스를 함께 쓰므로, 빈 스캔·JPA 엔티티 스캔·JPA 리포지토리 스캔 범위에
 * user 패키지뿐 아니라 common 패키지도 넣는다(기본값은 이 클래스의 패키지만 훑는다).
 */
@SpringBootApplication(scanBasePackages = {"com.whiteeyes.common", "com.whiteeyes.user"})
@EntityScan({"com.whiteeyes.common", "com.whiteeyes.user"})
@EnableJpaRepositories({"com.whiteeyes.common", "com.whiteeyes.user"})
public class UserApplication {

    public static void main(String[] args) {
        // 스프링 컨텍스트를 만들고 내장 웹 서버를 띄운다.
        SpringApplication.run(UserApplication.class, args);
    }
}
