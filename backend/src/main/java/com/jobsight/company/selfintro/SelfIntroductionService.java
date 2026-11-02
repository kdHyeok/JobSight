package com.jobsight.company.selfintro;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.LinkSource;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.position.Position;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeRepository;
import com.jobsight.company.selfintro.dto.SelfIntroductionConfirmRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionCreateRequest;
import com.jobsight.company.selfintro.dto.SelfIntroductionResponse;
import com.jobsight.company.selfintro.dto.SelfIntroductionUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SelfIntroductionService {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    /** 요청 DTO 의 @Size 상한과 같다. MCP 의 더하기 결과에도 적용한다. */
    private static final int MAX_POSITIONS = 10;
    private static final int MAX_TYPES = 3;

    private final SelfIntroductionRepository repository;
    private final ResumeRepository resumeRepository;
    private final PositionRepository positionRepository;
    private final CurrentUser currentUser;

    public SelfIntroductionService(SelfIntroductionRepository repository, ResumeRepository resumeRepository,
                                   PositionRepository positionRepository, CurrentUser currentUser) {
        this.repository = repository;
        this.resumeRepository = resumeRepository;
        this.positionRepository = positionRepository;
        this.currentUser = currentUser;
    }

    public List<SelfIntroductionResponse> findAll(String query, UUID resumeId) {
        List<SelfIntroduction> values = repository.findAllByOwnerIdOrderByUpdatedAtDesc(currentUser.id()).stream()
                .filter(value -> resumeId == null || value.getResumeIds().contains(resumeId))
                .toList();
        return rank(values, query).stream().map(SelfIntroductionResponse::of).toList();
    }

    public SelfIntroductionResponse findById(UUID id) {
        return SelfIntroductionResponse.of(findOwned(id));
    }

    @Transactional
    public SelfIntroductionResponse create(SelfIntroductionCreateRequest request) {
        Set<UUID> resumeIds = requireOwnedResumes(request.resumeIds());
        Set<UUID> positionIds = requireOwnedPositions(request.positionIds());
        SelfIntroduction value = new SelfIntroduction(currentUser.id(), resumeIds, request.question().trim(),
                normalize(request.answer()));
        LinkSource source = source();
        value.linkPositions(positionIds, source);
        value.linkQuestionTypes(codes(request.questionTypes()), source);
        return SelfIntroductionResponse.of(repository.save(value));
    }

    /**
     * positionIds·questionTypes 가 null 이면 연결을 건드리지 않는다. 남는 연결은 출처를 유지한다(웹 저장이 AI 기록을 확정하지 않는다).
     * 웹은 보낸 목록으로 맞추고(✗ 해제), MCP 는 더하기만 한다(addOnly).
     */
    @Transactional
    public SelfIntroductionResponse update(UUID id, SelfIntroductionUpdateRequest request) {
        SelfIntroduction value = findOwned(id);
        LinkSource source = source();
        Set<UUID> resumeIds = requireOwnedResumes(request.resumeIds());
        Set<UUID> positionIds = request.positionIds() == null ? null
                : addOnly(requireOwnedPositions(request.positionIds()), value.getPositionLinks(), source, MAX_POSITIONS, "지원 직무");
        Set<Competency> types = request.questionTypes() == null ? null
                : addOnly(codes(request.questionTypes()), value.getQuestionTypes(), source, MAX_TYPES, "문항 유형");
        value.update(resumeIds, request.question().trim(), normalize(request.answer()));
        if (positionIds != null) value.linkPositions(positionIds, source);
        if (types != null) value.linkQuestionTypes(types, source);
        return SelfIntroductionResponse.of(repository.save(value));
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

    /** 웹의 ✓. 확정 도구는 MCP 에 없다 — 설계 3절 규칙 3. */
    @Transactional
    public SelfIntroductionResponse confirm(UUID id, SelfIntroductionConfirmRequest request) {
        SelfIntroduction value = findOwned(id);
        if (!value.confirm(orEmpty(request.positionIds()), orEmpty(request.questionTypes()))) {
            throw new ApiRuleException(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "연결되지 않은 직무나 문항 유형은 확정할 수 없습니다.");
        }
        return SelfIntroductionResponse.of(repository.save(value));
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

    /** BM25 유사 점수. 검색어가 없으면 null(정렬하지 않음), 점수가 0 인 문항은 빠진다. */
    static Map<SelfIntroduction, Double> scores(List<SelfIntroduction> values, String query) {
        List<String> terms = terms(query);
        if (terms.isEmpty() || values.isEmpty()) return null;

        Map<SelfIntroduction, List<String>> documents = new HashMap<>();
        values.forEach(value -> documents.put(value, words(value)));
        double averageLength = documents.values().stream().mapToInt(List::size).average().orElse(1);
        Map<SelfIntroduction, Double> scores = new HashMap<>();

        for (SelfIntroduction value : values) {
            List<String> words = documents.get(value);
            double score = 0;
            for (String term : terms) {
                long frequency = words.stream().filter(word -> word.contains(term)).count();
                if (frequency == 0) continue;
                long documentFrequency = documents.values().stream()
                        .filter(doc -> doc.stream().anyMatch(word -> word.contains(term)))
                        .count();
                double idf = Math.log(1 + (values.size() - documentFrequency + 0.5) / (documentFrequency + 0.5));
                double denominator = frequency + K1 * (1 - B + B * words.size() / averageLength);
                score += idf * frequency * (K1 + 1) / denominator;
            }
            if (score > 0) scores.put(value, score);
        }
        return scores;
    }

    static List<String> terms(String query) {
        return tokenize(query).stream().distinct().toList();
    }

    /** 질문과 답변의 단어. 검색어는 단어 안 포함으로 맞춘다(형태소 분석 없음). */
    static List<String> words(SelfIntroduction value) {
        return tokenize(value.getQuestion() + " " + (value.getAnswer() == null ? "" : value.getAnswer()));
    }

    private static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) return List.of();
        Matcher matcher = WORD.matcher(value.toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        while (matcher.find()) words.add(matcher.group());
        return words;
    }

    private SelfIntroduction findOwned(UUID id) {
        return repository.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    private LinkSource source() {
        return currentUser.viaMcp() ? LinkSource.AI : LinkSource.USER;
    }

    private Set<UUID> requireOwnedResumes(List<UUID> ids) {
        return requireOwned(ids, unique -> resumeRepository.findAllByIdInAndOwnerId(unique, currentUser.id()), Resume::getId);
    }

    private Set<UUID> requireOwnedPositions(List<UUID> ids) {
        return requireOwned(ids, unique -> positionRepository.findAllByIdInAndOwnerId(unique, currentUser.id()), Position::getId);
    }

    /** 묶음을 한 번에 소유자 조건으로 읽는다. 하나라도 없으면(남의 것 포함) 그 id 로 404. 빈 목록은 조회하지 않는다. */
    private static <T> Set<UUID> requireOwned(List<UUID> ids, Function<List<UUID>, List<T>> findOwned, Function<T, UUID> idOf) {
        Set<UUID> unique = new LinkedHashSet<>(orEmpty(ids));
        if (unique.isEmpty()) return unique;
        Set<UUID> found = findOwned.apply(List.copyOf(unique)).stream().map(idOf).collect(Collectors.toSet());
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
