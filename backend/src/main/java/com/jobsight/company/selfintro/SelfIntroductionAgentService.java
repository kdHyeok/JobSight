package com.jobsight.company.selfintro;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.LinkSource;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.CompanyRepository;
import com.jobsight.company.experience.ExperienceRepository;
import com.jobsight.company.experience.ExperienceRevisions;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.position.dto.PositionLabel;
import com.jobsight.company.posting.JobPostingRepository;
import com.jobsight.company.resume.ResumeRepository;
import com.jobsight.company.resume.dto.ResumePositionRow;
import com.jobsight.company.selfintro.dto.SelfIntroductionList;
import com.jobsight.company.embedding.DenseIndex;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MCP 가 읽는 자소서 모양(self_intro_list·self_intro_get) — 설계 5.3.
 * 웹 REST 목록(SelfIntroductionService.findAll)은 답변 전문을 그대로 PUT 하므로 여기와 섞지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class SelfIntroductionAgentService {
    static final int MAX_QUERY = 100;
    static final int PREVIEW = 300;
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;
    private static final int RRF_K = 60;
    /** 의미 검색 후보 수(설계 4.2 PAST_QUESTION 의 상위 30 과 같다). */
    private static final int DENSE_TOP = 30;

    private final SelfIntroductionRepository repository;
    private final PositionRepository positions;
    private final ResumeRepository resumes;
    private final JobPostingRepository postings;
    private final CompanyRepository companies;
    private final CurrentUser currentUser;
    private final ExperienceRepository experiences;
    private final ExperienceRevisions revisions;
    private final DenseIndex dense;
    private final TransactionTemplate readOnly;

    public SelfIntroductionAgentService(SelfIntroductionRepository repository, PositionRepository positions,
                                        ResumeRepository resumes, JobPostingRepository postings,
                                        CompanyRepository companies, CurrentUser currentUser,
                                        ExperienceRepository experiences, ExperienceRevisions revisions,
                                        DenseIndex dense, PlatformTransactionManager transactions) {
        this.dense = dense;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.experiences = experiences;
        this.revisions = revisions;
        this.repository = repository;
        this.positions = positions;
        this.resumes = resumes;
        this.postings = postings;
        this.companies = companies;
        this.currentUser = currentUser;
    }

    /**
     * 이 요청이 그리는 직무·이력서의 이름만 담는다. 계정의 직무 상세나 이력서 본문(content JSONB)은 읽지 않는다
     * (설계 1.3 P4). 아직 없는 것만 묶어서 한 번에 읽는다.
     */
    private final class Context {
        private final UUID owner = currentUser.id();
        private final Map<UUID, PositionLabel> positionLabels = new HashMap<>();
        private final Map<UUID, String> resumeNames = new HashMap<>();
        private final Map<UUID, List<UUID>> resumePositions = new HashMap<>();
        private final Map<UUID, String> experienceTitles = new HashMap<>();
        private Map<UUID, List<ExperienceRevisions.Rev>> experienceHistory = Map.of();

        void addPositions(Collection<PositionLabel> labels) {
            labels.forEach(label -> positionLabels.put(label.id(), label));
        }

        void loadResumes(Stream<UUID> ids) {
            List<UUID> missing = ids.distinct().filter(id -> !resumeNames.containsKey(id)).toList();
            if (missing.isEmpty()) return;
            for (ResumePositionRow row : resumes.findPositionRows(missing, owner)) {
                resumeNames.put(row.resumeId(), row.name());
                List<UUID> supported = resumePositions.computeIfAbsent(row.resumeId(), id -> new ArrayList<>());
                if (row.positionId() != null) supported.add(row.positionId());
            }
        }

        /** 문항들의 직접·간접 출처를 그리는 데 필요한 이름: 이력서 1회, 직무 1회. */
        void loadFor(Collection<SelfIntroduction> values) {
            loadResumes(values.stream().flatMap(value -> value.getResumeIds().stream()));
            List<UUID> missing = Stream.concat(
                            values.stream().flatMap(value -> value.getPositionLinks().stream().map(SelfIntroduction.PositionLink::positionId)),
                            values.stream().flatMap(value -> value.getResumeIds().stream()).flatMap(this::positionsOf))
                    .distinct().filter(id -> !positionLabels.containsKey(id)).toList();
            if (!missing.isEmpty()) addPositions(positions.findLabels(missing, owner));
            // 작성 근거가 있는 문항이 있을 때만 경험 이름과 개정 이력을 읽는다(각 1회).
            List<UUID> used = values.stream().flatMap(value -> value.getExperienceLinks().stream())
                    .map(SelfIntroduction.ExperienceLink::experienceId).distinct().toList();
            if (used.isEmpty()) return;
            experiences.findHeads(owner).forEach(head -> experienceTitles.put(head.id(), head.title()));
            experienceHistory = revisions.of(owner, used);
        }

        Stream<UUID> positionsOf(UUID resumeId) {
            return resumePositions.getOrDefault(resumeId, List.of()).stream();
        }
    }

    /** 직무·공고·기업 필터가 가리키는 직무들과 회사. company 는 alreadyUsedFor 의 sameCompany 판정에 쓴다. */
    private record Target(Set<UUID> positionIds, UUID companyId) {}

    /**
     * 필터는 모두 AND 다. 직무·공고·기업 필터가 있으면 그 직무에 written_for 로 쓴 답변(직접 출처)을 준다.
     * 직접 출처가 0건이면 빈 배열 대신 연결 이력서가 그 직무를 지원하는 답변 중 직접 출처가 없는 것을
     * fallback "indirect" 로 준다 — 다른 직무용으로 쓴 것이 분명한 답변은 섞지 않는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SelfIntroductionList list(String q, UUID resumeId, UUID positionId, UUID postingId, UUID companyId,
                                     Competency questionType, Boolean unlinked, Integer limit) {
        if (q != null && q.length() > MAX_QUERY) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "QUERY_TOO_LONG", "q 는 100자 이하여야 합니다.");
        }
        // q 의 질의 벡터는 트랜잭션 밖에서 받는다(설계 4.3 ⑥). 못 받으면 BM25 만 쓰고 degraded:["dense"].
        boolean searching = q != null && !q.isBlank();
        DenseIndex.Query query = searching ? dense.query(q).orElse(null) : null;
        List<String> degraded = searching && query == null ? List.of("dense") : List.of();
        return readOnly.execute(status -> list(q, query, degraded, resumeId, positionId, postingId, companyId,
                questionType, unlinked, limit));
    }

    private SelfIntroductionList list(String q, DenseIndex.Query query, List<String> degraded, UUID resumeId,
                                      UUID positionId, UUID postingId, UUID companyId, Competency questionType,
                                      Boolean unlinked, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Context context = new Context();
        Target target = target(context, positionId, postingId, companyId);

        List<SelfIntroduction> values = repository.findAllByOwnerIdOrderByUpdatedAtDesc(context.owner).stream()
                .filter(value -> resumeId == null || value.getResumeIds().contains(resumeId))
                .filter(value -> unlinked == null || value.getPositionLinks().isEmpty() == unlinked)
                .filter(value -> questionType == null
                        || questionTypes(value).stream().anyMatch(type -> type.code() == questionType))
                .toList();

        String fallback = null;
        if (target != null) {
            List<SelfIntroduction> direct = values.stream()
                    .filter(value -> value.getPositionLinks().stream().anyMatch(link -> target.positionIds().contains(link.positionId())))
                    .toList();
            if (direct.isEmpty()) {
                fallback = "indirect";
                List<SelfIntroduction> unknown = values.stream().filter(value -> value.getPositionLinks().isEmpty()).toList();
                context.loadResumes(unknown.stream().flatMap(value -> value.getResumeIds().stream()));
                values = unknown.stream()
                        .filter(value -> value.getResumeIds().stream().flatMap(context::positionsOf)
                                .anyMatch(target.positionIds()::contains))
                        .toList();
            } else {
                values = direct;
            }
        }

        Map<SelfIntroduction, Double> lexical = SelfIntroductionService.scores(values, q);
        Map<SelfIntroduction, Double> scores = query == null ? lexical : fuse(lexical, denseScores(values, query, context.owner));
        if (scores != null) values = SelfIntroductionService.ranked(scores);
        List<String> terms = SelfIntroductionService.terms(q);
        List<SelfIntroduction> page = values.stream().limit(size).toList();
        context.loadFor(page);
        List<SelfIntroductionList.Item> results = page.stream()
                .map(value -> item(value, context, target, scores == null ? null : scores.get(value), terms, false))
                .toList();
        return new SelfIntroductionList(fallback, values.size(), values.size() <= size, results, degraded);
    }

    /** 필터를 지난 문항만, 질문·답변 조각 max-sim 상위 DENSE_TOP 개. 아직 적재되지 않은 문항은 BM25 로만 나온다. */
    private Map<SelfIntroduction, Double> denseScores(List<SelfIntroduction> values, DenseIndex.Query query, UUID owner) {
        Map<UUID, Double> byId = dense.selfIntros(owner, query);
        return values.stream().filter(value -> byId.containsKey(value.getId()))
                .sorted(Comparator.comparing((SelfIntroduction value) -> byId.get(value.getId())).reversed())
                .limit(DENSE_TOP)
                .collect(Collectors.toMap(value -> value, value -> byId.get(value.getId())));
    }

    /**
     * BM25 와 DENSE 를 가중 없는 RRF(k=60)로 합친다(설계 4.3 ①, 척도가 다른 점수는 더하지 않는다). 반환 점수는 RRF 값이다.
     * 리랭커(K=10)는 6단계로 미뤘다(2026-11-02 사용자 결정).
     */
    static Map<SelfIntroduction, Double> fuse(Map<SelfIntroduction, Double> lexical, Map<SelfIntroduction, Double> denseScores) {
        Map<SelfIntroduction, Double> fused = new HashMap<>();
        for (Map<SelfIntroduction, Double> signal : List.of(lexical == null ? Map.<SelfIntroduction, Double>of() : lexical,
                denseScores)) {
            List<SelfIntroduction> ordered = SelfIntroductionService.ranked(signal);
            for (int i = 0; i < ordered.size(); i++) fused.merge(ordered.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        return fused;
    }

    /** 원문은 여기서 읽는다. 목록과 같은 출처·문항 유형에 답변 전문을 싣는다. */
    public SelfIntroductionList.Item get(UUID id) {
        Context context = new Context();
        SelfIntroduction value = repository.findByIdAndOwnerId(id, context.owner)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        context.loadFor(List.of(value));
        return item(value, context, null, null, List.of(), true);
    }

    /** 남의 직무·공고·기업 id 는 404 다(소유자 격리). 공고·기업 필터만 계정의 직무 이름 전체를 읽는다. */
    private Target target(Context context, UUID positionId, UUID postingId, UUID companyId) {
        if (positionId == null && postingId == null && companyId == null) return null;
        UUID owner = context.owner;
        if (postingId != null && !postings.existsByIdAndOwnerId(postingId, owner)) throw new ResourceNotFoundException(postingId);
        if (companyId != null && !companies.existsByIdAndOwnerId(companyId, owner)) throw new ResourceNotFoundException(companyId);
        List<PositionLabel> candidates = positionId != null
                ? positions.findLabels(List.of(positionId), owner) : positions.findAllLabels(owner);
        if (positionId != null && candidates.isEmpty()) throw new ResourceNotFoundException(positionId);
        context.addPositions(candidates);
        List<PositionLabel> matching = candidates.stream()
                .filter(p -> postingId == null || postingId.equals(p.postingId()))
                .filter(p -> companyId == null || companyId.equals(p.companyId()))
                .toList();
        UUID company = companyId != null ? companyId
                : matching.stream().map(PositionLabel::companyId).filter(Objects::nonNull).findFirst().orElse(null);
        return new Target(matching.stream().map(PositionLabel::id).collect(Collectors.toSet()), company);
    }

    private SelfIntroductionList.Item item(SelfIntroduction value, Context context, Target target, Double score,
                                           List<String> terms, boolean full) {
        List<SelfIntroductionList.Direct> direct = value.getPositionLinks().stream()
                .map(link -> {
                    PositionLabel p = context.positionLabels.get(link.positionId());
                    return new SelfIntroductionList.Direct(link.positionId(), p.name(), p.companyId(), p.companyName(),
                            link.source().trust());
                })
                .sorted(Comparator.comparing(SelfIntroductionList.Direct::position))
                .toList();
        List<SelfIntroductionList.Indirect> indirect = value.getResumeIds().stream()
                .filter(context.resumeNames::containsKey)
                .sorted(Comparator.comparing(context.resumeNames::get))
                .flatMap(resumeId -> {
                    String name = context.resumeNames.get(resumeId);
                    List<PositionLabel> supported = context.positionsOf(resumeId).map(context.positionLabels::get)
                            .filter(Objects::nonNull).toList();
                    return supported.isEmpty()
                            ? Stream.of(new SelfIntroductionList.Indirect(resumeId, name, null, null, null))
                            : supported.stream().map(p -> new SelfIntroductionList.Indirect(resumeId, name, p.id(), p.name(), p.companyName()));
                })
                .toList();
        List<SelfIntroductionList.QuestionType> types = questionTypes(value);
        boolean fit = types.stream().anyMatch(type -> type.code().searchMode() == Competency.SearchMode.FIT);
        UUID targetCompany = target == null ? null : target.companyId();
        List<SelfIntroductionList.UsedFor> usedFor = direct.stream()
                .map(d -> new SelfIntroductionList.UsedFor(d.companyId(), d.company(), d.position(),
                        targetCompany == null ? null : targetCompany.equals(d.companyId()),
                        fit && (targetCompany == null || !targetCompany.equals(d.companyId()))))
                .toList();

        String answer = value.getAnswer() == null ? "" : value.getAnswer();
        String preview = full ? null : answer.length() > PREVIEW ? answer.substring(0, PREVIEW) + "…" : answer;
        Set<String> words = Set.copyOf(SelfIntroductionService.words(value));
        List<String> matched = terms.stream().filter(term -> words.stream().anyMatch(word -> word.contains(term))).toList();
        return new SelfIntroductionList.Item(value.getId(), value.getQuestion(), preview, full ? value.getAnswer() : null,
                answer.length(), value.getUpdatedAt(), score, matched, direct.isEmpty() ? "출처 미상" : "직접 출처",
                new SelfIntroductionList.Sources(direct, indirect), types, usedFor, value.getVersion(),
                value.getExperienceLinks().stream()
                        .map(link -> new SelfIntroductionList.ExperienceUse(link.experienceId(), link.experienceRevision(),
                                context.experienceTitles.get(link.experienceId()), link.source().trust(),
                                ExperienceRevisions.needsReview(context.experienceHistory.getOrDefault(link.experienceId(),
                                        List.of()), link.experienceRevision())))
                        .sorted(Comparator.comparing(SelfIntroductionList.ExperienceUse::experienceId)).toList());
    }

    /**
     * 설계 4.1 순서: 사용자가 확정한(USER) 태그가 있으면 그것, 없으면 정규식 상위 2개 추정. AI 태그는 덧붙이기만 한다 —
     * 확인 안 된 태그가 추정(특히 지원동기 같은 FIT)을 가려 alreadyUsedFor 의 재사용 금지 표시를 끄면 안 된다.
     */
    private static List<SelfIntroductionList.QuestionType> questionTypes(SelfIntroduction value) {
        List<SelfIntroduction.CompetencyLink> stored = value.getQuestionTypes().stream()
                .sorted(Comparator.comparing(SelfIntroduction.CompetencyLink::code)).toList();
        Map<Competency, SelfIntroductionList.QuestionType> types = new LinkedHashMap<>();
        stored.stream().filter(link -> link.source() == LinkSource.USER).forEach(link -> types.put(link.code(), stored(link)));
        if (types.isEmpty()) {
            Competency.estimate(value.getQuestion()).forEach(estimate -> types.put(estimate.code(),
                    new SelfIntroductionList.QuestionType(estimate.code(), estimate.code().label(), estimate.confidence(), "REGEX", "추정")));
        }
        stored.stream().filter(link -> link.source() == LinkSource.AI).forEach(link -> types.put(link.code(), stored(link)));
        return List.copyOf(types.values());
    }

    private static SelfIntroductionList.QuestionType stored(SelfIntroduction.CompetencyLink link) {
        return new SelfIntroductionList.QuestionType(link.code(), link.code().label(), null, link.source().name(), link.source().trust());
    }
}
