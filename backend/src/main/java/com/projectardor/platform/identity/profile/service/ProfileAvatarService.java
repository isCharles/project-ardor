package com.projectardor.platform.identity.profile.service;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.platform.identity.profile.repository.ProfileAvatarStore;

@Service
public class ProfileAvatarService {
    private final ProfileAvatarStore store;
    private final AvatarImageNormalizer normalizer;
    public ProfileAvatarService(ProfileAvatarStore store, AvatarImageNormalizer normalizer) { this.store=store; this.normalizer=normalizer; }
    @Transactional(readOnly = true)
    public View read(UUID owner) { return store.find(owner).map(image -> view(owner, image)).orElse(new View(owner, null, null)); }
    public View upload(UUID owner, UUID expectedOwner, byte[] image) {
        requireOwner(owner, expectedOwner);
        // Decode before the short database write; malformed files never replace the previous image.
        return view(owner, store.save(owner, normalizer.normalize(image)));
    }
    public View remove(UUID owner, UUID expectedOwner) {
        requireOwner(owner, expectedOwner); store.remove(owner); return new View(owner, null, null);
    }
    private static void requireOwner(UUID owner, UUID expected) {
        if (!owner.equals(expected)) throw new IllegalStateException("账户已变化，请刷新设置后重试");
    }
    private static View view(UUID owner, ProfileAvatarStore.Image image) {
        return new View(owner, "data:image/png;base64," + Base64.getEncoder().encodeToString(image.png()), image.updatedAt());
    }
    public record View(UUID userId, String imageDataUrl, Instant updatedAt) {}
}
