package com.jobsight.company.resume;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 이력서 문서의 모양. JSON 으로 저장되고 요청·응답에 그대로 실린다.
 * 프런트 types/resume.ts 의 SECTIONS 와 키가 같아야 한다 — Jackson 은 모르는 키를 조용히 버린다.
 * 연월은 사용자가 쓴 표현("2024.03", "현재")을 잃지 않도록 문자열이다.
 */
public record ResumeContent(
        @Valid BasicInfo basic,
        @Size(max = 50, message = "학력은 50개 이하여야 합니다.") List<@Valid Education> educations,
        @Size(max = 50, message = "교육이수는 50개 이하여야 합니다.") List<@Valid Training> trainings,
        @Size(max = 50, message = "대내외활동은 50개 이하여야 합니다.") List<@Valid Activity> activities,
        @Size(max = 50, message = "경력은 50개 이하여야 합니다.") List<@Valid Experience> experiences,
        @Size(max = 50, message = "수상은 50개 이하여야 합니다.") List<@Valid Award> awards,
        @Size(max = 50, message = "자격증은 50개 이하여야 합니다.") List<@Valid Certificate> certificates,
        @Size(max = 50, message = "SW 역량은 50개 이하여야 합니다.") List<@Valid Skill> skills,
        @Size(max = 50, message = "프로젝트는 50개 이하여야 합니다.") List<@Valid Project> projects
) {
    static final int SHORT = 200;
    static final int URL = 500;
    static final int LONG = 5000;

    public static ResumeContent empty() {
        return new ResumeContent(BasicInfo.empty(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** null 을 빈 값으로. 저장된 JSON 이 항상 같은 모양이라 읽는 쪽이 null 검사를 안 한다. */
    public ResumeContent normalized() {
        return new ResumeContent(
                basic == null ? BasicInfo.empty() : basic,
                orEmpty(educations).stream().map(Education::normalized).toList(),
                orEmpty(trainings), orEmpty(activities), orEmpty(experiences),
                orEmpty(awards), orEmpty(certificates), orEmpty(skills), orEmpty(projects));
    }

    /** 수정 요청에서 빠진(null) 블록은 저장된 값을 쓴다. [] 는 비움이다 — docs/resumes.md 불변 조건 9. */
    public ResumeContent withMissingFrom(ResumeContent stored) {
        return new ResumeContent(
                basic != null ? basic : stored.basic,
                educations != null ? educations : stored.educations,
                trainings != null ? trainings : stored.trainings,
                activities != null ? activities : stored.activities,
                experiences != null ? experiences : stored.experiences,
                awards != null ? awards : stored.awards,
                certificates != null ? certificates : stored.certificates,
                skills != null ? skills : stored.skills,
                projects != null ? projects : stored.projects);
    }

    /** 첨부 id 가 들어가는 칸 전부. 프런트 SECTIONS 의 kind 'file' 필드와 같은 목록이다 — docs/attachments.md. */
    public List<String> attachmentIds() {
        List<String> ids = new ArrayList<>();
        if (basic != null) ids.add(basic.portfolioFileId());
        rows(educations).forEach(row -> { ids.add(row.diplomaId()); ids.add(row.transcriptId()); });
        rows(trainings).forEach(row -> ids.add(row.fileId()));
        rows(activities).forEach(row -> ids.add(row.fileId()));
        rows(awards).forEach(row -> ids.add(row.fileId()));
        rows(certificates).forEach(row -> ids.add(row.fileId()));
        return ids;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** null 행은 건너뛴다. 입력 검증이 [null] 을 막지 않고 예전에 저장된 문서에도 있다 — 응답 조립이 500 이 되면 안 된다. */
    private static <T> Stream<T> rows(List<T> list) {
        return orEmpty(list).stream().filter(Objects::nonNull);
    }

    public record BasicInfo(
            @Size(max = SHORT) String name,
            @Size(max = SHORT) String phone,
            @Size(max = SHORT) String birthDate,
            @Size(max = SHORT) String email,
            @Size(max = URL) String address,
            @Size(max = URL) String portfolioUrl,
            @Size(max = SHORT) String portfolioFileId,
            @Size(max = URL) String githubUrl
    ) {
        public static BasicInfo empty() {
            return new BasicInfo(null, null, null, null, null, null, null, null);
        }

        /** resume_basic_update 에서 빠진(null) 필드는 저장된 값을 쓴다. "" 는 비움 — 행 갱신과 같은 규칙(불변 조건 9). */
        BasicInfo withMissingFrom(BasicInfo stored) {
            return new BasicInfo(
                    name != null ? name : stored.name,
                    phone != null ? phone : stored.phone,
                    birthDate != null ? birthDate : stored.birthDate,
                    email != null ? email : stored.email,
                    address != null ? address : stored.address,
                    portfolioUrl != null ? portfolioUrl : stored.portfolioUrl,
                    portfolioFileId != null ? portfolioFileId : stored.portfolioFileId,
                    githubUrl != null ? githubUrl : stored.githubUrl);
        }
    }

    /**
     * 학력 한 줄. 대학교와 고등학교는 적는 것이 다르다 — 화면이 schoolType 으로 갈라 보여 주고,
     * 저장은 한 record 에 둘 다 담는다. 섹션을 둘로 쪼개면 학력 순서·정렬이 두 벌이 된다.
     * diplomaId·transcriptId 는 attachments 의 id 문자열이다 — docs/attachments.md.
     */
    public record Education(
            @Size(max = SHORT) String startYm,
            @Size(max = SHORT) String endYm,
            @Size(max = SHORT) String school,
            @Size(max = SHORT) String schoolType,
            @Size(max = SHORT) String major,
            @Size(max = SHORT) String gpa,
            @Size(max = SHORT) String totalCredits,
            @Size(max = SHORT) String admissionExam,
            @Size(max = SHORT) String overallRank,
            @Size(max = SHORT) String diplomaId,
            @Size(max = SHORT) String transcriptId,
            @Size(max = 20, message = "학년별 이수 내역은 20개 이하여야 합니다.")
            List<@Valid CollegeTerm> collegeTerms,
            @Size(max = 300, message = "학기별 이수 과목은 300개 이하여야 합니다.")
            List<@Valid SchoolTerm> schoolTerms
    ) {
        /** 중첩 표도 null 이 아니라 [] 로. 읽는 쪽이 null 검사를 안 하게 한다. */
        Education normalized() {
            return new Education(startYm, endYm, school, schoolType, major, gpa, totalCredits,
                    admissionExam, overallRank, diplomaId, transcriptId,
                    orEmpty(collegeTerms), orEmpty(schoolTerms));
        }
    }

    /** 대학교: 학년 하나에 이수 과목 목록과 그 학년의 학점. */
    public record CollegeTerm(
            @Size(max = SHORT) String grade,
            @Size(max = LONG) String courses,
            @Size(max = SHORT) String credits,
            @Size(max = SHORT) String gpa
    ) {
    }

    /** 고등학교: 생활기록부 그대로 학기·과목 단위. */
    public record SchoolTerm(
            @Size(max = SHORT) String grade,
            @Size(max = SHORT) String term,
            @Size(max = SHORT) String subject,
            @Size(max = SHORT) String units,
            @Size(max = SHORT) String achievement,
            @Size(max = SHORT) String rank,
            @Size(max = SHORT) String students
    ) {
    }

    public record Training(
            @Size(max = SHORT) String name,
            @Size(max = SHORT) String institution,
            @Size(max = SHORT) String startYm,
            @Size(max = SHORT) String endYm,
            @Size(max = LONG) String description,
            /** 이수증. attachments 의 id 문자열. */
            @Size(max = SHORT) String fileId,
            /** 경험 앵커(설계 6.1): 이 행이 쓴 경험 id 와 그 개정 번호. 개정은 링커(ResumeWriter)만 정한다. */
            UUID experienceId,
            Integer experienceRevision
    ) {
    }

    public record Activity(
            @Size(max = SHORT) String name,
            @Size(max = SHORT) String organizer,
            @Size(max = SHORT) String startYm,
            @Size(max = SHORT) String endYm,
            @Size(max = LONG) String description,
            @Size(max = SHORT) String fileId,
            /** 경험 앵커(설계 6.1): 이 행이 쓴 경험 id 와 그 개정 번호. 개정은 링커(ResumeWriter)만 정한다. */
            UUID experienceId,
            Integer experienceRevision
    ) {
    }

    public record Experience(
            @Size(max = SHORT) String company,
            @Size(max = SHORT) String startYm,
            @Size(max = SHORT) String endYm,
            @Size(max = LONG) String description,
            /** 경험 앵커(설계 6.1): 이 행이 쓴 경험 id 와 그 개정 번호. 개정은 링커(ResumeWriter)만 정한다. */
            UUID experienceId,
            Integer experienceRevision
    ) {
    }

    public record Award(
            @Size(max = SHORT) String name,
            @Size(max = SHORT) String issuer,
            @Size(max = SHORT) String awardedYm,
            @Size(max = SHORT) String fileId
    ) {
    }

    public record Certificate(
            @Size(max = SHORT) String name,
            @Size(max = SHORT) String issuer,
            @Size(max = SHORT) String acquiredYm,
            @Size(max = SHORT) String licenseNo,
            /** 자격증 사본. attachments 의 id 문자열. */
            @Size(max = SHORT) String fileId
    ) {
    }

    public record Skill(
            @Size(max = SHORT) String name,
            @Size(max = SHORT) String level,
            @Size(max = LONG) String description
    ) {
    }

    public record Project(
            @Size(max = SHORT) String name,
            @Size(max = SHORT) String headcount,
            @Size(max = SHORT) String startYm,
            @Size(max = SHORT) String endYm,
            @Size(max = LONG) String summary,
            @Size(max = URL) String techStack,
            @Size(max = URL) String role,
            @Size(max = LONG) String outcome,
            @Size(max = LONG) String description,
            @Size(max = URL) String url,
            /** 경험 앵커(설계 6.1): 이 행이 쓴 경험 id 와 그 개정 번호. 개정은 링커(ResumeWriter)만 정한다. */
            UUID experienceId,
            Integer experienceRevision
    ) {
    }
}
