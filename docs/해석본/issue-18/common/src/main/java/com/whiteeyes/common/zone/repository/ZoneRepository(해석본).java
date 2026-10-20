/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/zone/repository/ZoneRepository.java
 * 관련 이슈: #18(FNC-A-028). 임시 코드, 정식 코드는 #10(FNC-A-014 구역 등록)
 *
 * [무엇] Zone 엔티티를 DB에서 읽고 쓰는 Spring Data JPA 리포지토리.
 * [왜] #18은 findAllById(여러 구역을 한 번에 조회) 하나만 쓰는데, 이건 JpaRepository가 기본으로 준다.
 *   그래서 직접 쓴 메서드가 하나도 없다. 필요한 조회는 #10이 정식으로 만들 때 추가한다.
 */
package com.whiteeyes.common.zone.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.zone.entity.Zone;

// TODO(#10): 구역 등록(FNC-A-014)에서 정식으로 만든다. FNC-A-028 구현을 위해 먼저 둔 임시 코드
// [해석] JpaRepository<엔티티, 키 타입>. 이 선언만으로 save·findById·findAllById 등이 생긴다.
public interface ZoneRepository extends JpaRepository<Zone, Long> {
}
