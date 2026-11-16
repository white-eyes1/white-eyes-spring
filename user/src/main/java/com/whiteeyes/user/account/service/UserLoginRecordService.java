package com.whiteeyes.user.account.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.security.LoginRecorder;

/**
 * 사용자 로그인에 성공하면 user_account.last_login_at을 갱신한다(FNC-C-001 처리 순서 5).
 * 승인 대기(PENDING) 계정의 로그인도 기록한다.
 */
@Service
@RequiredArgsConstructor
public class UserLoginRecordService implements LoginRecorder {

    // DB에는 한국 시간(KST)으로 저장한다(conventions.md '시각 형식')
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserAccountRepository userAccountRepository;

    /**
     * 로그인에 성공한 계정의 마지막 로그인 시각을 갱신한다.
     *
     * @param loginId 로그인한 아이디
     */
    @Override
    @Transactional
    public void record(String loginId) {
        // 계정이 없으면 아무것도 하지 않는다. 있으면 엔티티의 시각만 바꾸고 UPDATE는 트랜잭션 종료 시 변경 감지로 나간다.
        userAccountRepository.findByLoginId(loginId).ifPresent(account -> account.recordLogin(LocalDateTime.now(KST)));
    }
}
