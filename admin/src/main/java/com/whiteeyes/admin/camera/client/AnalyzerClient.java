package com.whiteeyes.admin.camera.client;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.admin.camera.exception.AnalyzerUnavailableException;
import com.whiteeyes.admin.camera.exception.CameraStoppingException;
import com.whiteeyes.admin.camera.exception.SourceNotFoundException;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;

/**
 * 분석 서버(api/analyzer.md '분석 서버(5000)')를 부른다. 요청마다 X-Internal-Token을 붙인다.
 * 분석 서버가 응답으로 돌려준 업무 결과만 예외로 바꾸고, 판단(상태 저장 등)은 service가 한다.
 */
@Slf4j
@Component
public class AnalyzerClient {

    // 서버끼리 부르는 내부 API임을 증명하는 헤더 이름. 값(토큰)은 설정(internal-api.token)에서 받는다.
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    // 캠 시작: 타임아웃 5초(api/analyzer.md). 명세의 값이 하나라 연결·응답 모두 같게 둔다
    private static final Duration START_TIMEOUT = Duration.ofSeconds(5);
    // 캠 정지: 분석 서버가 종료를 최대 10초 기다리므로 15초(api/analyzer.md)
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    // 분석 서버가 오류 응답 본문의 code 필드에 담아 보내는 업무 오류 코드. 이 둘만 별도 예외로 구분하고 나머지는 모두 '분석 서버 불가'로 본다.
    private static final String SOURCE_NOT_FOUND_CODE = "SOURCE_NOT_FOUND";
    private static final String CAMERA_STOPPING_CODE = "CAMERA_STOPPING";

    // 분석 서버 응답 본문(JSON)을 직접 읽는 데 쓰는 매퍼. 오류 응답도 본문의 code를 봐야 해서 RestClient의 자동 변환을 쓰지 않는다.
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    // 시작용과 정지용은 타임아웃이 달라(5초/15초) 클라이언트를 따로 만들어 둔다.
    private final RestClient startClient;
    private final RestClient stopClient;

    /**
     * 설정에서 분석 서버 주소와 내부 토큰을 받아 시작용·정지용 HTTP 클라이언트를 만든다.
     *
     * @param baseUrl 분석 서버 기본 주소(analyzer.base-url)
     * @param internalToken 내부 API 인증 토큰(internal-api.token)
     */
    public AnalyzerClient(
            @Value("${analyzer.base-url}") String baseUrl, @Value("${internal-api.token}") String internalToken) {
        this.startClient = buildClient(baseUrl, internalToken, START_TIMEOUT);
        this.stopClient = buildClient(baseUrl, internalToken, STOP_TIMEOUT);
    }

    /**
     * 분석 서버에 캠 작업 시작을 지시하고 돌려준 상태(PREPARING 또는 RUNNING)를 반환한다(FNC-A-020 처리 순서 3).
     *
     * @throws SourceNotFoundException 분석 서버가 400 SOURCE_NOT_FOUND로 답했을 때
     * @throws CameraStoppingException 분석 서버가 409 CAMERA_STOPPING으로 답했을 때(직전 정지가 아직 끝나지 않음)
     * @throws AnalyzerUnavailableException 연결·응답 실패, 타임아웃, 그 밖의 오류 응답, 알 수 없는 상태 값
     */
    public CameraStatus startCamera(Long cameraId, CameraSourceType sourceType, String sourceUri) {
        // 분석 서버의 응답을 (HTTP 상태, JSON 본문) 한 쌍으로 받아 둔다. 오류 상태여도 예외 없이 본문까지 읽기 위함.
        Reply reply;
        try {
            // exchange를 쓰면 4xx/5xx여도 예외 없이 응답 객체를 직접 다룰 수 있다. retrieve()는 오류 상태에서 예외를 던져 code를 읽기 어렵다.
            reply = startClient
                    .post()
                    .uri("/analyzer/v1/cameras/{id}/start", cameraId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new StartRequest(sourceType, sourceUri))
                    // 응답 스트림은 이 콜백이 끝나면 닫히므로, 콜백 안에서 상태 코드와 본문을 모두 읽어 Reply로 만든다.
                    .exchange((request, response) ->
                            new Reply(response.getStatusCode().value(), readJson(response.getBody())));
            // 연결 거부·타임아웃·읽기 실패 등 응답을 못 받은 경우. 분석 서버가 죽었거나 느린 것으로 보고 한 종류의 예외로 올린다.
        } catch (RestClientException e) {
            log.warn("분석 서버 캠 시작 호출 실패 cameraId={}: {}", cameraId, e.getMessage());
            throw new AnalyzerUnavailableException(e);
        }

        // 정상 응답이면 본문의 status(PREPARING/RUNNING)를 읽어 돌려준다.
        if (reply.httpStatus() == 200) {
            return parseStartedStatus(cameraId, reply.body());
        }
        // 오류 응답의 업무 코드. 본문이 비었거나 code가 없으면 빈 문자열로 두어 아래 비교가 모두 false가 되게 한다.
        String errorCode = reply.body().path("code").asString("");
        // 영상 원본(파일·주소)을 분석 서버가 못 찾은 경우: 사용자가 입력을 고쳐야 하는 문제라 별도 예외로 구분한다.
        if (reply.httpStatus() == 400 && SOURCE_NOT_FOUND_CODE.equals(errorCode)) {
            throw new SourceNotFoundException(cameraId);
        }
        // 직전 정지가 아직 끝나지 않은 경우: 잠시 뒤 다시 시도하면 되는 상태 충돌이라 별도 예외로 구분한다.
        if (reply.httpStatus() == 409 && CAMERA_STOPPING_CODE.equals(errorCode)) {
            throw new CameraStoppingException();
        }
        // 그 밖의 거부(인증 실패, 500 등)는 사용자가 고칠 수 없으므로 원인은 로그로 남기고 호출자에게는 '분석 서버 불가'로만 알린다.
        log.warn("분석 서버가 캠 시작을 거부했습니다 cameraId={} http={} code={}", cameraId, reply.httpStatus(), errorCode);
        throw new AnalyzerUnavailableException();
    }

