package com.whiteeyes.admin.account.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.common.security.LoginRecorder;

/**
 * 관리자 로그인에 성공하면 admin_account.last_login_at을 갱신한다(FNC-C-001 처리 순서 5).
 */
@Service
@RequiredArgsConstructor
public class AdminLoginRecordService implements LoginRecorder {

    // DB에는 한국 시간(KST)으로 저장한다(conventions.md '시각 형식')
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AdminAccountRepository adminAccountRepository;

    /** 로그인 성공 시각(KST)을 기록한다. 아이디에 해당하는 계정이 없으면 아무것도 하지 않는다. */
    @Override
    @Transactional
    public void record(String loginId) {
        // 엔티티의 값만 바꾸면 트랜잭션이 끝날 때 JPA가 UPDATE한다(save 호출이 필요 없다)
        adminAccountRepository.findByLoginId(loginId).ifPresent(account -> account.recordLogin(LocalDateTime.now(KST)));
    }
}
