/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/camera/client/AnalyzerClient.java (feature/15-cam-status-report 브랜치)
 * 관련 이슈: #15(FNC-A-025) — TODO(#13) 임시 코드
 *
 * [무엇] 관리자 앱이 분석 서버(Flask, 5000 포트)를 HTTP로 부르는 클라이언트. 지금은 '캠 정지' 하나만 있다.
 * [왜 필요한가] FNC-A-025 업무 규칙: run_requested=0인 캠이 RUNNING이라고 보고하면 무시하고 분석 서버에 정지를 지시한다
 *   (관리자는 껐는데 분석 서버는 돌고 있는 '엇갈림'을 바로잡기). 그 '정지 지시'가 이 클래스의 stopCamera다.
 * [왜 임시인가] 분석 서버를 처음 부르는 기능은 #13(캠 가동, start 호출)이다. 클라이언트의 정식 모양(타임아웃 5초, 요청 ID 전달,
 *   start 메서드)은 #13이 정한다. #15는 stop만 필요해서 최소한으로 만들고 TODO(#13)로 표시했다.
 * [이름·위치] 외부 호출은 'Client' 접미사(code-conventions 3-2), camera 도메인 안의 client/ 패키지(package-structure 3-1).
 *   client는 '다른 서버 호출'만 하고 판단은 하지 않는다. 언제 정지를 지시할지는 서비스가 정한다.
 */
package com.whiteeyes.admin.camera.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// TODO(#13): 캠 가동(FNC-A-020)에서 start 호출·타임아웃·요청 ID 전달과 함께 정식으로 만든다.
// 지금은 FNC-A-025의 엇갈림 정리에 필요한 stop 호출만 둔 임시 코드
@Component
public class AnalyzerClient {

    // [해석] 서버 간 요청을 확인하는 헤더 이름(conventions.md). 값이 다르면 분석 서버가 401을 돌려준다.
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;

    // [해석] 주소와 토큰을 코드에 박지 않고 설정(@Value)으로 받는다. 설정은 환경변수에서 온다(CLAUDE.md "키·비밀번호는 환경변수로만").
    //        서버 주소도 환경변수인 이유: conventions.md "서버 주소는 모두 환경변수(DMZ 전환 대비)".
    //        RestClient.builder()를 직접 쓴 이유: Spring Boot 4에서 RestClient.Builder 자동 설정은 별도 모듈에 있어,
    //        지금 의존성으로는 주입받을 빈이 있다고 장담할 수 없었다. #13이 타임아웃 등을 정할 때 함께 정리하면 된다.
    public AnalyzerClient(
            @Value("${analyzer.base-url}") String baseUrl, @Value("${internal-api.token}") String internalToken) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                // [해석] 모든 요청에 토큰 헤더를 기본으로 붙인다. 메서드마다 붙이다 빠뜨리는 실수를 막으려고.
                //        토큰 값은 로그에 남기지 않는다(code-conventions 7-4) — 이 클래스는 로그를 찍지 않는다.
                .defaultHeader(INTERNAL_TOKEN_HEADER, internalToken)
                .build();
    }

    // [해석] api/analyzer.md: POST /analyzer/v1/cameras/{id}/stop, 응답 200, 본문 없음, '멱등'.
    //        멱등 = 이미 멈춘 캠에 다시 보내도 결과가 같다. 그래서 엇갈림을 바로잡을 때 상태를 따지지 않고 그냥 보내도 안전하다.
    //        toBodilessEntity(): 응답 본문은 읽지 않고 상태 코드만 확인한다. 4xx/5xx나 연결 실패면 RestClientException을 던지고,
    //        그 예외를 어떻게 처리할지(무시하고 WARN)는 서비스가 정한다.
    public void stopCamera(Long cameraId) {
        restClient
                .post()
                .uri("/analyzer/v1/cameras/{id}/stop", cameraId)
                .retrieve()
                .toBodilessEntity();
    }
}
