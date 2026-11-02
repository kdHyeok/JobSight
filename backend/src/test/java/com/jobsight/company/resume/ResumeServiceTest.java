package com.jobsight.company.resume;

import com.jobsight.company.attachment.AttachmentRepository;
import com.jobsight.company.attachment.dto.AttachmentResponse;
import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.position.Position;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.resume.dto.ResumeCopyRequest;
import com.jobsight.company.resume.dto.ResumeRequest;
import com.jobsight.company.resume.dto.ResumeResponse;
import com.jobsight.company.resume.dto.ResumeRowRequest;
import com.jobsight.company.resume.dto.ResumeRowResult;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ResumeServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private ResumeRepository repository;
    @Mock private CurrentUser currentUser;
    @Mock private PositionRepository positionRepository;
    @Mock private AttachmentRepository attachmentRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private ResumeService service;

    @BeforeEach
    void setUp() {
        service = new ResumeService(repository, currentUser, mapper,
                Validation.buildDefaultValidatorFactory().getValidator(), positionRepository, attachmentRepository);
        given(currentUser.id()).willReturn(OWNER_ID);
    }

    private ResumeRowRequest row(Map<String, String> fields) {
        return mapper.convertValue(fields, ResumeRowRequest.class);
    }

    /** 생성·복제는 save, 수정 경로는 version 을 응답에 싣기 위해 saveAndFlush 를 쓴다. */
    private void saveReturnsArgument() {
        Answer<Resume> answer = inv -> {
            Resume r = inv.getArgument(0);
            r.onCreate();
            return r;
        };
        lenient().when(repository.save(any())).thenAnswer(answer);
        lenient().when(repository.saveAndFlush(any())).thenAnswer(answer);
    }

    /** 쓰기 경로는 잠금 조회로 읽는다(docs/resumes.md 불변 조건 10). 단위 테스트에서 version 은 0 그대로다. */
    private Resume lockedResume(String contentJson) {
        Resume resume = new Resume(OWNER_ID, "v1", contentJson);
        resume.onCreate();
        given(repository.findOwnedForUpdate(resume.getId(), OWNER_ID)).willReturn(Optional.of(resume));
        return resume;
    }

    private ResumeContent stored(Resume resume) {
        return mapper.readValue(resume.getContent(), ResumeContent.class);
    }

    private static void assertCode(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOfSatisfying(ApiRuleException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    /** content 없이 만들어도 저장 JSON 은 항상 같은 모양이다 — 읽는 쪽이 null 검사를 안 한다. */
    @Test
    void createWithoutContentStoresEmptyDocument() {
        saveReturnsArgument();

        ResumeResponse created = service.create(new ResumeRequest("  새 이력서 ", null, List.of(), null));

        ArgumentCaptor<Resume> saved = ArgumentCaptor.forClass(Resume.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("새 이력서");
        assertThat(saved.getValue().getContent()).contains("\"educations\":[]").contains("\"projects\":[]");
        assertThat(created.content().basic()).isNotNull();
        assertThat(created.content().educations()).isEmpty();
    }

    /** 행 배열은 순서 그대로 왕복한다. 연월은 문자열이라 "현재" 같은 표현이 살아남는다. */
    @Test
    void contentRoundTripsThroughJson() {
        saveReturnsArgument();
        ResumeContent content = new ResumeContent(
                new ResumeContent.BasicInfo("테스트", null, null, null, null, null, null, "https://example.com/gh"),
                List.of(new ResumeContent.Education("2020.03", "현재", "테스트 대학교", "대학교", "컴퓨터공학과",
                        "4.0 / 4.5", "133.00", null, null, null, null,
                        List.of(new ResumeContent.CollegeTerm("1학년", "일반화학(A+)", "24.00", "4.38")), null)),
                null, null, null, null, null, null, null);

        ResumeResponse created = service.create(new ResumeRequest("v1", content, List.of(), null));

        assertThat(created.content().educations()).hasSize(1);
        assertThat(created.content().educations().get(0).endYm()).isEqualTo("현재");
        assertThat(created.content().basic().githubUrl()).isEqualTo("https://example.com/gh");
        // 생성 때 null 로 보낸 섹션은 빈 배열로 정규화된다.
        assertThat(created.content().trainings()).isEmpty();
    }

    /** 복제는 새 id 에 같은 content. 원본은 건드리지 않는다. */
    @Test
    void copyCreatesNewVersionWithSameContent() {
        saveReturnsArgument();
        Resume source = new Resume(OWNER_ID, "v1", "{\"basic\":{\"name\":\"테스트\"},\"educations\":[]}");
        source.onCreate();
        UUID positionId = UUID.randomUUID();
        source.replacePositions(Set.of(positionId));
        given(repository.findByIdAndOwnerId(source.getId(), OWNER_ID)).willReturn(Optional.of(source));

        ResumeResponse copy = service.copy(source.getId(), new ResumeCopyRequest("v2"));

        assertThat(copy.id()).isNotEqualTo(source.getId());
        assertThat(copy.name()).isEqualTo("v2");
        assertThat(copy.content().basic().name()).isEqualTo("테스트");
        assertThat(copy.positionIds()).containsExactly(positionId);
        assertThat(source.getName()).isEqualTo("v1");
    }

    @Test
    void linkedPositionsMustBelongToOwner() {
        saveReturnsArgument();
        UUID positionId = UUID.randomUUID();
        UUID secondPositionId = UUID.randomUUID();
        Position position = mock(Position.class);
        Position secondPosition = mock(Position.class);
        given(position.getId()).willReturn(positionId);
        given(secondPosition.getId()).willReturn(secondPositionId);
        given(positionRepository.findAllByIdInAndOwnerId(Set.of(positionId, secondPositionId), OWNER_ID))
                .willReturn(List.of(position, secondPosition));

        ResumeResponse created = service.create(new ResumeRequest("지원용", null,
                List.of(positionId, secondPositionId), null));

        assertThat(created.positionIds()).containsExactly(positionId, secondPositionId);
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(new ResumeRequest("다른 직무", null, List.of(unknown), null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** 문서 전체 저장은 버전이 필수이고, 예전 버전이면 409 다(docs/resumes.md 불변 조건 8). */
    @Test
    void updateRequiresCurrentVersion() {
        saveReturnsArgument();
        Resume resume = lockedResume("{\"basic\":{\"name\":\"테스트\"}}");

        assertCode(catchThrown(() -> service.update(resume.getId(), new ResumeRequest("v1", null, null, null))),
                "VERSION_REQUIRED");
        assertCode(catchThrown(() -> service.update(resume.getId(), new ResumeRequest("v1", null, null, 3L))),
                "RESUME_STALE");
        assertThat(service.update(resume.getId(), new ResumeRequest("v2", null, null, 0L)).name()).isEqualTo("v2");
    }

    /** 수정 요청의 null 은 유지, [] 는 비움이다. AI 가 섹션을 빠뜨려도 조용히 지워지지 않는다(불변 조건 9). */
    @Test
    void updateKeepsWhatTheRequestLeavesOut() {
        saveReturnsArgument();
        Resume resume = lockedResume("{\"basic\":{\"name\":\"테스트\"},\"skills\":[{\"name\":\"Python\"}],"
                + "\"projects\":[{\"name\":\"Planetory\"}]}");
        UUID positionId = UUID.randomUUID();
        resume.replacePositions(Set.of(positionId));
        ResumeContent partial = new ResumeContent(null,
                List.of(new ResumeContent.Education(null, null, "테스트 대학교", null, null, null, null,
                        null, null, null, null, null, null)),
                null, null, null, null, null, List.of(), null);

        ResumeResponse updated = service.update(resume.getId(), new ResumeRequest("v1", partial, null, 0L));

        assertThat(updated.content().basic().name()).isEqualTo("테스트");
        assertThat(updated.content().educations()).extracting(ResumeContent.Education::school).containsExactly("테스트 대학교");
        assertThat(updated.content().skills()).isEmpty();
        assertThat(updated.content().projects()).extracting(ResumeContent.Project::name).containsExactly("Planetory");
        assertThat(updated.positionIds()).containsExactly(positionId);
    }

    /** 행 도구: 그 섹션 필드만 받고, 다른 섹션 필드·범위 밖 index 는 거부한다. 나머지 섹션은 그대로. */
    @Test
    void rowToolsEditOneSectionAndRejectForeignFields() {
        saveReturnsArgument();
        Resume resume = lockedResume(
                "{\"basic\":{\"name\":\"테스트\"},\"educations\":[{\"school\":\"테스트 대학교\"}],\"certificates\":[]}");

        ResumeRowResult added = service.addRow(resume.getId(), ResumeSection.certificates,
                row(Map.of("name", "SQLD", "issuer", "기관 A", "acquiredYm", "2026.03")), null);
        assertThat(added.count()).isEqualTo(1);
        assertThat(added.index()).isZero();
        assertThat(added.row()).containsEntry("name", "SQLD");
        assertThat(stored(resume).educations()).extracting(ResumeContent.Education::school).containsExactly("테스트 대학교");
        assertThat(stored(resume).basic().name()).isEqualTo("테스트");

        ResumeRowResult replaced = service.updateRow(resume.getId(), ResumeSection.certificates, 0,
                row(Map.of("name", "ADsP", "acquiredYm", "2025.09")), 0L);
        assertThat(replaced.row()).containsEntry("name", "ADsP").containsEntry("issuer", "기관 A");

        // 학력 필드(school)를 자격증 섹션에 넣으면 조용히 버리지 않고 거부한다.
        assertThatThrownBy(() -> service.addRow(resume.getId(), ResumeSection.certificates, row(Map.of("school", "x")), null))
                .isInstanceOf(ApiRuleException.class).hasMessageContaining("certificates");
        assertCode(catchThrown(() -> service.deleteRow(resume.getId(), ResumeSection.certificates, 5, 0L)), "ROW_NOT_FOUND");

        ResumeRowResult removed = service.deleteRow(resume.getId(), ResumeSection.certificates, 0, 0L);
        assertThat(removed.count()).isZero();
        assertThat(removed.index()).isNull();
        assertThat(stored(resume).certificates()).isEmpty();
    }

    /** 행 갱신은 보낸 필드만 덮는다. "" 는 비움이고, 요청이 표현하지 못하는 성적표 배열은 남는다(불변 조건 9). */
    @Test
    void rowUpdateOverlaysOnlySentFields() {
        saveReturnsArgument();
        Resume resume = lockedResume("{\"educations\":[{\"school\":\"테스트 대학교\",\"major\":\"컴퓨터공학과\","
                + "\"collegeTerms\":[{\"grade\":\"1학년\",\"courses\":\"일반화학(A+)\"}]}],"
                + "\"certificates\":[{\"name\":\"SQLD\",\"issuer\":\"기관 A\"}]}");

        ResumeRowResult education = service.updateRow(resume.getId(), ResumeSection.educations, 0,
                row(Map.of("major", "통계학과")), 0L);
        assertThat(education.row()).containsEntry("school", "테스트 대학교").containsEntry("major", "통계학과");
        assertThat(stored(resume).educations().get(0).collegeTerms()).extracting(ResumeContent.CollegeTerm::grade)
                .containsExactly("1학년");

        service.updateRow(resume.getId(), ResumeSection.certificates, 0, row(Map.of("issuer", "")), 0L);
        assertThat(stored(resume).certificates().get(0).name()).isEqualTo("SQLD");
        assertThat(stored(resume).certificates().get(0).issuer()).isEmpty();
    }

    /** 행 번호로 고치거나 지우면 버전이 필수, 추가는 선택이다(불변 조건 8). */
    @Test
    void rowIndexToolsRequireVersion() {
        saveReturnsArgument();
        Resume resume = lockedResume("{\"projects\":[{\"name\":\"Planetory\"},{\"name\":\"JOBIS\"}]}");

        assertCode(catchThrown(() -> service.updateRow(resume.getId(), ResumeSection.projects, 0,
                row(Map.of("name", "다른 이름")), null)), "VERSION_REQUIRED");
        assertCode(catchThrown(() -> service.deleteRow(resume.getId(), ResumeSection.projects, 0, 5L)), "RESUME_STALE");
        assertThat(stored(resume).projects()).extracting(ResumeContent.Project::name).containsExactly("Planetory", "JOBIS");
        assertThat(service.addRow(resume.getId(), ResumeSection.projects, row(Map.of("name", "새 프로젝트")), null).count())
                .isEqualTo(3);
    }

    @Test
    void updateBasicKeepsOtherSections() {
        saveReturnsArgument();
        Resume resume = lockedResume("{\"basic\":{},\"skills\":[{\"name\":\"Python\"}]}");

        ResumeResponse updated = service.updateBasic(resume.getId(),
                new ResumeContent.BasicInfo("테스트", null, null, null, null, null, null, "https://example.com/gh"), null);

        assertThat(updated.content().basic().name()).isEqualTo("테스트");
        assertThat(updated.content().skills()).extracting(ResumeContent.Skill::name).containsExactly("Python");
    }

    /** AI 가 {phone} 만 보내도 이름·이메일·주소·포트폴리오 첨부가 지워지지 않는다(불변 조건 9). */
    @Test
    void updateBasicKeepsFieldsTheRequestLeavesOut() {
        saveReturnsArgument();
        Resume resume = lockedResume("{\"basic\":{\"name\":\"테스트\",\"email\":\"test@example.com\","
                + "\"address\":\"서울\",\"portfolioFileId\":\"pf-1\"}}");

        ResumeResponse updated = service.updateBasic(resume.getId(),
                new ResumeContent.BasicInfo(null, "010-0000-0000", null, null, null, null, null, null), null);

        assertThat(updated.content().basic()).isEqualTo(new ResumeContent.BasicInfo(
                "테스트", "010-0000-0000", null, "test@example.com", "서울", null, "pf-1", null));
    }

    /** 행 갱신과 같이 "" 는 그 필드를 비운다. 나머지는 그대로다. */
    @Test
    void updateBasicEmptyStringClearsOnlyThatField() {
        saveReturnsArgument();
        Resume resume = lockedResume("{\"basic\":{\"name\":\"테스트\",\"email\":\"test@example.com\"}}");

        ResumeResponse updated = service.updateBasic(resume.getId(),
                new ResumeContent.BasicInfo(null, null, null, "", null, null, null, null), null);

        assertThat(updated.content().basic().email()).isEmpty();
        assertThat(updated.content().basic().name()).isEqualTo("테스트");
    }

    /** 다른 계정 소유의 이력서는 존재를 알리지 않고 404. */
    @Test
    void otherOwnersResumeIsNotReachable() {
        UUID id = UUID.randomUUID();
        given(repository.findByIdAndOwnerId(id, OWNER_ID)).willReturn(Optional.empty());
        given(repository.findOwnedForUpdate(id, OWNER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.copy(id, new ResumeCopyRequest("x"))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(id, new ResumeRequest("x", null, null, 0L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * 편집기가 그려진 뒤 첨부 필드마다 메타를 다시 부르지 않게, 문서가 가리키는 첨부의 메타를 응답에 싣는다(설계 1.3 P5).
     * 첨부 칸 7개를 모두 모으고 id 가 아닌 값은 버린다. 소유자 조건은 저장소 조회에 들어간다.
     */
    @Test
    void detailCarriesAttachmentMetasOfEveryFileField() {
        List<UUID> ids = Stream.generate(UUID::randomUUID).limit(7).toList();
        Resume resume = new Resume(OWNER_ID, "v1", """
                {"basic":{"portfolioFileId":"%s"},
                 "educations":[{"school":"대학","diplomaId":"%s","transcriptId":"%s"}],
                 "trainings":[{"name":"교육","fileId":"%s"}],
                 "activities":[{"name":"활동","fileId":"%s"}],
                 "awards":[{"name":"상","fileId":"%s"},{"name":"옛 값","fileId":"첨부 아님"}],
                 "certificates":[{"name":"자격증","fileId":"%s"},{"name":"빈 칸","fileId":""}]}
                """.formatted(ids.toArray()));
        resume.onCreate();
        given(repository.findByIdAndOwnerId(resume.getId(), OWNER_ID)).willReturn(Optional.of(resume));
        AttachmentResponse mine = new AttachmentResponse(ids.get(0), "포트폴리오.pdf", "application/pdf", 2048);
        // 저장소는 내 것만 돌려준다. 남의 것·지워진 것은 응답에서 빠지고, 화면이 그 칸만 직접 부른다.
        given(attachmentRepository.findMetas(Set.copyOf(ids), OWNER_ID)).willReturn(List.of(mine));

        ResumeResponse response = service.findById(resume.getId());

        assertThat(response.attachments()).containsExactly(Map.entry(ids.get(0), mine));
    }

    /**
     * 컨테이너 요소에 @NotNull 이 없어 [null] 행은 검증을 통과하고 그대로 저장된다. 예전에 저장된 문서에도 있을 수 있다.
     * 첨부 칸을 모으다가 그 행에서 NPE 가 나면 상세·생성이 500 이 되고 이력서를 열 수 없다.
     */
    @Test
    void nullRowsDoNotBreakTheResponse() {
        saveReturnsArgument();
        UUID fileId = UUID.randomUUID();
        AttachmentResponse meta = new AttachmentResponse(fileId, "이수증.pdf", "application/pdf", 1024);
        given(attachmentRepository.findMetas(Set.of(fileId), OWNER_ID)).willReturn(List.of(meta));
        Resume resume = new Resume(OWNER_ID, "v1", """
                {"trainings":[null,{"name":"교육","fileId":"%s"}],"activities":[null],"awards":[null],"certificates":[null]}
                """.formatted(fileId));
        resume.onCreate();
        given(repository.findByIdAndOwnerId(resume.getId(), OWNER_ID)).willReturn(Optional.of(resume));

        assertThat(service.findById(resume.getId()).attachments()).containsExactly(Map.entry(fileId, meta));

        ResumeContent withNullRows = new ResumeContent(null, null,
                Arrays.asList(null, new ResumeContent.Training("교육", null, null, null, null, fileId.toString())),
                Collections.singletonList(null), null, Collections.singletonList(null),
                Collections.singletonList(null), null, null);
        ResumeResponse created = service.create(new ResumeRequest("v2", withNullRows, List.of(), null));
        assertThat(created.attachments()).containsExactly(Map.entry(fileId, meta));
    }

    @Test
    void detailWithoutAttachmentsSkipsTheLookup() {
        Resume resume = new Resume(OWNER_ID, "v1", "{\"skills\":[{\"name\":\"Java\"}]}");
        resume.onCreate();
        given(repository.findByIdAndOwnerId(resume.getId(), OWNER_ID)).willReturn(Optional.of(resume));

        assertThat(service.findById(resume.getId()).attachments()).isEmpty();
        verifyNoInteractions(attachmentRepository);
    }

    private static Throwable catchThrown(Runnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            return thrown;
        }
        throw new AssertionError("예외가 나야 하는데 나지 않았다");
    }
}
