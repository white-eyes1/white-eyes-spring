package com.whiteeyes.admin.camera.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// TODO(#13): 캠 가동(FNC-A-020)에서 start 호출·타임아웃·요청 ID 전달과 함께 정식으로 만든다.
// 지금은 FNC-A-025의 엇갈림 정리에 필요한 stop 호출만 둔 임시 코드
@Component
public class AnalyzerClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;

    public AnalyzerClient(
            @Value("${analyzer.base-url}") String baseUrl, @Value("${internal-api.token}") String internalToken) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_TOKEN_HEADER, internalToken)
                .build();
    }

    public void stopCamera(Long cameraId) {
        restClient
                .post()
                .uri("/analyzer/v1/cameras/{id}/stop", cameraId)
                .retrieve()
                .toBodilessEntity();
    }
}
