package com.jobsight.company.selfintro;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.CompanyRepository;
import com.jobsight.company.position.PositionService;
import com.jobsight.company.position.dto.PositionResponse;
import com.jobsight.company.posting.JobPostingRepository;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeRepository;
import com.jobsight.company.selfintro.dto.SelfIntroductionList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

    private final SelfIntroductionRepository repository;
    private final PositionService positions;
    private final ResumeRepository resumes;
    private final JobPostingRepository postings;
    private final CompanyRepository companies;
    private final CurrentUser currentUser;

    public SelfIntroductionAgentService(SelfIntroductionRepository repository, PositionService positions,
                                        ResumeRepository resumes, JobPostingRepository postings,
                                        CompanyRepository companies, CurrentUser currentUser) {
        this.repository = repository;
        this.positions = positions;
        this.resumes = resumes;
        this.postings = postings;
        this.companies = companies;
        this.currentUser = currentUser;
    }

    /** 계정의 직무(공고·기업 이름 포함)와 이력서를 한 번씩 읽어 둔다. */
    private record Context(Map<UUID, PositionResponse> positions, Map<UUID, Resume> resumes) {}

    /** 직무·공고·기업 필터가 가리키는 직무들과 회사. company 는 alreadyUsedFor 의 sameCompany 판정에 쓴다. */
    private record Target(Set<UUID> positionIds, UUID companyId) {}

    /**
     * 필터는 모두 AND 다. 직무·공고·기업 필터가 있으면 그 직무에 written_for 로 쓴 답변(직접 출처)을 준다.
     * 직접 출처가 0건이면 빈 배열 대신 연결 이력서가 그 직무를 지원하는 답변 중 직접 출처가 없는 것을
     * fallback "indirect" 로 준다 — 다른 직무용으로 쓴 것이 분명한 답변은 섞지 않는다.
     */
    public SelfIntroductionList list(String q, UUID resumeId, UUID positionId, UUID postingId, UUID companyId,
                                     Competency questionType, Boolean unlinked, Integer limit) {
        if (q != null && q.length() > MAX_QUERY) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "QUERY_TOO_LONG", "q 는 100자 이하여야 합니다.");
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Context context = context();
        Target target = target(context, positionId, postingId, companyId);

        List<SelfIntroduction> values = repository.findAllByOwnerIdOrderByUpdatedAtDesc(currentUser.id()).stream()
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
                values = values.stream()
                        .filter(value -> value.getPositionLinks().isEmpty())
                        .filter(value -> value.getResumeIds().stream().map(context.resumes()::get).filter(Objects::nonNull)
                                .anyMatch(resume -> resume.getPositionIds().stream().anyMatch(target.positionIds()::contains)))
                        .toList();
            } else {
                values = direct;
            }
        }

        Map<SelfIntroduction, Double> scores = SelfIntroductionService.scores(values, q);
        if (scores != null) values = SelfIntroductionService.ranked(scores);
        List<String> terms = SelfIntroductionService.terms(q);
        List<SelfIntroductionList.Item> results = values.stream().limit(size)
                .map(value -> item(value, context, target, scores == null ? null : scores.get(value), terms, false))
                .toList();
        return new SelfIntroductionList(fallback, values.size(), values.size() <= size, results);
    }

    /** 원문은 여기서 읽는다. 목록과 같은 출처·문항 유형에 답변 전문을 싣는다. */
    public SelfIntroductionList.Item get(UUID id) {
        SelfIntroduction value = repository.findByIdAndOwnerId(id, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException(id));
        return item(value, context(), null, null, List.of(), true);
    }

    private Context context() {
        UUID owner = currentUser.id();
        return new Context(
                positions.findAll(null).stream().collect(Collectors.toMap(PositionResponse::id, Function.identity())),
                resumes.findAllByOwnerIdOrderByUpdatedAtDesc(owner).stream().collect(Collectors.toMap(Resume::getId, Function.identity())));
    }

    /** 남의 직무·공고·기업 id 는 404 다(소유자 격리). */
    private Target target(Context context, UUID positionId, UUID postingId, UUID companyId) {
        if (positionId == null && postingId == null && companyId == null) return null;
        UUID owner = currentUser.id();
        if (positionId != null && !context.positions().containsKey(positionId)) throw new ResourceNotFoundException(positionId);
        if (postingId != null) postings.findByIdAndOwnerId(postingId, owner).orElseThrow(() -> new ResourceNotFoundException(postingId));
        if (companyId != null) companies.findByIdAndOwnerId(companyId, owner).orElseThrow(() -> new ResourceNotFoundException(companyId));
        List<PositionResponse> matching = context.positions().values().stream()
                .filter(p -> positionId == null || p.id().equals(positionId))
                .filter(p -> postingId == null || postingId.equals(p.postingId()))
                .filter(p -> companyId == null || companyId.equals(p.companyId()))
                .toList();
        UUID company = companyId != null ? companyId
                : matching.stream().map(PositionResponse::companyId).filter(Objects::nonNull).findFirst().orElse(null);
        return new Target(matching.stream().map(PositionResponse::id).collect(Collectors.toSet()), company);
    }

    private SelfIntroductionList.Item item(SelfIntroduction value, Context context, Target target, Double score,
                                           List<String> terms, boolean full) {
        List<SelfIntroductionList.Direct> direct = value.getPositionLinks().stream()
                .map(link -> {
                    PositionResponse p = context.positions().get(link.positionId());
                    return new SelfIntroductionList.Direct(link.positionId(), p.name(), p.companyId(), p.companyName(),
                            link.source().trust());
                })
                .sorted(Comparator.comparing(SelfIntroductionList.Direct::position))
                .toList();
        List<SelfIntroductionList.Indirect> indirect = value.getResumeIds().stream()
                .map(context.resumes()::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Resume::getName))
                .flatMap(resume -> resume.getPositionIds().isEmpty()
                        ? Stream.of(new SelfIntroductionList.Indirect(resume.getId(), resume.getName(), null, null, null))
                        : resume.getPositionIds().stream().map(context.positions()::get).filter(Objects::nonNull)
                            .map(p -> new SelfIntroductionList.Indirect(resume.getId(), resume.getName(), p.id(), p.name(), p.companyName())))
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
                new SelfIntroductionList.Sources(direct, indirect), types, usedFor);
    }

    /** 저장된 태그(USER·AI)가 있으면 그것, 없으면 정규식 상위 2개 추정(설계 4.1). */
    private static List<SelfIntroductionList.QuestionType> questionTypes(SelfIntroduction value) {
        Collection<SelfIntroduction.CompetencyLink> stored = value.getQuestionTypes();
        if (!stored.isEmpty()) {
            return stored.stream().sorted(Comparator.comparing(SelfIntroduction.CompetencyLink::code))
                    .map(link -> new SelfIntroductionList.QuestionType(link.code(), link.code().label(), null,
                            link.source().name(), link.source().trust()))
                    .toList();
        }
        return Competency.estimate(value.getQuestion()).stream()
                .map(estimate -> new SelfIntroductionList.QuestionType(estimate.code(), estimate.code().label(),
                        estimate.confidence(), "REGEX", "추정"))
                .toList();
    }
}
