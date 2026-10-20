package com.whiteeyes.common.zone.repository;

import com.whiteeyes.common.zone.entity.Zone;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneRepository extends JpaRepository<Zone, Long> {

    /** 같은 동의 삭제되지 않은 구역 중 이름이 같은 것이 있는지 본다(FNC-A-014 처리 순서 2). */
    boolean existsByDongIdAndNameAndDeletedAtIsNull(Long dongId, String name);

    List<Zone> findByDongIdInAndDeletedAtIsNull(Collection<Long> dongIds);
}
