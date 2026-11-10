package com.whiteeyes.admin.region.dto;

/**
 * 읍·면·동 하나의 소속 이름. 단계마다 따로 담는다.
 *
 * @param sido 시·도 이름
 * @param sigungu 시·군·구 이름
 * @param emd 읍·면·동 이름
 */
public record RegionNames(String sido, String sigungu, String emd) {}
