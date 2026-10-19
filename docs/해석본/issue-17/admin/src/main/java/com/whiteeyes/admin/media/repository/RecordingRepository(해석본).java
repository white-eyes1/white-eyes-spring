/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/media/repository/RecordingRepository.java
 * 관련 이슈: #17(FNC-A-026 처리 순서 2)
 *
 * [무엇] recording 테이블 리포지토리. JpaRepository가 주는 save·findById에 더해, 더미 캠의 '이전 반복 닫기' UPDATE 하나를 둔다.
 * [왜 UPDATE 한 문장으로 했나] 진행 중인 녹화를 하나씩 읽어 와서 ended_at을 바꿔 저장해도 되지만, 그러면 조회 1번 + 변경 N번이다.
 *   조건에 맞는 행을 DB가 한 번에 바꾸게 하면 쿼리가 1번이고, 읽기와 쓰기 사이에 다른 등록이 끼어드는 틈도 줄어든다.
 */
package com.whiteeyes.admin.media.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.admin.media.entity.Recording;

public interface RecordingRepository extends JpaRepository<Recording, Long> {

    /**
     * 그 캠의 진행 중(ended_at 없음)인 녹화 중 closedAt보다 먼저 시작한 것을 closedAt으로 닫는다(FNC-A-026).
     *
     * @return 닫은 녹화 수
     */
    // 늦게 도착한 등록이 더 나중에 시작한 녹화를 과거 시각으로 닫지 않도록 started_at < closedAt만 닫는다
    // [해석] 조건 세 개의 뜻:
    //        r.cameraId = :cameraId   → 같은 캠의 녹화만(다른 캠의 재생 중인 녹화는 건드리지 않음)
    //        r.endedAt is null         → 아직 진행 중인 것만(이미 닫힌 녹화의 종료 시각을 덮어쓰지 않음)
    //        r.startedAt < :closedAt   → 이번 반복보다 '먼저' 시작한 것만(사용자와 합의한 결정 5, PR에 '검토 필요')
    //        세 번째 조건이 없으면: 14:05 반복이 먼저 등록된 뒤 재전송된 14:00 반복이 늦게 오면, 14:05 녹화를 14:00에 닫아
    //        '끝이 시작보다 빠른' 녹화가 생긴다. 방금 저장한 이번 녹화도 started_at이 같아서(<가 아니므로) 스스로 닫히지 않는다.
    // [해석] @Modifying: 이 @Query가 조회가 아니라 변경(UPDATE)이라고 알린다. 없으면 Spring Data가 SELECT로 보고 실패한다.
    //        clearAutomatically = true: UPDATE는 DB를 직접 바꾸고 JPA가 들고 있던 엔티티(1차 캐시)는 모른다. 끝난 뒤 캐시를 비워,
    //        같은 트랜잭션에서 findById로 다시 읽을 때 바뀐 ended_at이 보이게 한다(통합 테스트가 이것에 기댄다).
    @Modifying(clearAutomatically = true)
    @Query("update Recording r set r.endedAt = :closedAt"
            + " where r.cameraId = :cameraId and r.endedAt is null and r.startedAt < :closedAt")
    // [해석] 돌려주는 int(닫은 건수)는 서비스가 DEBUG 로그에 남긴다. 더미 캠이 정상이면 보통 0 또는 1이다.
    int closeOpenRecordingsStartedBefore(
            @Param("cameraId") Long cameraId, @Param("closedAt") LocalDateTime closedAt);
}
