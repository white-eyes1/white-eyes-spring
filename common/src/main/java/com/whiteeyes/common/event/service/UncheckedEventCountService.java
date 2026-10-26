package com.whiteeyes.common.event.service;

import com.whiteeyes.common.event.dto.UncheckedCountResponse;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미확인 이벤트 수(FNC-A-048). 기간·구역과 관계없이 처리 상태가 미확인인 이벤트 전체를 센다(헤더 배지 숫자).
 * 다른 도메인은 이벤트 리포지토리가 아니라 이 서비스를 부른다.
 */
@Service
@RequiredArgsConstructor
public class UncheckedEventCountService {

    private final EventRepository eventRepository;

    @Transactional(readOnly = true)
    public UncheckedCountResponse count() {
        return new UncheckedCountResponse(eventRepository.countByStatus(EventStatus.UNCHECKED));
    }
}
