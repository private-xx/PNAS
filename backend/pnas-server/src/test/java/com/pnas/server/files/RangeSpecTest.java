package com.pnas.server.files;

import com.pnas.server.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RangeSpecTest {

    @Test
    void noHeaderMeansWholeFile() {
        var spec = RangeSpec.parse(null, 1000);
        assertThat(spec.ranged()).isFalse();
        assertThat(spec.start()).isZero();
        assertThat(spec.length()).isEqualTo(1000);
    }

    @Test
    void parsesStartEndAndOpenEnded() {
        assertThat(RangeSpec.parse("bytes=10-19", 1000).length()).isEqualTo(10);
        assertThat(RangeSpec.parse("bytes=10-", 1000).length()).isEqualTo(990);
    }

    @Test
    void parsesSuffixRange() {
        var spec = RangeSpec.parse("bytes=-100", 1000);
        assertThat(spec.start()).isEqualTo(900);
        assertThat(spec.length()).isEqualTo(100);
    }

    @Test
    void clampsEndBeyondSize() {
        assertThat(RangeSpec.parse("bytes=990-99999", 1000).length()).isEqualTo(10);
    }

    @Test
    void outOfBoundsStartIs416() {
        assertThatThrownBy(() -> RangeSpec.parse("bytes=5000-", 1000))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Range");
    }

    @Test
    void bareStartIsOpenEndedLikeM2() {
        var spec = RangeSpec.parse("bytes=10", 1000);
        assertThat(spec.start()).isEqualTo(10);
        assertThat(spec.length()).isEqualTo(990);
        assertThat(spec.ranged()).isTrue();

        var whole = RangeSpec.parse("bytes=0", 1000);
        assertThat(whole.start()).isZero();
        assertThat(whole.length()).isEqualTo(1000);
        assertThat(whole.ranged()).isTrue();
    }

    @Test
    void bareStartOutOfBoundsIs416() {
        assertThatThrownBy(() -> RangeSpec.parse("bytes=5000", 1000))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Range");
    }

    @Test
    void bareNonNumericStartIs400() {
        assertThatThrownBy(() -> RangeSpec.parse("bytes=abc", 1000))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void nonBytesUnitIs400() {
        assertThatThrownBy(() -> RangeSpec.parse("items=0-1", 1000))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void multiRangeIs400() {
        assertThatThrownBy(() -> RangeSpec.parse("bytes=0-1,4-5", 1000))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("单段");
    }

    @Test
    void invalidNumberIs400() {
        assertThatThrownBy(() -> RangeSpec.parse("bytes=abc-def", 1000))
            .isInstanceOf(BusinessException.class);
    }
}
