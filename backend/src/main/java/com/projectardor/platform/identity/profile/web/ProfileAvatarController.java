package com.projectardor.platform.identity.profile.web;

import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.identity.profile.service.AvatarImageNormalizer;
import com.projectardor.platform.identity.profile.service.ProfileAvatarService;

@RestController
@RequestMapping("/api/profile/avatar")
public class ProfileAvatarController {
    private final ProfileAvatarService service;
    public ProfileAvatarController(ProfileAvatarService service) { this.service = service; }
    @GetMapping
    ResponseEntity<ProfileAvatarService.View> get(@AuthenticationPrincipal ArdorPrincipal principal) {
        return response(service.read(principal.userId()));
    }
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ProfileAvatarService.View> upload(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam UUID expectedUserId, @RequestParam MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > AvatarImageNormalizer.MAX_UPLOAD_BYTES)
            throw new IllegalArgumentException("头像需为不超过 2 MB 的 PNG 或 JPEG 图片");
        return response(service.upload(principal.userId(), expectedUserId, file.getBytes()));
    }
    @DeleteMapping
    ResponseEntity<ProfileAvatarService.View> remove(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam UUID expectedUserId) { return response(service.remove(principal.userId(), expectedUserId)); }
    private static ResponseEntity<ProfileAvatarService.View> response(ProfileAvatarService.View view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
}