    /** 분석 서버에 캠 정지를 지시한다(FNC-A-021, #15의 엇갈림 정리도 쓴다). 실패하면 RestClientException을 던지고 호출하는 쪽이 정한다. */
    // 정지는 응답 본문이 필요 없어 retrieve()로 오류 상태를 곧바로 예외로 받는다(호출하는 쪽 service가 실패해도 앱 쪽은 정지로 저장한다).
    public void stopCamera(Long cameraId) {
        stopClient
                .post()
                .uri("/analyzer/v1/cameras/{id}/stop", cameraId)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 시작 성공(200) 응답 본문에서 상태 값을 읽는다. 명세에 있는 PREPARING·RUNNING 외의 값은 신뢰하지 않고 오류로 처리한다.
     */
    private CameraStatus parseStartedStatus(Long cameraId, JsonNode body) {
        // 필드가 없으면 빈 문자열이 되어 아래 어느 상태와도 맞지 않고 오류로 떨어진다.
        String status = body.path("status").asString("");
        if (CameraStatus.PREPARING.name().equals(status)) {
            return CameraStatus.PREPARING;
        }
        if (CameraStatus.RUNNING.name().equals(status)) {
            return CameraStatus.RUNNING;
        }
        // 명세는 시작 응답의 상태를 PREPARING 또는 RUNNING으로만 정한다
        log.warn("분석 서버가 알 수 없는 상태로 답했습니다 cameraId={} status={}", cameraId, status);
        throw new AnalyzerUnavailableException();
    }

    // 본문이 비었거나 JSON이 아니면 빈 노드로 본다(오류 코드·상태 값을 못 읽은 것으로 처리)
    /**
     * 응답 본문 스트림을 JSON 트리로 읽는다. 본문이 비었거나 JSON이 아니어도 예외 대신 빈 객체 노드를 돌려줘,
     * 호출하는 쪽이 path(...)로 안전하게 읽을 수 있게 한다.
     */
    private JsonNode readJson(InputStream body) throws IOException {
        // 스트림은 읽고 나서 반드시 닫는다.
        try (body) {
            return jsonMapper.readTree(body);
        } catch (JacksonException e) {
            return jsonMapper.createObjectNode();
        }
    }

    /**
     * 분석 서버용 RestClient를 만든다. 모든 요청에 내부 토큰 헤더가 붙고, 연결·응답 대기 시간은 같은 값으로 제한한다.
     */
    private static RestClient buildClient(String baseUrl, String internalToken, Duration timeout) {
        // 타임아웃을 지정할 수 있는 가장 단순한 JDK HttpURLConnection 기반 요청 팩토리.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_TOKEN_HEADER, internalToken)
                .build();
    }

    // 분석 서버 시작 요청 본문. 캠 ID는 주소(path)로 가므로 본문에는 입력 방식과 영상 주소만 담는다.
    private record StartRequest(CameraSourceType sourceType, String sourceUri) {}

    // 분석 서버 응답을 HTTP 상태와 본문으로 묶은 내부용 값.
    private record Reply(int httpStatus, JsonNode body) {}
}
