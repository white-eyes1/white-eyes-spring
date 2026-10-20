package com.whiteeyes.common.zone.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.zone.entity.Zone;

// TODO(#10): 구역 등록(FNC-A-014)에서 정식으로 만든다. FNC-A-028 구현을 위해 먼저 둔 임시 코드
public interface ZoneRepository extends JpaRepository<Zone, Long> {
}
