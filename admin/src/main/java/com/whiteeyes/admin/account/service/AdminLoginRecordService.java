package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.common.security.LoginRecorder;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 로그인에 성공하면 admin_account.last_login_at을 갱신한다(FNC-C-001 처리 순서 5).
 */
@Service
public class AdminLoginRecordService implements LoginRecorder {

    // DB에는 한국 시간(KST)으로 저장한다(conventions.md '시각 형식')
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AdminAccountRepository adminAccountRepository;

    public AdminLoginRecordService(AdminAccountRepository adminAccountRepository) {
        this.adminAccountRepository = adminAccountRepository;
    }

    @Override
    @Transactional
    public void record(String loginId) {
        adminAccountRepository.findByLoginId(loginId).ifPresent(account -> account.recordLogin(LocalDateTime.now(KST)));
    }
}
