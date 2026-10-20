package com.whiteeyes.admin.region.exception;

public class InvalidPolygonException extends RuntimeException {

    public InvalidPolygonException(String message) {
        super(message);
    }

    public InvalidPolygonException(String message, Throwable cause) {
        super(message, cause);
    }
}
