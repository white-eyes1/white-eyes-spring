package com.whiteeyes.common.account.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.account.dto.PasswordChangeRequest;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;

/**
 * 비밀번호 변경(FNC-C-007). 현재 비밀번호를 확인하고 새 비밀번호로 바꾼 뒤, 이 계정의 다른 세션을 지우고
 * 현재 세션의 로그인 정보에서 must_change_password를 끈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    // bcrypt 비교·암호화 도구
    private final PasswordEncoder passwordEncoder;
    // 사용자 앱·관리자 앱이 각자 구현한 비밀번호 읽기·쓰기 입구
    private final AccountPasswordStore accountPasswordStore;
    // 세션 저장소에서 "계정 이름으로 세션 찾기"가 가능한 형태. 같은 계정의 다른 세션을 끊는 데 쓴다
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    // 현재 로그인한 계정을 세션에서 꺼내는 도구
    private final LoginAccountResolver loginAccountResolver;

    /**
     * 로그인한 계정의 비밀번호를 바꾼다. 현재 스레드의 로그인 정보도 새로 만들어 넣으므로,
     * 호출한 쪽은 {@code SecurityContextHolder}의 값을 세션에 다시 저장해야 한다.
     *
     * @param currentSessionId 지금 요청의 세션 번호. 이 세션만 남기고 같은 계정의 나머지 세션을 지운다
     * @throws BusinessException 로그인하지 않았으면 401, 현재 비밀번호가 틀리면 400 WRONG_PASSWORD
     */
    @Transactional
    public void change(PasswordChangeRequest request, String currentSessionId) {
        // 로그인하지 않았으면 여기서 401 예외가 난다
        LoginAccount account = loginAccountResolver.require();
        // 저장된 해시를 읽는다. 계정 행이 없으면 인증이 무효이므로 401
        String storedPassword = accountPasswordStore
                .findEncodedPassword(account.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        // 평문 현재 비밀번호를 해시와 비교한다. 틀리면 변경하지 않는다
        if (!passwordEncoder.matches(request.currentPassword(), storedPassword)) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }

        // 새 비밀번호를 암호화해 저장하고 변경 강제 표시를 끈다
        accountPasswordStore.changePassword(account.accountId(), passwordEncoder.encode(request.newPassword()));
        // 비밀번호가 바뀌었으므로 다른 기기에 남은 세션(탈취됐을 수 있음)을 끊는다
        int ended = deleteOtherSessions(account.loginId(), currentSessionId);
        // 현재 세션의 로그인 정보에서 must_change_password를 끄지 않으면 필터가 계속 변경 화면으로 보낸다
        refreshLoginInfo();
        log.info("비밀번호 변경 accountId={} 끊은 세션={}", account.accountId(), ended);
    }

    // 세션 주인 이름(PRINCIPAL_NAME)은 그 앱 계정 테이블의 login_id다(conventions.md 세션·로그인)
    /** 같은 계정의 세션 중 현재 세션을 뺀 나머지를 지우고, 지운 개수를 돌려준다. */
    private int deleteOtherSessions(String loginId, String currentSessionId) {
        int deleted = 0;
        for (String sessionId : sessionRepository.findByPrincipalName(loginId).keySet()) {
            // 지금 요청의 세션은 남겨야 사용자가 다시 로그인하지 않는다
            if (!sessionId.equals(currentSessionId)) {
                sessionRepository.deleteById(sessionId);
                deleted++;
            }
        }
        return deleted;
    }

    /** 현재 스레드의 인증 정보를 "비밀번호 변경 완료" 상태의 새 주체로 바꾼다. 세션에 저장하는 일은 호출한 컨트롤러가 한다. */
    private void refreshLoginInfo() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        // 주체는 불변이라 must_change_password만 꺼진 새 객체를 받아 온다
        AccountPrincipal refreshed = ((AccountPrincipal) current.getPrincipal()).passwordChanged();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(refreshed, null, current.getAuthorities()));
    }
}
