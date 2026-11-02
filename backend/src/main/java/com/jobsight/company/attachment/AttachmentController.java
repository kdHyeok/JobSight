package com.jobsight.company.attachment;

import com.jobsight.company.attachment.dto.AttachmentResponse;
import com.jobsight.company.common.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Tag(name = "attachments", description = "이력서 증빙 파일(jpg·png·pdf). 타인의 파일은 404.")
@RestController
@RequestMapping(ApiPaths.ATTACHMENTS)
public class AttachmentController {
    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @Operation(summary = "파일 올리기",
            description = "multipart/form-data, part 이름은 file. 타입은 파일 앞머리로 판정하며 jpg·png·pdf 만 받는다(아니면 415). 10MB 이하.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentResponse upload(@RequestPart("file") MultipartFile file) {
        return attachments.upload(file);
    }

    @Operation(summary = "파일 내려받기", description = "미리보기도 이 경로를 쓴다. inline + nosniff 로 내려간다.")
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        Attachment attachment = attachments.findOwned(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                // 브라우저가 타입을 다시 추측하지 못하게 한다 — 저장된 타입은 앞머리로 판정한 값이다.
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(attachment.getFilename(), StandardCharsets.UTF_8).toString())
                .body(attachment.getData());
    }

    @Operation(summary = "파일 메타", description = "파일명·타입·크기만. 바이트를 읽지 않는다.")
    @GetMapping("/{id}/meta")
    public AttachmentResponse meta(@PathVariable UUID id) {
        return attachments.findMeta(id);
    }

    @Operation(summary = "파일 삭제", description = "되돌릴 수 없다. 화면은 교체·제거 때 부르지 않는다 — "
            + "복제한 이력서가 같은 id 를 가리킬 수 있어서다(docs/attachments.md).")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        attachments.delete(id);
        return ResponseEntity.noContent().build();
    }
}
