package com.whiteeyes.tools.region;

/**
 * 행정구역 경계 파일의 내용이 기대한 형식이 아닐 때 던진다. region-sql 명령이 메시지를 출력하고 실패로 끝낸다.
 */
public class RegionFileFormatException extends RuntimeException {

    /**
     * @param message 무엇이 잘못되었는지(코드·값 포함)
     */
    public RegionFileFormatException(String message) {
        super(message);
    }

    /**
     * @param message 무엇이 잘못되었는지
     * @param cause 원래 예외(JSON 읽기 오류 등)
     */
    public RegionFileFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
