package com.whiteeyes.tools.region;

public class RegionFileFormatException extends RuntimeException {

    public RegionFileFormatException(String message) {
        super(message);
    }

    public RegionFileFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
