package com.whiteeyes.admin.event.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.whiteeyes.admin.event.service.CamFaultEventService;

/** 캠 이상 감지 예약 작업(FNC-A-035) */
@ExtendWith(MockitoExtension.class)
class CamFaultSchedulerTest {

    @Mock
    private CamFaultEventService camFaultEventService;

    @InjectMocks
    private CamFaultScheduler camFaultScheduler;

    @Test
    @DisplayName("FNC-A-035 예약 작업은 캠 이상 감지 서비스를 한 번 부른다")
    void callsDetect() {
        camFaultScheduler.detect();

        verify(camFaultEventService).detect(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("FNC-A-035 감지 중 예외가 나도 밖으로 던지지 않아 다음 주기가 계속된다")
    void absorbsRuntimeException() {
        willThrow(new IllegalStateException("db down")).given(camFaultEventService).detect(any(LocalDateTime.class));

        assertThatCode(camFaultScheduler::detect).doesNotThrowAnyException();
        assertThatCode(camFaultScheduler::detect).doesNotThrowAnyException();

        verify(camFaultEventService, times(2)).detect(any(LocalDateTime.class));
    }
}
