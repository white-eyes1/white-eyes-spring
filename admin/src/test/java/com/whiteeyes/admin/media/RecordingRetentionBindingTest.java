package com.whiteeyes.admin.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.media.exception.RecordingExpiredException;
import com.whiteeyes.admin.media.exception.RecordingNotFoundException;
import com.whiteeyes.admin.media.service.PlaybackFindService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 설정(retention.recording-days)이 실제 스프링 빈에 연결되어 다시보기 만료 경계를 바꾼다(#182). */
@SpringBootTest(properties = "retention.recording-days=7")
class RecordingRetentionBindingTest {

    private static final long NO_SUCH_ZONE = 999_999_999L;

    @Autowired
    private RetentionProperties retentionProperties;

    @Autowired
    private PlaybackFindService playbackFindService;

    @Test
    @DisplayName("#182 retention.recording-days=7이면 다시보기 찾기가 8일 전 시각은 만료로 본다")
    void configuredDaysReachService() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);

        assertThat(retentionProperties.recordingDays()).isEqualTo(7);
        assertThatThrownBy(() -> playbackFindService.findAt(NO_SUCH_ZONE, now.minusDays(8), now, ""))
                .isInstanceOf(RecordingExpiredException.class);
        // 경계 안쪽은 만료가 아니라, 캠이 없는 구역이라 '녹화 없음'이 된다
        assertThatThrownBy(() -> playbackFindService.findAt(NO_SUCH_ZONE, now.minusDays(6), now, ""))
                .isInstanceOf(RecordingNotFoundException.class);
    }
}
