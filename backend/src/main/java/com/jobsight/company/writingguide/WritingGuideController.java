package com.jobsight.company.writingguide;

import com.jobsight.company.common.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.WRITING_GUIDE)
@Tag(name = "writing-guide", description = "계정당 작성 기준 Markdown 하나. 인증 필수.")
public class WritingGuideController {
    private final WritingGuideService service;

    public WritingGuideController(WritingGuideService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "작성 기준 조회",
            description = "{version, body, updatedAt}. body 는 사용자가 정한 선호 문체·피할 표현·성과 설명 범위(Markdown)다. "
                    + "자소서·이력서 문구를 쓰기 전에 읽고 따른다. 경험별 주의사항은 experience_index 상세의 dossier.cautions 에 있다. "
                    + "아직 쓰지 않았으면 version 0 과 빈 body 다. 수정은 웹에서만 한다.")
    public WritingGuideService.Guide get() {
        return service.get();
    }

    @PutMapping
    @Operation(summary = "작성 기준 저장", description = "expectedVersion 은 읽은 version(처음이면 0). 다르면 409 WRITING_GUIDE_STALE.")
    public WritingGuideService.Guide update(@Valid @RequestBody WritingGuideService.UpdateRequest request) {
        return service.update(request);
    }
}
