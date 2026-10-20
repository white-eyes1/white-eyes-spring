/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/constant/InputRules.java (feature/30-signup 브랜치)
 * 관련 이슈: #29(FNC-U-001), #30(FNC-U-002), 규칙: conventions.md '입력값 규칙'
 *
 * [무엇] 입력값 규칙의 정규식·길이·위반 문구를 한 곳에 모은 상수. #29가 아이디를, #30이 나머지 가입 항목을 넣었다.
 * [왜 상수인가] @Pattern(regexp = ...)·@Size(max = ...) 같은 애너테이션 속성에는 컴파일 시점 상수만 들어간다.
 *   관리자 앱의 계정 생성·비밀번호 변경(FNC-C-007)도 같은 규칙이라 common에 둔다.
 */
package com.whiteeyes.common.constant;

/**
 * 입력값 규칙(conventions.md '입력값 규칙'). 검증 애너테이션에서 쓰도록 컴파일 시점 상수로 둔다.
 */
public final class InputRules {

    // [해석] 위반 문구는 conventions.md·screens.md 어디에도 없다(PR '검토 필요'). REQUIRED_MESSAGE는 #96 GlobalExceptionHandler가
    //        파라미터 누락 때 쓰는 '필수입니다.'와 같은 문장으로 맞췄다. 같은 '빠짐'이 경로에 따라 다른 말로 보이지 않게 하려는 것.
    // 명세에 정해진 문구가 없어 규칙을 그대로 문장으로 옮겼다(#29, #30)
    public static final String REQUIRED_MESSAGE = "필수입니다.";

    /** 아이디: 4~20자, 영문 소문자로 시작, 영문 소문자·숫자만. */
    public static final String LOGIN_ID_REGEX = "^[a-z][a-z0-9]{3,19}$";

    public static final String LOGIN_ID_MESSAGE = "아이디는 영문 소문자로 시작하는 4~20자의 영문 소문자·숫자여야 합니다.";

    /** 비밀번호: 8~64자, 공백을 뺀 ASCII 출력 문자만, 영문과 숫자를 각각 1자 이상. */
    // [해석] (?=.*[A-Za-z])(?=.*[0-9]): 앞을 내다보는 조건 두 개로 '영문 1자 이상'·'숫자 1자 이상'을 확인한다.
    //        [\x21-\x7E]{8,64}: ASCII 출력 문자에서 공백(0x20)을 뺀 범위(! 부터 ~ 까지)만 8~64자. 한글·공백은 여기서 걸린다.
    //        ASCII로 제한하는 이유: bcrypt는 72바이트까지만 보므로 1바이트 문자만 쓰면 64자가 항상 한도 안이다(decisions/0013).
    //        Java 문자열 안이라 정규식의 \x21을 \\x21처럼 역슬래시 두 번으로 쓴다.
    public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,64}$";

    public static final String PASSWORD_MESSAGE = "비밀번호는 영문과 숫자를 각각 1자 이상 포함한 8~64자의 영문·숫자·특수문자여야 합니다.";

    public static final String PASSWORD_CONFIRM_MESSAGE = "비밀번호가 일치하지 않습니다.";

    // [해석] 이름 '1~50자' 중 50자 이하는 @Size(max = 이 값)가, 1자 이상은 SignupRequest의 @NotBlank가 맡는다.
    public static final int NAME_MAX_LENGTH = 50;

    public static final String NAME_MESSAGE = "이름은 1~50자여야 합니다.";

    public static final int EMAIL_MAX_LENGTH = 100;

    // [해석] 형식 위반(@Email)과 길이 초과(@Size)에 같은 문구를 쓴다. 둘 다 걸려도 같은 문장이라 화면에서 어색하지 않다.
    public static final String EMAIL_MESSAGE = "메일은 100자 이하의 메일 형식이어야 합니다.";

    /** 연락처: 숫자와 - 만, 20자 이하. 선택 항목이라 빈 값도 맞는 것으로 본다. */
    // [해석] {0,20}: 0자도 맞는 것으로 본다. 선택 항목이라 빈 문자열을 오류로 보지 않고, 서비스가 NULL로 바꿔 저장한다.
    //        '-'만 넣어도 통과한다. 명세 규칙('숫자와 - 만, 20자 이하')을 그대로 옮긴 결과다.
    public static final String PHONE_REGEX = "^[0-9-]{0,20}$";

    public static final String PHONE_MESSAGE = "연락처는 숫자와 -만 쓸 수 있고 20자 이하여야 합니다.";

    private InputRules() {}
}
