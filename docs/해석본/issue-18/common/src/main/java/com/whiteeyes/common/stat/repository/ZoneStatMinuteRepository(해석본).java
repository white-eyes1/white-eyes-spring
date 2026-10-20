/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/stat/repository/ZoneStatMinuteRepository.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] zone_stat_minute를 DB에 '넣거나 덮어쓰는' upsert 메서드를 가진 리포지토리.
 * [왜 save()가 아닌가] 명세(FNC-A-028 처리 순서 3)는 "(zone_id, stat_at) 기준 INSERT … ON DUPLICATE KEY UPDATE"다.
 *   JPA의 save()는 '있는지 먼저 조회 → 없으면 INSERT, 있으면 UPDATE'로 두 번 일하고, 그 사이에 다른 쪽이 같은 행을
 *   넣으면 중복 키 오류가 난다. ON DUPLICATE KEY UPDATE는 DB가 한 문장 안에서 '있으면 덮어쓰기'를 처리한다.
 *   JPA(JPQL)에는 이 문법이 없어서 네이티브 SQL로 쓴다.
 * [언제 덮어쓰나] 정상 운영에서는 같은 (구역, 분)이 두 번 저장될 일이 거의 없다. 다시 집계하거나 보충할 때
 *   같은 키가 와도 오류 없이 결과가 같아지게(멱등) 하려는 것이다.
 * [사용자와 합의한 결정 4] 갱신 부분에 VALUES(count_avg) 함수를 쓰지 않고 값을 한 번 더 쓴다.
 *   VALUES()는 MySQL 8.0.20부터 사용 중단 예정(경고)이고, 대안인 행 별칭(AS new)은 테스트용 H2가 지원하지 않는다.
 *   값을 다시 쓰는 방식은 MySQL 8과 H2 모두에서 동작한다(MinuteStatSaveIntegrationTest로 H2에서 확인).
 */
package com.whiteeyes.common.stat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.entity.ZoneStatMinuteId;

public interface ZoneStatMinuteRepository extends JpaRepository<ZoneStatMinute, ZoneStatMinuteId> {

    /**
     * (zone_id, stat_at) 기준으로 넣거나 덮어쓴다(FNC-A-028 처리 순서 3).
     * 갱신 부분은 VALUES() 대신 값을 다시 써서 MySQL 8(VALUES() 사용 중단 예정)과 H2 모두에서 동작하게 한다.
     */
    // [해석] @Transactional: 쓰기 쿼리는 트랜잭션 안에서만 실행된다. 이 메서드에 직접 붙여서, 부르는 쪽
    //        (MinuteStatService)이 트랜잭션이 없으면 구역마다 따로 트랜잭션이 생긴다. 그래서 한 구역 저장이 실패해도
    //        이미 저장한 다른 구역이 함께 취소(롤백)되지 않는다.
    @Transactional
    // [해석] @Modifying: SELECT가 아니라 데이터를 바꾸는 쿼리라고 알림. clearAutomatically: 실행 뒤 JPA가 메모리에 들고 있던
    //        엔티티를 비워서, 바로 다음 findById가 옛날 값이 아니라 DB의 새 값을 읽게 한다.
    @Modifying(clearAutomatically = true)
    // [해석] :#{#stat.countAvg} 는 SpEL 표현식. 파라미터로 받은 엔티티(stat)의 getter를 불러 그 값을 SQL에 넣는다.
    //        파라미터 17개를 @Param으로 하나씩 받는 대신 엔티티 하나만 넘길 수 있다. 문자열을 이어 붙이지 않고
    //        바인딩 파라미터로 들어가서 SQL 인젝션 걱정도 없다.
    //        ON DUPLICATE KEY UPDATE 뒤에는 키(zone_id, stat_at)를 뺀 15칸을 모두 다시 쓴다(결정 4).
    @Query(
            value = """
                    INSERT INTO zone_stat_minute (
                        zone_id, stat_at, count_avg, count_max, count_min, density,
                        in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w,
                        dwell_sum_sec, dwell_count, sample_sec)
                    VALUES (
                        :#{#stat.zoneId}, :#{#stat.statAt}, :#{#stat.countAvg}, :#{#stat.countMax},
                        :#{#stat.countMin}, :#{#stat.density},
                        :#{#stat.inN}, :#{#stat.inE}, :#{#stat.inS}, :#{#stat.inW},
                        :#{#stat.outN}, :#{#stat.outE}, :#{#stat.outS}, :#{#stat.outW},
                        :#{#stat.dwellSumSec}, :#{#stat.dwellCount}, :#{#stat.sampleSec})
                    ON DUPLICATE KEY UPDATE
                        count_avg = :#{#stat.countAvg}, count_max = :#{#stat.countMax},
                        count_min = :#{#stat.countMin}, density = :#{#stat.density},
                        in_n = :#{#stat.inN}, in_e = :#{#stat.inE}, in_s = :#{#stat.inS}, in_w = :#{#stat.inW},
                        out_n = :#{#stat.outN}, out_e = :#{#stat.outE}, out_s = :#{#stat.outS}, out_w = :#{#stat.outW},
                        dwell_sum_sec = :#{#stat.dwellSumSec}, dwell_count = :#{#stat.dwellCount},
                        sample_sec = :#{#stat.sampleSec}
                    """,
            nativeQuery = true)
    void upsert(@Param("stat") ZoneStatMinute stat);
}
