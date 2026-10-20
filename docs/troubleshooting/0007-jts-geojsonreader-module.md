# 0007. `GeoJsonReader` 패키지를 찾을 수 없음(jts-core에 없음)

- 기록: 2026-10-20
- 관련: 이슈 #9, PR #105, FNC-A-013
- 상태: 해결

## 증상
동 경계(JSON)를 도형으로 읽으려고 `org.locationtech.jts.io.geojson.GeoJsonReader`를 import하자 컴파일 오류가 났다.

```
package org.locationtech.jts.io.geojson does not exist
```

## 원인
`GeoJsonReader`는 `jts-core`가 아니라 별도 라이브러리 `jts-io-common`에 있다. admin은 `jts-core:1.20.0`만 의존한다.

## 해결
의존성을 늘리지 않고, #7에서 만든 `RegionGeoJsonParser`에 `readGeometry()` 공개 메서드를 추가해 재사용했다.

## 재발 방지
JTS 클래스를 쓰기 전에 어느 모듈(jts-core / jts-io-common)에 있는지 확인한다.
