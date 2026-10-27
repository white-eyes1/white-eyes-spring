package com.whiteeyes.admin.demo.service;

import java.util.Collection;
import java.util.function.IntSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.demo.repository.DemoStatRepository;

/**
 * 시연용 데이터 삭제(FNC-A-052). zone_stat_hourly·zone_stat_daily에서 is_demo=1 행만 1만 건씩 지운다.
 * 시연용 통계 생성(FNC-A-051)이 --zone으로 다시 만들 때는 그 구역의 시연용 행만 지운다.
 * 실제 데이터(is_demo=0)는 건드리지 않는다.
 */
@Service
public class DemoDeleteService {

    static final int BATCH_SIZE = 10_000;

    private final DemoStatRepository demoStatRepository;
    private final int batchSize;

    // 생성자가 둘이라 빈을 만들 때 쓸 쪽을 정한다
    @Autowired
    public DemoDeleteService(DemoStatRepository demoStatRepository) {
        this(demoStatRepository, BATCH_SIZE);
    }

    // 테스트에서 나눠 지우는 동작을 적은 행으로 확인하려고 묶음 크기를 받는다
    DemoDeleteService(DemoStatRepository demoStatRepository, int batchSize) {
        this.demoStatRepository = demoStatRepository;
        this.batchSize = batchSize;
    }

    /** @return 표별 지운 건수 */
    public DemoDeleteResult deleteAll() {
        long hourly = deleteInBatches(() -> demoStatRepository.deleteDemoRows(DemoStatRepository.HOURLY_TABLE, batchSize));
        long daily = deleteInBatches(() -> demoStatRepository.deleteDemoRows(DemoStatRepository.DAILY_TABLE, batchSize));
        return new DemoDeleteResult(hourly, daily);
    }

    /**
     * 정해진 구역의 시연용 행만 지운다(시연용 통계 생성의 --zone, FNC-A-051).
     *
     * @param zoneIds 지울 구역 번호. 비어 있으면 안 된다
     * @return 표별 지운 건수
     */
    public DemoDeleteResult deleteZones(Collection<Long> zoneIds) {
        long hourly = deleteInBatches(
                () -> demoStatRepository.deleteDemoRows(DemoStatRepository.HOURLY_TABLE, zoneIds, batchSize));
        long daily = deleteInBatches(
                () -> demoStatRepository.deleteDemoRows(DemoStatRepository.DAILY_TABLE, zoneIds, batchSize));
        return new DemoDeleteResult(hourly, daily);
    }

    // 한 번에 batchSize건씩, 덜 지워질 때까지 반복한다
    private long deleteInBatches(IntSupplier deleteBatch) {
        long total = 0;
        int deleted;
        do {
            deleted = deleteBatch.getAsInt();
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
