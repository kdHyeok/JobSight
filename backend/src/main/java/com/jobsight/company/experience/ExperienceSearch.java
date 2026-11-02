package com.jobsight.company.experience;

import com.jobsight.company.auth.CurrentUser;
import com.jobsight.company.common.ApiRuleException;
import com.jobsight.company.common.Bm25;
import com.jobsight.company.common.ResourceNotFoundException;
import com.jobsight.company.company.CompanyRepository;
import com.jobsight.company.experience.dto.ExperienceDossier;
import com.jobsight.company.experience.dto.ExperienceFacts;
import com.jobsight.company.experience.dto.ExperienceSearchRequest;
import com.jobsight.company.experience.dto.ExperienceSearchRequest.Field;
import com.jobsight.company.experience.dto.ExperienceSearchResult;
import com.jobsight.company.position.Position;
import com.jobsight.company.position.PositionRepository;
import com.jobsight.company.posting.JobPosting;
import com.jobsight.company.posting.JobPostingRepository;
import com.jobsight.company.embedding.DenseIndex;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * experience_search — 설계 4.1~4.3. 공고를 저장하지 않고 내 경험과 그 출처를 찾는다. 신호는 LEXICAL·REQUIREMENT·DENSE(5단계, 경험 원본만),
 * 융합은 가중 RRF(k=60)다. 4단계(전환 경로 완료): 응답 형식은 3단계 그대로이고 자료원이 경험 원본이다 —
 * EXPERIENCE 후보는 경험 원본(REQUIREMENT 의 정확 일치는 experiences.skills, 본문은 사실·소재집·메모), RESUME_ROW 후보는
 * 앵커가 없는 미연결 행 묶음뿐이다. 이력서 검색과 경험 검색을 따로 운영하지 않는다.
 * 쓰기는 없다. 질의 벡터는 트랜잭션 밖에서 받고(4.3 ⑥), embedder 가 없으면 degraded:["dense"] 로 나머지 신호만 쓴다.
 */
@Service
public class ExperienceSearch {
    /** 신호 구성·가중값을 바꾸면 올린다 — 재현 정보 context.searchConfig(설계 6.7). */
    static final String SEARCH_CONFIG = "search-v2-2026-11-dense";
    private static final int RRF_K = 60;
    /** DENSE 순위에 넣는 후보 수. 가중 RRF 의 다른 신호와 같은 척도로 상위만 본다. */
    private static final int DENSE_TOP = 20;
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final int EXCERPT = 200;
    /** 토큰 포함 일치("AWS EC2" ⊇ "AWS")와 본문 언급은 0.5배(설계 4.2). */
    private static final double PARTIAL = 0.5;

    private final ExperienceService experiences;
    private final ExperienceRepository bank;
    private final PositionRepository positions;
    private final JobPostingRepository postings;
    private final CompanyRepository companies;
    private final CurrentUser currentUser;
    private final DenseIndex dense;
    private final TransactionTemplate readOnly;

    public ExperienceSearch(ExperienceService experiences, ExperienceRepository bank, PositionRepository positions,
                            JobPostingRepository postings, CompanyRepository companies, CurrentUser currentUser,
                            DenseIndex dense, PlatformTransactionManager transactions) {
        this.dense = dense;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.experiences = experiences;
        this.bank = bank;
        this.positions = positions;
        this.postings = postings;
        this.companies = companies;
        this.currentUser = currentUser;
    }

    /** 질의 대상: 다섯 칸 본문과 기술 환경 목록. 저장하지 않은 공고면 saved=false. */
    private record Target(String label, boolean saved, Map<Field, String> texts, List<String> techStack) {
    }

    /** 점수에 쓰는 요구 하나(SKILL). key 는 KeyNormalizer.skill 을 거친 값이다. */
    private record Need(String key, Field field, String quote) {
    }

