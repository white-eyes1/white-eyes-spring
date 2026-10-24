package com.whiteeyes.admin.media.service;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.exception.RecordingExpiredException;
import com.whiteeyes.admin.media.exception.RecordingNotFoundException;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

/**
 * 녹화 영상·좌표 파일을 nginx가 보내도록 내부 주소를 정해 준다(FNC-A-044). 파일은 앱이 읽지 않고,
 * 권한과 녹화 존재·보관 기간만 확인한 뒤 X-Accel-Redirect로 nginx에 넘긴다.
 */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
@RequiredArgsConstructor
public class RecordingFileService {

    /** nginx의 internal location(conventions.md 'nginx 경로 규칙'). 외부에서 직접 요청하면 404다 */
    static final String PROTECTED_PREFIX = "/protected/media/";

    private final RecordingRepository recordingRepository;
    private final RetentionProperties retentionProperties;

    /**
     * 영상 파일의 X-Accel-Redirect 값을 돌려준다.
     *
     * @param now 지금 시각(KST). 보관 기간 판정에 쓴다
     * @throws RecordingNotFoundException 녹화가 없으면(404)
     * @throws RecordingExpiredException 보관 기간 30일을 지났으면(404)
     */
    @Transactional(readOnly = true)
    public String videoRedirect(Long recordingId, LocalDateTime now) {
        return PROTECTED_PREFIX + encodePath(findRetained(recordingId, now).getVideoPath());
    }

    /** 좌표 파일의 X-Accel-Redirect 값을 돌려준다. 예외는 {@link #videoRedirect}와 같다. */
    @Transactional(readOnly = true)
    public String boxesRedirect(Long recordingId, LocalDateTime now) {
        return PROTECTED_PREFIX + encodePath(findRetained(recordingId, now).getBoxPath());
    }

    private Recording findRetained(Long recordingId, LocalDateTime now) {
        Recording recording = recordingRepository.findById(recordingId).orElseThrow(RecordingNotFoundException::new);
        // 보관 기간(RECORDING_RETENTION_DAYS)이 지난 행은 매일 정리되지만 정리 전에도 보내지 않는다
        if (recording.getStartedAt().isBefore(now.minusDays(retentionProperties.recordingDays()))) {
            throw new RecordingExpiredException();
        }
        return recording;
    }

    // 헤더 값은 ASCII여야 하고 nginx가 주소를 해석하므로 구분자 '/'를 뺀 나머지를 퍼센트 인코딩한다
    private String encodePath(String path) {
        return UriUtils.encodePath(path, StandardCharsets.UTF_8);
    }
}
