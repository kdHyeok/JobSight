package com.jobsight.company.experience.dto;

import com.jobsight.company.experience.ExperienceKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 경험 쓰기 요청들 — 설계 5.2 experience_save, 6.3 가져오기, 6.4 삭제·검토. */
public final class ExperienceRequests {
    private ExperienceRequests() {
    }

    /**
     * 생성·수정 공용. null 은 유지다(생성 때는 kind·title 필수). 기존 경험에 facts·dossier 를 보내면 expectedVersion 필수.
     * changeKind 입력은 없다 — 서버가 facts 배열 항목의 안정 id 로 ADD·CORRECT 를 판정한다(R39).
     */
    public record Save(
            ExperienceKind kind,
            @Size(max = 200) String title,
            @Size(max = 200) String startYm,
            @Size(max = 2000) String memo,
            @Valid ExperienceFacts facts,
            @Valid ExperienceDossier dossier,
            @Size(max = 50) List<@NotNull @Size(max = 100) String> skills,
            @Size(max = 20) List<@NotNull @Size(max = 100) String> domains,
            Long expectedVersion
    ) {
    }

    /** 웹 검토 확정. 화면이 보고 있던 개정이어야 한다 — 그사이 AI 가 개정을 올렸으면 409. */
    public record Review(@NotNull Integer revision) {
    }

    public enum ImportAction { CREATE, SKIP, LINK_TO }

    /** 가져오기 묶음 하나의 결정. key 는 계획의 묶음 키, LINK_TO 면 experienceId 필수. */
    public record ImportDecision(@NotNull @Size(max = 500) String key, @NotNull ImportAction action, UUID experienceId) {
    }

    /** resumeVersions 는 계획에 실린 이력서 version 그대로. 하나라도 바뀌었으면 409 IMPORT_PLAN_STALE — 계획을 다시 만든다. */
    public record Import(@NotNull Map<UUID, Long> resumeVersions,
                         @NotNull @Size(max = 500) List<@NotNull @Valid ImportDecision> decisions) {
    }

    /** resume_add_experiences 항목. 경험 원본의 사실로 중립 문구를 만든다(FACTS). */
    public record AddItem(@NotNull UUID experienceId) {
    }

    /** resume_add_experiences 입력. 전부 들어가거나 전부 실패한다(섹션 상한 T6). */
    public record AddExperiences(@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid AddItem> items) {
    }
}
