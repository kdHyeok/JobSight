package com.jobsight.company.selfintro;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.Bm25;
import com.jobsight.company.common.LinkSource;
import com.jobsight.company.common.OwnerLock;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.experience.Experience;
import com.jobsight.company.experience.ExperienceRepository;
import com.jobsight.company.selfintro.dto.ExperienceRef;
import org.springframework.jdbc.core.JdbcTemplate;
import com.jobsight.company.experience.dto.ExperienceSearchResult;
import com.jobsight.company.writingguide.WritingGuideService;
import tools.jackson.databind.ObjectMapper;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.resume.ResumeRepository;
import com.jobsight.company.selfintro.dto.SelfIntroductionConfirmRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
import com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SelfIntroductionService {
    /** 요청 DTO 의 @Size 상한과 같다. MCP 의 더하기 결과에도 적용한다. */
    private static final int MAX_POSITIONS = 10;
    private static final int MAX_TYPES = 3;
    private static final int MAX_EXPERIENCES = 10;

    private final SelfIntroductionRepository repository;
    private final ResumeRepository resumeRepository;
    private final PositionRepository positionRepository;
    private final CurrentUser currentUser;
    private final OwnerLock ownerLock;
    private final ExperienceRepository experienceRepository;
    private final JdbcTemplate jdbc;
    private final WritingGuideService writingGuides;
    private final ObjectMapper mapper;

    public SelfIntroductionService(SelfIntroductionRepository repository, ResumeRepository resumeRepository,
                                   PositionRepository positionRepository, CurrentUser currentUser, OwnerLock ownerLock,
                                   ExperienceRepository experienceRepository, JdbcTemplate jdbc,
                                   WritingGuideService writingGuides, ObjectMapper mapper) {
        this.repository = repository;
        this.resumeRepository = resumeRepository;
        this.positionRepository = positionRepository;
        this.currentUser = currentUser;
        this.ownerLock = ownerLock;
        this.experienceRepository = experienceRepository;
        this.jdbc = jdbc;
        this.writingGuides = writingGuides;
        this.mapper = mapper;
    }

    public List<SelfIntroductionResponse> findAll(String query, UUID resumeId) {
        List<SelfIntroduction> values = resumeId == null
                ? repository.findAllByOwnerIdOrderByUpdatedAtDesc(currentUser.id())
                : repository.findAllLinkedToResume(currentUser.id(), resumeId);
        return rank(values, query).stream().map(SelfIntroductionResponse::of).toList();
    }

    public SelfIntroductionResponse findById(UUID id) {
        return SelfIntroductionResponse.of(findOwned(id));
    }

    @Transactional
    public SelfIntroductionResponse create(SelfIntroductionCreateRequest request) {
        // 작성 근거(경험 쌍)를 쓰는 쓰기는 계정 잠금(T9) 아래에서 한다 — 경험 삭제의 사용 중 확인과 엇갈리지 않게.
        ownerLock.acquire(currentUser.id());
        Set<UUID> resumeIds = requireOwnedResumes(request.resumeIds());
        Map<UUID, Integer> experiences = request.experiences() == null ? null : requireExperiences(request.experiences());
        Set<UUID> positionIds = requireOwnedPositions(request.positionIds());
        SelfIntroduction value = new SelfIntroduction(currentUser.id(), resumeIds, request.question().trim(),
                normalize(request.answer()));
        LinkSource source = source();
        value.linkPositions(positionIds, source);
        value.linkQuestionTypes(codes(request.questionTypes()), source);
        if (experiences != null) value.linkExperiences(experiences, source);
        if (source == LinkSource.AI) recordWritingContext(value, request.searchContext());
        // flush 뒤의 version 을 싣는다. id 를 미리 정한 엔티티라 save 가 merge 로 가고, 연결 컬렉션을 옮겨 담는 flush 에서
        // version 이 한 번 더 오른다 — flush 전에 응답을 만들면 MCP 가 생성 직후 수정할 때 거짓 409 를 받는다.
        return SelfIntroductionResponse.of(saveAndCheck(value, experiences != null));
    }

    /**
     * positionIds·questionTypes 가 null 이면 연결을 건드리지 않는다. 남는 연결은 출처를 유지한다(웹 저장이 AI 기록을 확정하지 않는다).
     * 웹은 보낸 목록으로 맞추고(✗ 해제), MCP 는 더하기만 한다(addOnly).
     * 행을 잠근 뒤 version 을 확인한다. 웹은 expectedVersion 필수, MCP 는 이력서·질문·답변을 바꿀 때만 필수다 —
     * 더하기만 하는 연결은 남이 저장한 내용을 지우지 못한다(불변 조건 12).
     */
    @Transactional
    public SelfIntroductionResponse update(UUID id, SelfIntroductionUpdateRequest request) {
        ownerLock.acquire(currentUser.id());
        SelfIntroduction value = lockOwned(id);
        LinkSource source = source();
        String question = request.question().trim();
        String answer = normalize(request.answer());
        boolean rewritesText = !question.equals(value.getQuestion()) || !Objects.equals(answer, value.getAnswer())
                || !new HashSet<>(orEmpty(request.resumeIds())).equals(value.getResumeIds());
        checkVersion(value, request.expectedVersion(), source == LinkSource.USER || rewritesText);
        Set<UUID> resumeIds = requireOwnedResumes(request.resumeIds());
        Set<UUID> positionIds = request.positionIds() == null ? null
                : addOnly(requireOwnedPositions(request.positionIds()), value.getPositionLinks(), source, MAX_POSITIONS, "지원 직무");
        Set<Competency> types = request.questionTypes() == null ? null
                : addOnly(codes(request.questionTypes()), value.getQuestionTypes(), source, MAX_TYPES, "문항 유형");
        Map<UUID, Integer> experiences = request.experiences() == null ? null
                : addOnlyExperiences(requireExperiences(request.experiences()), value.getExperienceLinks(), source);
        value.update(resumeIds, question, answer);
        if (positionIds != null) value.linkPositions(positionIds, source);
        if (types != null) value.linkQuestionTypes(types, source);
        if (experiences != null) value.linkExperiences(experiences, source);
        if (source == LinkSource.AI) recordWritingContext(value, request.searchContext());
        return SelfIntroductionResponse.of(saveAndCheck(value, experiences != null));
    }

    /**
     * MCP 저장의 재현 정보(설계 6.7): 작성 기준 version, 저장 뒤의 작성 근거 (경험, 개정) 쌍, 클라이언트가 넘긴 검색 context.
     * 저장할 때마다 덮는다 — 가장 최근 AI 작성의 기준이다. 웹 저장은 부르지 않는다.
     */
    private void recordWritingContext(SelfIntroduction value, ExperienceSearchResult.Context searchContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("writingGuideVersion", writingGuides.version(currentUser.id()));
        context.put("experiences", value.getExperienceLinks().stream()
                .sorted(Comparator.comparing(SelfIntroduction.ExperienceLink::experienceId))
                .map(link -> Map.of("experienceId", link.experienceId(), "experienceRevision", link.experienceRevision()))
                .toList());
        context.put("searchContext", searchContext);
        value.recordWritingContext(mapper.writeValueAsString(context));
    }

    /**
     * 작성 근거를 쓴 저장은 커밋 전에 지연 FK 위반을 당긴다(설계 2.3) — 확인 뒤 경험이 지워졌으면 이 문장에서 409 EXPERIENCE_CHANGED.
     */
    private SelfIntroduction saveAndCheck(SelfIntroduction value, boolean experiencesTouched) {
        SelfIntroduction saved = repository.saveAndFlush(value);
        if (experiencesTouched) jdbc.execute("set constraints all immediate");
        return saved;
    }

    /**
     * 작성 근거 쌍 검사(설계 6.8): 두 키 필수(개정 1 이상), 경험은 내 것(아니면 404), 그 개정이 있어야 한다
     * (개정 이력은 1..지금 개정이라 지금 개정보다 크면 404 EXPERIENCE_REVISION_NOT_FOUND). 받은 쌍을 그대로 쓴다.
     */
    private Map<UUID, Integer> requireExperiences(List<ExperienceRef> refs) {
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (ExperienceRef ref : refs) {
            if (ref == null || ref.experienceId() == null || ref.experienceRevision() == null || ref.experienceRevision() < 1) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "EXPERIENCE_REFERENCE_REQUIRED",
                        "작성 근거는 experienceId 와 experienceRevision(1 이상)을 함께 보내야 합니다. experience_index 상세에서 읽은 개정을 씁니다.");
            }
            Integer previous = wanted.putIfAbsent(ref.experienceId(), ref.experienceRevision());
            if (previous != null && !previous.equals(ref.experienceRevision())) {
                throw new ApiRuleException(HttpStatus.BAD_REQUEST, "EXPERIENCE_REFERENCE_REQUIRED",
                        "같은 경험(" + ref.experienceId() + ")을 다른 개정으로 두 번 보냈습니다.");
            }
        }
        if (wanted.isEmpty()) return wanted;
        Map<UUID, Integer> current = experienceRepository.findAllByIdInAndOwnerId(wanted.keySet(), currentUser.id()).stream()
                .collect(Collectors.toMap(Experience::getId, Experience::getRevision));
        for (Map.Entry<UUID, Integer> entry : wanted.entrySet()) {
            Integer revision = current.get(entry.getKey());
            if (revision == null) throw new ResourceNotFoundException(entry.getKey());
            if (entry.getValue() > revision) {
                throw new ApiRuleException(HttpStatus.NOT_FOUND, "EXPERIENCE_REVISION_NOT_FOUND",
                        "경험 " + entry.getKey() + " 에 개정 " + entry.getValue() + " 이 없습니다.");
            }
        }
        return wanted;
    }

    /** MCP 는 더하기만 한다(6.8): 이미 연결된 경험은 저장된 개정을 그대로 두고 건너뛴다. 빼기와 개정 바꾸기는 웹에서. */
    private static Map<UUID, Integer> addOnlyExperiences(Map<UUID, Integer> wanted, Set<SelfIntroduction.ExperienceLink> links,
                                                         LinkSource source) {
        if (source != LinkSource.AI) return wanted;
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        links.forEach(link -> merged.put(link.experienceId(), link.experienceRevision()));
        wanted.forEach(merged::putIfAbsent);
        if (merged.size() > MAX_EXPERIENCES) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "TOO_MANY_LINKS", "작성 근거 경험은 " + MAX_EXPERIENCES
                    + "개 이하여야 합니다. MCP 는 연결을 더하기만 하므로 기존 연결을 빼는 것은 웹 자기소개 화면에서 합니다.");
        }
        return merged;
    }

    /**
     * MCP(AI) 요청은 연결을 더하기만 한다 — 목록에 없는 기존 연결(확정·AI 기록)도 남긴다. AI 기록의 확정과 삭제는
     * 웹 전용이다(설계 3절 규칙 3, 5.2). 그래서 AI 가 다른 회사용으로 고치며 목록을 바꿔도 사용자가 남긴 출처가 지워지지 않는다.
     * 더한 결과가 요청 상한을 넘으면 400 이다(빼는 것은 웹에서).
     */
    private static <K> Set<K> addOnly(Set<K> wanted, Set<? extends SelfIntroduction.Link<K>> links, LinkSource source,
                                      int max, String label) {
        if (source != LinkSource.AI) return wanted;
        links.forEach(link -> wanted.add(link.key()));
        if (wanted.size() > max) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "TOO_MANY_LINKS", label + "는 " + max
                    + "개 이하여야 합니다. MCP 는 연결을 더하기만 하므로 기존 연결을 빼는 것은 웹 자기소개 화면에서 합니다.");
        }
        return wanted;
    }

    /**
     * 웹의 ✓. 확정 도구는 MCP 에 없다 — 설계 3절 규칙 3. 연결만 바꿔도 version 이 오르므로(컬렉션 변경) 같은 잠금을 타고,
     * 응답에 새 version 을 싣는다. 화면은 이 값을 다음 저장의 expectedVersion 으로 쓴다.
     */
    @Transactional
    public SelfIntroductionResponse confirm(UUID id, SelfIntroductionConfirmRequest request) {
        SelfIntroduction value = lockOwned(id);
        if (!value.confirm(orEmpty(request.positionIds()), orEmpty(request.questionTypes()))) {
            throw new ApiRuleException(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "연결되지 않은 직무나 문항 유형은 확정할 수 없습니다.");
        }
        return SelfIntroductionResponse.of(repository.saveAndFlush(value));
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(findOwned(id));
    }

    static List<SelfIntroduction> rank(List<SelfIntroduction> values, String query) {
        Map<SelfIntroduction, Double> scores = scores(values, query);
        return scores == null ? values : ranked(scores);
    }

    /** 점수 내림차순, 같으면 최근 수정순. */
    static List<SelfIntroduction> ranked(Map<SelfIntroduction, Double> scores) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<SelfIntroduction, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().getUpdatedAt(), Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** BM25 유사 점수(common.Bm25). 검색어가 없으면 null(정렬하지 않음), 점수가 0 인 문항은 빠진다. */
    static Map<SelfIntroduction, Double> scores(List<SelfIntroduction> values, String query) {
        Map<SelfIntroduction, List<String>> documents = new HashMap<>();
        values.forEach(value -> documents.put(value, words(value)));
        return Bm25.scores(documents, terms(query));
    }

    static List<String> terms(String query) {
        return Bm25.terms(query);
    }

    /** 질문과 답변의 단어. 검색어는 단어 안 포함으로 맞춘다(형태소 분석 없음). */
    static List<String> words(SelfIntroduction value) {
        return Bm25.tokenize(value.getQuestion() + " " + (value.getAnswer() == null ? "" : value.getAnswer()));
    }

    private SelfIntroduction findOwned(UUID id) {
        return repository.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    /** 쓰기 경로의 조회. 행을 잠가 웹·MCP·확정이 동시에 와도 서로를 덮지 않게 한다 — 이력서와 같은 모양(docs/resumes.md 불변 조건 10). */
    private SelfIntroduction lockOwned(UUID id) {
        try {
            repository.setLocalLockTimeout();
            return repository.findOwnedForUpdate(id, currentUser.id())
                    .orElseThrow(() -> new ResourceNotFoundException(id));
        } catch (PessimisticLockingFailureException exception) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "SELF_INTRO_BUSY",
                    "같은 자기소개 문항의 다른 저장을 처리 중입니다. 잠시 후 다시 읽고 시도해 주세요.");
        }
    }

    /**
     * 예전에 읽은 답변으로 덮어쓰지 않게 한다 — 불변 조건 12. 오류에 지금 version 을 싣지 않는다.
     * 실어 주면 클라이언트가 바뀐 내용을 확인하지 않고 그 값으로 다시 보낼 수 있다.
     */
    private static void checkVersion(SelfIntroduction value, Long expected, boolean required) {
        if (expected == null) {
            if (!required) return;
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED",
                    "expectedVersion 이 필요합니다. 자기소개 문항을 다시 읽고 그 version 을 보내세요.");
        }
        if (expected != value.getVersion()) {
            throw new ApiRuleException(HttpStatus.CONFLICT, "SELF_INTRO_STALE",
                    "다른 곳에서 먼저 저장했습니다. 자기소개 문항을 다시 불러와 바뀐 내용을 확인한 뒤 저장하세요.");
        }
    }

    private LinkSource source() {
        return currentUser.viaMcp() ? LinkSource.AI : LinkSource.USER;
    }

    private Set<UUID> requireOwnedResumes(List<UUID> ids) {
        return requireOwned(ids, unique -> resumeRepository.findOwnedIds(unique, currentUser.id()));
    }

    private Set<UUID> requireOwnedPositions(List<UUID> ids) {
        return requireOwned(ids, unique -> positionRepository.findOwnedIds(unique, currentUser.id()));
    }

    /**
     * 묶음을 한 번에 소유자 조건으로 읽는다. 하나라도 없으면(남의 것 포함) 그 id 로 404. 빈 목록은 조회하지 않는다.
     * id 만 읽는다 — 엔티티와 EAGER 컬렉션을 로드하지 않는다(설계 1.3 P7).
     */
    private static Set<UUID> requireOwned(List<UUID> ids, Function<List<UUID>, List<UUID>> findOwnedIds) {
        Set<UUID> unique = new LinkedHashSet<>(orEmpty(ids));
        if (unique.isEmpty()) return unique;
        Set<UUID> found = new HashSet<>(findOwnedIds.apply(List.copyOf(unique)));
        UUID missing = unique.stream().filter(id -> !found.contains(id)).findFirst().orElse(null);
        if (missing != null) throw new ResourceNotFoundException(missing);
        return unique;
    }

    private static Set<Competency> codes(List<Competency> codes) {
        return new LinkedHashSet<>(orEmpty(codes));
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
