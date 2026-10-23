package com.whiteeyes.admin.demo.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.demo.repository.DemoStatRepository;

/**
 * 시연용 데이터 삭제(FNC-A-052). zone_stat_hourly·zone_stat_daily에서 is_demo=1 행만 1만 건씩 지운다.
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
        long hourly = deleteAllFrom(DemoStatRepository.HOURLY_TABLE);
        long daily = deleteAllFrom(DemoStatRepository.DAILY_TABLE);
        return new DemoDeleteResult(hourly, daily);
    }

    private long deleteAllFrom(String table) {
        long total = 0;
        int deleted;
        do {
            deleted = demoStatRepository.deleteDemoRows(table, batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
