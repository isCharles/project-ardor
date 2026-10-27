package com.projectardor.assistant.conversation.web;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.assistant.conversation.service.ChatImageService;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

@RestController
@RequestMapping("/api/agent/images")
public class ChatImageController {
    private final ChatImageService images;

    public ChatImageController(ChatImageService images) {
        this.images = images;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ChatImageService.Upload upload(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        return images.upload(principal.userId(), file);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> read(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id) {
        ChatImageService.StoredImage image = images.read(principal.userId(), id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .body(image.content());
    }
}
