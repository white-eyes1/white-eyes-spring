package com.whiteeyes.common.region.repository;

import com.whiteeyes.common.region.entity.Zone;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneRepository extends JpaRepository<Zone, Long> {

    /** 같은 동의 삭제되지 않은 구역 중 이름이 같은 것이 있는지 본다(FNC-A-014 처리 순서 2). */
    boolean existsByDongIdAndNameAndDeletedAtIsNull(Long dongId, String name);

    /** 구역이 있고 삭제되지 않았는지 본다(캠 등록 FNC-A-018 처리 순서 1). */
    boolean existsByIdAndDeletedAtIsNull(Long id);
}