    /**
     * 후보 하나의 대조 자료: 기술 태그(정확 일치 — 행 techStack 또는 경험 skills), 언급 키(포함 일치), 평문 키(TAG_MISMATCH 판정),
     * BM25 단어. group(RESUME_ROW)과 experience(EXPERIENCE) 중 하나만 있다.
     */
    private record Candidate(String key, RowGroups.Group group, Experience experience, Set<String> tags,
                             Set<String> mentions, String flat, List<String> words) {
        double match(String key) {
            if (tags.contains(key)) return 1;
            return mentions.contains(key) ? PARTIAL : 0;
        }

        /** 맵 키로 쓴다. 묶음 키가 정체성이다 — 기본 record 동등성은 이력서 문서 전체를 해시한다. */
        @Override
        public boolean equals(Object other) {
            return other instanceof Candidate that && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    /**
     * 트랜잭션은 여기 두지 않는다(설계 4.3 ⑥): 대상 직무를 읽는 readOnly 한 번 → 트랜잭션 밖에서 질의 벡터(2초, 실패하면 degraded)
     * → 나머지를 readOnly 한 번. embedder 를 기다리는 동안 DB 연결을 쥐지 않는다.
     */
    public ExperienceSearchResult search(ExperienceSearchRequest request) {
        if ((request.positionId() == null) == (request.posting() == null)) {
            throw new ApiRuleException(HttpStatus.BAD_REQUEST, "QUERY_REQUIRED",
                    "positionId(등록 직무) 또는 posting(저장하지 않는 공고) 중 하나만 보내세요.");
        }
        Target target = readOnly.execute(status ->
                request.positionId() != null ? saved(request.positionId()) : unsaved(request.posting()));
        String queryText = Stream.concat(Stream.of(target.label()), Stream.concat(target.texts().values().stream(),
                target.techStack().stream())).filter(Objects::nonNull).collect(Collectors.joining(" "));
        DenseIndex.Query query = dense.query(queryText).orElse(null);
        return readOnly.execute(status -> rank(request, target, queryText, query));
    }

    private ExperienceSearchResult rank(ExperienceSearchRequest request, Target target, String queryText,
                                        DenseIndex.Query query) {
        List<RowGroups.Doc> docs = experiences.docs();
        List<RowGroups.Group> unlinked = RowGroups.unlinked(docs);
        List<Experience> bank = this.bank.findAllByOwnerIdOrderByKindAscTitleKeyAscIdAsc(currentUser.id());
        List<Candidate> candidates = Stream.concat(bank.stream().map(this::candidate),
                unlinked.stream().map(ExperienceSearch::candidate)).toList();
        Map<UUID, List<ExperienceSearchResult.Source>> linkedRows = new HashMap<>();
        docs.forEach(doc -> RowGroups.rows(doc).stream().filter(row -> row.experienceId() != null)
                .forEach(row -> linkedRows.computeIfAbsent(row.experienceId(), id -> new ArrayList<>()).add(source(row))));

        // 요구: 클라이언트가 공고에서 뽑은 것을 우선한다. 없으면 기술 환경 목록과 일반 사전으로 뽑는다(4.2 ①).
        boolean fromClient = request.requirements() != null && !request.requirements().isEmpty();
        Map<String, Need> needs = new LinkedHashMap<>();
        List<ExperienceSearchResult.Qualification> qualifications = new ArrayList<>();
        if (fromClient) {
            for (ExperienceSearchRequest.Requirement requirement : request.requirements()) {
                if (requirement.kind() == ExperienceSearchRequest.Kind.QUALIFICATION) {
                    qualifications.add(new ExperienceSearchResult.Qualification(requirement.text(), requirement.field(),
                            requirement.quote()));
                } else {
                    add(needs, KeyNormalizer.skill(requirement.text()), requirement.field(), requirement.quote());
                }
            }
        } else {
            target.techStack().forEach(tech -> add(needs, KeyNormalizer.skill(tech), Field.techStack, tech));
            target.texts().forEach((field, text) -> KeyNormalizer.dictionaryTerms(text)
                    .forEach(key -> add(needs, key, field, null)));
        }

        // REQUIREMENT: Σ 칸 가중 × 일치(1·0.5) × 희소도 ln(1 + N/df). N = 묶음 수, df = 그 키에 맞는 묶음 수.
        int n = candidates.size();
        Map<String, Long> df = new HashMap<>();
        needs.values().forEach(need -> df.computeIfAbsent(need.key(),
                key -> candidates.stream().filter(c -> c.match(key) > 0).count()));
        Map<Candidate, Double> requirementScores = new HashMap<>();
        Map<Candidate, List<ExperienceSearchResult.Why>> requirementWhy = new HashMap<>();
        for (Candidate candidate : candidates) {
            double score = 0;
            for (Need need : needs.values()) {
                double match = candidate.match(need.key());
                if (match == 0) continue;
                score += need.field().weight() * match * Math.log(1 + (double) n / df.get(need.key()));
                requirementWhy.computeIfAbsent(candidate, c -> new ArrayList<>()).add(new ExperienceSearchResult.Why(
                        "REQUIREMENT", null, List.of(need.key()), need.field(), need.quote(), null));
            }
            if (score > 0) requirementScores.put(candidate, score);
        }

        // LEXICAL: 다섯 칸 본문은 요구 태그 유무와 관계없이 항상 질의에 넣는다(4.2).
        List<String> terms = Bm25.terms(queryText);
        Map<Candidate, List<String>> documents = new HashMap<>();
        candidates.forEach(candidate -> documents.put(candidate, candidate.words()));
        Map<Candidate, Double> lexicalScores = Bm25.scores(documents, terms);
        if (lexicalScores == null) lexicalScores = Map.of();

        // DENSE(5단계): 같은 질의 텍스트의 벡터와 경험 조각들의 max-sim. RESUME_ROW 는 대상이 아니다(4.2). 상위 DENSE_TOP 만 순위에 넣는다 —
        // cosine 은 모든 후보에 값이 있어서 다 넣으면 관계없는 경험까지 결과를 채운다.
        UUID owner = currentUser.id();
        Map<Candidate, Double> denseScores = new HashMap<>();
        Map<Candidate, String> denseField = new HashMap<>();
        if (query != null) {
            Map<UUID, DenseIndex.Hit> hits = dense.experiences(owner, query);
            for (Candidate candidate : candidates) {
                DenseIndex.Hit hit = candidate.experience() == null ? null : hits.get(candidate.experience().getId());
                if (hit == null) continue;
                denseScores.put(candidate, hit.score());
                denseField.put(candidate, hit.field());
            }
        }

        // 가중 RRF. 3단계 후보(이력서 행)는 모두 사용자가 쓴 원문이라 출처 가중이 1이다. 동점은 묶음 키로 정렬한다.
        Map<Candidate, Integer> requirementRank = ranks(requirementScores);
        Map<Candidate, Integer> lexicalRank = ranks(lexicalScores);
        Map<Candidate, Integer> denseRank = new HashMap<>(ranks(denseScores));
        denseRank.values().removeIf(rank -> rank > DENSE_TOP);
        Map<Candidate, Double> fused = new HashMap<>();
        requirementRank.forEach((c, rank) -> fused.merge(c, 1.0 / (RRF_K + rank), Double::sum));
        lexicalRank.forEach((c, rank) -> fused.merge(c, 1.0 / (RRF_K + rank), Double::sum));
        denseRank.forEach((c, rank) -> fused.merge(c, 1.0 / (RRF_K + rank), Double::sum));
        int limit = Math.min(Math.max(request.limit() == null ? DEFAULT_LIMIT : request.limit(), 1), MAX_LIMIT);
        List<Candidate> ordered = fused.entrySet().stream()
                .sorted(Map.Entry.<Candidate, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().key()))
                .limit(limit).map(Map.Entry::getKey).toList();
        List<ExperienceSearchResult.Result> results = new ArrayList<>();
        for (Candidate candidate : ordered) {
            List<ExperienceSearchResult.Why> why = new ArrayList<>(requirementWhy.getOrDefault(candidate, List.of()));
            if (lexicalRank.containsKey(candidate)) {
                why.add(new ExperienceSearchResult.Why("LEXICAL", lexicalRank.get(candidate),
                        matchedTerms(candidate.words(), terms), null, null, null));
            }
            if (denseRank.containsKey(candidate)) {
                why.add(new ExperienceSearchResult.Why("DENSE", denseRank.get(candidate), List.of(), null, null,
                        denseField.get(candidate)));
            }
            Experience experience = candidate.experience();
            if (experience != null) {
                // 경험 원본: source 는 없고(원문 근거는 experience_index 상세의 facts.evidence), alsoIn 은 이 경험을 쓰는 이력서 행이다.
                results.add(new ExperienceSearchResult.Result(results.size() + 1, "EXPERIENCE", experience.getKind(),
                        experience.getTitle(), experiences.period(experience), null,
                        linkedRows.getOrDefault(experience.getId(), List.of()), why, experience.getId(),
                        experience.getRevision(), experience.isReviewed()));
                continue;
            }
            RowGroups.Group group = candidate.group();
            List<ExperienceSearchResult.Source> sources = group.rows().stream().map(ExperienceSearch::source).toList();
            results.add(new ExperienceSearchResult.Result(results.size() + 1, "RESUME_ROW", group.kind(), group.title(),
                    group.period(), sources.get(0), sources.subList(1, sources.size()), why, null, null, null));
        }

        // uncovered: 지원자격·요구 역량 칸의 키 중 받쳐 줄 묶음이 없는 것. 우대·기술 환경 나열은 결핍이 아니다.
        Map<String, ExperienceSearchResult.Uncovered> uncovered = new LinkedHashMap<>();
        for (Need need : needs.values()) {
            if (!need.field().reportsGap() || df.get(need.key()) > 0 || uncovered.containsKey(need.key())) continue;
            boolean visible = candidates.stream().anyMatch(c -> c.flat().contains(need.key()));
            uncovered.put(need.key(), new ExperienceSearchResult.Uncovered(need.key(), need.field(),
                    visible ? "TAG_MISMATCH_POSSIBLE" : "NO_MATCH"));
        }

        ExperienceSearchResult.Query echo = new ExperienceSearchResult.Query(target.label(), target.saved(),
                fromClient ? "CLIENT" : "SERVER_DICTIONARY",
                needs.values().stream().map(need -> new ExperienceSearchResult.RequirementKey(need.key(), need.field()))
                        .toList());
        // 미등록 행이 남아 있거나 경험 원본이 없으면 커버리지가 불완전하다 — 결핍을 단정하지 않게 한다(5.5).
        boolean coverageIncomplete = !unlinked.isEmpty() || bank.isEmpty();
        DenseIndex.Status index = dense.experienceStatus(owner);
        return new ExperienceSearchResult(echo, new ExperienceSearchResult.Context(dataVersion(docs, bank), SEARCH_CONFIG,
                query == null ? null : query.model(), index.indexGeneration(), index.indexBehind()),
                coverageIncomplete, fromClient ? List.of() : List.of("REQUIREMENTS_PARTIAL"),
                query == null ? List.of("dense") : List.of(), List.copyOf(uncovered.values()), qualifications, results);
    }

    /** 등록 직무의 다섯 칸. 직무·공고·기업 모두 소유자 조건으로 읽는다. 남의 직무는 404. */
    private Target saved(UUID positionId) {
        UUID owner = currentUser.id();
        Position position = positions.findByIdAndOwnerId(positionId, owner)
                .orElseThrow(() -> new ResourceNotFoundException(positionId));
        JobPosting posting = postings.findByIdAndOwnerId(position.getPostingId(), owner).orElse(null);
        String company = posting == null ? null
                : companies.findByIdAndOwnerId(posting.getCompanyId(), owner).map(c -> c.getName()).orElse(null);
        Map<Field, String> texts = new EnumMap<>(Field.class);
        put(texts, Field.qualifications, posting == null ? null : posting.getQualifications());
        put(texts, Field.responsibilities, position.getResponsibilities());
        put(texts, Field.requiredSkills, position.getRequiredSkills());
        put(texts, Field.preferredSkills, position.getPreferredSkills());
        String label = company == null ? position.getName() : company + " · " + position.getName();
        return new Target(label, true, texts, List.copyOf(position.getTechStack()));
    }

    /** 저장하지 않는 공고. 여기서 어떤 엔티티도 만들지 않는다. */
    private static Target unsaved(ExperienceSearchRequest.Posting posting) {
        Map<Field, String> texts = new EnumMap<>(Field.class);
        put(texts, Field.qualifications, posting.qualifications());
        put(texts, Field.responsibilities, posting.responsibilities());
        put(texts, Field.requiredSkills, posting.requiredSkills());
        put(texts, Field.preferredSkills, posting.preferredSkills());
        String label = Stream.of(posting.companyName(), posting.positionName())
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.joining(" · "));
        return new Target(label.isEmpty() ? null : label, false, texts,
                posting.techStack() == null ? List.of() : posting.techStack());
    }

