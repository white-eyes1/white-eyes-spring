/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/media/dto/RecordingRegisterResponse.java
 * 관련 이슈: #17(FNC-A-026 처리 순서 3 "{id} 응답")
 *
 * [무엇] 녹화 등록 응답 {"id": 900}. 계약(api/analyzer.md)의 201 응답 본문 그대로다.
 * [왜 엔티티를 그대로 돌려주지 않나] 엔티티는 controller 밖으로 내보내지 않는다(package-structure 3-3). 엔티티를 JSON으로 내보내면
 *   파일 경로 같은 내부 값까지 새어 나가고, 엔티티를 바꿀 때마다 API 응답 모양도 함께 바뀐다. 응답 DTO를 따로 두면 계약만 지킨다.
 * [이름] 응답 DTO는 'Response' 접미사(code-conventions 3-2).
 */
package com.whiteeyes.admin.media.dto;

public record RecordingRegisterResponse(Long id) {}
