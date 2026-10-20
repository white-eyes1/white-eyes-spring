package com.whiteeyes.common.region.repository;

import com.whiteeyes.common.region.entity.Region;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, Long> {

    List<Region> findByCodeIn(Collection<String> codes);
}
