package com.whiteeyes.admin.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 오래된 데이터 정리(FNC-A-032) 보관 일수 검증 */
class RetentionPropertiesTest {

    @ParameterizedTest
    @CsvSource({"0,30", "-1,30", "90,0", "90,-5"})
    @DisplayName("FNC-A-032 보관 일수가 1 미만이면 예외")
    void rejectsLessThanOneDay(int statMinuteDays, int recordingDays) {
        assertThatThrownBy(() -> new RetentionProperties(statMinuteDays, recordingDays))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FNC-A-032 보관 일수 1은 허용")
    void acceptsOneDay() {
        assertThat(new RetentionProperties(1, 1).statMinuteDays()).isEqualTo(1);
    }
}
