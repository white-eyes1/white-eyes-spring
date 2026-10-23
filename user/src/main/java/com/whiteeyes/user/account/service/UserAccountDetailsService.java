package com.whiteeyes.user.account.service;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.entity.UserAccountStatus;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.security.AccountPrincipal;
import com.whiteeyes.user.config.UserRoles;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 앱 로그인 때 user_account에서 계정을 찾는다(FNC-C-001 처리 순서 1·3·4).
 * 관리자 계정(admin_account)은 이 테이블에 없으므로 '아이디 없음'과 같이 거부된다.
 * <ul>
 *   <li>ACTIVE: ROLE_USER</li>
 *   <li>PENDING: ROLE_PENDING(승인 대기 화면만, FNC-U-003)</li>
 *   <li>WITHDRAWN: 로그인 거부. 문구는 '아이디 또는 비밀번호가 올바르지 않습니다.'(별도 문구는 DISABLED만이고
 *       user_account에는 DISABLED가 없다)</li>
 * </ul>
 */
@Service
public class UserAccountDetailsService implements UserDetailsService {

    private static final String ROLE_PREFIX = "ROLE_";

    private final UserAccountRepository userAccountRepository;

    public UserAccountDetailsService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * 탈퇴 계정은 아이디가 없는 것과 같이 다룬다. Spring Security가 '아이디 없음'을 틀린 비밀번호와 같은 실패로 바꾸고
     * 비밀번호 비교에 드는 시간도 맞춰 주므로, 탈퇴 여부가 문구나 응답 시간으로 드러나지 않는다.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String loginId) {
        UserAccount account = userAccountRepository
                .findByLoginId(loginId)
                .filter(found -> found.getStatus() != UserAccountStatus.WITHDRAWN)
                .orElseThrow(() -> new UsernameNotFoundException("로그인할 수 있는 사용자 계정이 없습니다"));
        return new AccountPrincipal(
                account.getId(),
                account.getLoginId(),
                account.getPassword(),
                true,
                account.isMustChangePassword(),
                List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role(account.getStatus()))));
    }

    private String role(UserAccountStatus status) {
        return status == UserAccountStatus.PENDING ? UserRoles.PENDING : UserRoles.USER;
    }
}
