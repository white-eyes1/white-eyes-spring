package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminStatus;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.common.security.AccountPrincipal;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 앱 로그인 때 admin_account에서 계정을 찾는다(FNC-C-001 처리 순서 1·4).
 * 사용자 앱의 계정(user_account)은 이 테이블에 없으므로 '아이디 없음'과 같이 거부된다.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AdminAccountRepository adminAccountRepository;

    public AdminUserDetailsService(AdminAccountRepository adminAccountRepository) {
        this.adminAccountRepository = adminAccountRepository;
    }

    /**
     * 상태가 ACTIVE가 아니면 enabled=false로 돌려준다. 비활성화 여부는 비밀번호를 확인한 뒤에 판정한다
     * (AdminSecurityConfig). 아이디 존재를 비밀번호 없이 알아내지 못하게 하려는 것이다.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String loginId) {
        AdminAccount account = adminAccountRepository
                .findByLoginId(loginId)
                .orElseThrow(() -> new UsernameNotFoundException("관리자 계정이 없습니다"));
        return new AccountPrincipal(
                account.getLoginId(),
                account.getPassword(),
                account.getStatus() == AdminStatus.ACTIVE,
                account.isMustChangePassword(),
                List.of(new SimpleGrantedAuthority(ROLE_PREFIX + account.getRole().name())));
    }
}