    private static void put(Map<Field, String> texts, Field field, String value) {
        if (value != null && !value.isBlank()) texts.put(field, value);
    }

    private static void add(Map<String, Need> needs, String key, Field field, String quote) {
        if (!key.isEmpty()) needs.putIfAbsent(key + "|" + field, new Need(key, field, quote));
    }

    /** 경험 원본 후보. 검색 텍스트는 제목·메모·사실(주장·근거 인용)·소재집이다. unverified·cautions 는 넣지 않는다(6.5). */
    private Candidate candidate(Experience experience) {
        ExperienceFacts facts = experiences.facts(experience);
        ExperienceDossier dossier = experiences.dossier(experience);
        StringBuilder text = new StringBuilder();
        Stream.of(experience.getTitle(), experience.getMemo(), facts.period(), facts.role(),
                        dossier == null ? null : dossier.narrative())
                .filter(Objects::nonNull).forEach(part -> text.append(part).append('\n'));
        Stream.of(facts.actions(), facts.results()).filter(Objects::nonNull).flatMap(List::stream)
                .forEach(item -> text.append(item.text()).append('\n'));
        if (facts.evidence() != null) facts.evidence().forEach(ev -> text.append(ev.quote()).append('\n'));
        if (dossier != null && dossier.phrasings() != null) dossier.phrasings().forEach(p -> text.append(p).append('\n'));
        String body = text.toString();
        Set<String> mentions = new LinkedHashSet<>(KeyNormalizer.mentions(body));
        experience.getDomains().forEach(mentions::add);
        return new Candidate(experience.getKind() + "|" + experience.getTitleKey() + "|~" + experience.getId(), null,
                experience, new LinkedHashSet<>(experience.getSkills()), mentions, KeyNormalizer.key(body),
                Bm25.tokenize(body + "\n" + String.join(" ", experience.getSkills())));
    }

