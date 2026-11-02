package com.jobsight.company.experience;

import com.jobsight.company.resume.ResumeContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 묶음 함수 — 이력서 경험 행을 (kind, title_key, 시작 연월 숫자)로 묶는다(설계 7절 3단계 ②).
 * experience_index 의 unlinkedRows, resume_get·resume_create 의 missing[], experience_search 의 RESUME_ROW 후보,
 * 4단계 가져오기 계획(6.3)이 모두 이 함수 하나를 쓴다. 키가 갈리면 목차와 missing 이 서로 다른 말을 한다.
 * 제목이 빈 행은 묶지 않는다(가리킬 이름이 없다).
 */
public final class RowGroups {
    private RowGroups() {
    }

    /** 이력서 한 버전. 묶음 출처에 이름과 version 을 싣는다. */
    public record Doc(UUID id, String name, long version, ResumeContent content) {
    }

    /** 경험 섹션의 행 하나. text 는 검색·해시·발췌용 평문이다. */
    public record Row(Doc resume, String section, int index, ExperienceKind kind, String title,
                      String startYm, String endYm, String techStack, String text, UUID experienceId,
                      Integer experienceRevision) {
        public String groupKey() {
            return kind + "|" + KeyNormalizer.key(title) + "|" + KeyNormalizer.yearMonth(startYm);
        }

        public String period() {
            return RowGroups.period(startYm, endYm);
        }
    }

    /** 같은 묶음 키의 행들. 대표 제목·기간은 첫 행(가장 최근에 고친 이력서의 앞 행)이다. */
    public record Group(String key, ExperienceKind kind, List<Row> rows) {
        public String title() {
            return rows.get(0).title();
        }

        public String period() {
            return rows.get(0).period();
        }
    }

    /**
     * 이력서 하나의 경험 행(프로젝트·경력·대내외활동·교육이수). null 행과, 앵커 없이 제목만 빈 행은 뺀다.
     * 제목이 빈 행도 앵커가 있으면 남긴다 — 그 경험의 사용처다(상세 rows·목차·missing). 묶음(unlinked)에는 앵커가 없는 행만 들어간다.
     */
    public static List<Row> rows(Doc doc) {
        ResumeContent c = doc.content();
        List<Row> rows = new ArrayList<>();
        each(c.projects(), (row, i) -> rows.add(new Row(doc, "projects", i, ExperienceKind.PROJECT, row.name(),
                row.startYm(), row.endYm(), row.techStack(),
                join(row.name(), row.summary(), row.techStack(), row.role(), row.outcome(), row.description()),
                row.experienceId(), row.experienceRevision())));
        each(c.experiences(), (row, i) -> rows.add(new Row(doc, "experiences", i, ExperienceKind.CAREER, row.company(),
                row.startYm(), row.endYm(), null, join(row.company(), row.description()), row.experienceId(), row.experienceRevision())));
        each(c.activities(), (row, i) -> rows.add(new Row(doc, "activities", i, ExperienceKind.ACTIVITY, row.name(),
                row.startYm(), row.endYm(), null, join(row.name(), row.organizer(), row.description()),
                row.experienceId(), row.experienceRevision())));
        each(c.trainings(), (row, i) -> rows.add(new Row(doc, "trainings", i, ExperienceKind.TRAINING, row.name(),
                row.startYm(), row.endYm(), null, join(row.name(), row.institution(), row.description()),
                row.experienceId(), row.experienceRevision())));
        return rows.stream().filter(row -> row.experienceId() != null || !KeyNormalizer.key(row.title()).isEmpty()).toList();
    }

    /**
     * 여러 이력서의 미연결 행(경험 앵커가 없는 행)을 묶는다 — 은행에 없는 행이다(4단계). 앵커가 있는 행은 경험 원본이 대표한다.
     * 정렬은 종류 순서, 같은 종류 안에서는 처음 나온 순서(docs 순서를 따른다).
     */
    public static List<Group> unlinked(List<Doc> docs) {
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        docs.stream().flatMap(doc -> rows(doc).stream()).filter(row -> row.experienceId() == null)
                .forEach(row -> byKey.computeIfAbsent(row.groupKey(), key -> new ArrayList<>()).add(row));
        return byKey.entrySet().stream()
                .map(entry -> new Group(entry.getKey(), entry.getValue().get(0).kind(), List.copyOf(entry.getValue())))
                .sorted(Comparator.comparing(Group::kind))
                .toList();
    }

    static String period(String start, String end) {
        String from = start == null ? "" : start.trim();
        String to = end == null ? "" : end.trim();
        return from.isEmpty() && to.isEmpty() ? "" : from + "~" + to;
    }

    private static String join(String... parts) {
        return Stream.of(parts).filter(Objects::nonNull).map(String::trim).filter(part -> !part.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private interface IndexedConsumer<T> {
        void accept(T row, int index);
    }

    /** 저장된 문서의 null 행은 건너뛰되 행 번호는 원래 위치를 쓴다 — 행 도구의 index 와 같아야 한다. */
    private static <T> void each(List<T> list, IndexedConsumer<T> consumer) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) != null) consumer.accept(list.get(i), i);
        }
    }
}
