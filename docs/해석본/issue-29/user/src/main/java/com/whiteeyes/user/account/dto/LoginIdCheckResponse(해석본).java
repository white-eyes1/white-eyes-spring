/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/dto/LoginIdCheckResponse.java (29-fnc-u-001-아이디-중복-확인 브랜치)
 * 관련 이슈: #29(FNC-U-001), API: GET /api/v1/signup/check-id (public.md)
 *
 * [무엇] 아이디 중복 확인 응답 본문. JSON으로 {"available": true} 또는 {"available": false}가 된다(public.md Response 그대로).
 * [이름] 응답 DTO는 'Response' 접미사(code-conventions 3-2).
 * [왜 record인가] DTO는 record가 기본(code-conventions 3-3). 필드 이름 available이 그대로 JSON 키가 된다(camelCase).
 * [왜 boolean을 그대로 내보내지 않나] 컨트롤러가 true/false만 돌려주면 응답이 {"available": ...} 모양이 되지 않는다.
 *   엔티티나 원시값이 아니라 항상 DTO로 응답한다(package-structure 3-3).
 */
package com.whiteeyes.user.account.dto;

public record LoginIdCheckResponse(boolean available) {}
