package com.whiteeyes.common.level.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.whiteeyes.common.level.dto.LevelResponse;

/**
 * 혼잡 단계 판정(FNC-C-025). 지도 색(FNC-C-009)·현재 현황(FNC-C-016)·혼잡 이벤트(FNC-A-033)가 모두 이 기준을 쓴다.
 *
 * <p>사용 예: {@code levelService.judgeLevel(LevelTarget.zone(zoneId), stat.getDensity()).getLevel()}.
 * 이벤트에 당시 기준을 남길 때는 {@code findThresholds(LevelTarget.zone(zoneId))}를 threshold_snapshot으로 저장한다.
 */
@Service
public class LevelService {

    // 전체 공통 단계 경계. 구역별로 다르게 하는 기능은 아직 없다
    private final LevelThresholds commonThresholds;

    // 테스트 application.yml이 main 설정을 가리고 두 앱이 함께 쓰는 코드라, yml을 거치지 않고 환경변수를 바로 읽는다(기본값 conventions.md)
    public LevelService(
            @Value("${THRESHOLD_WARN_DENSITY:0.7}") BigDecimal warnDensity,
            @Value("${THRESHOLD_DANGER_DENSITY:1.5}") BigDecimal dangerDensity) {
        // 잘못된 기준값이면 앱이 뜨는 시점에 바로 실패시킨다
        validateThresholds(warnDensity, dangerDensity);
        // 검증을 통과한 값을 불변 값 객체로 보관한다
        this.commonThresholds = new LevelThresholds(warnDensity, dangerDensity);
    }

    /**
     * 대상에 적용할 단계 경계 밀도를 돌려준다(FNC-C-025 처리 순서 2).
     */
    // 지금은 구역·지역 모두 같은 공통 기준이라 target을 쓰지 않는다. 구역별 기준이 생길 때를 대비해 인자를 미리 받아 둔다
    public LevelThresholds findThresholds(LevelTarget target) {
        // 구역별 기준 조정(backlog-p2 8번)이 들어오면 ZONE일 때 구역 값을 찾는다. 지역은 계속 공통 기준
        return commonThresholds;
    }

    /**
     * 대상의 밀집도를 단계로 바꾼다. 경계값과 같은 밀집도는 높은 단계.
     */
    public CongestionLevel judgeLevel(LevelTarget target, BigDecimal density) {
        // 대상의 기준을 찾아 그 기준으로 단계를 판정한다
        return findThresholds(target).levelOf(density);
    }

    /**
     * 단계 이름·색·시작 밀집도(GET /api/v1/meta/levels). 화면 범례·혼잡 기준 표시는 공통 기준을 보여준다.
     */
    public List<LevelResponse> findLevelCriteria() {
        // 모든 단계를 순서대로 훑어 각 단계의 이름·색·시작 밀집도를 응답 형태로 만든다
        return Arrays.stream(CongestionLevel.values())
                .map(level -> new LevelResponse(
                        level.getLevel(),
                        level.getDisplayName(),
                        level.getColor(),
                        commonThresholds.minDensityOf(level)))
                .toList();
    }

    // 순서가 뒤집히면 오류 없이 지도 색·이벤트가 틀리므로, 기준을 바꾸는 유일한 방법인 재배포 때 바로 드러나게 시작을 막는다
    private static void validateThresholds(BigDecimal warnDensity, BigDecimal dangerDensity) {
        // '주의' 시작 밀집도는 0보다 커야 한다(0 이하면 모든 곳이 주의가 된다)
        if (warnDensity.signum() <= 0) {
            throw new IllegalStateException("THRESHOLD_WARN_DENSITY(" + warnDensity + ")는 0보다 커야 합니다");
        }
        // '주의' 경계는 '위험' 경계보다 작아야 단계가 순서대로 나뉜다. BigDecimal은 equals가 아니라 compareTo로 비교한다
        if (warnDensity.compareTo(dangerDensity) >= 0) {
            throw new IllegalStateException("THRESHOLD_WARN_DENSITY(" + warnDensity + ")는 THRESHOLD_DANGER_DENSITY("
                    + dangerDensity + ")보다 작아야 합니다");
        }
    }
}
