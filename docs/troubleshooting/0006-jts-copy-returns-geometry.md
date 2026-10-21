# 0006. JTS `Polygon.copy()`가 `Geometry`를 돌려줘 형변환 컴파일 오류

- 기록: 2026-10-20
- 관련: 이슈 #9, PR #105, FNC-A-013
- 상태: 해결

## 증상
`ZoneAreaCalculator`에서 `Polygon meters = polygon.copy();`가 컴파일되지 않았다.

```
incompatible types: Geometry cannot be converted to Polygon
```

## 원인
JTS 1.20의 `Polygon.copy()`는 반환 타입이 상위 타입 `Geometry`다.

## 해결
변수 타입을 `Geometry`로 선언했다. 좌표 변환은 `CoordinateSequenceFilter`로 하고 `getArea()`는 `Geometry`에서 호출한다.

## 재발 방지
JTS의 `copy()`·`buffer()` 등은 반환형이 `Geometry`라고 가정하고, 필요할 때만 형변환한다.
