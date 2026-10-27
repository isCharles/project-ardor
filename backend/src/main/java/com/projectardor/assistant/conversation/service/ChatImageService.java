package com.projectardor.assistant.conversation.service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.common.web.ResourceNotFoundException;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;

@Service
public class ChatImageService {
    public static final int MAX_IMAGES = 4;
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private final JdbcTemplate jdbc;

    public ChatImageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Upload upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择图片");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("单张图片不能超过 8 MB");
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("图片读取失败，请重新选择", exception);
        }
        if (content.length == 0 || content.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("单张图片不能超过 8 MB");
        }
        String mediaType = detectMediaType(content);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_images(id,user_id,media_type,content) VALUES(?,?,?,?)",
                id, userId, mediaType, content);
        return new Upload(id, mediaType);
    }

    public StoredImage read(UUID userId, UUID id) {
        return jdbc.query("SELECT media_type,content FROM chat_images WHERE id=? AND user_id=?",
                (rs, row) -> new StoredImage(rs.getBytes("content"), rs.getString("media_type")), id, userId)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("图片不存在"));
    }

    public void requireOwned(UUID userId, List<UUID> imageIds) {
        load(userId, imageIds);
    }

    public List<Content> contents(UUID userId, String text, List<UUID> imageIds) {
        List<StoredImage> images = load(userId, imageIds);
        if (images.isEmpty()) return List.of(TextContent.from(text));
        return contents(text, images);
    }

    /**
     * History replay is best effort: a turn whose upload is gone (for example an abandoned
     * upload past its retention) degrades to text instead of failing the current request.
     */
    public List<Content> historyContents(UUID userId, String text, List<UUID> imageIds) {
        List<StoredImage> images = new ArrayList<>();
        int missing = 0;
        for (UUID id : normalize(imageIds)) {
            try {
                images.add(read(userId, id));
            } catch (ResourceNotFoundException gone) {
                missing++;
            }
        }
        if (missing == 0) return images.isEmpty() ? List.of(TextContent.from(text)) : contents(text, images);
        String note = "（此前消息中的 " + missing + " 张图片已不可用，本次未发送；不要猜测图片内容。）";
        String combined = text == null || text.isBlank() ? note : text + "\n\n" + note;
        List<Content> result = new ArrayList<>();
        result.add(TextContent.from(combined));
        for (StoredImage image : images) {
            result.add(ImageContent.from(Base64.getEncoder().encodeToString(image.content()), image.mediaType()));
        }
        return List.copyOf(result);
    }

    private static List<Content> contents(String text, List<StoredImage> images) {
        List<Content> contents = new ArrayList<>();
        if (text != null && !text.isBlank()) contents.add(TextContent.from(text));
        for (StoredImage image : images) {
            contents.add(ImageContent.from(Base64.getEncoder().encodeToString(image.content()), image.mediaType()));
        }
        return List.copyOf(contents);
    }

    @Transactional
    public void attach(UUID userId, UUID messageId, List<UUID> imageIds) {
        for (UUID id : normalize(imageIds)) {
            int updated = jdbc.update("""
                    UPDATE chat_images SET message_id=? WHERE id=? AND user_id=?
                      AND (message_id IS NULL OR message_id=?)
                    """, messageId, id, userId, messageId);
            if (updated != 1) throw new IllegalArgumentException("图片不存在或已用于其他消息");
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void discardAbandonedUploads() {
        // Accepted-but-unfinished runs keep their image references; their bytes stay readable
        // so the next turn can rebuild the multimodal input.
        jdbc.update("""
                DELETE FROM chat_images c WHERE c.message_id IS NULL
                  AND c.created_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
                  AND NOT EXISTS (SELECT 1 FROM agent_runs r
                                  WHERE r.user_id=c.user_id AND jsonb_exists(r.image_ids, c.id::text))
                """);
    }

    private List<StoredImage> load(UUID userId, List<UUID> imageIds) {
        return normalize(imageIds).stream().map(id -> read(userId, id)).toList();
    }

    private static List<UUID> normalize(List<UUID> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) return List.of();
        if (imageIds.size() > MAX_IMAGES) throw new IllegalArgumentException("每条消息最多附带 4 张图片");
        if (imageIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(imageIds).size() != imageIds.size()) {
            throw new IllegalArgumentException("图片列表无效");
        }
        return List.copyOf(imageIds);
    }

    static String detectMediaType(byte[] content) {
        if (matches(content, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (matches(content, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (matches(content, 0, 0x52, 0x49, 0x46, 0x46) && matches(content, 8, 0x57, 0x45, 0x42, 0x50)) return "image/webp";
        if (matches(content, 0, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
                || matches(content, 0, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) return "image/gif";
        throw new IllegalArgumentException("仅支持 PNG、JPEG、WebP 或 GIF 图片");
    }

    private static boolean matches(byte[] content, int offset, int... signature) {
        if (content.length < offset + signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if ((content[offset + index] & 0xFF) != signature[index]) return false;
        }
        return true;
    }

    public record Upload(UUID id, String mediaType) {}
    public record StoredImage(byte[] content, String mediaType) {}
}
