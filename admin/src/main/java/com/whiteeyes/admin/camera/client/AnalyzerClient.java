package com.whiteeyes.admin.camera.client;

import com.whiteeyes.admin.camera.exception.AnalyzerUnavailableException;
import com.whiteeyes.admin.camera.exception.SourceNotFoundException;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 분석 서버(api/analyzer.md '분석 서버(5000)')를 부른다. 요청마다 X-Internal-Token을 붙인다.
 * 분석 서버가 응답으로 돌려준 업무 결과만 예외로 바꾸고, 판단(상태 저장 등)은 service가 한다.
 */
@Component
@Log4j2
public class AnalyzerClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    // 캠 시작: 타임아웃 5초(api/analyzer.md). 명세의 값이 하나라 연결·응답 모두 같게 둔다
    private static final Duration START_TIMEOUT = Duration.ofSeconds(5);
    // 캠 정지: 분석 서버가 종료를 최대 10초 기다리므로 15초(api/analyzer.md)
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final String SOURCE_NOT_FOUND_CODE = "SOURCE_NOT_FOUND";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RestClient startClient;
    private final RestClient stopClient;

    public AnalyzerClient(
            @Value("${analyzer.base-url}") String baseUrl, @Value("${internal-api.token}") String internalToken) {
        this.startClient = buildClient(baseUrl, internalToken, START_TIMEOUT);
        this.stopClient = buildClient(baseUrl, internalToken, STOP_TIMEOUT);
    }

    /**
     * 분석 서버에 캠 작업 시작을 지시하고 돌려준 상태(PREPARING 또는 RUNNING)를 반환한다(FNC-A-020 처리 순서 3).
     *
     * @throws SourceNotFoundException 분석 서버가 400 SOURCE_NOT_FOUND로 답했을 때
     * @throws AnalyzerUnavailableException 연결·응답 실패, 타임아웃, 그 밖의 오류 응답, 알 수 없는 상태 값
     */
    public CameraStatus startCamera(Long cameraId, CameraSourceType sourceType, String sourceUri) {
        Reply reply;
        try {
            reply = startClient
                    .post()
                    .uri("/analyzer/v1/cameras/{id}/start", cameraId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new StartRequest(sourceType, sourceUri))
                    .exchange((request, response) ->
                            new Reply(response.getStatusCode().value(), readJson(response.getBody())));
        } catch (RestClientException e) {
            log.warn("분석 서버 캠 시작 호출 실패 cameraId={}: {}", cameraId, e.getMessage());
            throw new AnalyzerUnavailableException(e);
        }

        if (reply.httpStatus() == 200) {
            return parseStartedStatus(cameraId, reply.body());
        }
        String errorCode = reply.body().path("code").asString("");
        if (reply.httpStatus() == 400 && SOURCE_NOT_FOUND_CODE.equals(errorCode)) {
            throw new SourceNotFoundException(cameraId);
        }
        log.warn("분석 서버가 캠 시작을 거부했습니다 cameraId={} http={} code={}", cameraId, reply.httpStatus(), errorCode);
        throw new AnalyzerUnavailableException();
    }

    // TODO(#14): 캠 정지(FNC-A-021)에서 오류 처리와 함께 정식으로 만든다. #15의 엇갈림 정리가 쓰는 stop 호출만 둔다
    public void stopCamera(Long cameraId) {
        stopClient
                .post()
                .uri("/analyzer/v1/cameras/{id}/stop", cameraId)
                .retrieve()
                .toBodilessEntity();
    }

    private CameraStatus parseStartedStatus(Long cameraId, JsonNode body) {
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
    private JsonNode readJson(InputStream body) throws IOException {
        try (body) {
            return jsonMapper.readTree(body);
        } catch (JacksonException e) {
            return jsonMapper.createObjectNode();
        }
    }

    private static RestClient buildClient(String baseUrl, String internalToken, Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_TOKEN_HEADER, internalToken)
                .build();
    }

    private record StartRequest(CameraSourceType sourceType, String sourceUri) {}

    private record Reply(int httpStatus, JsonNode body) {}
}
