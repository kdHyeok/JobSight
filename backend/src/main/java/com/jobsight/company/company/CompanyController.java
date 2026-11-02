package com.jobsight.company.company;

import com.jobsight.company.common.ApiPaths;
import com.jobsight.company.company.dto.CompanyRequest;
import com.jobsight.company.company.dto.CompanyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 모든 응답은 로그인 계정이 소유한 기업으로 한정된다. 타인 소유 id 는 404. */
@Tag(name = "companies", description = "로그인 계정 소유의 기업 정보 CRUD. 인증 필수.")
@RestController
@RequestMapping(ApiPaths.COMPANIES)
public class CompanyController {
    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    @Operation(summary = "내 기업 목록", description = "최근 수정 순. 공고 리스트는 담기지 않는다(상세에서 채워진다).")
    @GetMapping
    public List<CompanyResponse> findAll() {
        return service.findAll();
    }

    @Operation(summary = "기업 상세",
            description = "openPostings 에 마감이 지나지 않은 이 기업의 공고가 마감 임박 순으로 담긴다. "
                    + "linkedSelfIntroCount 는 이 기업을 지우면 소속 공고 직무용 직접 출처 연결이 함께 사라지는 자기소개 문항 수다"
                    + "(답변은 남는다). 삭제 전에 알린다. 타인 소유이거나 없으면 404.")
    @GetMapping("/{id}")
    public CompanyResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @Operation(summary = "기업 생성", description = "소유자는 현재 로그인 계정으로 고정된다. 업종은 여러 개 보낼 수 있다.")
    @PostMapping
    public ResponseEntity<CompanyResponse> create(@Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Operation(summary = "기업 수정", description = "타인 소유이거나 없으면 404.")
    @PutMapping("/{id}")
    public CompanyResponse update(@PathVariable UUID id, @Valid @RequestBody CompanyRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "기업 삭제", description = "이 기업에 연결된 채용공고도 함께 삭제된다. 타인 소유이거나 없으면 404.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
