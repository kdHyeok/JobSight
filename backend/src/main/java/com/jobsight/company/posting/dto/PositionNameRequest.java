package com.jobsight.company.posting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 공고 폼이 다루는 직무는 이름뿐이다. 상세(팀·역량…)는 직무 페이지에서.
 * id 가 있으면 그 직무, 없으면 같은 이름(공백·대소문자 무시)의 기존 직무를 쓰고 이름만 바꾼다. 둘 다 없으면 새로 만든다.
 * 어느 항목과도 맞지 않은 기존 직무는 삭제된다.
 */
public record PositionNameRequest(
        UUID id,
        @NotBlank(message = "직무 이름은 필수입니다.")
        @Size(max = 160, message = "직무 이름은 160자 이하여야 합니다.")
        String name
) {
}
