package com.whiteeyes.tools.demo.service;

import java.util.Collection;
import java.util.function.IntUnaryOperator;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.whiteeyes.tools.demo.repository.DemoStatRepository;

/**
 * 시연용 데이터 삭제(FNC-A-052). zone_stat_hourly·zone_stat_daily에서 is_demo=1 행만 1만 건씩 지운다.
 * 시연용 통계 생성(FNC-A-051)이 --zone으로 다시 만들 때는 그 구역의 시연용 행만 지운다.
 * 실제 데이터(is_demo=0)는 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DemoDeleteService {

    // 한 문장으로 지우는 최대 건수. 한 번에 지우면 DB 잠금이 길어지므로 나눠 지운다.
    static final int BATCH_SIZE = 10_000;

    private final DemoStatRepository demoStatRepository;

    /** @return 표별 지운 건수 */
    public DemoDeleteResult deleteAll() {
        // 표 하나씩 BATCH_SIZE건 단위로 지운다. 지운 건수가 한도보다 작아지면 더 지울 행이 없는 것이다.
        long hourly = deleteInBatches(
                limit -> demoStatRepository.deleteDemoRows(DemoStatRepository.HOURLY_TABLE, limit), BATCH_SIZE);
        long daily = deleteInBatches(
                limit -> demoStatRepository.deleteDemoRows(DemoStatRepository.DAILY_TABLE, limit), BATCH_SIZE);
        return new DemoDeleteResult(hourly, daily);
    }

    /**
     * 정해진 구역의 시연용 행만 지운다(시연용 통계 생성의 --zone, FNC-A-051).
     *
     * @param zoneIds 지울 구역 번호. 비어 있으면 안 된다
     * @return 표별 지운 건수
     */
    public DemoDeleteResult deleteZones(Collection<Long> zoneIds) {
        // deleteAll과 같은 방식으로 지우되, 지정한 구역의 행만 지운다.
        long hourly = deleteInBatches(
                limit -> demoStatRepository.deleteDemoRows(DemoStatRepository.HOURLY_TABLE, zoneIds, limit),
                BATCH_SIZE);
        long daily = deleteInBatches(
                limit -> demoStatRepository.deleteDemoRows(DemoStatRepository.DAILY_TABLE, zoneIds, limit), BATCH_SIZE);
        return new DemoDeleteResult(hourly, daily);
    }

    /** 한 번에 batchSize건씩(deleteBatch에 한도로 넘김), 덜 지워질 때까지 반복한다. @return 모두 지운 건수 */
    static long deleteInBatches(IntUnaryOperator deleteBatch, int batchSize) {
        long total = 0;
        int deleted;
        // 적어도 한 번은 지운다. 지운 건수가 한도와 같으면 아직 남았을 수 있어 한 번 더 지우고, 한도보다 적으면 끝낸다.
        do {
            deleted = deleteBatch.applyAsInt(batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
