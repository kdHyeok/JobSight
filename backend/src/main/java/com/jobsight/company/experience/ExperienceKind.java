package com.jobsight.company.experience;

/**
 * 경험 종류 — 설계 2.1. 선언 순서가 목차 정렬 순서다(PROJECT → CAREER → ACTIVITY → TRAINING → OTHER, 5.2).
 * OTHER 는 이력서에 렌더하지 않아 3단계 행 묶음에는 나오지 않는다(4단계 경험 원본용).
 */
public enum ExperienceKind {
    PROJECT, CAREER, ACTIVITY, TRAINING, OTHER
}
