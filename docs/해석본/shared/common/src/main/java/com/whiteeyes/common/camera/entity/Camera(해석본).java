/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/camera/entity/Camera.java
 * 관련 이슈: #15(FNC-A-025 캠 상태 보고 수신), #16(FNC-A-024 분석 결과 수신), #17(FNC-A-026 녹화 등록 수신)
 *   — 셋 다 TODO(#11) 임시 코드
 *
 * [무엇] tables.md 'camera' 테이블을 JPA 엔티티로 옮긴 것. 단, 전체 13칼럼 중 #15·#16·#17이 실제로 쓰는 7칼럼만 매핑했다.
 * [왜 임시인가] 원래 캠 엔티티는 #11(캠 등록)에서 만든다. #11이 아직 dev에 없어서 컴파일·테스트를 위해 먼저 둔 것이다.
 * [염두에 둔 것]
 *   1. #15·#16·#17 세 브랜치의 이 파일은 한 글자도 다르지 않게 유지한다. 같은 경로에 같은 내용의 파일을 여러 브랜치가 각각
 *      추가하면 git은 충돌 없이 합친다. 하나라도 다르면 합칠 때마다 충돌을 손으로 풀어야 한다.
 *      그래서 한 이슈가 칼럼을 추가하면(#16의 zoneId, #17의 sourceType) 나머지 브랜치에도 같은 커밋을 넣었다.
 *   2. 칼럼 이름·길이는 tables.md와 똑같이 적었다. #11이 정식 엔티티를 만들 때 이 파일과 비교만 하면 되도록.
 *   3. common 모듈에 둔 이유: camera 테이블은 사용자 앱·관리자 앱이 함께 읽는 테이블이라 package-structure.md 4장에
 *      따라 common에 둔다. admin 전용 테이블(admin_account, recording)만 admin에 둔다.
 */
package com.whiteeyes.common.camera.entity;

// [해석] import 순서는 code-conventions 2-3: 표준 라이브러리(java.*) → 외부 라이브러리(jakarta, lombok) → 프로젝트 내부.
//        와일드카드(jakarta.persistence.*)를 쓰지 않는 것도 같은 규칙.
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// TODO(#11): 캠 등록(FNC-A-018)에서 tables.md 'camera'의 전체 칼럼으로 정식으로 만든다.
// 지금은 FNC-A-024·025·026에 필요한 칼럼만 둔 임시 코드(#15·#16·#17 브랜치에서 같은 내용으로 유지)
// [해석] TODO에 이슈 번호를 붙여 두면 나중에 "TODO(#11)"로 검색해 교체할 곳을 한 번에 찾을 수 있다.
@Entity
@Table(name = "camera")
// [해석] @Getter: 다른 클래스가 값을 읽을 수 있게 getter만 만든다. setter는 만들지 않는다(code-conventions 3-3
//        "엔티티에 public setter를 열지 않는다"). 값을 바꾸는 길은 아래 updateStatus 같은 의미 있는 메서드뿐이다.
@Getter
// [해석] JPA는 DB에서 읽은 행으로 객체를 만들 때 인자 없는 생성자가 필요하다. PROTECTED로 둬서 JPA(같은 패키지·하위 클래스
//        프록시)는 쓸 수 있지만 일반 코드가 new Camera()로 빈 캠을 만들지는 못하게 막았다.
//        캠을 새로 만드는 생성 메서드는 #11의 몫이라 일부러 만들지 않았다(테스트는 리플렉션으로 값을 넣는다).
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Camera {

    // [해석] tables.md: id BIGINT AUTO_INCREMENT → MySQL의 자동 증가를 그대로 쓰는 IDENTITY 전략.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Zone 엔티티가 아직 없어 id 값으로 둔다
    // [해석] #16에서 추가한 칼럼. 분석 결과는 '구역·분' 단위로 모으므로(FNC-A-024) 캠이 어느 구역 소속인지 알아야 한다.
    //        원래는 @ManyToOne Zone으로 연관관계를 걸겠지만 Zone 엔티티도 아직 없어서(region 도메인 이슈 몫) 숫자 id로만 둔다.
    //        #15·#17에는 필요 없는 칼럼이지만 위 '염두에 둔 것 1' 때문에 두 브랜치에도 똑같이 넣는다.
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    // [해석] #17에서 추가한 칼럼. tables.md: source_type VARCHAR(10) — LIVE(RTSP 실시간) / DUMMY(미리 올린 영상 반복).
    //        녹화 등록 규칙이 입력 방식에 따라 다르다: 라이브는 endedAt 필수, 더미는 endedAt 없음 + 이전 반복 닫기(FNC-A-026).
    //        요청 경로(recordings/live/ 인지)로 짐작하지 않고 캠에 저장된 실제 입력 방식으로 판단하려고 넣었다(사용자와 합의).
    //        status와 같은 이유로 EnumType.STRING(글자로 저장).
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private CameraSourceType sourceType;

    // [해석] tables.md: run_requested TINYINT(1) — "관리자가 가동을 원하는가". 실제 상태(status)와 따로 둔다.
    //        #15: 0인데 RUNNING 보고가 오면 엇갈림으로 보고 정지 지시. #16: 0이면 분석 결과를 버림.
    //        boolean으로 받으면 코드에서 isRunRequested()로 읽혀 의도가 잘 드러난다(code-conventions 2-1 Boolean 이름).
    @Column(name = "run_requested", nullable = false)
    private boolean runRequested;

    // [해석] EnumType.STRING: DB에 'RUNNING' 같은 글자로 저장한다. 기본값인 ORDINAL(0,1,2…)로 두면 enum 순서를 바꾸는 순간
    //        기존 데이터의 뜻이 뒤바뀌는 사고가 난다. 길이 20은 tables.md VARCHAR(20)과 같다.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CameraStatus status;

    // [해석] tables.md: progress TINYINT 0~100(분석 준비 진행률). NOT NULL이라 기본형 int로 받았다.
    @Column(name = "progress", nullable = false)
    private int progress;

    // [해석] tables.md: error_message VARCHAR(500) NULL. 값이 없을 수 있으므로 String(null 허용) 그대로.
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    // [해석] 소프트 삭제 시각. NULL이면 사용 중. #15는 삭제된 캠을 '없는 캠'(404)으로, #16은 결과를 버릴 대상으로 본다.
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 분석 서버가 보고한 상태로 바꾼다(FNC-A-025). progress가 null이면 기존 진행률을 유지한다.
     */
    // [해석] setter 대신 '상태 보고를 반영한다'는 뜻이 이름에 드러나는 메서드(code-conventions 3-3).
    //        progress를 Integer(null 가능)로 받는 이유: API에서 progress는 선택 항목이라, 안 보내면 null이 들어온다.
    //        사용자와 합의한 대로 "안 보내면 기존 값 유지"를 여기 한 곳에서 처리한다.
    //        errorMessage는 합의대로 "받은 값 그대로"(null이면 지움) — 오류가 풀린 보고(RUNNING)에서 옛 오류가 남지 않게.
    //        이 엔티티 메서드는 '값을 어떻게 바꾸는가'만 안다. 'RUNNING 보고를 무시할지' 같은 판단은 서비스가 한다.
    public void updateStatus(CameraStatus status, Integer progress, String errorMessage) {
        this.status = status;
        if (progress != null) {
            this.progress = progress;
        }
        this.errorMessage = errorMessage;
    }
}
