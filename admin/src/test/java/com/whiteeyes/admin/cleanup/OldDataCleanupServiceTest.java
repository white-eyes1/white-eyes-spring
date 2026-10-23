package com.whiteeyes.admin.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.cleanup.service.OldDataCleanupService;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 오래된 데이터 정리(FNC-A-032) — 한쪽이 실패해도 다른 쪽은 계속한다 */
class OldDataCleanupServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 3, 12, 30);

    private final ZoneStatMinuteRepository statRepository = mock(ZoneStatMinuteRepository.class);
    private final RecordingRepository recordingRepository = mock(RecordingRepository.class);
    private final OldDataCleanupService service =
            new OldDataCleanupService(statRepository, recordingRepository, new RetentionProperties(90, 30));

    private final List<String> logs = new ArrayList<>();
    private AbstractAppender appender;
    private org.apache.logging.log4j.core.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(OldDataCleanupService.class);
        appender = new AbstractAppender("cleanupTest", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                logs.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);
        // 단위 시험은 기본 설정(ERROR)으로 뜨므로 INFO 로그도 받도록 올린다
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        logger.setLevel(originalLevel);
        logger.removeAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("FNC-A-032 1분 통계 삭제가 실패해도 녹화 기록 삭제는 계속하고 실패를 로그에 남김")
    void recordingCleanupContinuesWhenStatDeleteFails() {
        when(statRepository.deleteOlderThan(any(), anyInt())).thenThrow(new IllegalStateException("통계 삭제 오류"));
        when(recordingRepository.deleteStartedBefore(any(), anyInt())).thenReturn(3);

        service.cleanUp(NOW);

        verify(recordingRepository).deleteStartedBefore(LocalDateTime.of(2098, 12, 4, 12, 30), OldDataCleanupService.BATCH_SIZE);
        assertThat(logs).anyMatch(l -> l.startsWith("ERROR 오래된 1분 통계 삭제 실패"));
        assertThat(logs).anyMatch(l -> l.startsWith("INFO 오래된 녹화 기록 삭제 3건"));
    }

    @Test
    @DisplayName("FNC-A-032 녹화 기록 삭제가 실패해도 1분 통계 삭제는 이미 끝나 있고 실패를 로그에 남김")
    void statCleanupDoneWhenRecordingDeleteFails() {
        when(statRepository.deleteOlderThan(any(), anyInt())).thenReturn(5);
        when(recordingRepository.deleteStartedBefore(any(), anyInt())).thenThrow(new IllegalStateException("녹화 삭제 오류"));

        service.cleanUp(NOW);

        verify(statRepository).deleteOlderThan(LocalDateTime.of(2098, 10, 5, 0, 0), OldDataCleanupService.BATCH_SIZE);
        assertThat(logs).anyMatch(l -> l.startsWith("INFO 오래된 1분 통계 삭제 5건"));
        assertThat(logs).anyMatch(l -> l.startsWith("ERROR 오래된 녹화 기록 삭제 실패"));
    }
}