    private static Candidate candidate(RowGroups.Group group) {
        Set<String> tags = new LinkedHashSet<>();
        Set<String> mentions = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder();
        for (RowGroups.Row row : group.rows()) {
            tags.addAll(KeyNormalizer.skills(row.techStack()));
            mentions.addAll(KeyNormalizer.mentions(row.text()));
            text.append(row.text()).append('\n');
        }
        return new Candidate(group.key(), group, null, tags, mentions, KeyNormalizer.key(text.toString()),
                Bm25.tokenize(text.toString()));
    }

    /** 점수 내림차순, 같으면 묶음 키. 1부터. */
    private static Map<Candidate, Integer> ranks(Map<Candidate, Double> scores) {
        List<Candidate> sorted = scores.entrySet().stream()
                .sorted(Map.Entry.<Candidate, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().key()))
                .map(Map.Entry::getKey).toList();
        Map<Candidate, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) ranks.put(sorted.get(i), i + 1);
        return ranks;
    }

    private static List<String> matchedTerms(List<String> words, List<String> terms) {
        return terms.stream().filter(term -> words.stream().anyMatch(word -> word.contains(term))).limit(5).toList();
    }

    private static ExperienceSearchResult.Source source(RowGroups.Row row) {
        String text = row.text();
        return new ExperienceSearchResult.Source(row.resume().id(), row.resume().name(), row.section(), row.index(),
                row.resume().version(), sha256(text).substring(0, 16),
                text.length() <= EXCERPT ? text : text.substring(0, EXCERPT) + "…");
    }

    /** 후보로 쓴 이력서·경험 (id, version) 목록의 해시. 같은 dataVersion·searchConfig·질의면 같은 결과다(동점은 키 정렬). */
    private static String dataVersion(List<RowGroups.Doc> docs, List<Experience> bank) {
        String joined = Stream.concat(docs.stream().map(doc -> doc.id() + ":" + doc.version()),
                        bank.stream().map(experience -> experience.getId() + ":" + experience.getVersion()))
                .sorted().collect(Collectors.joining(","));
        return sha256(joined).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
