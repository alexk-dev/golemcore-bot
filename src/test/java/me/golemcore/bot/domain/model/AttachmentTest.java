package me.golemcore.bot.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentTest {

    @Test
    void builderCreatesAttachment() {
        byte[] data = new byte[] { 1, 2, 3 };
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(data)
                .filename("test.png")
                .mimeType("image/png")
                .caption("A test image")
                .build();

        assertEquals(Attachment.Type.IMAGE, attachment.getType());
        assertArrayEquals(data, attachment.getData());
        assertEquals("test.png", attachment.getFilename());
        assertEquals("image/png", attachment.getMimeType());
        assertEquals("A test image", attachment.getCaption());
    }

    @Test
    void documentType() {
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 1 })
                .filename("report.pdf")
                .mimeType("application/pdf")
                .build();

        assertEquals(Attachment.Type.DOCUMENT, attachment.getType());
        assertNull(attachment.getCaption());
    }

    @Test
    void typeEnumValues() {
        assertEquals(2, Attachment.Type.values().length);
        assertEquals(Attachment.Type.IMAGE, Attachment.Type.valueOf("IMAGE"));
        assertEquals(Attachment.Type.DOCUMENT, Attachment.Type.valueOf("DOCUMENT"));
    }
}
