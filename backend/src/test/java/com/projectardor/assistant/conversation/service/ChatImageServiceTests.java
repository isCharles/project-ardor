package com.projectardor.assistant.conversation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;

class ChatImageServiceTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatImageService service = spy(new ChatImageService(jdbc));

    @Test void detectsBytesInsteadOfTrustingUploadMimeType() {
        UUID owner = UUID.randomUUID();
        byte[] png = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        var upload = service.upload(owner, new MockMultipartFile("file", "image.txt", "text/plain", png));
        assertThat(upload.mediaType()).isEqualTo("image/png");
        verify(jdbc).update(anyString(), eq(upload.id()), eq(owner), eq("image/png"), eq(png));
    }

    @Test void recognizesOtherSupportedSignatures() {
        assertThat(ChatImageService.detectMediaType(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF })).isEqualTo("image/jpeg");
        assertThat(ChatImageService.detectMediaType(new byte[] { 0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50 })).isEqualTo("image/webp");
        assertThat(ChatImageService.detectMediaType(new byte[] { 0x47, 0x49, 0x46, 0x38, 0x39, 0x61 })).isEqualTo("image/gif");
    }

    @Test void rejectsInvalidUploadsAndImageLists() {
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> service.upload(owner, new MockMultipartFile("file", new byte[0]))).hasMessage("请选择图片");
        assertThatThrownBy(() -> service.upload(owner, new MockMultipartFile("file", new byte[ChatImageService.MAX_IMAGE_BYTES + 1]))).hasMessage("单张图片不能超过 8 MB");
        assertThatThrownBy(() -> service.upload(owner, new MockMultipartFile("file", "fake.png", "image/png", new byte[] { 1 }))).hasMessage("仅支持 PNG、JPEG、WebP 或 GIF 图片");
        UUID image = UUID.randomUUID();
        assertThatThrownBy(() -> service.requireOwned(owner, List.of(image, image))).hasMessage("图片列表无效");
        assertThatThrownBy(() -> service.requireOwned(owner, java.util.stream.IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList())).hasMessage("每条消息最多附带 4 张图片");
    }

    @Test void constructsProtocolImageContents() {
        UUID owner = UUID.randomUUID(), imageId = UUID.randomUUID();
        byte[] png = { 1, 2, 3 };
        doReturn(new ChatImageService.StoredImage(png, "image/png")).when(service).read(owner, imageId);
        var contents = service.contents(owner, "图里写了什么？", List.of(imageId));
        assertThat(((TextContent) contents.get(0)).text()).isEqualTo("图里写了什么？");
        ImageContent image = (ImageContent) contents.get(1);
        assertThat(image.image().base64Data()).isEqualTo(java.util.Base64.getEncoder().encodeToString(png));
        assertThat(image.image().mimeType()).isEqualTo("image/png");
        assertThat(service.contents(owner, "", List.of(imageId))).singleElement().isInstanceOf(ImageContent.class);
    }
}
