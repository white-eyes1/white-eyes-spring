package com.whiteeyes.admin.media.service;

import com.whiteeyes.common.camera.service.CameraRunningService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.stereotype.Service;

/**
 * 실시간 화면·카드 미리보기를 nginx가 열어 줘도 되는지 답한다(FNC-A-041, nginx auth_request).
 *
 * <p>이 경로는 Spring Security·세션 필터를 거치지 않고(AdminSecurityConfig) 쿠키의 세션 번호로 세션 저장소를 직접 조회한다.
 * 2초마다 오는 미리보기 확인이 세션 마지막 접근 시각과 만료(30분)를 늘리지 않도록 조회만 하고 저장하지 않는다.
 * nginx는 2xx·401·403 말고는 500으로 바꾸므로 어떤 경우에도 이 셋 중 하나로만 답한다.
 */
@Slf4j
@Service
// 웹 서버 없이 띄우는 실행(--job=… 같은 1회 작업)에는 세션 저장소가 없어 이 서비스도 필요 없다(AdminSecurityConfig와 같은 조건)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LiveAuthService {

    /** 스프링 시큐리티가 세션에 로그인 정보를 넣어 두는 속성 이름 */
    private static final String SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";

    private static final Set<String> ADMIN_AUTHORITIES = Set.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN");

    private final SessionRepository<? extends Session> sessionRepository;
    // 세션 쿠키 이름과 Base64 해석은 로그인 때 쿠키를 만든 것과 같은 설정을 쓴다
    private final CookieSerializer cookieSerializer;
    private final CameraRunningService cameraRunningService;

    public LiveAuthService(
            SessionRepository<? extends Session> sessionRepository,
            CookieSerializer cookieSerializer,
            CameraRunningService cameraRunningService) {
        this.sessionRepository = sessionRepository;
        this.cookieSerializer = cookieSerializer;
        this.cameraRunningService = cameraRunningService;
    }

    /** 확인 결과. 응답 상태는 OK 204 / UNAUTHENTICATED 401 / FORBIDDEN 403 */
    public enum Result {
        OK,
        UNAUTHENTICATED,
        FORBIDDEN
    }

    /**
     * @param cameraIdText 쿼리의 cameraId 원문(숫자가 아니거나 없으면 가동 중인 캠이 아니므로 FORBIDDEN)
     */
    public Result check(HttpServletRequest request, String cameraIdText) {
        try {
            // 처리 순서 0: 쿠키의 세션을 저장소에서 직접 조회. 없거나 만료면 401
            Authentication authentication = findAuthentication(request);
            if (authentication == null) {
                return Result.UNAUTHENTICATED;
            }
            // 처리 순서 1: 역할이 ADMIN 이상인지
            if (!hasAdminRole(authentication)) {
                return Result.FORBIDDEN;
            }
            // 처리 순서 2: 캠이 있고 가동 중(RUNNING)인지
            Long cameraId = parseCameraId(cameraIdText);
            return cameraId != null && cameraRunningService.isRunning(cameraId) ? Result.OK : Result.FORBIDDEN;
        } catch (RuntimeException e) {
            // 404·5xx는 nginx가 500으로 바꾸므로 확인할 수 없으면 로그인하지 않은 것으로 답한다
            log.warn("실시간 화면 권한 확인 중 오류가 나 401로 답합니다.", e);
            return Result.UNAUTHENTICATED;
        }
    }

    private Authentication findAuthentication(HttpServletRequest request) {
        List<String> sessionIds = cookieSerializer.readCookieValues(request);
        for (String sessionId : sessionIds) {
            // findById는 만료된 세션이면 null을 돌려준다. 접근 시각을 고치거나 저장하는 일은 하지 않는다
            Session session = sessionRepository.findById(sessionId);
            if (session == null) {
                continue;
            }
            Object context = session.getAttribute(SECURITY_CONTEXT);
            if (context instanceof SecurityContext securityContext
                    && securityContext.getAuthentication() != null
                    && securityContext.getAuthentication().isAuthenticated()) {
                return securityContext.getAuthentication();
            }
        }
        return null;
    }

    private boolean hasAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITIES::contains);
    }

    private Long parseCameraId(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
