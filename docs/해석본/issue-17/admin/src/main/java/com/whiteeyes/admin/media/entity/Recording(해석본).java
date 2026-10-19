/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/media/entity/Recording.java
 * 관련 이슈: #17(FNC-A-026 녹화 등록 수신) — 정식 코드(임시 아님)
 *
 * [무엇] tables.md 'recording' 테이블(녹화 1건 = 영상 파일 1개 + 좌표 파일 1개)을 JPA 엔티티로 옮긴 것. 7칼럼 전부 매핑했다.
 * [왜 admin 모듈인가] recording은 관리자 앱만 쓰는 테이블이다(package-structure.md 4장). 사용자 앱 DB 계정에는 이 테이블 권한이
 *   없어서, common에 두면 사용자 앱이 시작할 때 JPA 검증(validate)에서 실패한다. 그래서 admin의 media 도메인에 둔다.
 * [왜 임시가 아닌가] 이 테이블은 #17이 처음 만드는 테이블이라 #17이 주인이다. 다만 테이블을 만드는 Flyway V파일은
 *   camera(#11)를 가리키는 FK 때문에 #11 머지 뒤에 추가한다(사용자와 합의한 결정 1).
 */
package com.whiteeyes.admin.media.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 관리자 앱만 쓰는 테이블이라 admin 모듈에 둔다(package-structure.md 4장)
@Entity
@Table(name = "recording")
// [해석] Camera와 같은 규칙: getter만 열고 setter는 열지 않는다. 인자 없는 생성자는 JPA용으로만 PROTECTED.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recording {

    // [해석] 녹화 번호. 다시보기 요청에서 파일 경로 대신 이 번호로 녹화를 가리킨다(tables.md 비고, 경로를 브라우저에 노출하지 않으려고).
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 도메인(camera)의 엔티티를 직접 참조하지 않고 id 값으로 둔다(package-structure.md 3-2)
    // [해석] @ManyToOne Camera로 걸면 media 도메인이 camera 도메인의 엔티티에 직접 기대게 된다. 의존 규칙상 다른 도메인은
    //        service로만 부르므로 숫자 id만 들고 있는다. 녹화를 찾을 때도 캠 id로만 찾으면 되어 연관관계가 필요 없다.
    @Column(name = "camera_id", nullable = false)
    private Long cameraId;

    // [해석] 녹화 시작 시각(KST). tables.md는 DATETIME(3)(밀리초까지)이다. 다시보기 재생 위치 = 클릭한 시각 − started_at이라
    //        밀리초가 어긋나면 박스가 영상과 미세하게 어긋난다. 밀리초까지 자르는 일은 서비스(toKstMillis)가 한다.
    //        칼럼 정밀도(3)는 V파일에서 정한다. 엔티티에서 columnDefinition으로 박지 않은 이유: 테스트 DB(H2)와 실제 DB(MySQL)의
    //        문법 차이에 기대지 않으려고.
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    // [해석] 녹화 종료 시각. NULL이면 진행 중(더미 캠이 지금 재생 중인 반복). 라이브는 등록할 때부터 값이 있다.
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    // [해석] 공유 폴더(/srv/media) 기준 상대경로. 길이 255는 tables.md VARCHAR(255)와 같다.
    @Column(name = "video_path", nullable = false, length = 255)
    private String videoPath;

    // [해석] 좌표 파일(.json.gz) 경로. 다시보기에서 탐지 박스를 그릴 때 쓴다.
    @Column(name = "box_path", nullable = false, length = 255)
    private String boxPath;

    // [해석] 등록 시각. 명세는 DB 기본값(CURRENT_TIMESTAMP)이지만, 그 기본값을 만드는 V파일이 아직 없어 테스트 DB에는 기본값이 없다.
    //        그래서 Hibernate가 저장할 때 현재 시각을 채우게 했다(@CreationTimestamp). 기록되는 의미(등록 시각)는 같다.
    //        updatable = false: 한 번 정해지면 바뀌지 않는 값이라 UPDATE 문에서 빼 둔다. V파일을 추가할 때 다시 본다(PR에 적음).
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // [해석] 생성자를 private으로 두고 아래 정적 메서드(register)로만 만들게 했다. 이름만 보고 '녹화를 등록한다'는 뜻이 드러나고,
    //        나중에 만드는 방법이 늘어나도(예: 테스트용) 이름으로 구분할 수 있다.
    private Recording(
            Long cameraId, LocalDateTime startedAt, LocalDateTime endedAt, String videoPath, String boxPath) {
        this.cameraId = cameraId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.videoPath = videoPath;
        this.boxPath = boxPath;
    }

    /**
     * 분석 서버가 보낸 녹화를 등록한다(FNC-A-026). 더미 캠은 endedAt 없이(진행 중) 등록한다.
     */
    // [해석] 값의 검사(경로 규칙, endedAt 규칙)는 여기서 하지 않고 서비스가 먼저 끝낸다. 엔티티는 '검사를 통과한 값으로 만들기'만 한다.
    public static Recording register(
            Long cameraId, LocalDateTime startedAt, LocalDateTime endedAt, String videoPath, String boxPath) {
        return new Recording(cameraId, startedAt, endedAt, videoPath, boxPath);
    }
}
