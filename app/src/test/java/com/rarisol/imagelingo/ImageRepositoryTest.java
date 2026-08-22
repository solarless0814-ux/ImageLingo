package com.rarisol.imagelingo;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ImageRepositoryTest {
    @Test public void naturalSortOrdersNumericPages() {
        assertTrue(ImageRepository.naturalCompare("page2.png", "page10.png") < 0);
        assertTrue(ImageRepository.naturalCompare("page10.png", "page100.png") < 0);
    }

    @Test public void naturalSortUsesLeadingZerosAsDeterministicTieBreak() {
        assertTrue(ImageRepository.naturalCompare("page2.png", "page002.png") < 0);
        assertTrue(ImageRepository.naturalCompare("A2.png", "a10.png") < 0);
    }
}
