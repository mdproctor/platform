package io.casehub.platform.api.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RefreshResultTest {

    @Test
    void record_components() {
        var result = new RefreshResult(3, 12, 2, 1, 0);
        assertThat(result.sourcesRefreshed()).isEqualTo(3);
        assertThat(result.totalModels()).isEqualTo(12);
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(0);
    }
}
