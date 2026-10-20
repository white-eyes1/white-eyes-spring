/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/level/service/LevelService.java
 * 관련 이슈: #19(FNC-C-025). 이 서비스를 쓸 이슈: #20(지도 색), #21(현재 현황), #38(혼잡 이벤트)
 *
 * [무엇] 혼잡 단계 판정의 본체. 명세 처리 순서와 이 클래스의 대응:
 *   1. 설정값 THRESHOLD_WARN_DENSITY / THRESHOLD_DANGER_DENSITY를 읽어 단계 계산 → 생성자, judgeLevel
 *   2. '구역(또는 지역)을 받아 적용 기준을 돌려주는' 함수 하나 → findThresholds
 *   3. GET /api/v1/meta/levels로 단계 이름·색·경계값 제공 → findLevelCriteria(컨트롤러가 부름)
 * [왜 common에 있나] 지도·대시보드는 두 앱에 모두 있고, 이벤트는 관리자 앱에 있다. 모두가 같은 기준을 써야
 *   "지도 색, 대시보드의 혼잡 정도, 이벤트가 항상 맞는다"(overview.md '이벤트 > 기준'). 그래서 한 곳(common level/)에 둔다.
 * [기준을 화면에서 바꾸지 않는다] 업무 규칙. 기준은 환경변수로만 바꾸고 재배포한다. 그래서 값을 앱이 시작할 때
 *   한 번 읽어 두고 계속 쓴다(DB 조회나 캐시가 필요 없다).
 */
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
// [해석] 위 JavaDoc의 사용 예는 다른 이슈 담당자를 위한 것이다(code-conventions 3-5: common의 public 클래스에는 JavaDoc).
@Service
public class LevelService {

    // [해석] 지금은 기준이 하나뿐이라 필드 하나에 들고 있다. 이름을 'common'이라 붙인 이유: 2순위에서 구역별 기준이
    //        생기면 이것은 '공통 기준'이 되고, 구역별 값이 없을 때 돌려줄 기본값이 된다.
    private final LevelThresholds commonThresholds;

    // 테스트 application.yml이 main 설정을 가리고 두 앱이 함께 쓰는 코드라, yml을 거치지 않고 환경변수를 바로 읽는다(기본값 conventions.md)
    // [해석] 사용자와 합의한 결정 2. #18의 MINUTE_CLOSE_GRACE_SEC와 같은 방식이다.
    //        "${THRESHOLD_WARN_DENSITY:0.7}" = 환경변수가 있으면 그 값, 없으면 0.7.
    //        BigDecimal로 바로 받으면 Spring이 문자열 "0.7"을 new BigDecimal("0.7")처럼 정확한 값으로 바꿔 준다.
    //        숫자가 아닌 값이 들어오면 여기서 변환에 실패해 앱이 시작하지 않는다.
    public LevelService(
            @Value("${THRESHOLD_WARN_DENSITY:0.7}") BigDecimal warnDensity,
            @Value("${THRESHOLD_DANGER_DENSITY:1.5}") BigDecimal dangerDensity) {
        validateThresholds(warnDensity, dangerDensity);
        this.commonThresholds = new LevelThresholds(warnDensity, dangerDensity);
    }

    /**
     * 대상에 적용할 단계 경계 밀도를 돌려준다(FNC-C-025 처리 순서 2).
     */
    // [해석] 사용자와 합의한 결정 1. target을 지금은 쓰지 않지만, 2순위 기능이 들어올 때 이 메서드 안만 고치면 되도록
    //        모든 호출이 이 한 곳을 거치게 한다(backlog-p2.md 8번 '지금 지켜둘 것').
    public LevelThresholds findThresholds(LevelTarget target) {
        // 구역별 기준 조정(backlog-p2 8번)이 들어오면 ZONE일 때 구역 값을 찾는다. 지역은 계속 공통 기준
        return commonThresholds;
    }

    /**
     * 대상의 밀집도를 단계로 바꾼다. 경계값과 같은 밀집도는 높은 단계.
     */
    // [해석] commonThresholds를 바로 쓰지 않고 findThresholds를 거친다. 그래야 2순위 기능이 들어왔을 때
    //        판정도 자동으로 구역별 기준을 따른다. 돌려주는 값은 enum이라 부르는 쪽이 .getLevel()로 숫자를,
    //        .getColor()로 색을 꺼낼 수 있다.
    public CongestionLevel judgeLevel(LevelTarget target, BigDecimal density) {
        return findThresholds(target).levelOf(density);
    }

    /**
     * 단계 이름·색·시작 밀집도(GET /api/v1/meta/levels). 화면 범례·혼잡 기준 표시는 공통 기준을 보여준다.
     */
    // [해석] enum의 세 단계를 순서대로(정상 → 주의 → 위험) 응답 DTO로 바꾼다. 엔티티나 enum을 그대로 내보내지 않고
    //        DTO로 바꾸는 것은 service의 일이다(package-structure.md 3-3).
    //        특정 구역이 아니라 공통 기준을 보여주는 이유: 범례는 지도 전체에 하나뿐이다.
    public List<LevelResponse> findLevelCriteria() {
        return Arrays.stream(CongestionLevel.values())
                .map(level -> new LevelResponse(
                        level.getLevel(),
                        level.getDisplayName(),
                        level.getColor(),
                        commonThresholds.minDensityOf(level)))
                .toList();
    }

    // 순서가 뒤집히면 오류 없이 지도 색·이벤트가 틀리므로, 기준을 바꾸는 유일한 방법인 재배포 때 바로 드러나게 시작을 막는다
    // [해석] 사용자와 합의한 결정 2(명세에 없어 PR에 '검토 필요'로 적음). 조건은 0 < 주의 < 위험.
    //        - 주의가 0 이하면 사람이 한 명도 없어도(밀집도 0) '주의'가 된다.
    //        - 주의 ≥ 위험이면 levelOf가 위험부터 검사하므로 '주의' 단계가 사실상 사라진다.
    //        위험 기준의 양수 검사는 따로 하지 않는다. 주의 > 0이고 위험 > 주의면 위험도 저절로 양수다.
    //        생성자에서 예외를 던지면 Spring이 이 빈을 만들지 못해 앱 시작이 실패하고, 이 메시지가 로그에 남는다.
    //        static: 객체의 상태를 쓰지 않는 검사라서.
    private static void validateThresholds(BigDecimal warnDensity, BigDecimal dangerDensity) {
        if (warnDensity.signum() <= 0) {
            throw new IllegalStateException("THRESHOLD_WARN_DENSITY(" + warnDensity + ")는 0보다 커야 합니다");
        }
        if (warnDensity.compareTo(dangerDensity) >= 0) {
            throw new IllegalStateException("THRESHOLD_WARN_DENSITY(" + warnDensity
                    + ")는 THRESHOLD_DANGER_DENSITY(" + dangerDensity + ")보다 작아야 합니다");
        }
    }
}
